package proxynet

import (
	"bufio"
	"bytes"
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/base64"
	"encoding/binary"
	"encoding/pem"
	"errors"
	"io"
	"math/big"
	"net"
	"net/http"
	"net/netip"
	"strconv"
	"strings"
	"sync"

	"golang.org/x/net/dns/dnsmessage"
	"testing"
	"time"

	"github.com/briqt/moke/netbridge/internal/codes"
	"tailscale.com/net/socks5"
)

// echoTarget answers each TCP connection with "id:" + the first line it receives.
func echoTarget(t *testing.T, id string) string {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { ln.Close() })
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			go func() {
				defer c.Close()
				line, _ := bufio.NewReader(c).ReadString('\n')
				io.WriteString(c, id+":"+line)
			}()
		}
	}()
	return ln.Addr().String()
}

func roundTrip(t *testing.T, conn net.Conn, want string) {
	t.Helper()
	defer conn.Close()
	conn.SetDeadline(time.Now().Add(5 * time.Second))
	io.WriteString(conn, "ping\n")
	got, err := bufio.NewReader(conn).ReadString('\n')
	if err != nil || got != want {
		t.Fatalf("round trip = %q, %v; want %q", got, err, want)
	}
}

func hostPort(t *testing.T, addr string) (string, int) {
	host, port, _ := net.SplitHostPort(addr)
	p, _ := strconv.Atoi(port)
	return host, p
}

func TestSOCKS5TCPAndUDPAgainstIndependentServer(t *testing.T) {
	target := echoTarget(t, "t")
	ln, _ := net.Listen("tcp", "127.0.0.1:0")
	defer ln.Close()
	server := &socks5.Server{Username: "u", Password: "p", Dialer: func(ctx context.Context, network, addr string) (net.Conn, error) {
		if h, p, _ := net.SplitHostPort(addr); h == "target.test" {
			addr = net.JoinHostPort("127.0.0.1", p) // The proxy's own name resolution.
		}
		return (&net.Dialer{}).DialContext(ctx, network, addr)
	}}
	go server.Serve(ln)
	host, port := hostPort(t, ln.Addr().String())
	c := Config{Type: "socks5", Host: host, Port: port, Username: "u", Password: "p"}
	conn, err := c.DialTCP(context.Background(), target)
	if err != nil {
		t.Fatal(err)
	}
	roundTrip(t, conn, "t:ping\n")
	bad := c
	bad.Password = "wrong"
	if _, err := bad.DialTCP(context.Background(), target); err == nil {
		t.Fatal("wrong password accepted")
	}

	udpTarget, _ := net.ListenPacket("udp", "127.0.0.1:0")
	defer udpTarget.Close()
	go func() {
		buf := make([]byte, 2048)
		for {
			n, from, err := udpTarget.ReadFrom(buf)
			if err != nil {
				return
			}
			udpTarget.WriteTo(append([]byte("echo:"), buf[:n]...), from)
		}
	}()
	assoc, err := c.AssociateUDP(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if _, err := assoc.WriteTo([]byte("dgram"), udpTarget.LocalAddr()); err != nil {
		t.Fatal(err)
	}
	assoc.SetReadDeadline(time.Now().Add(5 * time.Second))
	buf := make([]byte, 2048)
	n, from, err := assoc.ReadFrom(buf)
	if err != nil || string(buf[:n]) != "echo:dgram" || from.String() != udpTarget.LocalAddr().String() {
		t.Fatalf("UDP via SOCKS5 = %q from %v, %v", buf[:n], from, err)
	}
	// A name target is resolved by the proxy (ATYP=3); this server labels the
	// reply with the same name.
	_, udpPort := hostPort(t, udpTarget.LocalAddr().String())
	if _, err := assoc.WriteToHost([]byte("named"), "target.test", uint16(udpPort)); err != nil {
		t.Fatal(err)
	}
	n, from, err = assoc.ReadFrom(buf)
	if err != nil || string(buf[:n]) != "echo:named" || from != (HostAddr{Host: "target.test", Port: uint16(udpPort)}) {
		t.Fatalf("named UDP via SOCKS5 = %q from %v, %v", buf[:n], from, err)
	}
	// Losing the control connection must end the association (RFC 1928 §7).
	assoc.control.Close()
	select {
	case <-assoc.Done():
	case <-time.After(3 * time.Second):
		t.Fatal("association outlived its control connection")
	}
}

func TestUDPRefusedForTCPOnlyTypes(t *testing.T) {
	for _, kind := range []string{"http", "https", "socks4", "socks4a"} {
		if _, err := (Config{Type: kind, Host: "192.0.2.1", Port: 1}).AssociateUDP(context.Background()); !errors.Is(err, ErrUDPUnsupported) {
			t.Fatalf("%s: %v", kind, err)
		}
	}
}

// httpProxy is a minimal CONNECT proxy requiring Basic u:p, optionally behind TLS.
func httpProxy(t *testing.T, cert *tls.Certificate) string {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	if cert != nil {
		ln = tls.NewListener(ln, &tls.Config{Certificates: []tls.Certificate{*cert}})
	}
	t.Cleanup(func() { ln.Close() })
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			go func() {
				defer c.Close()
				r := bufio.NewReader(c)
				req, err := http.ReadRequest(r)
				if err != nil || req.Method != http.MethodConnect {
					return
				}
				switch req.Header.Get("Proxy-Authorization") {
				case "Basic " + base64.StdEncoding.EncodeToString([]byte("u:p")):
				case "":
					io.WriteString(c, "HTTP/1.1 407 Proxy Authentication Required\r\n\r\n")
					return
				default:
					// tinyproxy answers wrong credentials with 401, not 407.
					io.WriteString(c, "HTTP/1.1 401 Unauthorized\r\n\r\n")
					return
				}
				up, err := net.Dial("tcp", req.Host)
				if err != nil {
					io.WriteString(c, "HTTP/1.1 502 Bad Gateway\r\n\r\n")
					return
				}
				defer up.Close()
				io.WriteString(c, "HTTP/1.1 200 Connection established\r\n\r\n")
				go io.Copy(up, r)
				io.Copy(c, up)
			}()
		}
	}()
	return ln.Addr().String()
}

