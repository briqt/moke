// Package socks is a minimal SOCKS5 server (RFC 1928 CONNECT, RFC 1929 auth)
// for the app's own loopback use.
//
// Each connection authenticates with single-use credentials issued by the
// bridge, so a failed dial can be traced back to its connection and the
// connection itself is a lease on its network. Target names are passed to the
// session unresolved; this package never touches DNS.
package socks

import (
	"context"
	"encoding/binary"
	"io"
	"net"
	"net/netip"
	"strconv"
	"sync"
	"time"
)

// SOCKS5 reply codes (RFC 1928 §6).
const (
	RepSucceeded          byte = 0x00
	RepGeneralFailure     byte = 0x01
	RepNotAllowed         byte = 0x02
	RepNetworkUnreachable byte = 0x03
	RepHostUnreachable    byte = 0x04
	RepConnectionRefused  byte = 0x05
	RepTTLExpired         byte = 0x06
	RepCommandNotSupport  byte = 0x07
	RepAddressNotSupport  byte = 0x08
)

// Session is one authenticated connection's view of its network.
type Session interface {
	// Dial opens target ("host:port", host possibly a name) on the session's network.
	Dial(ctx context.Context, target string) (net.Conn, error)
	// Failed records why the dial failed and returns the reply code to send.
	Failed(err error) byte
	// Done is called exactly once when the client connection ends, however it ends.
	Done()
}

// Authenticate returns the session for valid credentials; it must consume them.
type Authenticate func(username, password string) (Session, bool)

type Server struct {
	Authenticate     Authenticate
	HandshakeTimeout time.Duration // Default 15 s.
	DialTimeout      time.Duration // Default 30 s.

	mu     sync.Mutex
	conns  map[net.Conn]struct{}
	closed bool
}

// Serve accepts until ln is closed. Close the listener, then CloseAll, to stop.
func (s *Server) Serve(ln net.Listener) error {
	for {
		conn, err := ln.Accept()
		if err != nil {
			return err
		}
		if !s.track(conn) {
			conn.Close()
			continue
		}
		go s.handle(conn)
	}
}

func (s *Server) track(conn net.Conn) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.closed {
		return false
	}
	if s.conns == nil {
		s.conns = make(map[net.Conn]struct{})
	}
	s.conns[conn] = struct{}{}
	return true
}

func (s *Server) untrack(conn net.Conn) {
	s.mu.Lock()
	delete(s.conns, conn)
	s.mu.Unlock()
}

// CloseAll ends every open client connection and refuses new ones.
func (s *Server) CloseAll() {
	s.mu.Lock()
	s.closed = true
	conns := make([]net.Conn, 0, len(s.conns))
	for c := range s.conns {
		conns = append(conns, c)
	}
	s.mu.Unlock()
	for _, c := range conns {
		c.Close()
	}
}

func (s *Server) timeouts() (time.Duration, time.Duration) {
	handshake, dial := s.HandshakeTimeout, s.DialTimeout
	if handshake <= 0 {
		handshake = 15 * time.Second
	}
	if dial <= 0 {
		dial = 30 * time.Second
	}
	return handshake, dial
}

func (s *Server) handle(client net.Conn) {
	defer s.untrack(client)
	defer client.Close()
	handshake, dialTimeout := s.timeouts()
	client.SetDeadline(time.Now().Add(handshake))

	session, ok := s.authenticate(client)
	if !ok {
		return
	}
	defer session.Done()

	target, rep := readRequest(client)
	if rep != RepSucceeded {
		writeReply(client, rep)
		return
	}
	client.SetDeadline(time.Time{})

	// A client that hangs up mid-dial is noticed when the reply is written; the
	// dial itself is bounded by dialTimeout.
	ctx, cancel := context.WithTimeout(context.Background(), dialTimeout)
	upstream, err := session.Dial(ctx, target)
	cancel()
	if err != nil {
		writeReply(client, session.Failed(err))
		return
	}
	defer upstream.Close()
	if err := writeReply(client, RepSucceeded); err != nil {
		return
	}
	relay(client, upstream)
}

