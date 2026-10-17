package relay

import (
	"bytes"
	"errors"
	"fmt"
	"net"
	"net/netip"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

type datagram struct {
	data []byte
	addr net.Addr
}

type fakePacketConn struct {
	addr       net.Addr
	reads      chan datagram
	writes     chan datagram
	closed     chan struct{}
	once       sync.Once
	closeCount atomic.Int32
	writeErr   error
	failWrites atomic.Int32 // Transient failures to return before writing normally.
}

func fakeConn(address string) *fakePacketConn {
	return &fakePacketConn{
		addr:  net.UDPAddrFromAddrPort(netip.MustParseAddrPort(address)),
		reads: make(chan datagram, 16), writes: make(chan datagram, 16), closed: make(chan struct{}),
	}
}

func (f *fakePacketConn) ReadFrom(p []byte) (int, net.Addr, error) {
	select {
	case d := <-f.reads:
		return copy(p, d.data), d.addr, nil
	case <-f.closed:
		return 0, nil, net.ErrClosed
	}
}

func (f *fakePacketConn) WriteTo(p []byte, addr net.Addr) (int, error) {
	if f.writeErr != nil {
		return 0, f.writeErr
	}
	if f.failWrites.Add(-1) >= 0 {
		return 0, errors.New("transient send failure")
	}
	select {
	case f.writes <- datagram{data: append([]byte(nil), p...), addr: addr}:
		return len(p), nil
	case <-f.closed:
		return 0, net.ErrClosed
	}
}

func (f *fakePacketConn) Close() error {
	f.once.Do(func() {
		f.closeCount.Add(1)
		close(f.closed)
	})
	return nil
}
func (f *fakePacketConn) LocalAddr() net.Addr              { return f.addr }
func (f *fakePacketConn) SetDeadline(time.Time) error      { return nil }
func (f *fakePacketConn) SetReadDeadline(time.Time) error  { return nil }
func (f *fakePacketConn) SetWriteDeadline(time.Time) error { return nil }

func sendPacket(f *fakePacketConn, data []byte, source string) {
	f.reads <- datagram{data: data, addr: net.UDPAddrFromAddrPort(netip.MustParseAddrPort(source))}
}

func receivePacket(t *testing.T, f *fakePacketConn, data []byte, target string) {
	t.Helper()
	select {
	case d := <-f.writes:
		if !bytes.Equal(d.data, data) || d.addr.String() != target {
			t.Fatalf("datagram = %d bytes to %v, want %d bytes to %s", len(d.data), d.addr, len(data), target)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("timed out waiting for datagram")
	}
}

func newFixture(t *testing.T) (*Manager, *fakePacketConn, *fakePacketConn) {
	t.Helper()
	local := fakeConn("127.0.0.1:32000")
	remote := fakeConn("100.64.0.1:33000")
	m := NewManager()
	m.listenLocal = func(network, address string) (net.PacketConn, error) {
		if network != "udp4" || address != "127.0.0.1:0" {
			t.Errorf("local bind %q %q", network, address)
		}
		return local, nil
	}
	info, err := m.Open(Config{ID: "s1", NetworkID: "a", TargetIP: "100.64.0.2", TargetPort: 60000}, Network{
		ID: "a", IPs: []netip.Addr{netip.MustParseAddr("fd7a:115c:a1e0::1"), netip.MustParseAddr("100.64.0.1")},
		ListenPacket: func(network, address string) (net.PacketConn, error) {
			if network != "udp" || address != "100.64.0.1:0" {
				t.Errorf("node bind %q %q", network, address)
			}
			return remote, nil
		},
	})
	if err != nil {
		t.Fatal(err)
	}
	if info != (Info{ID: "s1", NetworkID: "a", LocalHost: "127.0.0.1", LocalPort: 32000}) {
		t.Fatalf("unexpected info: %+v", info)
	}
	t.Cleanup(func() { m.CloseAll() })
	return m, local, remote
}

func TestDatagramBoundariesAndFiltering(t *testing.T) {
	_, local, remote := newFixture(t)
	// Non-loopback traffic cannot claim the local endpoint.
	sendPacket(local, []byte("reject"), "192.0.2.1:40000")
	sendPacket(local, []byte("first"), "127.0.0.1:40000")
	receivePacket(t, remote, []byte("first"), "100.64.0.2:60000")
	sendPacket(local, nil, "127.0.0.1:40000")
	sendPacket(local, []byte("second"), "127.0.0.1:40000")
	receivePacket(t, remote, nil, "100.64.0.2:60000")
	receivePacket(t, remote, []byte("second"), "100.64.0.2:60000")
	// Neither another IP nor another port can inject replies.
	sendPacket(remote, []byte("reject"), "100.64.0.3:60000")
	sendPacket(remote, []byte("reject"), "100.64.0.2:60001")
	sendPacket(remote, []byte("reply"), "100.64.0.2:60000")
	receivePacket(t, local, []byte("reply"), "127.0.0.1:40000")
}

func TestPayloadBoundedBothDirections(t *testing.T) {
	_, local, remote := newFixture(t)
	big := bytes.Repeat([]byte{0xa5}, maxPayload)
	sendPacket(local, append(append([]byte(nil), big...), 1), "127.0.0.1:40000")
	sendPacket(local, big, "127.0.0.1:40000")
	receivePacket(t, remote, big, "100.64.0.2:60000")
	sendPacket(remote, append(append([]byte(nil), big...), 1), "100.64.0.2:60000")
	sendPacket(remote, big, "100.64.0.2:60000")
	receivePacket(t, local, big, "127.0.0.1:40000")
}

func TestCloseIsIdempotentAndUnblocksReaders(t *testing.T) {
	m, local, remote := newFixture(t)
	var wg sync.WaitGroup
	for range 8 {
		wg.Add(1)
		go func() {
			defer wg.Done()
			if err := m.Close("s1"); err != nil {
				t.Errorf("Close: %v", err)
			}
		}()
	}
	completed := make(chan struct{})
	go func() { wg.Wait(); close(completed) }()
	select {
	case <-completed:
	case <-time.After(2 * time.Second):
		t.Fatal("close did not cancel blocked reads")
	}
	if m.Count() != 0 || local.closeCount.Load() != 1 || remote.closeCount.Load() != 1 {
		t.Fatal("close did not release each owned socket exactly once")
	}
	if err := m.CloseAll(); err != nil {
		t.Fatal(err)
	}
}

func TestStrictTargetsAndFamilies(t *testing.T) {
	for _, target := range []string{"peer.example", "::ffff:100.64.0.2", "fe80::1%wlan0", "0.0.0.0", "224.0.0.1", "::"} {
		t.Run(target, func(t *testing.T) {
			c := Config{ID: "s", NetworkID: "a", TargetIP: target, TargetPort: 60000}
			if _, err := c.target(); err == nil {
				t.Fatal("accepted unsupported target")
			}
		})
	}
	if _, err := ParseConfig(`{"id":"s","networkID":"a","targetIP":"peer.example","targetPort":60000}`); err == nil || !strings.Contains(err.Error(), "DNS names are not supported") {
		t.Fatalf("domain error was not explicit: %v", err)
	}
	m := NewManager()
	defer m.CloseAll()
	binds := 0
	network := Network{ID: "a", IPs: []netip.Addr{netip.MustParseAddr("100.64.0.1")}, ListenPacket: func(string, string) (net.PacketConn, error) {
		binds++
		return nil, errors.New("must not bind")
	}}
	if _, err := m.Open(Config{ID: "s", NetworkID: "a", TargetIP: "fd7a:115c:a1e0::2", TargetPort: 60000}, network); err == nil || binds != 0 {
		t.Fatal("IPv6 target fell back to IPv4")
	}
	if _, err := m.Open(Config{ID: "s", NetworkID: "b", TargetIP: "100.64.0.2", TargetPort: 60000}, network); err == nil || binds != 0 {
		t.Fatal("selected a different network")
	}
}

func TestIPv6UsesExplicitAssignedSource(t *testing.T) {
	m := NewManager()
	defer m.CloseAll()
	local := fakeConn("127.0.0.1:32001")
	remote := fakeConn("[fd7a:115c:a1e0::1]:33000")
	m.listenLocal = func(string, string) (net.PacketConn, error) { return local, nil }
	_, err := m.Open(Config{ID: "s", NetworkID: "a", TargetIP: "fd7a:115c:a1e0::2", TargetPort: 60000}, Network{
		ID: "a", IPs: []netip.Addr{netip.MustParseAddr("100.64.0.1"), netip.MustParseAddr("fd7a:115c:a1e0::1")},
		ListenPacket: func(network, address string) (net.PacketConn, error) {
			if network != "udp" || address != "[fd7a:115c:a1e0::1]:0" {
				t.Fatalf("wrong IPv6 bind %q %q", network, address)
			}
			return remote, nil
		},
	})
	if err != nil {
		t.Fatal(err)
	}
	sendPacket(local, []byte("v6"), "127.0.0.1:40000")
	receivePacket(t, remote, []byte("v6"), "[fd7a:115c:a1e0::2]:60000")
	sendPacket(remote, []byte("reply"), "[fd7a:115c:a1e0::2]:60000")
	receivePacket(t, local, []byte("reply"), "127.0.0.1:40000")
}

func TestNetworkCloseDoesNotTouchOtherNode(t *testing.T) {
	m := NewManager()
	defer m.CloseAll()
	locals := []*fakePacketConn{fakeConn("127.0.0.1:32000"), fakeConn("127.0.0.1:32001")}
	remotes := []*fakePacketConn{fakeConn("100.64.0.1:33000"), fakeConn("100.64.0.1:33001")}
	index := 0
	m.listenLocal = func(string, string) (net.PacketConn, error) { return locals[index], nil }
	for i, id := range []string{"a", "b"} {
		index = i
		_, err := m.Open(Config{ID: id, NetworkID: id, TargetIP: "100.64.0.2", TargetPort: 60000}, Network{
			ID: id, IPs: []netip.Addr{netip.MustParseAddr("100.64.0.1")},
			ListenPacket: func(string, string) (net.PacketConn, error) { return remotes[i], nil },
		})
		if err != nil {
			t.Fatal(err)
		}
	}
	sendPacket(locals[0], []byte("network-a"), "127.0.0.1:40000")
	sendPacket(locals[1], []byte("network-b"), "127.0.0.1:40000")
	receivePacket(t, remotes[0], []byte("network-a"), "100.64.0.2:60000")
	receivePacket(t, remotes[1], []byte("network-b"), "100.64.0.2:60000")
	if err := m.CloseNetwork("a"); err != nil {
		t.Fatal(err)
	}
	if m.Count() != 1 || remotes[0].closeCount.Load() != 1 || remotes[1].closeCount.Load() != 0 {
		t.Fatal("closing network a affected network b")
	}
	sendPacket(remotes[1], []byte("still-b"), "100.64.0.2:60000")
	receivePacket(t, locals[1], []byte("still-b"), "127.0.0.1:40000")
}

func TestPartialOpenCleansRemoteAndCanRetry(t *testing.T) {
	m := NewManager()
	defer m.CloseAll()
	remote := fakeConn("100.64.0.1:33000")
	m.listenLocal = func(string, string) (net.PacketConn, error) { return nil, errors.New("local bind failed") }
	c := Config{ID: "s", NetworkID: "a", TargetIP: "100.64.0.2", TargetPort: 60000}
	network := Network{ID: "a", IPs: []netip.Addr{netip.MustParseAddr("100.64.0.1")}, ListenPacket: func(string, string) (net.PacketConn, error) { return remote, nil }}
	if _, err := m.Open(c, network); err == nil || remote.closeCount.Load() != 1 || m.Count() != 0 {
		t.Fatal("partial open leaked a socket or registry entry")
	}
	remote = fakeConn("100.64.0.1:33001")
	m.listenLocal = func(string, string) (net.PacketConn, error) { return fakeConn("127.0.0.1:32001"), nil }
	if _, err := m.Open(c, network); err != nil {
		t.Fatalf("retry after partial failure: %v", err)
	}
}

func TestTransientSendFailureDropsDatagramOnly(t *testing.T) {
	m, local, remote := newFixture(t)
	remote.failWrites.Store(2)
	sendPacket(local, []byte("lost-1"), "127.0.0.1:40000")
	sendPacket(local, []byte("lost-2"), "127.0.0.1:40000")
	sendPacket(local, []byte("after"), "127.0.0.1:40000")
	receivePacket(t, remote, []byte("after"), "100.64.0.2:60000")
	if got := m.Dropped(); got != 2 {
		t.Fatalf("dropped = %d, want 2", got)
	}
	local.failWrites.Store(1)
	sendPacket(remote, []byte("back-lost"), "100.64.0.2:60000")
	sendPacket(remote, []byte("back"), "100.64.0.2:60000")
	receivePacket(t, local, []byte("back"), "127.0.0.1:40000")
	if got := m.Dropped(); got != 3 {
		t.Fatalf("dropped = %d, want 3", got)
	}
	if err := m.Close("s1"); err != nil {
		t.Fatalf("transient drops must not be reported as a terminal error: %v", err)
	}
	if got := m.Dropped(); got != 3 {
		t.Fatalf("closing a relay lost its drop count: %d", got)
	}
}

func TestClosedSocketEndsRelay(t *testing.T) {
	local := fakeConn("127.0.0.1:32001")
	remote := fakeConn("100.64.0.1:33000")
	remote.writeErr = fmt.Errorf("wrapped: %w", net.ErrClosed)
	m := NewManager()
	defer m.CloseAll()
	m.listenLocal = func(string, string) (net.PacketConn, error) { return local, nil }
	_, err := m.Open(Config{ID: "s", NetworkID: "a", TargetIP: "100.64.0.2", TargetPort: 60000}, Network{
		ID: "a", IPs: []netip.Addr{netip.MustParseAddr("100.64.0.1")},
		ListenPacket: func(string, string) (net.PacketConn, error) { return remote, nil },
	})
	if err != nil {
		t.Fatal(err)
	}
	sendPacket(local, []byte("failure"), "127.0.0.1:40000")
	select {
	case <-remote.closed:
	case <-time.After(2 * time.Second):
		t.Fatal("closed socket did not end relay")
	}
	// Registry lifetime is explicit, even after an I/O failure closes sockets.
	if m.Count() != 1 {
		t.Fatal("terminal session disappeared before explicit Close")
	}
	if err := m.Close("s"); err == nil || !errors.Is(err, net.ErrClosed) {
		t.Fatalf("terminal I/O cause was lost: %v", err)
	}
	if err := m.Close("s"); err != nil {
		t.Fatalf("repeated Close: %v", err)
	}
	if m.Count() != 0 || local.closeCount.Load() != 1 || remote.closeCount.Load() != 1 {
		t.Fatal("failed relay leaked or closed twice")
	}
}

func TestFollowsClientSourcePortHop(t *testing.T) {
	m, local, remote := newFixture(t)
	sendPacket(local, []byte("old-port"), "127.0.0.1:40000")
	receivePacket(t, remote, []byte("old-port"), "100.64.0.2:60000")
	// mosh-client hop_port(): later datagrams come from a new loopback source port.
	sendPacket(local, []byte("new-port"), "127.0.0.1:40001")
	receivePacket(t, remote, []byte("new-port"), "100.64.0.2:60000")
	sendPacket(remote, []byte("reply"), "100.64.0.2:60000")
	receivePacket(t, local, []byte("reply"), "127.0.0.1:40001")
	if got := m.Rebinds(); got != 1 {
		t.Fatalf("rebinds = %d, want 1", got)
	}
	// Non-loopback senders still never claim the session.
	sendPacket(local, []byte("reject"), "192.0.2.1:40002")
	sendPacket(remote, []byte("reply-2"), "100.64.0.2:60000")
	receivePacket(t, local, []byte("reply-2"), "127.0.0.1:40001")
}

func TestLoopbackTargetOnlyForProxyBackends(t *testing.T) {
	m := NewManager()
	defer m.CloseAll()
	local := fakeConn("127.0.0.1:32002")
	m.listenLocal = func(string, string) (net.PacketConn, error) { return local, nil }
	c := Config{ID: "lo", NetworkID: "a", TargetIP: "127.0.0.1", TargetPort: 60021}
	node := Network{ID: "a", IPs: []netip.Addr{netip.MustParseAddr("100.64.0.1")},
		ListenPacket: func(string, string) (net.PacketConn, error) { return fakeConn("100.64.0.1:1"), nil }}
	if _, err := m.Open(c, node); err == nil {
		t.Fatal("tailnet relay accepted a loopback target")
	}
	var bind = "unset"
	proxy := Network{ID: "a", AnySource: true, ListenPacket: func(_, address string) (net.PacketConn, error) {
		bind = address
		return fakeConn("192.0.2.9:1"), nil
	}}
	if _, err := m.Open(c, proxy); err != nil || bind != "" {
		t.Fatalf("proxy relay: %v, bind %q", err, bind)
	}
}
