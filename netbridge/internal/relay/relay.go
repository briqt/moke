// Package relay provides bounded, IP-only UDP forwarding for mosh sessions.
package relay

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/netip"
	"sync"
	"sync/atomic"
	"time"
)

const (
	maxRelays    = 64
	maxPayload   = 65507 // The IPv4 UDP payload limit also bounds IPv6 relays.
	writeTimeout = 2 * time.Second
)

// Config describes one session. Names are intentionally not resolved by Go.
type Config struct {
	ID         string `json:"id"`
	NetworkID  string `json:"networkID"`
	TargetIP   string `json:"targetIP"`
	TargetPort int    `json:"targetPort"`
}

// Info contains no credentials.
type Info struct {
	ID        string `json:"id"`
	LocalHost string `json:"localHost"`
	LocalPort int    `json:"localPort"`
	NetworkID string `json:"networkID"`
}

// Network is supplied by the node registry while that node's lifecycle is locked.
// ListenPacket must bind the given literal source address on this network only.
type Network struct {
	ID           string
	IPs          []netip.Addr
	ListenPacket func(network, address string) (net.PacketConn, error)
	// AnySource: the backend picks its own source (e.g. a SOCKS5 UDP association);
	// IPs are ignored and ListenPacket receives an empty address.
	AnySource bool
}

// Manager owns relay sockets. The node owner must CloseNetwork before closing a
// node, and must not Open another relay on that node concurrently with its stop.
type Manager struct {
	mu            sync.Mutex
	relays        map[string]*session
	listenLocal   func(network, address string) (net.PacketConn, error)
	closedDrops   int64 // Drops of already-closed sessions, so the counter is cumulative.
	closedFlow    [4]int64
	closedRebinds int64
}

func NewManager() *Manager {
	return &Manager{relays: make(map[string]*session), listenLocal: net.ListenPacket}
}

func ParseConfig(data string) (Config, error) {
	var c Config
	if len(data) > 8192 || json.Unmarshal([]byte(data), &c) != nil {
		return c, errors.New("invalid UDP relay configuration JSON")
	}
	// Syntax-level check only; Open applies the per-network rule (loopback is
	// meaningful only at a proxy host).
	if _, err := c.targetFor(true); err != nil {
		return Config{}, err
	}
	return c, nil
}

func validID(id string) bool {
	if len(id) == 0 || len(id) > 64 {
		return false
	}
	for _, ch := range id {
		if (ch < 'a' || ch > 'z') && (ch < 'A' || ch > 'Z') && (ch < '0' || ch > '9') && ch != '-' && ch != '_' && ch != '.' {
			return false
		}
	}
	return true
}

func (c Config) target() (netip.AddrPort, error) { return c.targetFor(false) }

// targetFor allows a loopback target only for AnySource backends (a proxy), where
// the address is interpreted on the proxy host, never on this device.
func (c Config) targetFor(anySource bool) (netip.AddrPort, error) {
	if !validID(c.ID) || !validID(c.NetworkID) {
		return netip.AddrPort{}, errors.New("invalid UDP relay or network ID")
	}
	ip, err := netip.ParseAddr(c.TargetIP)
	if err != nil {
		return netip.AddrPort{}, errors.New("UDP relay requires a literal targetIP; DNS names are not supported")
	}
	if ip.Zone() != "" || ip.Is4In6() || !(ip.IsGlobalUnicast() || (anySource && ip.IsLoopback())) {
		return netip.AddrPort{}, errors.New("UDP target must be an unzoned, unmapped unicast IP")
	}
	if c.TargetPort < 1 || c.TargetPort > 65535 {
		return netip.AddrPort{}, errors.New("UDP targetPort must be between 1 and 65535")
	}
	return netip.AddrPortFrom(ip, uint16(c.TargetPort)), nil
}

func sourceIP(ips []netip.Addr, target netip.Addr) (netip.Addr, error) {
	// A v4 destination always uses an explicitly assigned v4 source. A v6
	// destination requires v6; never unmap or silently fall back across families.
	for _, ip := range ips {
		if ip.IsValid() && ip.Zone() == "" && !ip.Is4In6() && ip.IsGlobalUnicast() && ip.Is4() == target.Is4() {
			return ip, nil
		}
	}
	return netip.Addr{}, errors.New("selected node has no assigned IP matching the UDP target family")
}