func (s *Server) authenticate(client net.Conn) (Session, bool) {
	var head [2]byte
	if _, err := io.ReadFull(client, head[:]); err != nil || head[0] != 5 || head[1] == 0 {
		return nil, false
	}
	methods := make([]byte, head[1])
	if _, err := io.ReadFull(client, methods); err != nil {
		return nil, false
	}
	offered := false
	for _, m := range methods {
		offered = offered || m == 2
	}
	if !offered {
		client.Write([]byte{5, 0xff})
		return nil, false
	}
	if _, err := client.Write([]byte{5, 2}); err != nil {
		return nil, false
	}
	var version [2]byte
	if _, err := io.ReadFull(client, version[:]); err != nil || version[0] != 1 {
		return nil, false
	}
	user := make([]byte, version[1])
	if _, err := io.ReadFull(client, user); err != nil {
		return nil, false
	}
	var plen [1]byte
	if _, err := io.ReadFull(client, plen[:]); err != nil {
		return nil, false
	}
	pass := make([]byte, plen[0])
	if _, err := io.ReadFull(client, pass); err != nil {
		return nil, false
	}
	var session Session
	ok := false
	if s.Authenticate != nil {
		session, ok = s.Authenticate(string(user), string(pass))
	}
	if !ok {
		client.Write([]byte{1, 1})
		return nil, false
	}
	if _, err := client.Write([]byte{1, 0}); err != nil {
		session.Done()
		return nil, false
	}
	return session, true
}

// readRequest parses a CONNECT request; names stay unresolved.
func readRequest(r io.Reader) (string, byte) {
	var head [4]byte
	if _, err := io.ReadFull(r, head[:]); err != nil || head[0] != 5 {
		return "", RepGeneralFailure
	}
	if head[1] != 1 {
		return "", RepCommandNotSupport
	}
	var host string
	switch head[3] {
	case 1, 4:
		size := 4
		if head[3] == 4 {
			size = 16
		}
		buf := make([]byte, size)
		if _, err := io.ReadFull(r, buf); err != nil {
			return "", RepGeneralFailure
		}
		ip, _ := netip.AddrFromSlice(buf)
		host = ip.Unmap().String()
	case 3:
		var n [1]byte
		if _, err := io.ReadFull(r, n[:]); err != nil || n[0] == 0 {
			return "", RepGeneralFailure
		}
		name := make([]byte, n[0])
		if _, err := io.ReadFull(r, name); err != nil {
			return "", RepGeneralFailure
		}
		host = string(name)
		for _, ch := range host {
			if ch <= ' ' || ch == '/' || ch == '\\' || ch == '@' || ch == ':' || ch > '~' {
				return "", RepAddressNotSupport
			}
		}
	default:
		return "", RepAddressNotSupport
	}
	var port [2]byte
	if _, err := io.ReadFull(r, port[:]); err != nil {
		return "", RepGeneralFailure
	}
	p := binary.BigEndian.Uint16(port[:])
	if p == 0 {
		return "", RepAddressNotSupport
	}
	return net.JoinHostPort(host, strconv.Itoa(int(p))), RepSucceeded
}

func writeReply(w io.Writer, rep byte) error {
	_, err := w.Write([]byte{5, rep, 0, 1, 0, 0, 0, 0, 0, 0})
	return err
}

// relay copies both ways; when either side ends, both connections are closed.
func relay(a, b net.Conn) {
	done := make(chan struct{}, 2)
	copyHalf := func(dst, src net.Conn) {
		io.Copy(dst, src)
		done <- struct{}{}
	}
	go copyHalf(a, b)
	go copyHalf(b, a)
	<-done
	a.Close()
	b.Close()
	<-done
}
