package netbridge

import (
	"context"
	"crypto/rand"
	"crypto/subtle"
	"crypto/x509"
	"encoding/hex"
	"errors"
	"net"
	"net/netip"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"github.com/briqt/moke/netbridge/internal/codes"
	"github.com/briqt/moke/netbridge/internal/netstate"
	"github.com/briqt/moke/netbridge/internal/proxynet"
	"github.com/briqt/moke/netbridge/internal/relay"
	"github.com/briqt/moke/netbridge/internal/socks"
	"github.com/briqt/moke/netbridge/internal/tailnet"
	"tailscale.com/net/netmon"
)

const (
	maxNetworks    = 16
	requestTimeout = 10 * time.Second
	// A dial ticket not used by then is dropped and its lease returned.
	ticketTTL     = 60 * time.Second
	maxDialErrors = 256
	dialErrorTTL  = 5 * time.Minute
)

// tailnetNode is the subset of *tailnet.Node the bridge uses (fakes in tests).
type tailnetNode interface {
	Start() error
	Close() error
	Snapshot(context.Context) (tailnet.Snapshot, error)
	StartLogin(context.Context) error
	Logout(context.Context) error
	Dial(context.Context, string) (net.Conn, error)
	ResolveUDP(context.Context, string) (netip.AddrPort, error)
	IPs() []netip.Addr
	ListenPacket(string, string) (net.PacketConn, error)
	InterfacesChanged()
}

// network is one started tailnet node or proxy entry.
type network struct {
	id string
	// op serialises lifecycle calls on this network (start/login/logout/stop);
	// it is never held while waiting on another network.
	op      sync.Mutex
	node    tailnetNode
	dir     string // Tailnet working directory; unique per live node.
	proxy   *proxynet.Config
	secrets []string
	// Guarded by bridge.mu.
	leases   int
	stopping bool
}

type ticket struct {
	secret  string
	network *network
	expires time.Time
}

type dialError struct {
	err *codes.Error
	at  time.Time
}

type bridge struct {
	// mu guards the registry and lease counts only; it is held briefly and
	// never across network I/O or a call into a node.
	mu          sync.Mutex
	networks    map[string]*network
	tickets     map[string]*ticket
	dialErrors  map[string]dialError
	initialized bool
	logsDir     string
	roots       *x509.CertPool
	store       StateStore

	ifaces netstate.Store
	relays *relay.Manager
	socks  *socks.Server
	ln     net.Listener

	newNode       func(tailnet.Config, tailnet.Store) tailnetNode
	setup         func(func() ([]netmon.Interface, error))
	setenv        func(string, string) error
	reportDefault func(string)
	now           func() time.Time
}

func newBridge() *bridge {
	b := &bridge{
		networks:      make(map[string]*network),
		tickets:       make(map[string]*ticket),
		dialErrors:    make(map[string]dialError),
		relays:        relay.NewManager(),
		newNode:       func(c tailnet.Config, s tailnet.Store) tailnetNode { return tailnet.New(c, s) },
		setenv:        os.Setenv,
		reportDefault: reportDefaultRoute,
		now:           time.Now,
	}
	b.socks = &socks.Server{Authenticate: b.authenticate}
	return b
}

func randomHex(n int) string {
	buf := make([]byte, n)
	rand.Read(buf)
	return hex.EncodeToString(buf)
}

func (b *bridge) interfaceSnapshot() ([]netmon.Interface, error) {
	values := b.ifaces.Snapshot()
	result := make([]netmon.Interface, 0, len(values))
	for _, value := range values {
		addrs := value.Addrs
		if addrs == nil {
			addrs = make([]net.Addr, 0)
		}
		result = append(result, netmon.Interface{
			Interface: &net.Interface{Index: value.Index, Name: value.Name, MTU: value.MTU, Flags: value.Flags},
			AltAddrs:  addrs, // Non-nil even when empty: never fall back to netlink.
		})
	}
	return result, nil
}