func (m *Manager) Open(c Config, network Network) (Info, error) {
	target, err := c.targetFor(network.AnySource)
	if err != nil {
		return Info{}, err
	}
	if c.NetworkID != network.ID || network.ListenPacket == nil {
		return Info{}, errors.New("UDP relay network does not match the selected node")
	}
	bind := ""
	if !network.AnySource {
		source, err := sourceIP(network.IPs, target.Addr())
		if err != nil {
			return Info{}, err
		}
		bind = netip.AddrPortFrom(source, 0).String()
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	if _, exists := m.relays[c.ID]; exists {
		return Info{}, errors.New("UDP relay ID is already open")
	}
	if len(m.relays) >= maxRelays {
		return Info{}, errors.New("UDP relay limit reached")
	}
	remote, err := network.ListenPacket("udp", bind)
	if err != nil {
		return Info{}, fmt.Errorf("bind UDP on selected node: %w", err)
	}
	local, err := m.listenLocal("udp4", "127.0.0.1:0")
	if err != nil {
		remote.Close()
		return Info{}, fmt.Errorf("bind UDP loopback: %w", err)
	}
	localAddr, ok := packetAddr(local.LocalAddr())
	if !ok || localAddr.Addr() != netip.MustParseAddr("127.0.0.1") || localAddr.Port() == 0 {
		local.Close()
		remote.Close()
		return Info{}, errors.New("UDP listener did not bind IPv4 loopback")
	}
	ctx, cancel := context.WithCancel(context.Background())
	s := &session{local: local, remote: remote, target: target, networkID: c.NetworkID, ctx: ctx, cancel: cancel}
	m.relays[c.ID] = s
	s.wg.Add(2)
	go s.fromLocal()
	go s.fromRemote()
	return Info{ID: c.ID, LocalHost: "127.0.0.1", LocalPort: int(localAddr.Port()), NetworkID: c.NetworkID}, nil
}

func (m *Manager) Close(id string) error {
	m.mu.Lock()
	s := m.relays[id]
	delete(m.relays, id)
	m.mu.Unlock()
	defer m.retire(s)
	if s == nil {
		return nil
	}
	return s.close()
}

func (m *Manager) closeMatching(networkID string, all bool) error {
	m.mu.Lock()
	var sessions []*session
	for id, s := range m.relays {
		if all || s.networkID == networkID {
			sessions = append(sessions, s)
			delete(m.relays, id)
		}
	}
	m.mu.Unlock()
	var result error
	for _, s := range sessions {
		result = errors.Join(result, s.close())
		m.retire(s)
	}
	return result
}

func (m *Manager) CloseNetwork(networkID string) error { return m.closeMatching(networkID, false) }

// NetworkOf returns the network an open relay belongs to.
func (m *Manager) NetworkOf(id string) (string, bool) {
	m.mu.Lock()
	defer m.mu.Unlock()
	if s := m.relays[id]; s != nil {
		return s.networkID, true
	}
	return "", false
}
func (m *Manager) CloseAll() error { return m.closeMatching("", true) }

func (m *Manager) Count() int {
	m.mu.Lock()
	defer m.mu.Unlock()
	return len(m.relays)
}

func (m *Manager) retire(s *session) {
	if s == nil {
		return
	}
	m.mu.Lock()
	m.closedDrops += s.dropped.Load()
	m.closedRebinds += s.rebinds.Load()
	for i, v := range s.flow() {
		m.closedFlow[i] += v
	}
	m.mu.Unlock()
}

// Rebinds counts client source changes followed by open and closed relays.
func (m *Manager) Rebinds() int64 {
	m.mu.Lock()
	defer m.mu.Unlock()
	total := m.closedRebinds
	for _, s := range m.relays {
		total += s.rebinds.Load()
	}
	return total
}

func (s *session) flow() [4]int64 {
	return [4]int64{s.localIn.Load(), s.remoteOut.Load(), s.remoteIn.Load(), s.localOut.Load()}
}

// Flow returns cumulative datagram counts: localIn, remoteOut, remoteIn, localOut.
func (m *Manager) Flow() [4]int64 {
	m.mu.Lock()
	defer m.mu.Unlock()
	total := m.closedFlow
	for _, s := range m.relays {
		for i, v := range s.flow() {
			total[i] += v
		}
	}
	return total
}

// Dropped counts datagrams lost to transient send errors, cumulative over all relays.
func (m *Manager) Dropped() int64 {
	m.mu.Lock()
	defer m.mu.Unlock()
	total := m.closedDrops
	for _, s := range m.relays {
		total += s.dropped.Load()
	}
	return total
}

// sendFailed decides whether a send error ends the session. A UDP send failure
// (deadline, no route while a path is down, refused loopback peer) loses only that
// datagram; ending the relay would turn a short outage into a dead mosh session.
// Only a closed socket is terminal.
func (s *session) sendFailed(operation string, err error) bool {
	if errors.Is(err, net.ErrClosed) || s.ctx.Err() != nil {
		s.fail(operation, err)
		return true
	}
	s.dropped.Add(1)
	return false
}

type session struct {
	local     net.PacketConn
	remote    net.PacketConn
	target    netip.AddrPort
	networkID string
	ctx       context.Context
	cancel    context.CancelFunc
	wg        sync.WaitGroup
	once      sync.Once
	closeErr  error
	errorMu   sync.Mutex
	ioErr     error
	clientMu  sync.Mutex
	// The most recent IPv4-loopback sender. mosh-client hops to a new source port after
	// 10 s without a round trip (upstream PORT_HOP_INTERVAL); like mosh-server's own roaming,
	// follow it. Datagrams are authenticated end to end by mosh, so a hostile local sender
	// can only divert ciphertext until the real client's next packet reclaims the session.
	client  netip.AddrPort
	dropped atomic.Int64 // Datagrams lost to transient send errors (UDP semantics).
	// Per-direction datagram counts: client->relay, relay->node, node->relay, relay->client.
	localIn, remoteOut, remoteIn, localOut atomic.Int64
	rebinds                                atomic.Int64 // Client source-port changes followed.
}

func packetAddr(addr net.Addr) (netip.AddrPort, bool) {
	udp, ok := addr.(*net.UDPAddr)
	if !ok {
		return netip.AddrPort{}, false
	}
	ap := udp.AddrPort()
	// net.IP represents IPv4 as either four or sixteen bytes. AddrPort may
	// therefore be mapped even on a udp4 socket; normalize only that net.IP
	// representation. Configured target/source addresses are never unmapped.
	if ap.Addr().Is4In6() {
		ap = netip.AddrPortFrom(ap.Addr().Unmap(), ap.Port())
	}
	return ap, ap.IsValid() && ap.Addr().Zone() == ""
}

func (s *session) shutdown() {
	s.once.Do(func() {
		s.cancel()
		s.closeErr = errors.Join(s.local.Close(), s.remote.Close())
	})
}

func (s *session) fail(operation string, err error) {
	// Ignore cancellation errors produced by our own Close. Retain the first
	// genuine I/O failure so explicit Close reports why forwarding terminated.
	s.errorMu.Lock()
	if s.ctx.Err() == nil && s.ioErr == nil {
		s.ioErr = fmt.Errorf("%s: %w", operation, err)
	}
	s.errorMu.Unlock()
}

func (s *session) close() error {
	s.shutdown()
	s.wg.Wait()
	s.errorMu.Lock()
	defer s.errorMu.Unlock()
	return errors.Join(s.ioErr, s.closeErr)
}

func writePacket(conn net.PacketConn, payload []byte, target netip.AddrPort) error {
	if err := conn.SetWriteDeadline(time.Now().Add(writeTimeout)); err != nil {
		return err
	}
	n, err := conn.WriteTo(payload, net.UDPAddrFromAddrPort(target))
	if err == nil && n != len(payload) {
		return io.ErrShortWrite
	}
	return err
}

func (s *session) fromLocal() {
	defer s.wg.Done()
	defer s.shutdown()
	buffer := make([]byte, maxPayload+1)
	for s.ctx.Err() == nil {
		n, addr, err := s.local.ReadFrom(buffer)
		if err != nil {
			s.fail("read UDP loopback", err)
			return
		}
		if n < 0 || n > maxPayload {
			continue
		}
		client, ok := packetAddr(addr)
		if !ok || client.Addr() != netip.MustParseAddr("127.0.0.1") || client.Port() == 0 {
			continue
		}
		s.clientMu.Lock()
		if s.client.IsValid() && s.client != client {
			s.rebinds.Add(1)
		}
		s.client = client
		s.clientMu.Unlock()
		{
			s.localIn.Add(1)
			err := writePacket(s.remote, buffer[:n], s.target)
			if err != nil && s.sendFailed("write UDP to selected node", err) {
				return
			}
			if err == nil {
				s.remoteOut.Add(1)
			}
		}
	}
}

func (s *session) fromRemote() {
	defer s.wg.Done()
	defer s.shutdown()
	buffer := make([]byte, maxPayload+1)
	for s.ctx.Err() == nil {
		n, addr, err := s.remote.ReadFrom(buffer)
		if err != nil {
			s.fail("read UDP from selected node", err)
			return
		}
		peer, ok := packetAddr(addr)
		if n < 0 || n > maxPayload || !ok || peer != s.target {
			continue
		}
		s.clientMu.Lock()
		client := s.client
		s.clientMu.Unlock()
		s.remoteIn.Add(1)
		if client.IsValid() {
			err := writePacket(s.local, buffer[:n], client)
			if err != nil && s.sendFailed("write UDP loopback", err) {
				return
			}
			if err == nil {
				s.localOut.Add(1)
			}
		}
	}
}
