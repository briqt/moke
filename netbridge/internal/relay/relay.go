// Package relay provides bounded UDP forwarding for mosh sessions.
//
// A relay is opened before the mosh bootstrap (so a network without UDP fails
// before anything starts on the server) and started once mosh-server has
// printed its port and key. Only datagrams that authenticate with that key are
// forwarded, and the relay follows a new client source port by the same rule
// mosh-server uses for roaming.
package relay

import (
	"context"
	"crypto/aes"
	"crypto/cipher"
	"encoding/base64"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/netip"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/ProtonMail/go-crypto/ocb"
)

const (
	maxRelays    = 64
	maxPayload   = 65507 // The IPv4 UDP payload limit also bounds IPv6 relays.
	writeTimeout = 2 * time.Second

	// mosh datagram: 8-byte nonce (direction bit + 63-bit sequence), then
	// AES-128-OCB ciphertext with a 16-byte tag (mosh src/crypto, src/network).
	moshNonceWire = 8
	moshTag       = 16
	directionMask = uint64(1) << 63 // Set = TO_CLIENT.

	reopenMin = time.Second
	reopenMax = 10 * time.Second
)

// Config opens one session. The mosh port and key are given later to Start.
type Config struct {
	ID        string `json:"id"`
	NetworkID string `json:"networkID"`
	// TargetHost is a literal IP. A proxy backend (AnySource) may also take a
	// DNS name, which the proxy resolves (SOCKS5 ATYP=3); Go never resolves it.
	TargetHost string `json:"targetHost"`
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
	// Reopen: the backend socket can end on its own (a SOCKS5 association whose
	// control connection dropped, typically on a network change). The relay then
	// opens a new one instead of ending the session.
	Reopen bool
}

// HostWriter is a backend socket that sends to a name it resolves itself.
type HostWriter interface {
	WriteToHost(p []byte, host string, port uint16) (int, error)
}

// Manager owns relay sockets. The node owner must CloseNetwork before closing a
// node, and must not Open another relay on that node concurrently with its stop.
type Manager struct {
	mu             sync.Mutex
	relays         map[string]*session
	listenLocal    func(network, address string) (net.PacketConn, error)
	closedDrops    int64 // Drops of already-closed sessions, so the counter is cumulative.
	closedFlow     [4]int64
	closedRebinds  int64
	closedRejected int64
	closedReopens  int64
}

func NewManager() *Manager {
	return &Manager{relays: make(map[string]*session), listenLocal: net.ListenPacket}
}

