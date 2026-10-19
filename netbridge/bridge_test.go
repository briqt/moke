package netbridge

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/netip"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/briqt/moke/netbridge/internal/codes"
	"github.com/briqt/moke/netbridge/internal/tailnet"
	"golang.org/x/net/proxy"
	"tailscale.com/net/socks5"
	"tailscale.com/types/logger"
)

const snapshot = `{"interfaces":[{"index":2,"name":"wlan0","mtu":1500,"up":true,"addresses":[{"ip":"192.168.1.2","prefix":24}]}],"defaultInterface":"wlan0"}`

type memStore struct {
	mu sync.Mutex
	m  map[string][]byte
}

func (s *memStore) Read(id, key string) ([]byte, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.m[id+"/"+key], nil
}

func (s *memStore) Write(id, key string, value []byte) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.m == nil {
		s.m = map[string][]byte{}
	}
	if value == nil {
		delete(s.m, id+"/"+key)
	} else {
		s.m[id+"/"+key] = value
	}
	return nil
}

type fakeNode struct {
	startErr   error
	startGate  chan struct{}
	closed     atomic.Int32
	changed    atomic.Int32
	mu         sync.Mutex
	state      string
	authURL    string
	loginCalls int
	dial       func(ctx context.Context, target string) (net.Conn, error)
	packet     func() (net.PacketConn, error)
}

func (f *fakeNode) Start() error {
	if f.startGate != nil {
		<-f.startGate
	}
	return f.startErr
}
func (f *fakeNode) Close() error { f.closed.Add(1); return nil }
func (f *fakeNode) Snapshot(context.Context) (tailnet.Snapshot, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	return tailnet.Snapshot{State: f.state, AuthURL: f.authURL, IPs: []netip.Addr{netip.MustParseAddr("100.64.0.1")}}, nil
}
func (f *fakeNode) StartLogin(context.Context) error {
	f.mu.Lock()
	f.loginCalls++
	f.mu.Unlock()
	go func() {
		time.Sleep(150 * time.Millisecond)
		f.mu.Lock()
		f.authURL = "https://control.example/a/secret-login-token"
		f.mu.Unlock()
	}()
	return nil
}
func (f *fakeNode) Logout(context.Context) error { return nil }
func (f *fakeNode) Dial(ctx context.Context, target string) (net.Conn, error) {
	return f.dial(ctx, target)
}
func (f *fakeNode) ResolveUDP(_ context.Context, target string) (netip.AddrPort, error) {
	ap, err := netip.ParseAddrPort(target)
	if err != nil || !netip.MustParsePrefix("100.64.0.0/10").Contains(ap.Addr()) {
		return netip.AddrPort{}, codes.New(codes.NotInTailnet, "outside")
	}
	return ap, nil
}
func (f *fakeNode) IPs() []netip.Addr { return []netip.Addr{netip.MustParseAddr("100.64.0.1")} }
func (f *fakeNode) ListenPacket(string, string) (net.PacketConn, error) {
	if f.packet != nil {
		return f.packet()
	}
	return net.ListenPacket("udp4", "127.0.0.1:0")
}
func (f *fakeNode) InterfacesChanged() { f.changed.Add(1) }

func echoServer(t *testing.T) string {
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
			go func() { io.Copy(c, c); c.Close() }()
		}
	}()
	return ln.Addr().String()
}

type testBridge struct {
	*bridge
	nodes   map[string]*fakeNode
	route   atomic.Value
	nextNow atomic.Int64 // Extra seconds added to time.Now.
}