func certificate(t *testing.T, name string, notAfter time.Time) (tls.Certificate, string) {
	t.Helper()
	caKey, _ := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	caTemplate := &x509.Certificate{SerialNumber: big.NewInt(1), Subject: pkix.Name{CommonName: "d10 test CA"},
		NotBefore: time.Now().Add(-time.Hour), NotAfter: time.Now().Add(24 * time.Hour), IsCA: true,
		KeyUsage: x509.KeyUsageCertSign, BasicConstraintsValid: true}
	caDER, _ := x509.CreateCertificate(rand.Reader, caTemplate, caTemplate, &caKey.PublicKey, caKey)
	ca, _ := x509.ParseCertificate(caDER)
	key, _ := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	leaf := &x509.Certificate{SerialNumber: big.NewInt(2), Subject: pkix.Name{CommonName: name}, DNSNames: []string{name},
		NotBefore: notAfter.Add(-48 * time.Hour), NotAfter: notAfter, ExtKeyUsage: []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
		KeyUsage: x509.KeyUsageDigitalSignature}
	der, _ := x509.CreateCertificate(rand.Reader, leaf, ca, &key.PublicKey, caKey)
	return tls.Certificate{Certificate: [][]byte{der}, PrivateKey: key},
		string(pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: caDER}))
}

func TestHTTPAndHTTPSConnect(t *testing.T) {
	target := echoTarget(t, "h")
	host, port := hostPort(t, httpProxy(t, nil))
	c := Config{Type: "http", Host: host, Port: port, Username: "u", Password: "p"}
	conn, err := c.DialTCP(context.Background(), target)
	if err != nil {
		t.Fatal(err)
	}
	roundTrip(t, conn, "h:ping\n")
	noAuth := c
	noAuth.Username, noAuth.Password = "", ""
	if _, err := noAuth.DialTCP(context.Background(), target); err == nil || !strings.Contains(err.Error(), "407") {
		t.Fatalf("missing credentials: %v", err)
	}
	wrong := c
	wrong.Password = "x"
	if _, err := wrong.DialTCP(context.Background(), target); codes.Of(err, codes.Internal).Code != codes.ProxyAuth {
		t.Fatalf("wrong credentials: %v", err)
	}

	good, roots := certificate(t, "proxy.test", time.Now().Add(24*time.Hour))
	host, port = hostPort(t, httpProxy(t, &good))
	tlsConfig := Config{Type: "https", Host: host, Port: port, Username: "u", Password: "p", TLSServerName: "proxy.test", TLSRootsPEM: roots}
	conn, err = tlsConfig.DialTCP(context.Background(), target)
	if err != nil {
		t.Fatal(err)
	}
	roundTrip(t, conn, "h:ping\n")

	untrusted := tlsConfig
	untrusted.TLSRootsPEM = ""
	wrongName := tlsConfig
	wrongName.TLSServerName = "other.test"
	expiredCert, expiredRoots := certificate(t, "proxy.test", time.Now().Add(-time.Hour))
	eh, ep := hostPort(t, httpProxy(t, &expiredCert))
	expired := Config{Type: "https", Host: eh, Port: ep, Username: "u", Password: "p", TLSServerName: "proxy.test", TLSRootsPEM: expiredRoots}
	for name, bad := range map[string]Config{"untrusted": untrusted, "wrongName": wrongName, "expired": expired} {
		if _, err := bad.DialTCP(context.Background(), target); err == nil || !strings.Contains(err.Error(), "TLS to proxy") {
			t.Fatalf("%s certificate accepted: %v", name, err)
		}
	}
}