func (b *bridge) initialize(snapshotJSON string) error {
	if err := b.ifaces.Update(snapshotJSON); err != nil {
		return codes.Boundary("initialize", err, codes.InvalidArgument)
	}
	b.mu.Lock()
	first := !b.initialized
	b.initialized = true
	b.mu.Unlock()
	if first {
		if b.setup != nil {
			b.setup(b.interfaceSnapshot)
		}
		ln, err := net.Listen("tcp4", "127.0.0.1:0")
		if err != nil {
			return codes.Boundary("open loopback SOCKS", err, codes.Internal)
		}
		b.mu.Lock()
		b.ln = ln
		b.mu.Unlock()
		go b.socks.Serve(ln)
	}
	b.interfacesChanged()
	return nil
}

func (b *bridge) replaceInterfaces(snapshotJSON string) error {
	if !b.isInitialized() {
		return codes.New(codes.NotInitialized, "Initialize must be called first")
	}
	if err := b.ifaces.Update(snapshotJSON); err != nil {
		return codes.Boundary("replace interfaces", err, codes.InvalidArgument)
	}
	b.interfacesChanged()
	return nil
}

// interfacesChanged reports the default route, then makes every node re-read.
// It takes no lifecycle lock, so a long start or login never delays it.
func (b *bridge) interfacesChanged() {
	b.reportDefault(b.ifaces.DefaultInterface())
	b.mu.Lock()
	nodes := make([]tailnetNode, 0, len(b.networks))
	for _, n := range b.networks {
		if n.node != nil && !n.stopping {
			nodes = append(nodes, n.node)
		}
	}
	b.mu.Unlock()
	for _, node := range nodes {
		node.InterfacesChanged()
	}
}

func (b *bridge) isInitialized() bool {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.initialized
}

func (b *bridge) setLogsDir(dir string) error {
	if len(dir) > 4096 || !filepath.IsAbs(dir) || strings.ContainsAny(dir, "\x00\r\n") || filepath.Clean(dir) == string(filepath.Separator) {
		return codes.New(codes.InvalidArgument, "logsDir must be an absolute, non-root directory path")
	}
	dir = filepath.Clean(dir)
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.logsDir != "" {
		if b.logsDir == dir {
			return nil
		}
		return codes.New(codes.InvalidArgument, "logsDir is already set for this process")
	}
	if len(b.networks) > 0 {
		return codes.New(codes.InvalidArgument, "logsDir must be set before starting any network")
	}
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return codes.Boundary("create logsDir", err, codes.Internal)
	}
	// tsnet's logpolicy panics on Android without a writable logs directory.
	if err := b.setenv("TS_LOGS_DIR", dir); err != nil {
		return codes.Boundary("set logsDir", err, codes.Internal)
	}
	b.logsDir = dir
	return nil
}

func (b *bridge) setTrustAnchors(pem string) error {
	pool := x509.NewCertPool()
	if !pool.AppendCertsFromPEM([]byte(pem)) {
		return codes.New(codes.InvalidArgument, "no certificates in trust anchors")
	}
	b.mu.Lock()
	b.roots = pool
	b.mu.Unlock()
	return nil
}

// reserve registers a fully built network before its (slow) start, so
// concurrent starts of the same ID fail fast and other networks are not
// blocked. The network stays unusable until publish.
func (b *bridge) reserve(n *network) error {
	b.mu.Lock()
	defer b.mu.Unlock()
	if !b.initialized {
		return codes.New(codes.NotInitialized, "Initialize must be called first")
	}
	if _, exists := b.networks[n.id]; exists {
		return codes.New(codes.AlreadyStarted, "network ID is already started")
	}
	if len(b.networks) >= maxNetworks {
		return codes.New(codes.Limit, "network limit reached")
	}
	for _, other := range b.networks {
		if n.dir != "" && other.dir == n.dir {
			return codes.New(codes.InvalidArgument, "dir is already used by another network")
		}
	}
	n.stopping = true
	n.op.Lock()
	b.networks[n.id] = n
	return nil
}

