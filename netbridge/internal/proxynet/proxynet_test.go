package proxynet

import (
	"bufio"
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/base64"
	"encoding/pem"
	"errors"
	"io"
	"math/big"
	"net"
	"net/http"
	"strconv"
	"strings"
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
	server := &socks5.Server{Username: "u", Password: "p"}
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