func newTestBridge(t *testing.T) *testBridge {
	t.Helper()
	tb := &testBridge{bridge: newBridge(), nodes: map[string]*fakeNode{}}
	tb.setenv = func(string, string) error { return nil }
	tb.reportDefault = func(name string) { tb.route.Store(name) }
	tb.now = func() time.Time { return time.Now().Add(time.Duration(tb.nextNow.Load()) * time.Second) }
	tb.store = &memStore{}
	tb.newNode = func(c tailnet.Config, _ tailnet.Store) tailnetNode {
		n := tb.nodes[c.ID]
		if n == nil {
			t.Fatalf("no fake for %s", c.ID)
		}
		return n
	}
	if err := tb.setLogsDir(t.TempDir()); err != nil {
		t.Fatal(err)
	}
	if err := tb.initialize(snapshot); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		tb.mu.Lock()
		ln := tb.ln
		tb.mu.Unlock()
		ln.Close()
		tb.socks.CloseAll()
	})
	return tb
}

func (tb *testBridge) startFake(t *testing.T, id string, n *fakeNode) {
	t.Helper()
	tb.nodes[id] = n
	cfg := fmt.Sprintf(`{"id":%q,"dir":%q,"controlURL":"https://control.example","hostname":"moke-test"}`, id, t.TempDir())
	if err := tb.startNode(cfg); err != nil {
		t.Fatal(err)
	}
}

func dialVia(t *testing.T, info dialInfo, target string) (net.Conn, error) {
	t.Helper()
	d, err := proxy.SOCKS5("tcp", info.Addr, &proxy.Auth{User: info.Username, Password: info.Password}, &net.Dialer{Timeout: 3 * time.Second})
	if err != nil {
		t.Fatal(err)
	}
	return d.Dial("tcp", target)
}

func codeOf(t *testing.T, err error) codes.Code {
	t.Helper()
	var e codes.Error
	if err == nil || json.Unmarshal([]byte(err.Error()), &e) != nil {
		t.Fatalf("not a coded error: %v", err)
	}
	return e.Code
}

func waitUntil(t *testing.T, cond func() bool) {
	t.Helper()
	deadline := time.Now().Add(3 * time.Second)
	for !cond() {
		if time.Now().After(deadline) {
			t.Fatal("condition not met")
		}
		time.Sleep(10 * time.Millisecond)
	}
}

func TestTailnetDialHoldsLeaseUntilConnectionEnds(t *testing.T) {
	tb := newTestBridge(t)
	echo := echoServer(t)
	var targets []string
	tb.startFake(t, "home", &fakeNode{state: "Running", dial: func(ctx context.Context, target string) (net.Conn, error) {
		targets = append(targets, target)
		return (&net.Dialer{}).DialContext(ctx, "tcp", echo)
	}})
	info, err := tb.openDial("home")
	if err != nil {
		t.Fatal(err)
	}
	if tb.leaseCount("home") != 1 {
		t.Fatal("ticket must hold a lease")
	}
	conn, err := dialVia(t, info, "build-box:22")
	if err != nil {
		t.Fatal(err)
	}
	conn.Write([]byte("hi"))
	buf := make([]byte, 2)
	io.ReadFull(conn, buf)
	if string(buf) != "hi" || targets[0] != "build-box:22" {
		t.Fatalf("relay %q target %v", buf, targets)
	}
	if code := codeOf(t, tb.stop("home")); code != codes.Busy {
		t.Fatalf("stop with live connection: %s", code)
	}
	conn.Close()
	waitUntil(t, func() bool { return tb.leaseCount("home") == 0 })
	if err := tb.stop("home"); err != nil {
		t.Fatal(err)
	}
	if tb.nodes["home"].closed.Load() != 1 {
		t.Fatal("node not closed")
	}
	if _, err := tb.openDial("home"); codeOf(t, err) != codes.NotStarted {
		t.Fatal("stopped network still usable")
	}
}