func socks4Proxy(t *testing.T) (string, chan string) {
	t.Helper()
	ln, _ := net.Listen("tcp", "127.0.0.1:0")
	t.Cleanup(func() { ln.Close() })
	seen := make(chan string, 8)
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			go func() {
				defer c.Close()
				r := bufio.NewReader(c)
				head := make([]byte, 8)
				if _, err := io.ReadFull(r, head); err != nil || head[0] != 4 || head[1] != 1 {
					return
				}
				user, _ := r.ReadString(0)
				port := int(head[2])<<8 | int(head[3])
				host := net.IP(head[4:8]).String()
				if head[4] == 0 && head[5] == 0 && head[6] == 0 && head[7] != 0 {
					name, _ := r.ReadString(0)
					host = strings.TrimSuffix(name, "\x00")
				}
				seen <- strings.TrimSuffix(user, "\x00") + "@" + host
				if host == "localhost" {
					host = "127.0.0.1"
				}
				up, err := net.Dial("tcp", net.JoinHostPort(host, strconv.Itoa(port)))
				if err != nil {
					c.Write([]byte{0, 0x5b, 0, 0, 0, 0, 0, 0})
					return
				}
				defer up.Close()
				c.Write([]byte{0, 0x5a, 0, 0, 0, 0, 0, 0})
				go io.Copy(up, r)
				io.Copy(c, up)
			}()
		}
	}()
	return ln.Addr().String(), seen
}

func TestSOCKS4AndSOCKS4a(t *testing.T) {
	target := echoTarget(t, "s4")
	_, targetPort := hostPort(t, target)
	addr, seen := socks4Proxy(t)
	host, port := hostPort(t, addr)
	v4 := Config{Type: "socks4", Host: host, Port: port, Username: "token"}
	conn, err := v4.DialTCP(context.Background(), target)
	if err != nil {
		t.Fatal(err)
	}
	roundTrip(t, conn, "s4:ping\n")
	if got := <-seen; got != "token@127.0.0.1" {
		t.Fatalf("SOCKS4 request %q", got)
	}
	named := net.JoinHostPort("localhost", strconv.Itoa(targetPort))
	if _, err := v4.DialTCP(context.Background(), named); err == nil {
		t.Fatal("SOCKS4 resolved a name locally instead of refusing")
	}
	v4a := v4
	v4a.Type = "socks4a"
	conn, err = v4a.DialTCP(context.Background(), named)
	if err != nil {
		t.Fatal(err)
	}
	roundTrip(t, conn, "s4:ping\n")
	if got := <-seen; got != "token@localhost" {
		t.Fatalf("SOCKS4a must send the name to the proxy, got %q", got)
	}
	if _, err := ParseConfig(`{"type":"socks4","host":"h","port":1,"username":"u","password":"p"}`); err == nil {
		t.Fatal("SOCKS4 password accepted as if it were authentication")
	}
}

