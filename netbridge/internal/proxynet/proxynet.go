// Package proxynet dials TCP and associates UDP through one external proxy entry.
//
// Targets are never resolved locally: names go to the proxy (SOCKS4a/5 domain,
// HTTP CONNECT authority). Plain SOCKS4 carries IPv4 only, so it rejects names.
// TLS trust for an HTTPS proxy is per entry; no process-global state is touched.
// Every failure is a codes.Error, so the app can tell the proxy apart from the target.
package proxynet

import (
	"bufio"
	"context"
	"crypto/tls"
	"crypto/x509"
	"encoding/base64"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/netip"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/briqt/moke/netbridge/internal/codes"
)

// ErrUDPUnsupported is returned before any network I/O for proxy types that carry
// no UDP, so callers can refuse mosh up front with a clear reason.
var ErrUDPUnsupported = codes.New(codes.ProxyNoUDP, "proxy type cannot carry UDP")

type Config struct {
	ID       string `json:"id"`
	Type     string `json:"type"` // http, https, socks4, socks4a, socks5
	Host     string `json:"host"`
	Port     int    `json:"port"`
	Username string `json:"username"`
	Password string `json:"password"`
	// HTTPS proxy only: expected certificate name (default Host) and optional extra
	// PEM roots trusted for this entry in addition to the system pool.
	TLSServerName string `json:"tlsServerName"`
	TLSRootsPEM   string `json:"tlsRootsPEM"`

	// Roots is the trust store for HTTPS proxies (the app supplies the platform's
	// anchors); nil falls back to Go's system pool. TLSRootsPEM adds to it.
	Roots *x509.CertPool `json:"-"`
}

const handshakeTimeout = 15 * time.Second

func ParseConfig(data string) (Config, error) {
	var c Config
	if len(data) > 65536 || json.Unmarshal([]byte(data), &c) != nil {
		return Config{}, errors.New("invalid proxy configuration JSON")
	}
	switch c.Type {
	case "http", "https", "socks4", "socks4a", "socks5":
	default:
		return Config{}, errors.New("unsupported proxy type")
	}
	if c.Host == "" || len(c.Host) > 253 || strings.ContainsAny(c.Host, " /\\@\x00\r\n") || c.Port < 1 || c.Port > 65535 {
		return Config{}, errors.New("invalid proxy address")
	}
	if len(c.Username) > 255 || len(c.Password) > 255 || strings.ContainsAny(c.Username+c.Password, "\x00\r\n") {
		return Config{}, errors.New("invalid proxy credentials")
	}
	if c.Type == "socks4" || c.Type == "socks4a" {
		if c.Password != "" {
			// SOCKS4 only has a USERID field; it is not password authentication.
			return Config{}, errors.New("SOCKS4 supports a user ID only, not a password")
		}
	}
	if (c.Type == "http" || c.Type == "https") && strings.Contains(c.Username, ":") {
		return Config{}, errors.New("HTTP proxy user name must not contain ':'")
	}
	if c.Type != "https" && (c.TLSServerName != "" || c.TLSRootsPEM != "") {
		return Config{}, errors.New("TLS settings apply to HTTPS proxies only")
	}
	return c, nil
}

func (c Config) address() string { return net.JoinHostPort(c.Host, strconv.Itoa(c.Port)) }

// SupportsUDP reports whether this proxy type can, in principle, carry UDP.
// SOCKS5 servers may still refuse UDP ASSOCIATE; that is detected on association.
func (c Config) SupportsUDP() bool { return c.Type == "socks5" }

// DialTCP opens a tunnel to target ("host:port"); the host is sent unresolved.
func (c Config) DialTCP(ctx context.Context, target string) (net.Conn, error) {
	host, portText, err := net.SplitHostPort(target)
	if err != nil {
		return nil, errors.New("target must be host:port")
	}
	port, err := strconv.Atoi(portText)
	if err != nil || port < 1 || port > 65535 || host == "" {
		return nil, errors.New("invalid target")
	}
	switch c.Type {
	case "socks5":
		return c.dialSOCKS5(ctx, host, port)
	case "socks4", "socks4a":
		return c.dialSOCKS4(ctx, host, port)
	default:
		return c.dialHTTP(ctx, target)
	}
}

