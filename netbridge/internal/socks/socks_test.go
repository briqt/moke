package socks

import (
	"context"
	"encoding/binary"
	"errors"
	"io"
	"net"
	"sync"
	"testing"
	"time"
)

type fakeSession struct {
	mu      sync.Mutex
	dialed  []string
	failed  []error
	done    int
	dial    func(context.Context, string) (net.Conn, error)
	failRep byte
}

func (f *fakeSession) Dial(ctx context.Context, target string) (net.Conn, error) {
	f.mu.Lock()
	f.dialed = append(f.dialed, target)
	f.mu.Unlock()
	return f.dial(ctx, target)
}

func (f *fakeSession) Failed(err error) byte {
	f.mu.Lock()
	f.failed = append(f.failed, err)
	f.mu.Unlock()
	return f.failRep
}

func (f *fakeSession) Done() {
	f.mu.Lock()
	f.done++
	f.mu.Unlock()
}

func (f *fakeSession) doneCount() int {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.done
}

func startServer(t *testing.T, auth Authenticate) (string, *Server) {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	s := &Server{Authenticate: auth, HandshakeTimeout: 2 * time.Second, DialTimeout: 2 * time.Second}
	go s.Serve(ln)
	t.Cleanup(func() { ln.Close(); s.CloseAll() })
	return ln.Addr().String(), s
}

// connect performs a client handshake with a domain-name CONNECT and returns the reply code.
func connect(t *testing.T, addr, user, pass, host string, port int) (net.Conn, byte) {
	t.Helper()
	c, err := net.Dial("tcp", addr)
	if err != nil {
		t.Fatal(err)
	}
	c.SetDeadline(time.Now().Add(5 * time.Second))
	c.Write([]byte{5, 1, 2})
	var choice [2]byte
	if _, err := io.ReadFull(c, choice[:]); err != nil || choice[1] != 2 {
		t.Fatalf("method: %v %v", choice, err)
	}
	msg := append([]byte{1, byte(len(user))}, user...)
	msg = append(append(msg, byte(len(pass))), pass...)
	c.Write(msg)
	var status [2]byte
	if _, err := io.ReadFull(c, status[:]); err != nil {
		t.Fatalf("auth reply: %v", err)
	}
	if status[1] != 0 {
		return c, 0xff
	}
	req := append([]byte{5, 1, 0, 3, byte(len(host))}, host...)
	req = binary.BigEndian.AppendUint16(req, uint16(port))
	c.Write(req)
	var reply [10]byte
	if _, err := io.ReadFull(c, reply[:]); err != nil {
		t.Fatalf("connect reply: %v", err)
	}
	c.SetDeadline(time.Time{})
	return c, reply[1]
}

func TestConnectRelaysAndKeepsNameUnresolved(t *testing.T) {
	echo, _ := net.Listen("tcp", "127.0.0.1:0")
	defer echo.Close()
	go func() {
		for {
			c, err := echo.Accept()
			if err != nil {
				return
			}
			go func() { io.Copy(c, c); c.Close() }()
		}
	}()
	session := &fakeSession{dial: func(ctx context.Context, target string) (net.Conn, error) {
		return (&net.Dialer{}).DialContext(ctx, "tcp", echo.Addr().String())
	}}
	addr, _ := startServer(t, func(u, p string) (Session, bool) { return session, u == "u" && p == "p" })
	c, rep := connect(t, addr, "u", "p", "peer.example.ts.net", 22)
	if rep != RepSucceeded {
		t.Fatalf("reply %d", rep)
	}
	c.Write([]byte("ping"))
	buf := make([]byte, 4)
	io.ReadFull(c, buf)
	if string(buf) != "ping" {
		t.Fatalf("relay got %q", buf)
	}
	c.Close()
	if session.dialed[0] != "peer.example.ts.net:22" {
		t.Fatalf("target %q", session.dialed[0])
	}
	waitFor(t, func() bool { return session.doneCount() == 1 })
}

func TestBadCredentialsRejectedWithoutSession(t *testing.T) {
	addr, _ := startServer(t, func(u, p string) (Session, bool) { return nil, false })
	c, rep := connect(t, addr, "u", "wrong", "x", 1)
	defer c.Close()
	if rep != 0xff {
		t.Fatalf("expected auth failure, got %d", rep)
	}
}

func TestDialFailureRepliesWithSessionCode(t *testing.T) {
	session := &fakeSession{failRep: RepNotAllowed, dial: func(context.Context, string) (net.Conn, error) {
		return nil, errors.New("not in tailnet")
	}}
	addr, _ := startServer(t, func(string, string) (Session, bool) { return session, true })
	c, rep := connect(t, addr, "u", "p", "outside.example", 22)
	c.Close()
	if rep != RepNotAllowed || len(session.failed) != 1 {
		t.Fatalf("reply %d, failed %v", rep, session.failed)
	}
	waitFor(t, func() bool { return session.doneCount() == 1 })
}

func TestNoAuthMethodRefused(t *testing.T) {
	addr, _ := startServer(t, func(string, string) (Session, bool) { t.Fatal("must not authenticate"); return nil, false })
	c, _ := net.Dial("tcp", addr)
	defer c.Close()
	c.Write([]byte{5, 1, 0})
	var reply [2]byte
	io.ReadFull(c, reply[:])
	if reply[1] != 0xff {
		t.Fatalf("no-auth must be refused, got %v", reply)
	}
}

func TestCloseAllEndsLiveConnections(t *testing.T) {
	upstreamA, upstreamB := net.Pipe()
	defer upstreamB.Close()
	session := &fakeSession{dial: func(context.Context, string) (net.Conn, error) { return upstreamA, nil }}
	addr, s := startServer(t, func(string, string) (Session, bool) { return session, true })
	c, rep := connect(t, addr, "u", "p", "x", 1)
	if rep != RepSucceeded {
		t.Fatal(rep)
	}
	s.CloseAll()
	c.SetReadDeadline(time.Now().Add(2 * time.Second))
	if _, err := c.Read(make([]byte, 1)); err == nil {
		t.Fatal("connection still open")
	}
	waitFor(t, func() bool { return session.doneCount() == 1 })
}

func waitFor(t *testing.T, cond func() bool) {
	t.Helper()
	deadline := time.Now().Add(3 * time.Second)
	for !cond() {
		if time.Now().After(deadline) {
			t.Fatal("condition not met")
		}
		time.Sleep(10 * time.Millisecond)
	}
}