// publish makes a reserved network usable, or drops it if start failed.
func (b *bridge) publish(n *network, ok bool) {
	b.mu.Lock()
	if ok {
		n.stopping = false
	} else {
		delete(b.networks, n.id)
	}
	b.mu.Unlock()
	n.op.Unlock()
}

func (b *bridge) startNode(configJSON string) error {
	c, err := parseNodeConfig(configJSON)
	if err != nil {
		return err
	}
	if c.Dir, err = canonicalDir(c.Dir); err != nil {
		return codes.Boundary("start node", err, codes.InvalidArgument)
	}
	b.mu.Lock()
	store, logsDir := b.store, b.logsDir
	b.mu.Unlock()
	if store == nil {
		return codes.New(codes.NotInitialized, "SetStateStore must be called before starting a node")
	}
	if logsDir == "" {
		return codes.New(codes.NotInitialized, "SetLogsDir must be called before starting a node")
	}
	if err := checkCredentialEnvironment(c.AuthKey, os.Getenv); err != nil {
		return err
	}
	n := &network{id: c.ID, dir: c.Dir, secrets: []string{c.AuthKey}}
	n.node = b.newNode(tailnet.Config{ID: c.ID, Dir: c.Dir, ControlURL: c.ControlURL, AuthKey: c.AuthKey, Hostname: c.Hostname},
		boundStore{id: c.ID, store: store})
	if err := b.reserve(n); err != nil {
		return err
	}
	if err := n.node.Start(); err != nil {
		// Never reuse a server whose Start failed; close what it created.
		err = errors.Join(err, n.node.Close())
		b.publish(n, false)
		return codes.Boundary("start node", err, codes.Internal, c.AuthKey)
	}
	b.publish(n, true)
	return nil
}

func (b *bridge) startProxy(configJSON string) error {
	c, err := proxynet.ParseConfig(configJSON)
	if err != nil {
		return codes.Boundary("", err, codes.InvalidArgument)
	}
	if !validID(c.ID) {
		return codes.New(codes.InvalidArgument, "invalid proxy ID")
	}
	b.mu.Lock()
	c.Roots = b.roots
	b.mu.Unlock()
	n := &network{id: c.ID, proxy: &c, secrets: proxySecrets(c)}
	if err := b.reserve(n); err != nil {
		return err
	}
	b.publish(n, true)
	return nil
}

func proxySecrets(c proxynet.Config) []string {
	return []string{c.Password, c.Username}
}

// lookup returns a usable network.
func (b *bridge) lookup(id string) (*network, error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	n := b.networks[id]
	if n == nil || n.stopping {
		return nil, codes.New(codes.NotStarted, "network is not started")
	}
	return n, nil
}

func (b *bridge) acquire(id string) (*network, error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	n := b.networks[id]
	if n == nil || n.stopping {
		return nil, codes.New(codes.NotStarted, "network is not started")
	}
	n.leases++
	return n, nil
}

func (b *bridge) release(n *network) {
	b.mu.Lock()
	if n.leases > 0 {
		n.leases--
	}
	b.mu.Unlock()
}

// stop closes a network only when nothing uses it: a stop must never cut a
// live connection or relay (the app retries after its idle delay).
func (b *bridge) stop(id string) error {
	b.mu.Lock()
	n := b.networks[id]
	b.mu.Unlock()
	if n == nil {
		return nil
	}
	n.op.Lock()
	defer n.op.Unlock()
	b.sweepTickets()
	b.mu.Lock()
	if b.networks[id] != n {
		b.mu.Unlock()
		return nil
	}
	if n.leases > 0 {
		b.mu.Unlock()
		return codes.New(codes.Busy, "network still carries connections")
	}
	n.stopping = true
	delete(b.networks, id)
	b.mu.Unlock()
	if n.node != nil {
		return codes.Boundary("stop node", n.node.Close(), codes.Internal, n.secrets...)
	}
	return nil
}