func TestTicketsAreSingleUseAndExpire(t *testing.T) {
	tb := newTestBridge(t)
	echo := echoServer(t)
	tb.startFake(t, "home", &fakeNode{state: "Running", dial: func(ctx context.Context, _ string) (net.Conn, error) {
		return (&net.Dialer{}).DialContext(ctx, "tcp", echo)
	}})
	info, _ := tb.openDial("home")
	first, err := dialVia(t, info, "a:1")
	if err != nil {
		t.Fatal(err)
	}
	defer first.Close()
	if _, err := dialVia(t, info, "a:1"); err == nil {
		t.Fatal("credentials reused")
	}
	unused, _ := tb.openDial("home")
	if tb.leaseCount("home") != 2 {
		t.Fatalf("leases %d", tb.leaseCount("home"))
	}
	tb.nextNow.Store(int64(ticketTTL/time.Second) + 1)
	tb.sweepTickets()
	if tb.leaseCount("home") != 1 {
		t.Fatalf("expired ticket kept its lease: %d", tb.leaseCount("home"))
	}
	if _, err := dialVia(t, unused, "a:1"); err == nil {
		t.Fatal("expired credentials accepted")
	}
	tb.nextNow.Store(0)
	cancelled, _ := tb.openDial("home")
	tb.cancelDial(cancelled.Username)
	tb.cancelDial(cancelled.Username) // Idempotent.
	if tb.leaseCount("home") != 1 {
		t.Fatalf("cancel did not return the lease: %d", tb.leaseCount("home"))
	}
}

func TestDialFailureIsRecordedPerConnection(t *testing.T) {
	tb := newTestBridge(t)
	tb.startFake(t, "home", &fakeNode{state: "Running", dial: func(context.Context, string) (net.Conn, error) {
		return nil, codes.New(codes.NotInTailnet, "address is not routed by this tailnet")
	}})
	info, _ := tb.openDial("home")
	if _, err := dialVia(t, info, "192.168.1.10:22"); err == nil {
		t.Fatal("dial outside tailnet succeeded")
	}
	e := tb.dialErrorOf(info.Username)
	if e == nil || e.Code != codes.NotInTailnet {
		t.Fatalf("recorded %v", e)
	}
	if tb.dialErrorOf(info.Username) != nil {
		t.Fatal("dial error not cleared after read")
	}
	waitUntil(t, func() bool { return tb.leaseCount("home") == 0 })
}

func TestFailedStartIsClosedAndCanRetry(t *testing.T) {
	tb := newTestBridge(t)
	bad := &fakeNode{startErr: errors.New("state store said tskey-auth-leak")}
	tb.nodes["home"] = bad
	cfg := fmt.Sprintf(`{"id":"home","dir":%q,"controlURL":"https://control.example","hostname":"moke-test","authKey":"tskey-auth-leak"}`, t.TempDir())
	err := tb.startNode(cfg)
	if err == nil || strings.Contains(err.Error(), "tskey-auth-leak") {
		t.Fatalf("error %v", err)
	}
	if bad.closed.Load() != 1 {
		t.Fatal("failed start not closed")
	}
	tb.nodes["home"] = &fakeNode{state: "Running"}
	if err := tb.startNode(cfg); err != nil {
		t.Fatalf("retry: %v", err)
	}
}

func TestSlowStartDoesNotBlockOtherNetworks(t *testing.T) {
	tb := newTestBridge(t)
	gate := make(chan struct{})
	tb.nodes["slow"] = &fakeNode{state: "Running", startGate: gate}
	started := make(chan error, 1)
	go func() {
		started <- tb.startNode(fmt.Sprintf(`{"id":"slow","dir":%q,"controlURL":"https://control.example","hostname":"moke-test"}`, t.TempDir()))
	}()
	waitUntil(t, func() bool {
		tb.mu.Lock()
		defer tb.mu.Unlock()
		return tb.networks["slow"] != nil
	})
	done := make(chan struct{})
	go func() {
		defer close(done)
		if err := tb.startProxy(`{"id":"office","type":"socks5","host":"127.0.0.1","port":1080}`); err != nil {
			t.Error(err)
		}
		if _, err := tb.openDial("office"); err != nil {
			t.Error(err)
		}
		if _, err := tb.openDial("slow"); err == nil {
			t.Error("network usable before its start finished")
		}
		tb.interfacesChanged()
	}()
	select {
	case <-done:
	case <-time.After(2 * time.Second):
		t.Fatal("other network blocked by a slow start")
	}
	close(gate)
	if err := <-started; err != nil {
		t.Fatal(err)
	}
	if _, err := tb.openDial("slow"); err != nil {
		t.Fatal(err)
	}
}