func ParseConfig(data string) (Config, error) {
	var c Config
	if len(data) > 8192 || json.Unmarshal([]byte(data), &c) != nil {
		return c, errors.New("invalid UDP relay configuration JSON")
	}
	// Syntax-level check only; Open applies the per-network rule (names and
	// loopback are meaningful only at a proxy host).
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

// validHostName accepts a DNS name a proxy can resolve (letters, digits, '-', '.').
func validHostName(name string) bool {
	name = strings.TrimSuffix(name, ".")
	if name == "" || len(name) > 253 {
		return false
	}
	for _, label := range strings.Split(name, ".") {
		if label == "" || len(label) > 63 || label[0] == '-' || label[len(label)-1] == '-' {
			return false
		}
		for _, ch := range label {
			if (ch < 'a' || ch > 'z') && (ch < 'A' || ch > 'Z') && (ch < '0' || ch > '9') && ch != '-' {
				return false
			}
		}
	}
	return true
}

// target is the remote end: an IP, or a name only a proxy resolves.
type target struct {
	ip   netip.Addr
	name string
}

func (t target) String() string {
	if t.ip.IsValid() {
		return t.ip.String()
	}
	return t.name
}

// targetFor allows names and loopback only for AnySource backends (a proxy),
// where they are interpreted on the proxy host, never on this device.
func (c Config) targetFor(anySource bool) (target, error) {
	if !validID(c.ID) || !validID(c.NetworkID) {
		return target{}, errors.New("invalid UDP relay or network ID")
	}
	ip, err := netip.ParseAddr(c.TargetHost)
	if err != nil {
		if anySource && validHostName(c.TargetHost) {
			return target{name: c.TargetHost}, nil
		}
		return target{}, errors.New("UDP relay requires a literal target IP on this network")
	}
	if ip.Zone() != "" || ip.Is4In6() || !(ip.IsGlobalUnicast() || (anySource && ip.IsLoopback())) {
		return target{}, errors.New("UDP target must be an unzoned, unmapped unicast IP")
	}
	return target{ip: ip}, nil
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

// moshCipher builds the session cipher from a mosh key (22 base64 characters,
// 128 bits; the same canonical form mosh's Base64Key requires).
func moshCipher(key string) (cipher.AEAD, error) {
	if len(key) != 22 {
		return nil, errors.New("mosh key must be 22 base64 characters")
	}
	raw, err := base64.StdEncoding.Strict().DecodeString(key + "==")
	if err != nil || len(raw) != 16 {
		return nil, errors.New("mosh key must encode 128 bits")
	}
	block, err := aes.NewCipher(raw)
	if err != nil {
		return nil, err
	}
	return ocb.NewOCBWithNonceAndTagSize(block, 12, moshTag)
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
		source, err := sourceIP(network.IPs, target.ip)
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
	if _, ok := remote.(HostWriter); target.name != "" && !ok {
		remote.Close()
		return Info{}, errors.New("this network cannot send UDP to a host name")
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
	if network.Reopen {
		s.reopen = func() (net.PacketConn, error) { return network.ListenPacket("udp", bind) }
	}
	m.relays[c.ID] = s
	s.wg.Add(2)
	go s.fromLocal()
	go s.fromRemote()
	return Info{ID: c.ID, LocalHost: "127.0.0.1", LocalPort: int(localAddr.Port()), NetworkID: c.NetworkID}, nil
}

// Start begins forwarding once mosh-server has printed its UDP port and key.
// Until then every datagram is dropped.
func (m *Manager) Start(id string, port int, key string) error {
	if port < 1 || port > 65535 {
		return errors.New("UDP target port must be between 1 and 65535")
	}
	aead, err := moshCipher(key)
	if err != nil {
		return err
	}
	m.mu.Lock()
	s := m.relays[id]
	m.mu.Unlock()
	if s == nil {
		return errors.New("UDP relay is not open")
	}
	s.clientMu.Lock()
	defer s.clientMu.Unlock()
	if s.aead != nil {
		return errors.New("UDP relay is already started")
	}
	s.port, s.aead = uint16(port), aead
	return nil
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
	m.closedRejected += s.rejected.Load()
	m.closedReopens += s.reopens.Load()
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

// Rejected counts datagrams from this device that did not authenticate as the
// session's client (wrong key, wrong direction, malformed) and were dropped.
func (m *Manager) Rejected() int64 {
	m.mu.Lock()
	defer m.mu.Unlock()
	total := m.closedRejected
	for _, s := range m.relays {
		total += s.rejected.Load()
	}
	return total
}

// Reopens counts backend sockets reopened after they ended (SOCKS5 re-association).
func (m *Manager) Reopens() int64 {
	m.mu.Lock()
	defer m.mu.Unlock()
	total := m.closedReopens
	for _, s := range m.relays {
		total += s.reopens.Load()
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
// Only a closed socket is terminal, and not even that for a backend that is
// reopened (the reader replaces it).
func (s *session) sendFailed(operation string, err error, reopenable bool) bool {
	if s.ctx.Err() != nil || (errors.Is(err, net.ErrClosed) && !reopenable) {
		s.fail(operation, err)
		return true
	}
	s.dropped.Add(1)
	return false
}

type session struct {
	local     net.PacketConn
	target    target
	networkID string
	ctx       context.Context
	cancel    context.CancelFunc
	wg        sync.WaitGroup
	once      sync.Once
	closeErr  error
	errorMu   sync.Mutex
	ioErr     error

	remoteMu sync.Mutex
	remote   net.PacketConn
	reopen   func() (net.PacketConn, error) // nil: the backend socket ends the session.

	clientMu sync.Mutex
	// Set by Start; nil until then (every datagram is dropped).
	port uint16
	aead cipher.AEAD
	// The current mosh-client source. mosh-client hops to a new source port after
	// 10 s without a round trip (PORT_HOP_INTERVAL); the relay follows it exactly
	// as mosh-server roams: only for a datagram that authenticates with the session
	// key, travels toward the server, and is not older than one already seen. Other
	// apps on this device can therefore neither take the session over (they lack
	// the key) nor replay a captured datagram to divert it.
	client      netip.AddrPort
	expectedSeq uint64

	dropped  atomic.Int64 // Datagrams lost to transient send errors (UDP semantics).
	rejected atomic.Int64 // Local datagrams that did not authenticate.
	reopens  atomic.Int64
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

func (s *session) currentRemote() net.PacketConn {
	s.remoteMu.Lock()
	defer s.remoteMu.Unlock()
	return s.remote
}

func (s *session) shutdown() {
	s.once.Do(func() {
		s.cancel()
		s.remoteMu.Lock()
		remote := s.remote
		s.remoteMu.Unlock()
		s.closeErr = errors.Join(s.local.Close(), remote.Close())
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

func (s *session) writeRemote(payload []byte, port uint16) error {
	remote := s.currentRemote()
	if s.target.ip.IsValid() {
		return writePacket(remote, payload, netip.AddrPortFrom(s.target.ip, port))
	}
	if err := remote.SetWriteDeadline(time.Now().Add(writeTimeout)); err != nil {
		return err
	}
	n, err := remote.(HostWriter).WriteToHost(payload, s.target.name, port)
	if err == nil && n != len(payload) {
		return io.ErrShortWrite
	}
	return err
}

// admit authenticates a datagram from mosh-client and returns the target port.
// It follows a new source under mosh-server's own roaming rule (recv_one).
// scratch receives the plaintext, which is discarded.
func (s *session) admit(datagram, scratch []byte, from netip.AddrPort) (uint16, bool) {
	if len(datagram) < moshNonceWire+moshTag {
		return 0, false
	}
	wire := binary.BigEndian.Uint64(datagram[:moshNonceWire])
	if wire&directionMask != 0 {
		return 0, false // Toward the client: never forwarded to the server.
	}
	var nonce [12]byte // Four zero bytes, then the wire nonce (mosh Nonce).
	copy(nonce[4:], datagram[:moshNonceWire])
	s.clientMu.Lock()
	defer s.clientMu.Unlock()
	if s.aead == nil {
		return 0, false
	}
	if _, err := s.aead.Open(scratch[:0], nonce[:], datagram[moshNonceWire:], nil); err != nil {
		return 0, false
	}
	if seq := wire &^ directionMask; seq >= s.expectedSeq {
		s.expectedSeq = seq + 1
		if s.client.IsValid() && s.client != from {
			s.rebinds.Add(1)
		}
		s.client = from
	}
	return s.port, true
}

func (s *session) fromLocal() {
	defer s.wg.Done()
	defer s.shutdown()
	buffer := make([]byte, maxPayload+1)
	scratch := make([]byte, maxPayload)
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
		port, ok := s.admit(buffer[:n], scratch, client)
		if !ok {
			s.rejected.Add(1)
			continue
		}
		s.localIn.Add(1)
		err = s.writeRemote(buffer[:n], port)
		if err != nil && s.sendFailed("write UDP to selected node", err, s.reopen != nil) {
			return
		}
		if err == nil {
			s.remoteOut.Add(1)
		}
	}
}

// reopenRemote replaces a backend socket that ended on its own, retrying with
// backoff until it succeeds or the session closes.
func (s *session) reopenRemote(old net.PacketConn) bool {
	old.Close()
	delay := reopenMin
	for {
		select {
		case <-s.ctx.Done():
			return false
		case <-time.After(delay):
		}
		remote, err := s.reopen()
		if err != nil {
			delay = min(delay*2, reopenMax)
			continue
		}
		s.remoteMu.Lock()
		if s.ctx.Err() != nil {
			s.remoteMu.Unlock()
			remote.Close()
			return false
		}
		s.remote = remote
		s.remoteMu.Unlock()
		s.reopens.Add(1)
		return true
	}
}

func (s *session) fromRemote() {
	defer s.wg.Done()
	defer s.shutdown()
	buffer := make([]byte, maxPayload+1)
	for s.ctx.Err() == nil {
		remote := s.currentRemote()
		n, addr, err := remote.ReadFrom(buffer)
		if err != nil {
			if s.ctx.Err() == nil && s.reopen != nil {
				if s.reopenRemote(remote) {
					continue
				}
				return
			}
			s.fail("read UDP from selected node", err)
			return
		}
		if n < 0 || n > maxPayload {
			continue
		}
		s.clientMu.Lock()
		client, port, started := s.client, s.port, s.aead != nil
		s.clientMu.Unlock()
		if !started {
			continue
		}
		// An IP target must answer from exactly that address. A name target is
		// resolved by the proxy (which may label replies with an IP or the name);
		// its association carries only this session.
		if s.target.ip.IsValid() {
			if peer, ok := packetAddr(addr); !ok || peer != netip.AddrPortFrom(s.target.ip, port) {
				continue
			}
		}
		s.remoteIn.Add(1)
		if client.IsValid() {
			err := writePacket(s.local, buffer[:n], client)
			if err != nil && s.sendFailed("write UDP loopback", err, false) {
				return
			}
			if err == nil {
				s.localOut.Add(1)
			}
		}
	}
}
