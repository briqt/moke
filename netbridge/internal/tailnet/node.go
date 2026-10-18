// Package tailnet runs one userspace tailnet node (tsnet) for the app.
//
// Compared with tsnet's own Loopback/Dial, connections here never leave the
// tailnet: names resolve only through the node's MagicDNS view and addresses
// must be routed by the tailnet; tsdial's fallbacks to system DNS and to the
// system network are never used.
package tailnet

import (
	"context"
	"errors"
	"net"
	"net/netip"
	"time"

	"github.com/briqt/moke/netbridge/internal/codes"
	"tailscale.com/ipn"
	"tailscale.com/ipn/ipnstate"
	"tailscale.com/net/tsdial"
	"tailscale.com/tsnet"
	"tailscale.com/types/logger"
)

type Config struct {
	ID         string
	Dir        string // Private, not backed up: log config and tsnet working files.
	ControlURL string
	AuthKey    string // Used for first registration only; may be empty.
	Hostname   string
}

// Store keeps the node state outside Go (the app encrypts it). Read returns nil
// for a missing key; Write with nil deletes the key.
type Store interface {
	Read(key string) ([]byte, error)
	Write(key string, value []byte) error
}

// stateStore adapts Store to ipn.StateStore. A one-byte version prefix keeps
// every stored value non-empty, so "missing" and "empty" never collide.
type stateStore struct{ store Store }

const stateVersion = 1

func (s stateStore) ReadState(id ipn.StateKey) ([]byte, error) {
	value, err := s.store.Read(string(id))
	if err != nil {
		return nil, err
	}
	if len(value) == 0 {
		return nil, ipn.ErrStateNotExist
	}
	if value[0] != stateVersion {
		return nil, errors.New("unknown node state encoding")
	}
	out := make([]byte, len(value)-1) // Non-nil even when empty.
	copy(out, value[1:])
	return out, nil
}

func (s stateStore) WriteState(id ipn.StateKey, bs []byte) error {
	if bs == nil {
		return s.store.Write(string(id), nil)
	}
	return s.store.Write(string(id), append([]byte{stateVersion}, bs...))
}

type Node struct {
	server      *tsnet.Server
	startFailed bool
}

func New(c Config, store Store) *Node {
	return &Node{server: &tsnet.Server{
		Dir: c.Dir, Store: stateStore{store}, Hostname: c.Hostname, ControlURL: c.ControlURL, AuthKey: c.AuthKey,
		// Both callbacks can carry login URLs and keys; keep them silent.
		Logf: logger.Discard, UserLogf: logger.Discard,
	}}
}

func (n *Node) Start() error {
	err := n.server.Start()
	n.startFailed = err != nil
	return err
}

// engineCloser is the subset of the engine used to clean up a failed start.
type engineCloser interface {
	Close()
	Done() <-chan struct{}
}

func closeOrphanedEngine(engine engineCloser) {
	if engine == nil {
		return
	}
	select {
	case <-engine.Done():
	default:
		engine.Close()
		<-engine.Done()
	}
}

func (n *Node) Close() error {
	var engine engineCloser
	if n.startFailed {
		if sys := n.server.Sys(); sys != nil {
			if value, ok := sys.Engine.GetOK(); ok {
				engine = value
			}
		}
	}
	err := n.server.Close()
	// tsnet can create the engine before a failing state store aborts Start,
	// without a LocalBackend to own it; close only such an orphan.
	closeOrphanedEngine(engine)
	return err
}

// Snapshot is the node's state as the app shows it. AuthURL is never logged.
type Snapshot struct {
	State     string
	IPs       []netip.Addr
	AuthURL   string
	Tailnet   string
	DNSName   string
	LoginName string
	KeyExpiry time.Time
	Expired   bool
	// HaveNodeKey: the node has logged in before, so NeedsLogin means "log in again".
	HaveNodeKey bool
}

func (n *Node) Snapshot(ctx context.Context) (Snapshot, error) {
	client, err := n.server.LocalClient()
	if err != nil {
		return Snapshot{}, err
	}
	st, err := client.StatusWithoutPeers(ctx)
	if err != nil {
		return Snapshot{}, err
	}
	return snapshotOf(st), nil
}

func snapshotOf(st *ipnstate.Status) Snapshot {
	s := Snapshot{State: st.BackendState, IPs: st.TailscaleIPs, AuthURL: st.AuthURL, HaveNodeKey: st.HaveNodeKey}
	if st.CurrentTailnet != nil {
		s.Tailnet = st.CurrentTailnet.Name
	}
	if self := st.Self; self != nil {
		s.DNSName = self.DNSName
		s.Expired = self.Expired
		if self.KeyExpiry != nil {
			s.KeyExpiry = *self.KeyExpiry
		}
		if user, ok := st.User[self.UserID]; ok {
			s.LoginName = user.LoginName
		}
	}
	return s
}