func TestStartNodeValidation(t *testing.T) {
	tb := newTestBridge(t)
	dir := t.TempDir()
	tb.nodes["a"] = &fakeNode{state: "Running"}
	tb.nodes["b"] = &fakeNode{state: "Running"}
	ok := fmt.Sprintf(`{"id":"a","dir":%q,"controlURL":"https://control.example","hostname":"moke-test"}`, dir)
	if err := tb.startNode(ok); err != nil {
		t.Fatal(err)
	}
	if codeOf(t, tb.startNode(ok)) != codes.AlreadyStarted {
		t.Fatal("duplicate ID accepted")
	}
	sameDir := fmt.Sprintf(`{"id":"b","dir":%q,"controlURL":"https://control.example","hostname":"moke-test"}`, dir+"/.")
	if codeOf(t, tb.startNode(sameDir)) != codes.InvalidArgument {
		t.Fatal("shared dir accepted")
	}
	for _, bad := range []string{
		`{"id":"c d","dir":"/x","controlURL":"https://c.example","hostname":"h"}`,
		`{"id":"c","dir":"relative","controlURL":"https://c.example","hostname":"h"}`,
		`{"id":"c","dir":"/x","controlURL":"http://c.example","hostname":"h"}`,
		`{"id":"c","dir":"/x","controlURL":"https://user:pw@c.example","hostname":"h"}`,
		`{"id":"c","dir":"/x","controlURL":"https://c.example","hostname":"a/b"}`,
		`not json`,
	} {
		if codeOf(t, tb.startNode(bad)) != codes.InvalidArgument {
			t.Errorf("accepted %s", bad)
		}
	}
	fresh := newBridge()
	fresh.setenv = func(string, string) error { return nil }
	fresh.reportDefault = func(string) {}
	if codeOf(t, fresh.startNode(ok)) != codes.NotInitialized {
		t.Fatal("start without store/logs dir accepted")
	}
}

func TestInterfacesChangedReportsDefaultRouteThenNodes(t *testing.T) {
	tb := newTestBridge(t)
	n := &fakeNode{state: "Running"}
	tb.startFake(t, "home", n)
	before := n.changed.Load()
	if err := tb.replaceInterfaces(strings.Replace(snapshot, `"defaultInterface":"wlan0"`, `"defaultInterface":""`, 1)); err != nil {
		t.Fatal(err)
	}
	if n.changed.Load() != before+1 || tb.route.Load() != "" {
		t.Fatalf("changed %d route %v", n.changed.Load(), tb.route.Load())
	}
	if codeOf(t, tb.replaceInterfaces(`{"interfaces":null}`)) != codes.InvalidArgument {
		t.Fatal("bad snapshot accepted")
	}
}

func TestLoginURLWaitsForControlAndNeverLogsIt(t *testing.T) {
	tb := newTestBridge(t)
	n := &fakeNode{state: "NeedsLogin"}
	tb.startFake(t, "home", n)
	url, err := tb.loginURL("home")
	if err != nil || !strings.HasPrefix(url, "https://control.example/a/") {
		t.Fatalf("url %q err %v", url, err)
	}
	n.mu.Lock()
	n.state = "Running"
	n.mu.Unlock()
	if url, err := tb.loginURL("home"); url != "" || err != nil {
		t.Fatalf("running node asked to log in: %q %v", url, err)
	}
	status, err := NodeStatusFor(tb.bridge, "home")
	if err != nil || strings.Contains(status, "secret-login-token") {
		t.Fatalf("status leaks login URL: %s %v", status, err)
	}
}

// NodeStatusFor is NodeStatus against a given bridge (tests only).
func NodeStatusFor(b *bridge, id string) (string, error) {
	saved := defaultBridge
	defaultBridge = b
	defer func() { defaultBridge = saved }()
	return NodeStatus(id)
}