// scriptedProxy uses a different loopback address from the UDP relay so replacing
// a BND domain or explicit IP with the control connection's IP is observable.
func scriptedProxy(t *testing.T, handler func(net.Conn)) Config {
	t.Helper()
	ln, err := net.Listen("tcp4", "127.0.0.2:0")
	if err != nil {
		t.Fatal(err)
	}
	var mu sync.Mutex
	var active net.Conn
	closed := false
	done := make(chan struct{})
	go func() {
		defer close(done)
		conn, err := ln.Accept()
		if err != nil {
			return
		}
		defer conn.Close()
		mu.Lock()
		if closed {
			mu.Unlock()
			return
		}
		active = conn
		mu.Unlock()
		conn.SetDeadline(time.Now().Add(5 * time.Second))
		handler(conn)
	}()
	t.Cleanup(func() {
		ln.Close()
		mu.Lock()
		closed = true
		if active != nil {
			active.Close()
		}
		mu.Unlock()
		<-done
	})
	host, port := hostPort(t, ln.Addr().String())
	return Config{Type: "socks5", Host: host, Port: port}
}

func udpReplyProxy(t *testing.T, reply []byte, truncate bool) Config {
	t.Helper()
	return scriptedProxy(t, func(conn net.Conn) {
		var greeting [3]byte
		if _, err := io.ReadFull(conn, greeting[:]); err != nil || greeting != [3]byte{5, 1, 0} {
			t.Errorf("SOCKS5 greeting = %v, %v", greeting, err)
			return
		}
		conn.Write([]byte{5, 0})
		var request [10]byte
		if _, err := io.ReadFull(conn, request[:]); err != nil || request != [10]byte{5, 3, 0, 1} {
			t.Errorf("UDP ASSOCIATE = %v, %v", request, err)
			return
		}
		conn.Write(reply)
		if !truncate {
			io.Copy(io.Discard, conn)
		}
	})
}

func udpReply(host string, port uint16) []byte {
	reply := []byte{5, 0, 0}
	if ip, err := netip.ParseAddr(host); err == nil {
		atyp := byte(1)
		if ip.Is6() {
			atyp = 4
		}
		reply = append(append(reply, atyp), ip.AsSlice()...)
	} else {
		reply = append(append(reply, 3, byte(len(host))), host...)
	}
	return append(reply, byte(port>>8), byte(port))
}

// infrastructureResolver answers solely for the advertised relay; resolving the
// UDP target locally is a test failure. It serves framed DNS over a pipe, with no
// host DNS configuration or external DNS traffic involved.
func infrastructureResolver(t *testing.T, ip netip.Addr) *net.Resolver {
	t.Helper()
	return &net.Resolver{PreferGo: true, Dial: func(ctx context.Context, _, _ string) (net.Conn, error) {
		if deadline, ok := ctx.Deadline(); !ok || time.Until(deadline) > handshakeTimeout+time.Second {
			t.Error("infrastructure lookup has no bounded handshake deadline")
		}
		client, server := net.Pipe()
		go func() {
			defer server.Close()
			server.SetDeadline(time.Now().Add(3 * time.Second))
			var length [2]byte
			if _, err := io.ReadFull(server, length[:]); err != nil {
				return
			}
			wire := make([]byte, binary.BigEndian.Uint16(length[:]))
			if _, err := io.ReadFull(server, wire); err != nil {
				return
			}
			var query dnsmessage.Message
			if err := query.Unpack(wire); err != nil || len(query.Questions) != 1 {
				t.Errorf("DNS query: %v", err)
				return
			}
			q := query.Questions[0]
			if q.Name.String() != "relay.test." {
				t.Errorf("locally resolved a non-infrastructure name: %s", q.Name)
			}
			response := dnsmessage.Message{Header: dnsmessage.Header{ID: query.ID, Response: true, Authoritative: true}, Questions: query.Questions}
			h := dnsmessage.ResourceHeader{Name: q.Name, Type: q.Type, Class: dnsmessage.ClassINET}
			if q.Type == dnsmessage.TypeA && ip.Is4() {
				response.Answers = []dnsmessage.Resource{{Header: h, Body: &dnsmessage.AResource{A: ip.As4()}}}
			} else if q.Type == dnsmessage.TypeAAAA && ip.Is6() {
				response.Answers = []dnsmessage.Resource{{Header: h, Body: &dnsmessage.AAAAResource{AAAA: ip.As16()}}}
			}
			wire, err := response.Pack()
			if err != nil {
				t.Error(err)
				return
			}
			binary.BigEndian.PutUint16(length[:], uint16(len(wire)))
			server.Write(append(length[:], wire...))
		}()
		return client, nil
	}}
}