// readiness says why the node cannot carry a connection right now, if it cannot.
func readiness(st *ipnstate.Status, now time.Time) error {
	expired := st.Self != nil && (st.Self.Expired || (st.Self.KeyExpiry != nil && !st.Self.KeyExpiry.IsZero() && st.Self.KeyExpiry.Before(now)))
	switch st.BackendState {
	case ipn.Running.String():
		if expired {
			return codes.New(codes.LoginExpired, "node key expired")
		}
		return nil
	case ipn.NeedsLogin.String():
		if expired || st.HaveNodeKey {
			return codes.New(codes.LoginExpired, "node needs to log in again")
		}
		return codes.New(codes.NeedsLogin, "node is not logged in")
	case ipn.NeedsMachineAuth.String():
		return codes.New(codes.NeedsMachineAuth, "node awaits approval by an administrator")
	default:
		return codes.New(codes.NotReady, "node is "+st.BackendState)
	}
}

func (n *Node) StartLogin(ctx context.Context) error {
	client, err := n.server.LocalClient()
	if err != nil {
		return err
	}
	return client.StartLoginInteractive(ctx)
}

func (n *Node) Logout(ctx context.Context) error {
	client, err := n.server.LocalClient()
	if err != nil {
		return err
	}
	return client.Logout(ctx)
}

func (n *Node) dialer() (*tsdial.Dialer, error) {
	sys := n.server.Sys()
	if sys == nil {
		return nil, codes.New(codes.NotReady, "node is not started")
	}
	d, ok := sys.Dialer.GetOK()
	if !ok || d == nil {
		return nil, codes.New(codes.NotReady, "node has no dialer yet")
	}
	return d, nil
}

// plan resolves target on this tailnet for network ("tcp" or "udp").
//
// Right after login the control plane's netmap may not include the target yet. Treat
// NOT_IN_TAILNET / NAME_NOT_FOUND as transient for a short grace period, so the first
// connection after login does not fail with a misleading error.
func (n *Node) plan(ctx context.Context, target, network string) (netip.AddrPort, *ipnstate.Status, *tsdial.Dialer, error) {
	deadline := time.Now().Add(netmapGrace)
	for {
		ap, st, d, err := n.planOnce(ctx, target, network)
		if err == nil || time.Now().After(deadline) || !transientTarget(err) {
			return ap, st, d, err
		}
		select {
		case <-ctx.Done():
			return ap, st, d, err
		case <-time.After(200 * time.Millisecond):
		}
	}
}

const netmapGrace = 3 * time.Second

func transientTarget(err error) bool {
	var e *codes.Error
	if !errors.As(err, &e) {
		return false
	}
	return e.Code == codes.NotInTailnet || e.Code == codes.NameNotFound
}

func (n *Node) planOnce(ctx context.Context, target, network string) (netip.AddrPort, *ipnstate.Status, *tsdial.Dialer, error) {
	client, err := n.server.LocalClient()
	if err != nil {
		return netip.AddrPort{}, nil, nil, err
	}
	st, err := client.Status(ctx)
	if err != nil {
		return netip.AddrPort{}, nil, nil, err
	}
	if err := readiness(st, time.Now()); err != nil {
		return netip.AddrPort{}, st, nil, err
	}
	d, err := n.dialer()
	if err != nil {
		return netip.AddrPort{}, st, nil, err
	}
	inTailnet := func(ip netip.Addr) bool {
		// A literal address is never resolved; only its route is looked up.
		_, via, err := d.UserDialPlan(ctx, network, netip.AddrPortFrom(ip, 1).String())
		return err == nil && via
	}
	ap, err := resolveTarget(st, target, network, inTailnet)
	return ap, st, d, err
}

// Dial opens a TCP connection to target ("host:port") inside this tailnet only.
func (n *Node) Dial(ctx context.Context, target string) (net.Conn, error) {
	ap, st, d, err := n.plan(ctx, target, "tcp")
	if err != nil {
		return nil, err
	}
	conn, err := d.UserDial(ctx, "tcp", ap.String())
	if err != nil {
		if offline(st, ap.Addr()) {
			return nil, codes.New(codes.TargetUnreachable, "peer is offline")
		}
		return nil, codes.Of(err, codes.TargetUnreachable)
	}
	return conn, nil
}

// ResolveUDP resolves target once for a UDP relay, under the same rules as Dial.
func (n *Node) ResolveUDP(ctx context.Context, target string) (netip.AddrPort, error) {
	ap, _, _, err := n.plan(ctx, target, "udp")
	return ap, err
}

func offline(st *ipnstate.Status, ip netip.Addr) bool {
	for _, p := range st.Peer {
		for _, a := range p.TailscaleIPs {
			if a == ip {
				return !p.Online
			}
		}
	}
	return false
}

func (n *Node) IPs() []netip.Addr {
	ip4, ip6 := n.server.TailscaleIPs()
	ips := make([]netip.Addr, 0, 2)
	for _, ip := range []netip.Addr{ip4, ip6} {
		if ip.IsValid() {
			ips = append(ips, ip)
		}
	}
	return ips
}

func (n *Node) ListenPacket(network, address string) (net.PacketConn, error) {
	return n.server.ListenPacket(network, address)
}

// InterfacesChanged makes the node re-read the interface snapshot now.
func (n *Node) InterfacesChanged() {
	if sys := n.server.Sys(); sys != nil {
		if monitor, ok := sys.NetMon.GetOK(); ok && monitor != nil {
			monitor.InjectEvent()
		}
	}
}