func TestProxyEndToEndAndAuthFailureCode(t *testing.T) {
	tb := newTestBridge(t)
	echo := echoServer(t)
	upstream, _ := net.Listen("tcp", "127.0.0.1:0")
	defer upstream.Close()
	go (&socks5.Server{Logf: logger.Discard, Username: "u", Password: "p"}).Serve(upstream)
	host, port, _ := net.SplitHostPort(upstream.Addr().String())
	if err := tb.startProxy(fmt.Sprintf(`{"id":"office","type":"socks5","host":%q,"port":%s,"username":"u","password":"p"}`, host, port)); err != nil {
		t.Fatal(err)
	}
	info, _ := tb.openDial("office")
	conn, err := dialVia(t, info, echo)
	if err != nil {
		t.Fatal(err)
	}
	conn.Write([]byte("ok"))
	buf := make([]byte, 2)
	io.ReadFull(conn, buf)
	conn.Close()
	if string(buf) != "ok" {
		t.Fatalf("got %q", buf)
	}

	// An HTTP proxy that wants other credentials.
	httpProxy, _ := net.Listen("tcp", "127.0.0.1:0")
	defer httpProxy.Close()
	go func() {
		for {
			c, err := httpProxy.Accept()
			if err != nil {
				return
			}
			go func() {
				http.ReadRequest(bufio.NewReader(c))
				io.WriteString(c, "HTTP/1.1 407 Proxy Authentication Required\r\nContent-Length: 0\r\n\r\n")
				c.Close()
			}()
		}
	}()
	host, port, _ = net.SplitHostPort(httpProxy.Addr().String())
	tb.startProxy(fmt.Sprintf(`{"id":"web","type":"http","host":%q,"port":%s,"username":"u","password":"hunter22"}`, host, port))
	info, _ = tb.openDial("web")
	if _, err := dialVia(t, info, "example.com:443"); err == nil {
		t.Fatal("407 not reported")
	}
	e := tb.dialErrorOf(info.Username)
	if e == nil || e.Code != codes.ProxyAuth || strings.Contains(e.Detail, "hunter22") {
		t.Fatalf("recorded %+v", e)
	}
	if _, err := tb.openUDPRelay(`{"id":"r1","networkID":"web","targetHost":"203.0.113.5"}`); codeOf(t, err) != codes.ProxyNoUDP {
		t.Fatal("UDP through an HTTP proxy accepted")
	}
	if tb.leaseCount("web") != 0 {
		t.Fatal("refused relay kept a lease")
	}
}

func TestRelayHoldsLeaseAndStaysInTailnet(t *testing.T) {
	tb := newTestBridge(t)
	tb.startFake(t, "home", &fakeNode{state: "Running"})
	if _, err := tb.openUDPRelay(`{"id":"r0","networkID":"home","targetHost":"203.0.113.5"}`); codeOf(t, err) != codes.NotInTailnet {
		t.Fatal("relay outside tailnet accepted")
	}
	// Names are resolved first (ResolveTarget), so the relay never looks one up.
	if _, err := tb.openUDPRelay(`{"id":"r0","networkID":"home","targetHost":"peer"}`); codeOf(t, err) != codes.InvalidArgument {
		t.Fatal("tailnet relay accepted a name")
	}
	if tb.leaseCount("home") != 0 {
		t.Fatal("refused relay kept a lease")
	}
	info, err := tb.openUDPRelay(`{"id":"r1","networkID":"home","targetHost":"100.64.0.2"}`)
	if err != nil {
		t.Fatal(err)
	}
	const key = "AAAAAAAAAAAAAAAAAAAAAA"
	if err := tb.startUDPRelay("r1", 60001, key[:21]+"B"); err == nil {
		t.Fatal("non-canonical key accepted")
	}
	if err := tb.startUDPRelay("r1", 0, key); err == nil || strings.Contains(err.Error(), key) {
		t.Fatalf("bad port: %v", err)
	}
	if err := tb.startUDPRelay("r1", 60001, key); err != nil {
		t.Fatal(err)
	}
	if info.LocalPort == 0 || tb.leaseCount("home") != 1 {
		t.Fatalf("info %+v leases %d", info, tb.leaseCount("home"))
	}
	if codeOf(t, tb.stop("home")) != codes.Busy {
		t.Fatal("stopped under a live relay")
	}
	if err := tb.closeUDPRelay("r1"); err != nil {
		t.Fatal(err)
	}
	if err := tb.closeUDPRelay("r1"); err != nil { // Idempotent, no double release.
		t.Fatal(err)
	}
	if tb.leaseCount("home") != 0 {
		t.Fatalf("leases %d", tb.leaseCount("home"))
	}
	if err := tb.stop("home"); err != nil {
		t.Fatal(err)
	}
}