func TestUDPRelayEndpointAndRemoteTargetDNS(t *testing.T) {
	for _, tc := range []struct{ host, want string }{
		{"127.0.0.1", "127.0.0.1"}, {"::1", "::1"},
		{"relay.test", "127.0.0.1"}, {"relay.test", "::1"},
		{"0.0.0.0", "127.0.0.2"}, {"::", "127.0.0.2"},
	} {
		t.Run(tc.host+"/"+tc.want, func(t *testing.T) {
			host, want := tc.host, tc.want
			udp, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.ParseIP(want)})
			if err != nil {
				t.Fatal(err)
			}
			defer udp.Close()
			port := uint16(udp.LocalAddr().(*net.UDPAddr).Port)
			c := udpReplyProxy(t, udpReply(host, port), false)
			c.resolver = infrastructureResolver(t, netip.MustParseAddr(want))
			assoc, err := c.AssociateUDP(context.Background())
			if err != nil {
				t.Fatal(err)
			}
			defer assoc.Close()
			if assoc.relay != netip.AddrPortFrom(netip.MustParseAddr(want), port) {
				t.Fatalf("relay = %v, want %s:%d", assoc.relay, want, port)
			}
			if _, err := assoc.WriteToHost([]byte("payload"), "target.invalid", 60000); err != nil {
				t.Fatal(err)
			}
			udp.SetReadDeadline(time.Now().Add(time.Second))
			buf := make([]byte, 256)
			n, from, err := udp.ReadFromUDP(buf)
			wantPacket := append([]byte{0, 0, 0, 3, 14}, []byte("target.invalid")...)
			wantPacket = append(wantPacket, 0xea, 0x60)
			wantPacket = append(wantPacket, []byte("payload")...)
			if err != nil || !bytes.Equal(buf[:n], wantPacket) {
				t.Fatalf("named datagram = %x, %v", buf[:n], err)
			}
			udp.WriteToUDP(buf[:n], from)
			assoc.SetReadDeadline(time.Now().Add(time.Second))
			n, source, err := assoc.ReadFrom(buf)
			if err != nil || string(buf[:n]) != "payload" || source != (HostAddr{Host: "target.invalid", Port: 60000}) {
				t.Fatalf("named reply = %q, %v, %v", buf[:n], source, err)
			}
		})
	}
}

func TestUDPRelayRejectsInvalidOrTruncatedEndpoint(t *testing.T) {
	replies := [][]byte{udpReply("127.0.0.1", 0), udpReply("", 1080), {5, 0, 0, 9}}
	for _, host := range []string{"127.0.0.1", "::1", "relay.test"} {
		full := udpReply(host, 1080)
		for n := 4; n < len(full); n++ {
			replies = append(replies, full[:n])
		}
	}
	for i, reply := range replies {
		t.Run(strconv.Itoa(i), func(t *testing.T) {
			c := udpReplyProxy(t, reply, true)
			assoc, err := c.AssociateUDP(context.Background())
			if assoc != nil {
				assoc.Close()
			}
			if err == nil {
				t.Fatalf("accepted invalid BND reply: %x", reply)
			}
		})
	}
}

func TestUDPRelayDNSFailureAndCancellationNeverUseControlIP(t *testing.T) {
	for _, block := range []bool{false, true} {
		c := udpReplyProxy(t, udpReply("relay.test", 1080), false)
		c.resolver = &net.Resolver{PreferGo: true, Dial: func(ctx context.Context, _, _ string) (net.Conn, error) {
			if _, ok := ctx.Deadline(); !ok {
				t.Error("infrastructure DNS has no deadline")
			}
			if block {
				<-ctx.Done()
				return nil, ctx.Err()
			}
			return nil, errors.New("infrastructure DNS failed")
		}}
		ctx, cancel := context.WithTimeout(context.Background(), 100*time.Millisecond)
		assoc, err := c.AssociateUDP(ctx)
		cancel()
		if assoc != nil {
			assoc.Close()
		}
		if err == nil {
			t.Fatalf("DNS block=%v: fell back to control IP", block)
		}
	}
}

