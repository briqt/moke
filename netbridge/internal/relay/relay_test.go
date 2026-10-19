package relay

import (
	"bytes"
	"crypto/rand"
	"encoding/base64"
	"encoding/binary"
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

// testKey is a mosh session key (22 base64 characters, 128 bits).
var testKey = func() string {
	raw := make([]byte, 16)
	rand.Read(raw)
	return base64.RawStdEncoding.EncodeToString(raw)
}()

// moshDatagram builds a datagram as mosh-client sends it: an 8-byte nonce
// (direction bit + sequence) followed by AES-128-OCB ciphertext and tag.
func moshDatagram(t *testing.T, key string, seq uint64, toClient bool, payload string) []byte {
	t.Helper()
	aead, err := moshCipher(key)
	if err != nil {
		t.Fatal(err)
	}
	wire := seq
	if toClient {
		wire |= directionMask
	}
	var nonce [12]byte
	binary.BigEndian.PutUint64(nonce[4:], wire)
	return aead.Seal(append([]byte(nil), nonce[4:]...), nonce[:], []byte(payload), nil)
}

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
	info, err := m.Open(Config{ID: "s1", NetworkID: "a", TargetHost: "100.64.0.2"}, Network{
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
	if err := m.Start("s1", 60000, testKey); err != nil {
		t.Fatal(err)
	}
	return m, local, remote
}

func TestDatagramBoundariesAndFiltering(t *testing.T) {
	m, local, remote := newFixture(t)
	// Non-loopback traffic cannot claim the local endpoint, even when authentic.
	sendPacket(local, moshDatagram(t, testKey, 0, false, "reject"), "192.0.2.1:40000")
	first := moshDatagram(t, testKey, 1, false, "first")
	sendPacket(local, first, "127.0.0.1:40000")
	receivePacket(t, remote, first, "100.64.0.2:60000")
	// Too short to carry a nonce and tag: dropped.
	sendPacket(local, nil, "127.0.0.1:40000")
	sendPacket(local, make([]byte, moshNonceWire+moshTag-1), "127.0.0.1:40000")
	second := moshDatagram(t, testKey, 2, false, "")
	sendPacket(local, second, "127.0.0.1:40000")
	receivePacket(t, remote, second, "100.64.0.2:60000")
	if got := m.Rejected(); got != 2 {
		t.Fatalf("rejected = %d, want 2", got)
	}
	// Neither another IP nor another port can inject replies.
	sendPacket(remote, []byte("reject"), "100.64.0.3:60000")
	sendPacket(remote, []byte("reject"), "100.64.0.2:60001")
	sendPacket(remote, []byte("reply"), "100.64.0.2:60000")
	receivePacket(t, local, []byte("reply"), "127.0.0.1:40000")
}

func TestPayloadBoundedBothDirections(t *testing.T) {
	_, local, remote := newFixture(t)
	overhead := moshNonceWire + moshTag
	sendPacket(local, moshDatagram(t, testKey, 0, false, strings.Repeat("x", maxPayload+1-overhead)), "127.0.0.1:40000")
	fits := moshDatagram(t, testKey, 1, false, strings.Repeat("x", maxPayload-overhead))
	sendPacket(local, fits, "127.0.0.1:40000")
	receivePacket(t, remote, fits, "100.64.0.2:60000")
	big := bytes.Repeat([]byte{0xa5}, maxPayload)
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

type gatedCloseConn struct {
	*fakePacketConn
	entered chan struct{}
	release chan struct{}
	err     error
}

func (c *gatedCloseConn) Close() error {
	close(c.entered)
	<-c.release
	c.fakePacketConn.Close()
	return c.err
}

func TestCloseOwnershipSurvivesIDReuseAndBulkClose(t *testing.T) {
	m := NewManager()
	gate := make(chan struct{})
	var release sync.Once
	unblock := func() { release.Do(func() { close(gate) }) }
	t.Cleanup(func() { unblock(); m.CloseAll() })
	closeErr := errors.New("socket close failed")
	first := &gatedCloseConn{fakePacketConn: fakeConn("100.64.0.1:33000"), entered: make(chan struct{}), release: gate, err: closeErr}
	var closed [3]atomic.Int32
	open := func(index int, networkID string) *session {
		m.listenLocal = func(string, string) (net.PacketConn, error) { return fakeConn("127.0.0.1:32000"), nil }
		remote := net.PacketConn(fakeConn("100.64.0.1:33001"))
		if index == 0 {
			remote = first
		}
		_, err := m.Open(Config{ID: "same", NetworkID: networkID, TargetHost: "100.64.0.2"}, Network{
			ID: networkID, IPs: []netip.Addr{netip.MustParseAddr("100.64.0.1")},
			ListenPacket: func(string, string) (net.PacketConn, error) { return remote, nil },
			Closed: func() {
				closed[index].Add(1)
				m.Count() // Closing callbacks must also be outside the registry lock.
			},
		})
		if err != nil {
			t.Fatal(err)
		}
		m.mu.Lock()
		s := m.relays["same"]
		m.mu.Unlock()
		return s
	}
	old := open(0, "a")
	old.dropped.Store(7)
	if err := m.Start("same", 60000, testKey); err != nil {
		t.Fatal(err)
	}
	done := make(chan error, 1)
	go func() { done <- m.Close("same") }()
	select {
	case <-first.entered:
	case <-time.After(time.Second):
		t.Fatal("socket Close not reached")
	}
	registry := make(chan int, 1)
	go func() { registry <- m.Count() }()
	select {
	case count := <-registry:
		if count != 0 || closed[0].Load() != 0 {
			t.Fatal("session not detached, or lease returned before socket close finished")
		}
	case <-time.After(time.Second):
		t.Fatal("registry locked across socket Close")
	}
	if err := m.Start("same", 60000, testKey); err == nil {
		t.Fatal("started a detached session")
	}
	// A repeated single close and bulk closes cannot take the pending owner's
	// callback, nor accidentally remove a session subsequently reusing its ID.
	if err := m.Close("same"); err != nil {
		t.Fatal(err)
	}
	second := open(1, "b")
	second.dropped.Store(11)
	if err := m.CloseNetwork("a"); err != nil || m.Count() != 1 {
		t.Fatalf("old network bulk close touched replacement: %v", err)
	}
	if err := m.Start("same", 60001, testKey); err != nil {
		t.Fatal(err)
	}
	if err := m.CloseAll(); err != nil || closed[1].Load() != 1 || closed[0].Load() != 0 {
		t.Fatalf("bulk close ownership: %v, old=%d new=%d", err, closed[0].Load(), closed[1].Load())
	}
	third := open(2, "a")
	third.dropped.Store(13)
	unblock()
	if err := <-done; !errors.Is(err, closeErr) {
		t.Fatalf("lost close failure: %v", err)
	}
	if closed[0].Load() != 1 || closed[2].Load() != 0 || m.Count() != 1 {
		t.Fatal("old close affected new owner or skipped its own callback on error")
	}
	if err := m.Close("same"); err != nil || closed[2].Load() != 1 {
		t.Fatalf("third close: %v", err)
	}
	if m.Dropped() != 31 || first.closeCount.Load() != 1 {
		t.Fatalf("retired more than once: drops=%d closes=%d", m.Dropped(), first.closeCount.Load())
	}
}

func TestOpenFailureClosesSocketsOutsideRegistryWithoutTakingLease(t *testing.T) {
	m := NewManager()
	gate := make(chan struct{})
	var release sync.Once
	unblock := func() { release.Do(func() { close(gate) }) }
	t.Cleanup(func() { unblock(); m.CloseAll() })
	remote := &gatedCloseConn{fakePacketConn: fakeConn("100.64.0.1:33000"), entered: make(chan struct{}), release: gate}
	m.listenLocal = func(string, string) (net.PacketConn, error) { return nil, errors.New("loopback bind failed") }
	var released atomic.Int32
	done := make(chan error, 1)
	go func() {
		_, err := m.Open(Config{ID: "r", NetworkID: "a", TargetHost: "100.64.0.2"}, Network{
			ID: "a", IPs: []netip.Addr{netip.MustParseAddr("100.64.0.1")},
			ListenPacket: func(string, string) (net.PacketConn, error) { return remote, nil },
			Closed:       func() { released.Add(1) },
		})
		done <- err
	}()
	select {
	case <-remote.entered:
	case <-time.After(time.Second):
		t.Fatal("partial Open did not close its socket")
	}
	registry := make(chan int, 1)
	go func() { registry <- m.Count() }()
	select {
	case count := <-registry:
		if count != 0 || released.Load() != 0 {
			t.Fatal("failed Open transferred ownership")
		}
	case <-time.After(time.Second):
		t.Fatal("partial Open locked registry across socket Close")
	}
	unblock()
	if err := <-done; err == nil || remote.closeCount.Load() != 1 || released.Load() != 0 {
		t.Fatalf("partial Open error/ownership: %v", err)
	}
}

func TestStrictTargetsAndFamilies(t *testing.T) {
	for _, target := range []string{"peer.example", "::ffff:100.64.0.2", "fe80::1%wlan0", "0.0.0.0", "224.0.0.1", "::", "127.0.0.1"} {
		t.Run(target, func(t *testing.T) {
			c := Config{ID: "s", NetworkID: "a", TargetHost: target}
			if _, err := c.targetFor(false); err == nil {
				t.Fatal("tailnet relay accepted an unsupported target")
			}
		})
	}
	// Names are for the proxy to resolve; anything else is rejected outright.
	if _, err := ParseConfig(`{"id":"s","networkID":"a","targetHost":"peer.example"}`); err != nil {
		t.Fatalf("proxy name target: %v", err)
	}
	for _, bad := range []string{"", "bad_name", "-x.example", "a..b", strings.Repeat("a", 64) + ".example", "host:22"} {
		if _, err := ParseConfig(`{"id":"s","networkID":"a","targetHost":"` + bad + `"}`); err == nil {
			t.Fatalf("accepted target %q", bad)
		}
	}
	m := NewManager()
	defer m.CloseAll()
	binds := 0
	network := Network{ID: "a", IPs: []netip.Addr{netip.MustParseAddr("100.64.0.1")}, ListenPacket: func(string, string) (net.PacketConn, error) {
		binds++
		return nil, errors.New("must not bind")
	}}
	if _, err := m.Open(Config{ID: "s", NetworkID: "a", TargetHost: "fd7a:115c:a1e0::2"}, network); err == nil || binds != 0 {
		t.Fatal("IPv6 target fell back to IPv4")
	}
	if _, err := m.Open(Config{ID: "s", NetworkID: "b", TargetHost: "100.64.0.2"}, network); err == nil || binds != 0 {
		t.Fatal("selected a different network")
	}
}

func TestIPv6UsesExplicitAssignedSource(t *testing.T) {
	m := NewManager()
	defer m.CloseAll()
	local := fakeConn("127.0.0.1:32001")
	remote := fakeConn("[fd7a:115c:a1e0::1]:33000")
	m.listenLocal = func(string, string) (net.PacketConn, error) { return local, nil }
	_, err := m.Open(Config{ID: "s", NetworkID: "a", TargetHost: "fd7a:115c:a1e0::2"}, Network{
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
	if err := m.Start("s", 60000, testKey); err != nil {
		t.Fatal(err)
	}
	v6 := moshDatagram(t, testKey, 0, false, "v6")
	sendPacket(local, v6, "127.0.0.1:40000")
	receivePacket(t, remote, v6, "[fd7a:115c:a1e0::2]:60000")
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
		_, err := m.Open(Config{ID: id, NetworkID: id, TargetHost: "100.64.0.2"}, Network{
			ID: id, IPs: []netip.Addr{netip.MustParseAddr("100.64.0.1")},
			ListenPacket: func(string, string) (net.PacketConn, error) { return remotes[i], nil },
		})
		if err != nil {
			t.Fatal(err)
		}
		if err := m.Start(id, 60000, testKey); err != nil {
			t.Fatal(err)
		}
	}
	a, b := moshDatagram(t, testKey, 0, false, "network-a"), moshDatagram(t, testKey, 0, false, "network-b")
	sendPacket(locals[0], a, "127.0.0.1:40000")
	sendPacket(locals[1], b, "127.0.0.1:40000")
	receivePacket(t, remotes[0], a, "100.64.0.2:60000")
	receivePacket(t, remotes[1], b, "100.64.0.2:60000")
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
	c := Config{ID: "s", NetworkID: "a", TargetHost: "100.64.0.2"}
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
	sendPacket(local, moshDatagram(t, testKey, 0, false, "lost-1"), "127.0.0.1:40000")
	sendPacket(local, moshDatagram(t, testKey, 1, false, "lost-2"), "127.0.0.1:40000")
	after := moshDatagram(t, testKey, 2, false, "after")
	sendPacket(local, after, "127.0.0.1:40000")
	receivePacket(t, remote, after, "100.64.0.2:60000")
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
	_, err := m.Open(Config{ID: "s", NetworkID: "a", TargetHost: "100.64.0.2"}, Network{
		ID: "a", IPs: []netip.Addr{netip.MustParseAddr("100.64.0.1")},
		ListenPacket: func(string, string) (net.PacketConn, error) { return remote, nil },
	})
	if err != nil {
		t.Fatal(err)
	}
	if err := m.Start("s", 60000, testKey); err != nil {
		t.Fatal(err)
	}
	sendPacket(local, moshDatagram(t, testKey, 0, false, "failure"), "127.0.0.1:40000")
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
	old := moshDatagram(t, testKey, 0, false, "old-port")
	sendPacket(local, old, "127.0.0.1:40000")
	receivePacket(t, remote, old, "100.64.0.2:60000")
	// mosh-client hop_port(): later datagrams come from a new loopback source port.
	hop := moshDatagram(t, testKey, 1, false, "new-port")
	sendPacket(local, hop, "127.0.0.1:40001")
	receivePacket(t, remote, hop, "100.64.0.2:60000")
	sendPacket(remote, []byte("reply"), "100.64.0.2:60000")
	receivePacket(t, local, []byte("reply"), "127.0.0.1:40001")
	if got := m.Rebinds(); got != 1 {
		t.Fatalf("rebinds = %d, want 1", got)
	}
	// Non-loopback senders still never claim the session.
	sendPacket(local, moshDatagram(t, testKey, 5, false, "reject"), "192.0.2.1:40002")
	sendPacket(remote, []byte("reply-2"), "100.64.0.2:60000")
	receivePacket(t, local, []byte("reply-2"), "127.0.0.1:40001")
}

func TestLoopbackTargetOnlyForProxyBackends(t *testing.T) {
	m := NewManager()
	defer m.CloseAll()
	local := fakeConn("127.0.0.1:32002")
	m.listenLocal = func(string, string) (net.PacketConn, error) { return local, nil }
	c := Config{ID: "lo", NetworkID: "a", TargetHost: "127.0.0.1"}
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

func TestOnlyAuthenticClientDatagramsAreForwardedOrFollowed(t *testing.T) {
	m, local, remote := newFixture(t)
	legit := moshDatagram(t, testKey, 10, false, "legit")
	sendPacket(local, legit, "127.0.0.1:40000")
	receivePacket(t, remote, legit, "100.64.0.2:60000")

	other := base64.RawStdEncoding.EncodeToString(bytes.Repeat([]byte{7}, 16))
	tampered := moshDatagram(t, testKey, 11, false, "tampered")
	tampered[len(tampered)-1] ^= 1
	for _, d := range [][]byte{
		moshDatagram(t, other, 11, false, "wrong key"),
		moshDatagram(t, testKey, 11, true, "toward the client"),
		tampered,
		bytes.Repeat([]byte{0}, 64),
	} {
		sendPacket(local, d, "127.0.0.1:40666")
	}
	// A replayed authentic datagram reaches the server (which ignores it, as it
	// would on the wire) but cannot move the session to the replaying port.
	sendPacket(local, legit, "127.0.0.1:40666")
	receivePacket(t, remote, legit, "100.64.0.2:60000")
	sendPacket(remote, []byte("reply"), "100.64.0.2:60000")
	receivePacket(t, local, []byte("reply"), "127.0.0.1:40000")
	if m.Rejected() != 4 || m.Rebinds() != 0 {
		t.Fatalf("rejected = %d, rebinds = %d; want 4, 0", m.Rejected(), m.Rebinds())
	}
	select {
	case d := <-remote.writes:
		t.Fatalf("forwarded an unauthenticated datagram (%d bytes)", len(d.data))
	default:
	}
}

func TestNothingFlowsBeforeStart(t *testing.T) {
	m := NewManager()
	defer m.CloseAll()
	local, remote := fakeConn("127.0.0.1:32003"), fakeConn("100.64.0.1:33003")
	m.listenLocal = func(string, string) (net.PacketConn, error) { return local, nil }
	if _, err := m.Open(Config{ID: "s", NetworkID: "a", TargetHost: "100.64.0.2"}, Network{ID: "a",
		IPs: []netip.Addr{netip.MustParseAddr("100.64.0.1")}, ListenPacket: func(string, string) (net.PacketConn, error) { return remote, nil }}); err != nil {
		t.Fatal(err)
	}
	sendPacket(local, moshDatagram(t, testKey, 0, false, "early"), "127.0.0.1:40000")
	sendPacket(remote, []byte("early reply"), "100.64.0.2:60000")
	for deadline := time.Now().Add(2 * time.Second); m.Rejected() == 0 || len(remote.reads) > 0; {
		if time.Now().After(deadline) {
			t.Fatal("early datagrams were not processed")
		}
		time.Sleep(5 * time.Millisecond)
	}
	for _, bad := range []struct {
		port int
		key  string
	}{{0, testKey}, {65536, testKey}, {60000, ""}, {60000, testKey[:21]}, {60000, testKey[:21] + "!"}, {60000, "AAAAAAAAAAAAAAAAAAAAAB"}} {
		if err := m.Start("s", bad.port, bad.key); err == nil {
			t.Fatalf("Start accepted port %d key %q", bad.port, bad.key)
		}
	}
	if err := m.Start("missing", 60000, testKey); err == nil {
		t.Fatal("started a relay that is not open")
	}
	if err := m.Start("s", 60000, testKey); err != nil {
		t.Fatal(err)
	}
	if err := m.Start("s", 60001, testKey); err == nil {
		t.Fatal("a started relay accepted a second key")
	}
	d := moshDatagram(t, testKey, 1, false, "after start")
	sendPacket(local, d, "127.0.0.1:40000")
	receivePacket(t, remote, d, "100.64.0.2:60000")
	if got := m.Rejected(); got != 1 {
		t.Fatalf("rejected = %d, want the one datagram sent before Start", got)
	}
	select {
	case w := <-local.writes:
		t.Fatalf("delivered a reply before Start: %q", w.data)
	default:
	}
}

// hostConn is a proxy association that resolves names itself.
type hostConn struct{ *fakePacketConn }

type hostAddr string

func (h hostAddr) Network() string { return "udp" }
func (h hostAddr) String() string  { return string(h) }

func (h hostConn) WriteToHost(p []byte, host string, port uint16) (int, error) {
	return h.WriteTo(p, hostAddr(fmt.Sprintf("%s:%d", host, port)))
}

func TestNameTargetGoesToTheProxy(t *testing.T) {
	m := NewManager()
	defer m.CloseAll()
	local := fakeConn("127.0.0.1:32004")
	m.listenLocal = func(string, string) (net.PacketConn, error) { return local, nil }
	c := Config{ID: "n", NetworkID: "p", TargetHost: "target.lab"}
	plain := Network{ID: "p", AnySource: true, ListenPacket: func(string, string) (net.PacketConn, error) { return fakeConn("192.0.2.9:1"), nil }}
	if _, err := m.Open(c, plain); err == nil {
		t.Fatal("name target opened on a backend that cannot send to names")
	}
	assoc := hostConn{fakeConn("192.0.2.9:2")}
	proxy := Network{ID: "p", AnySource: true, ListenPacket: func(string, string) (net.PacketConn, error) { return assoc, nil }}
	if _, err := m.Open(c, proxy); err != nil {
		t.Fatal(err)
	}
	if err := m.Start("n", 60010, testKey); err != nil {
		t.Fatal(err)
	}
	d := moshDatagram(t, testKey, 0, false, "named")
	sendPacket(local, d, "127.0.0.1:40000")
	receivePacket(t, assoc.fakePacketConn, d, "target.lab:60010")
	// The proxy reports the address it resolved, or the name itself; the
	// association is ours alone.
	sendPacket(assoc.fakePacketConn, []byte("reply"), "203.0.113.5:60010")
	receivePacket(t, local, []byte("reply"), "127.0.0.1:40000")
	assoc.reads <- datagram{data: []byte("named reply"), addr: hostAddr("target.lab:60010")}
	receivePacket(t, local, []byte("named reply"), "127.0.0.1:40000")
}

func TestReopensAnEndedBackendSocket(t *testing.T) {
	m := NewManager()
	defer m.CloseAll()
	local := fakeConn("127.0.0.1:32005")
	m.listenLocal = func(string, string) (net.PacketConn, error) { return local, nil }
	backends := make(chan *fakePacketConn, 4)
	first, second := fakeConn("192.0.2.9:3"), fakeConn("192.0.2.9:4")
	backends <- first
	backends <- second
	opens := atomic.Int32{}
	proxy := Network{ID: "p", AnySource: true, Reopen: true, ListenPacket: func(string, string) (net.PacketConn, error) {
		opens.Add(1)
		return <-backends, nil
	}}
	if _, err := m.Open(Config{ID: "r", NetworkID: "p", TargetHost: "198.51.100.7"}, proxy); err != nil {
		t.Fatal(err)
	}
	if err := m.Start("r", 60020, testKey); err != nil {
		t.Fatal(err)
	}
	d0 := moshDatagram(t, testKey, 0, false, "before")
	sendPacket(local, d0, "127.0.0.1:40000")
	receivePacket(t, first, d0, "198.51.100.7:60020")
	first.Close() // The association's control connection dropped.
	deadline := time.Now().Add(5 * time.Second)
	for m.Reopens() == 0 && time.Now().Before(deadline) {
		time.Sleep(20 * time.Millisecond)
	}
	if m.Reopens() != 1 || opens.Load() != 2 || m.Count() != 1 {
		t.Fatalf("reopens = %d, opens = %d, relays = %d", m.Reopens(), opens.Load(), m.Count())
	}
	d1 := moshDatagram(t, testKey, 1, false, "after")
	sendPacket(local, d1, "127.0.0.1:40000")
	receivePacket(t, second, d1, "198.51.100.7:60020")
	sendPacket(second, []byte("reply"), "198.51.100.7:60020")
	receivePacket(t, local, []byte("reply"), "127.0.0.1:40000")
}