func (b *bridge) leaseCount(id string) int {
	b.mu.Lock()
	defer b.mu.Unlock()
	if n := b.networks[id]; n != nil {
		return n.leases
	}
	return 0
}

// --- Tailnet node operations -------------------------------------------------

func (b *bridge) nodeOf(id string) (*network, error) {
	n, err := b.lookup(id)
	if err != nil {
		return nil, err
	}
	if n.node == nil {
		return nil, codes.New(codes.InvalidArgument, "network is not a tailnet")
	}
	return n, nil
}

func (b *bridge) nodeStatus(id string) (tailnet.Snapshot, error) {
	n, err := b.nodeOf(id)
	if err != nil {
		return tailnet.Snapshot{}, err
	}
	ctx, cancel := context.WithTimeout(context.Background(), requestTimeout)
	defer cancel()
	s, err := n.node.Snapshot(ctx)
	if err != nil {
		return tailnet.Snapshot{}, codes.Boundary("read node status", err, codes.Internal, n.secrets...)
	}
	return s, nil
}

// loginURL starts an interactive login and waits for the control server's URL.
// "" means no login is needed (any more).
func (b *bridge) loginURL(id string) (string, error) {
	n, err := b.nodeOf(id)
	if err != nil {
		return "", err
	}
	n.op.Lock()
	defer n.op.Unlock()
	ctx, cancel := context.WithTimeout(context.Background(), requestTimeout)
	defer cancel()
	s, err := n.node.Snapshot(ctx)
	if err != nil {
		return "", codes.Boundary("read login state", err, codes.Internal, n.secrets...)
	}
	// Running + expired is a real state: the node key expired while the node kept running.
	// Log out first so the control plane accepts a fresh interactive login; otherwise the UI
	// would wait forever for an AuthURL that never comes.
	if s.State != "NeedsLogin" {
		if !s.Expired {
			return "", nil
		}
		if err := n.node.Logout(ctx); err != nil {
			return "", codes.Boundary("log out expired node", err, codes.Internal, n.secrets...)
		}
		s, err = n.node.Snapshot(ctx)
		if err != nil {
			return "", codes.Boundary("read login state after logout", err, codes.Internal, n.secrets...)
		}
		if s.State != "NeedsLogin" {
			return "", codes.New(codes.NotReady, "node is "+s.State)
		}
	}
	if s.AuthURL == "" {
		if err := n.node.StartLogin(ctx); err != nil {
			return "", codes.Boundary("start login", err, codes.Internal, n.secrets...)
		}
	}
	ticker := time.NewTicker(100 * time.Millisecond)
	defer ticker.Stop()
	for {
		s, err = n.node.Snapshot(ctx)
		if err != nil {
			return "", codes.Boundary("read login URL", err, codes.Internal, n.secrets...)
		}
		if s.State != "NeedsLogin" {
			return "", nil
		}
		if s.AuthURL != "" {
			return s.AuthURL, nil
		}
		select {
		case <-ctx.Done():
			return "", codes.New(codes.Timeout, "control server sent no login URL")
		case <-ticker.C:
		}
	}
}

func (b *bridge) logout(id string) error {
	n, err := b.nodeOf(id)
	if err != nil {
		return err
	}
	n.op.Lock()
	defer n.op.Unlock()
	ctx, cancel := context.WithTimeout(context.Background(), requestTimeout)
	defer cancel()
	return codes.Boundary("log out", n.node.Logout(ctx), codes.Internal, n.secrets...)
}