func TestCONNECTIgnoresBodyFramingAndPreservesBanner(t *testing.T) {
	for _, framing := range []string{"", "Content-Length: 24\r\n", "Transfer-Encoding: chunked\r\n", "Content-Length: invalid\r\n", "Content-Length: 1\r\nContent-Length: 2\r\n", "Transfer-Encoding: unsupported\r\nContent-Length: -1\r\n"} {
		t.Run(strings.TrimSpace(framing), func(t *testing.T) {
			banner := "SSH-2.0-tunnel-server\r\n"
			c := scriptedProxy(t, func(conn net.Conn) {
				if _, err := http.ReadRequest(bufio.NewReader(conn)); err != nil {
					t.Error(err)
					return
				}
				io.WriteString(conn, "HTTP/1.1 200 Connection established\r\n"+framing+"\r\n"+banner)
				io.Copy(conn, conn)
			})
			c.Type = "http"
			ctx, cancel := context.WithTimeout(context.Background(), 200*time.Millisecond)
			defer cancel()
			conn, err := c.DialTCP(ctx, "target.invalid:22")
			if err != nil {
				t.Fatal(err)
			}
			defer conn.Close()
			conn.SetDeadline(time.Now().Add(time.Second))
			got := make([]byte, len(banner))
			if _, err := io.ReadFull(conn, got); err != nil || string(got) != banner {
				t.Fatalf("tunnel banner = %q, %v", got, err)
			}
			io.WriteString(conn, "client bytes")
			got = make([]byte, len("client bytes"))
			if _, err := io.ReadFull(conn, got); err != nil || string(got) != "client bytes" {
				t.Fatalf("tunnel after banner = %q, %v", got, err)
			}
		})
	}
}

func TestCONNECTRejectsWithoutWaitingForBody(t *testing.T) {
	for _, tc := range []struct {
		status string
		code   codes.Code
	}{
		{"407 Proxy Authentication Required", codes.ProxyAuth}, {"401 Unauthorized", codes.ProxyAuth},
		{"504 Gateway Timeout", codes.Timeout}, {"502 Bad Gateway", codes.ProxyRefused},
	} {
		t.Run(tc.status, func(t *testing.T) {
			c := scriptedProxy(t, func(conn net.Conn) {
				http.ReadRequest(bufio.NewReader(conn))
				io.WriteString(conn, "HTTP/1.1 "+tc.status+"\r\nContent-Length: 99999\r\n\r\n")
				io.Copy(io.Discard, conn) // The claimed error body never arrives.
			})
			c.Type = "http"
			ctx, cancel := context.WithTimeout(context.Background(), 200*time.Millisecond)
			defer cancel()
			_, err := c.DialTCP(ctx, "target.invalid:22")
			if err == nil || codes.Of(err, codes.Internal).Code != tc.code || ctx.Err() != nil {
				t.Fatalf("refusal waited for body or lost code: %v, context %v", err, ctx.Err())
			}
		})
	}
}

func TestCONNECTRejectsMalformedAndOversizedHeaders(t *testing.T) {
	for _, response := range []string{
		"not an HTTP status\r\n\r\n", "HTTP/1.1 200 OK\r\nbad header\r\n\r\n",
		"HTTP/1.1 200 OK\r\nX-Large: " + strings.Repeat("x", 70<<10) + "\r\n\r\n",
		"HTTP/1.1 200 OK\r\n" + strings.Repeat("X-Fill: x\r\n", 7000) + "\r\n",
	} {
		c := scriptedProxy(t, func(conn net.Conn) {
			http.ReadRequest(bufio.NewReader(conn))
			io.WriteString(conn, response)
			io.Copy(io.Discard, conn)
		})
		c.Type = "http"
		ctx, cancel := context.WithTimeout(context.Background(), time.Second)
		conn, err := c.DialTCP(ctx, "target.invalid:22")
		cancel()
		if conn != nil {
			conn.Close()
		}
		if err == nil {
			t.Errorf("accepted malformed/oversized CONNECT header (%d bytes)", len(response))
		}
	}
}

func TestCONNECTCancelledDuringIncompleteHeaders(t *testing.T) {
	sent := make(chan struct{})
	c := scriptedProxy(t, func(conn net.Conn) {
		http.ReadRequest(bufio.NewReader(conn))
		io.WriteString(conn, "HTTP/1.1 200 OK\r\nX-Incomplete: ")
		close(sent)
		io.Copy(io.Discard, conn)
	})
	c.Type = "http"
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	done := make(chan error, 1)
	go func() {
		conn, err := c.DialTCP(ctx, "target.invalid:22")
		if conn != nil {
			conn.Close()
		}
		done <- err
	}()
	select {
	case <-sent:
	case <-time.After(time.Second):
		t.Fatal("proxy did not send partial headers")
	}
	cancel()
	select {
	case err := <-done:
		if err == nil {
			t.Fatal("cancelled incomplete CONNECT succeeded")
		}
	case <-time.After(time.Second):
		t.Fatal("CONNECT header read ignored cancellation")
	}
}