func TestConcurrentRelayCloseKeepsLiveConnectionLease(t *testing.T) {
	tb := newTestBridge(t)
	echo := echoServer(t)
	tb.startFake(t, "home", &fakeNode{state: "Running", dial: func(ctx context.Context, _ string) (net.Conn, error) {
		return (&net.Dialer{}).DialContext(ctx, "tcp", echo)
	}})
	ticket, err := tb.openDial("home")
	if err != nil {
		t.Fatal(err)
	}
	conn, err := dialVia(t, ticket, "peer:22")
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	for i := 0; i < 100; i++ {
		if _, err := tb.openUDPRelay(`{"id":"r","networkID":"home","targetHost":"100.64.0.2"}`); err != nil {
			t.Fatal(err)
		}
		gate := make(chan struct{})
		var ready, done sync.WaitGroup
		for range 32 {
			ready.Add(1)
			done.Add(1)
			go func() {
				defer done.Done()
				ready.Done()
				<-gate
				if err := tb.closeUDPRelay("r"); err != nil {
					t.Errorf("close: %v", err)
				}
			}()
		}
		ready.Wait()
		close(gate)
		done.Wait()
		if got := tb.leaseCount("home"); got != 1 {
			t.Fatalf("wave %d: repeated relay close stole live connection lease: %d", i, got)
		}
		if codeOf(t, tb.stop("home")) != codes.Busy {
			t.Fatal("stop did not remain BUSY under a live connection")
		}
	}
	conn.SetDeadline(time.Now().Add(time.Second))
	conn.Write([]byte("alive"))
	buf := make([]byte, 5)
	if _, err := io.ReadFull(conn, buf); err != nil || string(buf) != "alive" {
		t.Fatalf("other connection affected by relay closes: %q, %v", buf, err)
	}
}

type blockedPacketClose struct {
	net.PacketConn
	entered chan struct{}
	gate    chan struct{}
}

func (c *blockedPacketClose) Close() error {
	close(c.entered)
	<-c.gate
	c.PacketConn.Close()
	return errors.New("injected close failure")
}