// resolveTarget resolves "host:port" on a tailnet once (for mosh: the SSH
// bootstrap and the UDP relay then share one literal address).
func (b *bridge) resolveTarget(id, target string) (string, error) {
	n, err := b.nodeOf(id)
	if err != nil {
		return "", err
	}
	ctx, cancel := context.WithTimeout(context.Background(), requestTimeout)
	defer cancel()
	ap, err := n.node.ResolveUDP(ctx, target)
	if err != nil {
		return "", codes.Boundary("resolve target", err, codes.Internal, n.secrets...)
	}
	return ap.String(), nil
}

// --- Per-connection dial tickets ---------------------------------------------

type dialInfo struct {
	Addr     string `json:"addr"`
	Username string `json:"username"`
	Password string `json:"password"`
}

// openDial issues single-use SOCKS credentials for one connection on network
// id. The ticket holds a lease from now until the connection ends (or the
// ticket expires unused, or is cancelled).
func (b *bridge) openDial(id string) (dialInfo, error) {
	b.sweepTickets()
	n, err := b.acquire(id)
	if err != nil {
		return dialInfo{}, err
	}
	name, secret := randomHex(12), randomHex(24)
	b.mu.Lock()
	addr := ""
	if b.ln != nil {
		addr = b.ln.Addr().String()
	}
	b.tickets[name] = &ticket{secret: secret, network: n, expires: b.now().Add(ticketTTL)}
	b.mu.Unlock()
	if addr == "" {
		b.cancelDial(name)
		return dialInfo{}, codes.New(codes.NotInitialized, "loopback SOCKS is not running")
	}
	return dialInfo{Addr: addr, Username: name, Password: secret}, nil
}

func (b *bridge) cancelDial(name string) {
	b.mu.Lock()
	t := b.tickets[name]
	delete(b.tickets, name)
	b.mu.Unlock()
	if t != nil {
		b.release(t.network)
	}
}

func (b *bridge) sweepTickets() {
	now := b.now()
	b.mu.Lock()
	var expired []*ticket
	for name, t := range b.tickets {
		if now.After(t.expires) {
			expired = append(expired, t)
			delete(b.tickets, name)
		}
	}
	for name, e := range b.dialErrors {
		if now.Sub(e.at) > dialErrorTTL {
			delete(b.dialErrors, name)
		}
	}
	b.mu.Unlock()
	for _, t := range expired {
		b.release(t.network)
	}
}

func (b *bridge) authenticate(username, password string) (socks.Session, bool) {
	b.mu.Lock()
	t := b.tickets[username]
	valid := t != nil && subtle.ConstantTimeCompare([]byte(t.secret), []byte(password)) == 1 && !b.now().After(t.expires)
	if valid {
		delete(b.tickets, username) // Single use: the lease moves to the connection.
	}
	b.mu.Unlock()
	if !valid {
		return nil, false
	}
	return &dialSession{bridge: b, name: username, network: t.network}, true
}

type dialSession struct {
	bridge  *bridge
	name    string
	network *network
	once    sync.Once
}

func (s *dialSession) Dial(ctx context.Context, target string) (net.Conn, error) {
	n := s.network
	if n.node != nil {
		return n.node.Dial(ctx, target)
	}
	return n.proxy.DialTCP(ctx, target)
}

func (s *dialSession) Failed(err error) byte {
	e := codes.Of(err, codes.ProxyRefused)
	if s.network.node != nil {
		e = codes.Of(err, codes.TargetUnreachable)
	}
	e = &codes.Error{Code: e.Code, Detail: codes.Redact(e.Detail, s.network.secrets...)}
	b := s.bridge
	b.mu.Lock()
	if len(b.dialErrors) >= maxDialErrors {
		var oldest string
		for name, v := range b.dialErrors {
			if oldest == "" || v.at.Before(b.dialErrors[oldest].at) {
				oldest = name
			}
		}
		delete(b.dialErrors, oldest)
	}
	b.dialErrors[s.name] = dialError{err: e, at: b.now()}
	b.mu.Unlock()
	return replyFor(e.Code)
}