func (c Config) dialProxy(ctx context.Context) (net.Conn, error) {
	conn, err := (&net.Dialer{Timeout: handshakeTimeout}).DialContext(ctx, "tcp", c.address())
	if err != nil {
		code := codes.ProxyUnreachable
		if codes.Classify(err, code) == codes.Timeout {
			code = codes.Timeout
		}
		return nil, codes.New(code, "connect to proxy: "+err.Error())
	}
	if c.Type != "https" {
		return conn, nil
	}
	config := &tls.Config{ServerName: c.Host, MinVersion: tls.VersionTLS12}
	if c.TLSServerName != "" {
		config.ServerName = c.TLSServerName
	}
	pool := c.Roots
	if pool != nil {
		pool = pool.Clone()
	} else if system, err := x509.SystemCertPool(); err == nil && system != nil {
		pool = system
	} else {
		pool = x509.NewCertPool()
	}
	if c.TLSRootsPEM != "" && !pool.AppendCertsFromPEM([]byte(c.TLSRootsPEM)) {
		conn.Close()
		return nil, codes.New(codes.InvalidArgument, "invalid TLS roots PEM")
	}
	config.RootCAs = pool
	tlsConn := tls.Client(conn, config)
	if err := tlsConn.HandshakeContext(ctx); err != nil {
		conn.Close()
		return nil, codes.New(codes.ProxyTLS, "TLS to proxy: "+err.Error())
	}
	return tlsConn, nil
}

// withDeadline bounds a handshake by ctx and handshakeTimeout, then clears it.
func withDeadline(ctx context.Context, conn net.Conn, handshake func() error) error {
	deadline := time.Now().Add(handshakeTimeout)
	if d, ok := ctx.Deadline(); ok && d.Before(deadline) {
		deadline = d
	}
	conn.SetDeadline(deadline)
	stop := context.AfterFunc(ctx, func() { conn.SetDeadline(time.Unix(1, 0)) })
	err := handshake()
	if !stop() && err == nil {
		err = ctx.Err()
	}
	conn.SetDeadline(time.Time{})
	return err
}

// bufferedConn keeps bytes the handshake reader consumed past the response.
type bufferedConn struct {
	net.Conn
	reader *bufio.Reader
}

func (b *bufferedConn) Read(p []byte) (int, error) { return b.reader.Read(p) }

func (c Config) dialHTTP(ctx context.Context, target string) (net.Conn, error) {
	conn, err := c.dialProxy(ctx)
	if err != nil {
		return nil, err
	}
	reader := bufio.NewReader(conn)
	err = withDeadline(ctx, conn, func() error {
		request := "CONNECT " + target + " HTTP/1.1\r\nHost: " + target + "\r\n"
		if c.Username != "" || c.Password != "" {
			request += "Proxy-Authorization: Basic " + base64.StdEncoding.EncodeToString([]byte(c.Username+":"+c.Password)) + "\r\n"
		}
		if _, err := io.WriteString(conn, request+"\r\n"); err != nil {
			return err
		}
		response, err := http.ReadResponse(reader, &http.Request{Method: http.MethodConnect})
		if err != nil {
			return err
		}
		response.Body.Close()
		switch {
		case response.StatusCode == http.StatusProxyAuthRequired:
			return codes.New(codes.ProxyAuth, "proxy requires authentication: HTTP 407")
		case response.StatusCode == http.StatusGatewayTimeout:
			return codes.New(codes.Timeout, "proxy CONNECT timed out: HTTP 504")
		case response.StatusCode/100 != 2:
			return codes.New(codes.ProxyRefused, fmt.Sprintf("proxy CONNECT refused: HTTP %d", response.StatusCode))
		}
		return nil
	})
	if err != nil {
		conn.Close()
		return nil, err
	}
	return &bufferedConn{Conn: conn, reader: reader}, nil
}

func (c Config) dialSOCKS4(ctx context.Context, host string, port int) (net.Conn, error) {
	ip, ipErr := netip.ParseAddr(host)
	if ipErr == nil && !ip.Unmap().Is4() {
		return nil, codes.New(codes.InvalidArgument, "SOCKS4 cannot reach IPv6 targets")
	}
	if ipErr != nil && c.Type == "socks4" {
		// Never resolve locally (it would leak the name and bypass the selected route).
		return nil, codes.New(codes.InvalidArgument, "SOCKS4 needs an IPv4 literal; use SOCKS4a for host names")
	}
	if strings.ContainsRune(host, 0) || len(host) > 255 {
		return nil, errors.New("invalid target host")
	}
	conn, err := c.dialProxy(ctx)
	if err != nil {
		return nil, err
	}
	err = withDeadline(ctx, conn, func() error {
		request := []byte{4, 1, byte(port >> 8), byte(port)}
		if ipErr == nil {
			request = append(request, ip.Unmap().AsSlice()...)
		} else {
			request = append(request, 0, 0, 0, 1) // SOCKS4a: 0.0.0.x, name follows USERID.
		}
		request = append(append(request, c.Username...), 0)
		if ipErr != nil {
			request = append(append(request, host...), 0)
		}
		if _, err := conn.Write(request); err != nil {
			return err
		}
		var reply [8]byte
		if _, err := io.ReadFull(conn, reply[:]); err != nil {
			return err
		}
		switch {
		case reply[0] != 0:
			return codes.New(codes.ProxyRefused, "invalid SOCKS4 reply")
		case reply[1] == 0x5c || reply[1] == 0x5d:
			return codes.New(codes.ProxyAuth, fmt.Sprintf("SOCKS4 user ID rejected (code %d)", reply[1]))
		case reply[1] != 0x5a:
			return codes.New(codes.ProxyRefused, fmt.Sprintf("SOCKS4 request rejected (code %d)", reply[1]))
		}
		return nil
	})
	if err != nil {
		conn.Close()
		return nil, err
	}
	return conn, nil
}