type delayedDeadlineConn struct {
	net.Conn
	entered, allow, finished chan struct{}
	last                     time.Time
}

func (c *delayedDeadlineConn) SetDeadline(deadline time.Time) error {
	if deadline == time.Unix(1, 0) {
		close(c.entered)
		<-c.allow
	}
	c.last = deadline
	if deadline == time.Unix(1, 0) {
		close(c.finished)
	}
	return nil
}

func TestHandshakeWaitsForCancellationDeadlineBeforeClearing(t *testing.T) {
	c := &delayedDeadlineConn{entered: make(chan struct{}), allow: make(chan struct{}), finished: make(chan struct{})}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	done := make(chan error, 1)
	go func() {
		done <- withDeadline(ctx, c, func() error {
			cancel()
			<-c.entered // Force the AfterFunc to be running when the handshake ends.
			return nil
		})
	}()
	<-c.entered
	var err error
	select {
	case err = <-done:
		t.Error("handshake returned while its cancellation deadline was still pending")
		close(c.allow)
		<-c.finished
	case <-time.After(25 * time.Millisecond):
		close(c.allow)
		err = <-done
	}
	if !errors.Is(err, context.Canceled) || !c.last.IsZero() {
		t.Fatalf("cancelled handshake left a poisoned deadline: %v, %v", err, c.last)
	}
}

func TestCONNECTHeaderLimitAndFragmentedBufferedBytes(t *testing.T) {
	for _, size := range []int{maxConnectHeader - 1, maxConnectHeader, maxConnectHeader + 1} {
		prefix, suffix := "HTTP/1.1 201 Tunnel\r\nX-Pad: ", "\r\n\r\n"
		header := prefix + strings.Repeat("x", size-len(prefix)-len(suffix)) + suffix
		reader := bufio.NewReaderSize(strings.NewReader(header+"banner\x00\xff"), 16)
		status, err := readConnectStatus(reader)
		if size > maxConnectHeader {
			if err == nil {
				t.Fatal("accepted oversized fragmented header")
			}
			continue
		}
		if err != nil || status != 201 {
			t.Fatalf("size %d: status=%d err=%v", size, status, err)
		}
		got, err := io.ReadAll(reader)
		if err != nil || string(got) != "banner\x00\xff" {
			t.Fatalf("lost buffered tunnel bytes at size %d: %q, %v", size, got, err)
		}
	}
}

func TestInfrastructureProxyDNSHonoursCancellation(t *testing.T) {
	started := make(chan struct{})
	var once sync.Once
	c := Config{Type: "http", Host: "proxy.test", Port: 8080, resolver: &net.Resolver{PreferGo: true, Dial: func(ctx context.Context, _, _ string) (net.Conn, error) {
		if _, ok := ctx.Deadline(); !ok {
			t.Error("proxy infrastructure DNS has no deadline")
		}
		once.Do(func() { close(started) })
		<-ctx.Done()
		return nil, ctx.Err()
	}}}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	done := make(chan error, 1)
	go func() {
		_, err := c.DialTCP(ctx, "target.invalid:22")
		done <- err
	}()
	select {
	case <-started:
	case <-time.After(time.Second):
		t.Fatal("proxy lookup not started")
	}
	cancel()
	select {
	case err := <-done:
		if err == nil {
			t.Fatal("cancelled infrastructure lookup succeeded")
		}
	case <-time.After(time.Second):
		t.Fatal("infrastructure lookup ignored cancellation")
	}
}

func TestDialHonoursCancellation(t *testing.T) {
	ln, _ := net.Listen("tcp", "127.0.0.1:0") // Accepts, never answers.
	defer ln.Close()
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			defer c.Close()
		}
	}()
	host, port := hostPort(t, ln.Addr().String())
	ctx, cancel := context.WithTimeout(context.Background(), 300*time.Millisecond)
	defer cancel()
	began := time.Now()
	if _, err := (Config{Type: "http", Host: host, Port: port}).DialTCP(ctx, "example.invalid:22"); err == nil {
		t.Fatal("silent proxy did not fail")
	}
	if time.Since(began) > 3*time.Second {
		t.Fatal("handshake ignored cancellation")
	}
}