func (s *dialSession) Done() {
	s.once.Do(func() { s.bridge.release(s.network) })
}

func replyFor(code codes.Code) byte {
	switch code {
	case codes.NotInTailnet:
		return socks.RepNotAllowed
	case codes.NameNotFound, codes.TargetUnreachable:
		return socks.RepHostUnreachable
	case codes.TargetRefused:
		return socks.RepConnectionRefused
	case codes.Timeout:
		return socks.RepTTLExpired
	case codes.NeedsLogin, codes.NeedsMachineAuth, codes.LoginExpired, codes.NotReady:
		return socks.RepNetworkUnreachable
	default:
		return socks.RepGeneralFailure
	}
}

// dialErrorOf returns (and forgets) why the connection with these credentials failed.
func (b *bridge) dialErrorOf(name string) *codes.Error {
	b.mu.Lock()
	defer b.mu.Unlock()
	e, ok := b.dialErrors[name]
	if !ok {
		return nil
	}
	delete(b.dialErrors, name)
	return e.err
}

// --- UDP relays --------------------------------------------------------------

func (b *bridge) openUDPRelay(configJSON string) (relay.Info, error) {
	c, err := relay.ParseConfig(configJSON)
	if err != nil {
		return relay.Info{}, codes.Boundary("", err, codes.InvalidArgument)
	}
	n, err := b.acquire(c.NetworkID)
	if err != nil {
		return relay.Info{}, err
	}
	var network relay.Network
	switch {
	case n.proxy != nil:
		if !n.proxy.SupportsUDP() {
			b.release(n)
			return relay.Info{}, proxynet.ErrUDPUnsupported
		}
		proxy := *n.proxy
		// A network change drops the association's control connection; the relay
		// then associates again (RFC 1928 ties the association to that connection).
		network = relay.Network{ID: n.id, AnySource: true, Reopen: true, ListenPacket: func(string, string) (net.PacketConn, error) {
			ctx, cancel := context.WithTimeout(context.Background(), requestTimeout)
			defer cancel()
			return proxy.AssociateUDP(ctx)
		}}
	default:
		// The target must be a literal address inside this tailnet, exactly like a
		// TCP dial (the app resolves names with ResolveTarget first).
		var err error
		if _, perr := netip.ParseAddr(c.TargetHost); perr != nil {
			err = codes.New(codes.InvalidArgument, "tailnet UDP relay needs a literal target IP")
		} else {
			ctx, cancel := context.WithTimeout(context.Background(), requestTimeout)
			_, err = n.node.ResolveUDP(ctx, net.JoinHostPort(c.TargetHost, "1"))
			cancel()
		}
		if err != nil {
			b.release(n)
			return relay.Info{}, codes.Boundary("open UDP relay", err, codes.Internal, n.secrets...)
		}
		network = relay.Network{ID: n.id, IPs: n.node.IPs(), ListenPacket: n.node.ListenPacket}
	}
	info, err := b.relays.Open(c, network)
	if err != nil {
		b.release(n)
		return relay.Info{}, codes.Boundary("open UDP relay", err, codes.Internal, n.secrets...)
	}
	return info, nil
}

// startUDPRelay gives an open relay the mosh-server port and session key. The
// key stays in memory and is removed from any error text.
func (b *bridge) startUDPRelay(id string, port int, key string) error {
	return codes.Boundary("start UDP relay", b.relays.Start(id, port, key), codes.InvalidArgument, key)
}

func (b *bridge) closeUDPRelay(id string) error {
	networkID, ok := b.relays.NetworkOf(id)
	err := b.relays.Close(id)
	if ok {
		b.mu.Lock()
		n := b.networks[networkID]
		b.mu.Unlock()
		if n != nil {
			b.release(n)
		}
	}
	return codes.Boundary("close UDP relay", err, codes.Internal)
}