// dialSOCKS5 sends a CONNECT; a name goes as ATYP 3 and is resolved by the proxy.
func (c Config) dialSOCKS5(ctx context.Context, host string, port int) (net.Conn, error) {
	request := []byte{5, 1, 0}
	if ip, err := netip.ParseAddr(host); err == nil {
		ip = ip.Unmap()
		if ip.Is4() {
			request = append(request, 1)
		} else {
			request = append(request, 4)
		}
		request = append(request, ip.AsSlice()...)
	} else {
		if len(host) > 255 || strings.ContainsRune(host, 0) {
			return nil, codes.New(codes.InvalidArgument, "invalid target host")
		}
		request = append(append(request, 3, byte(len(host))), host...)
	}
	request = append(request, byte(port>>8), byte(port))
	conn, err := c.dialProxy(ctx)
	if err != nil {
		return nil, err
	}
	err = withDeadline(ctx, conn, func() error {
		if err := socks5Greeting(conn, c.Username, c.Password); err != nil {
			return err
		}
		if _, err := conn.Write(request); err != nil {
			return err
		}
		var head [4]byte
		if _, err := io.ReadFull(conn, head[:]); err != nil {
			return err
		}
		if head[0] != 5 {
			return codes.New(codes.ProxyRefused, "invalid SOCKS5 reply")
		}
		if head[1] != 0 {
			return socks5ReplyError(head[1])
		}
		_, err := readSOCKSAddr(conn, head[3])
		return err
	})
	if err != nil {
		conn.Close()
		return nil, err
	}
	return conn, nil
}

// socks5ReplyError maps RFC 1928 reply codes to what failed: the target or the proxy.
func socks5ReplyError(rep byte) error {
	detail := fmt.Sprintf("SOCKS5 CONNECT failed (code %d)", rep)
	switch rep {
	case 3, 4:
		return codes.New(codes.TargetUnreachable, detail)
	case 5:
		return codes.New(codes.TargetRefused, detail)
	case 6:
		return codes.New(codes.Timeout, detail)
	default:
		return codes.New(codes.ProxyRefused, detail)
	}
}

// Association is one SOCKS5 UDP ASSOCIATE. It implements net.PacketConn for
// datagrams to/from literal-IP targets and ends when its control connection does
// (RFC 1928 §7). No fragmentation is used or accepted.
type Association struct {
	control net.Conn
	udp     *net.UDPConn
	relay   netip.AddrPort
	done    chan struct{}
	once    sync.Once
}

// AssociateUDP requests a UDP relay from a SOCKS5 proxy.
func (c Config) AssociateUDP(ctx context.Context) (*Association, error) {
	if !c.SupportsUDP() {
		return nil, ErrUDPUnsupported
	}
	control, err := c.dialProxy(ctx)
	if err != nil {
		return nil, err
	}
	var relay netip.AddrPort
	err = withDeadline(ctx, control, func() error {
		if err := socks5Greeting(control, c.Username, c.Password); err != nil {
			return err
		}
		// DST.ADDR/PORT = 0: our sending address is not known before NAT.
		if _, err := control.Write([]byte{5, 3, 0, 1, 0, 0, 0, 0, 0, 0}); err != nil {
			return err
		}
		var head [4]byte
		if _, err := io.ReadFull(control, head[:]); err != nil {
			return err
		}
		if head[0] != 5 {
			return errors.New("invalid SOCKS5 reply")
		}
		if head[1] != 0 {
			return codes.New(codes.ProxyNoUDP, fmt.Sprintf("SOCKS5 UDP ASSOCIATE refused (code %d)", head[1]))
		}
		addr, err := readSOCKSAddr(control, head[3])
		if err != nil {
			return err
		}
		relay = addr
		return nil
	})
	if err != nil {
		control.Close()
		return nil, err
	}
	// A wildcard BND.ADDR means "the proxy's own address" (common behind NAT).
	if !relay.Addr().IsValid() || relay.Addr().IsUnspecified() {
		remote, ok := control.RemoteAddr().(*net.TCPAddr)
		if !ok {
			control.Close()
			return nil, errors.New("cannot determine proxy UDP relay address")
		}
		relay = netip.AddrPortFrom(remote.AddrPort().Addr().Unmap(), relay.Port())
	}
	udp, err := net.DialUDP("udp", nil, net.UDPAddrFromAddrPort(relay))
	if err != nil {
		control.Close()
		return nil, err
	}
	a := &Association{control: control, udp: udp, relay: relay, done: make(chan struct{})}
	go func() {
		// Any byte or EOF on the control connection ends the association.
		io.Copy(io.Discard, control)
		a.Close()
	}()
	return a, nil
}