func TestRelayClosingRetainsLeaseUntilShutdownAndIDReuse(t *testing.T) {
	tb := newTestBridge(t)
	udp, err := net.ListenPacket("udp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	blocked := &blockedPacketClose{PacketConn: udp, entered: make(chan struct{}), gate: make(chan struct{})}
	var unblock sync.Once
	release := func() { unblock.Do(func() { close(blocked.gate) }) }
	t.Cleanup(func() { release(); tb.relays.CloseAll(); udp.Close() })
	opens := 0
	tb.startFake(t, "home", &fakeNode{state: "Running", packet: func() (net.PacketConn, error) {
		opens++
		if opens == 1 {
			return blocked, nil
		}
		return net.ListenPacket("udp4", "127.0.0.1:0")
	}})
	ticket, err := tb.openDial("home")
	if err != nil {
		t.Fatal(err)
	}
	defer tb.cancelDial(ticket.Username)
	cfg := `{"id":"r","networkID":"home","targetHost":"100.64.0.2"}`
	if _, err := tb.openUDPRelay(cfg); err != nil {
		t.Fatal(err)
	}
	done := make(chan error, 1)
	go func() { done <- tb.closeUDPRelay("r") }()
	select {
	case <-blocked.entered:
	case <-time.After(time.Second):
		t.Fatal("relay never reached socket Close")
	}
	if err := tb.closeUDPRelay("r"); err != nil || tb.leaseCount("home") != 2 {
		t.Fatalf("non-owner released a lease: %v, leases=%d", err, tb.leaseCount("home"))
	}
	if codeOf(t, tb.stop("home")) != codes.Busy {
		t.Fatal("stop cut a relay still shutting down")
	}
	if _, err := tb.openUDPRelay(cfg); err != nil {
		t.Fatal(err)
	}
	if err := tb.startUDPRelay("r", 60000, "AAAAAAAAAAAAAAAAAAAAAA"); err != nil {
		t.Fatal(err)
	}
	if err := tb.relays.CloseAll(); err != nil || tb.leaseCount("home") != 2 {
		t.Fatalf("replacement/bulk close released wrong owner: %v", err)
	}
	release()
	if err := <-done; err == nil || tb.leaseCount("home") != 1 {
		t.Fatalf("failed shutdown skipped lease return: %v, leases=%d", err, tb.leaseCount("home"))
	}
	if codeOf(t, tb.stop("home")) != codes.Busy {
		t.Fatal("old relay release stole remaining ticket lease")
	}
}

func TestRelayBulkCloseReturnsOnlyOwnedLeases(t *testing.T) {
	tb := newTestBridge(t)
	tb.startFake(t, "a", &fakeNode{state: "Running"})
	tb.startFake(t, "b", &fakeNode{state: "Running"})
	for _, value := range []struct{ id, network string }{{"r1", "a"}, {"r2", "a"}, {"r3", "b"}} {
		if _, err := tb.openUDPRelay(fmt.Sprintf(`{"id":%q,"networkID":%q,"targetHost":"100.64.0.2"}`, value.id, value.network)); err != nil {
			t.Fatal(err)
		}
	}
	ticket, err := tb.openDial("a")
	if err != nil {
		t.Fatal(err)
	}
	defer tb.cancelDial(ticket.Username)
	if err := tb.relays.CloseNetwork("a"); err != nil {
		t.Fatal(err)
	}
	if tb.leaseCount("a") != 1 || tb.leaseCount("b") != 1 {
		t.Fatalf("bulk close: a=%d b=%d", tb.leaseCount("a"), tb.leaseCount("b"))
	}
	if err := tb.relays.CloseAll(); err != nil {
		t.Fatal(err)
	}
	tb.closeUDPRelay("r1")
	tb.closeUDPRelay("r3")
	if tb.leaseCount("a") != 1 || tb.leaseCount("b") != 0 {
		t.Fatalf("repeated/bulk close: a=%d b=%d", tb.leaseCount("a"), tb.leaseCount("b"))
	}
	if codeOf(t, tb.stop("a")) != codes.Busy {
		t.Fatal("bulk close stole a dial ticket lease")
	}
	if err := tb.stop("b"); err != nil {
		t.Fatal(err)
	}
}

func TestConcurrentDialsAndStopsAreRaceFree(t *testing.T) {
	tb := newTestBridge(t)
	echo := echoServer(t)
	tb.startFake(t, "home", &fakeNode{state: "Running", dial: func(ctx context.Context, _ string) (net.Conn, error) {
		return (&net.Dialer{}).DialContext(ctx, "tcp", echo)
	}})
	var wg sync.WaitGroup
	for i := 0; i < 20; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			info, err := tb.openDial("home")
			if err != nil {
				return
			}
			if c, err := dialVia(t, info, "x:1"); err == nil {
				c.Close()
			}
			tb.stop("home") // Must refuse while others are connected.
		}()
	}
	wg.Wait()
	waitUntil(t, func() bool { return tb.leaseCount("home") == 0 })
}