func socks5Greeting(conn net.Conn, user, password string) error {
	method := byte(0)
	if user != "" || password != "" {
		method = 2
	}
	if _, err := conn.Write([]byte{5, 1, method}); err != nil {
		return err
	}
	var choice [2]byte
	if _, err := io.ReadFull(conn, choice[:]); err != nil {
		return err
	}
	if choice[0] != 5 || choice[1] != method {
		return codes.New(codes.ProxyAuth, "SOCKS5 authentication method not accepted")
	}
	if method == 2 {
		message := append([]byte{1, byte(len(user))}, user...)
		message = append(append(message, byte(len(password))), password...)
		if _, err := conn.Write(message); err != nil {
			return err
		}
		var status [2]byte
		if _, err := io.ReadFull(conn, status[:]); err != nil {
			return err
		}
		if status[1] != 0 {
			return codes.New(codes.ProxyAuth, "SOCKS5 authentication failed")
		}
	}
	return nil
}

func readSOCKSAddr(r io.Reader, atyp byte) (netip.AddrPort, error) {
	var ip netip.Addr
	switch atyp {
	case 1, 4:
		size := 4
		if atyp == 4 {
			size = 16
		}
		buf := make([]byte, size)
		if _, err := io.ReadFull(r, buf); err != nil {
			return netip.AddrPort{}, err
		}
		ip, _ = netip.AddrFromSlice(buf)
	case 3:
		var n [1]byte
		if _, err := io.ReadFull(r, n[:]); err != nil {
			return netip.AddrPort{}, err
		}
		name := make([]byte, n[0])
		if _, err := io.ReadFull(r, name); err != nil {
			return netip.AddrPort{}, err
		}
		// A relay named by host name is not resolved here; treat as unspecified.
	default:
		return netip.AddrPort{}, errors.New("invalid SOCKS5 address type")
	}
	var port [2]byte
	if _, err := io.ReadFull(r, port[:]); err != nil {
		return netip.AddrPort{}, err
	}
	return netip.AddrPortFrom(ip.Unmap(), binary.BigEndian.Uint16(port[:])), nil
}

func (a *Association) WriteTo(p []byte, addr net.Addr) (int, error) {
	udp, ok := addr.(*net.UDPAddr)
	if !ok {
		return 0, errors.New("UDP address required")
	}
	target := udp.AddrPort()
	ip := target.Addr().Unmap()
	header := []byte{0, 0, 0, 1}
	if !ip.Is4() {
		header[3] = 4
	}
	header = append(append(header, ip.AsSlice()...), byte(target.Port()>>8), byte(target.Port()))
	if _, err := a.udp.Write(append(header, p...)); err != nil {
		return 0, err
	}
	return len(p), nil
}

func (a *Association) ReadFrom(p []byte) (int, net.Addr, error) {
	buf := make([]byte, 65535)
	for {
		n, err := a.udp.Read(buf)
		if err != nil {
			return 0, nil, err
		}
		// RSV(2) FRAG(1) ATYP(1) ADDR PORT DATA; drop fragments and malformed packets.
		if n < 4 || buf[0] != 0 || buf[1] != 0 || buf[2] != 0 {
			continue
		}
		reader := &sliceReader{data: buf[4:n]}
		from, err := readSOCKSAddr(reader, buf[3])
		if err != nil || !from.Addr().IsValid() {
			continue
		}
		return copy(p, reader.data), net.UDPAddrFromAddrPort(from), nil
	}
}

type sliceReader struct{ data []byte }

func (s *sliceReader) Read(p []byte) (int, error) {
	if len(s.data) == 0 {
		return 0, io.EOF
	}
	n := copy(p, s.data)
	s.data = s.data[n:]
	return n, nil
}

func (a *Association) Close() error {
	var err error
	a.once.Do(func() {
		close(a.done)
		err = errors.Join(a.udp.Close(), a.control.Close())
	})
	return err
}

// Done is closed when the association ends (explicit Close or control loss).
func (a *Association) Done() <-chan struct{} { return a.done }

func (a *Association) LocalAddr() net.Addr                { return a.udp.LocalAddr() }
func (a *Association) SetDeadline(t time.Time) error      { return a.udp.SetDeadline(t) }
func (a *Association) SetReadDeadline(t time.Time) error  { return a.udp.SetReadDeadline(t) }
func (a *Association) SetWriteDeadline(t time.Time) error { return a.udp.SetWriteDeadline(t) }
