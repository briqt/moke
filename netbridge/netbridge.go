// Package netbridge is the app's gomobile surface for networks: userspace
// tailnet nodes and external proxies, reached through one loopback SOCKS5
// server with per-connection credentials, plus UDP relays for mosh.
//
// Only strings, booleans, integers, byte slices, errors and the StateStore
// interface cross the boundary. Every error is a JSON object
// {"code": ..., "detail": ...} with secrets removed; see internal/codes.
package netbridge

import (
	"encoding/json"
	"io"
	"log"
	"os"
	"sync"
	"time"

	"github.com/briqt/moke/netbridge/internal/tailnet"
	"tailscale.com/envknob"
	"tailscale.com/logtail"
	"tailscale.com/net/netmon"
)

// StateStore keeps tailnet node state outside Go; the app encrypts it at rest.
// Read returns nil for a missing key. Write with a nil value deletes the key.
type StateStore interface {
	Read(networkID, key string) ([]byte, error)
	Write(networkID, key string, value []byte) error
}

var (
	setupOnce     sync.Once
	defaultBridge = func() *bridge {
		b := newBridge()
		b.setup = func(getter func() ([]netmon.Interface, error)) {
			setupOnce.Do(func() {
				// No log uploads: suppressing callbacks alone does not stop logtail,
				// and its buffer would otherwise keep login URLs on disk.
				envknob.SetNoLogsNoSupport()
				logtail.Disable()
				// A process-wide, unsynchronised upstream global: register once,
				// before any node; later updates only change the snapshot store.
				netmon.RegisterInterfaceGetter(getter)
			})
		}
		return b
	}()
)

func init() {
	// Go's standard logger reaches logcat through gomobile; it stays silent
	// unless the app asks for it (debug builds).
	log.SetOutput(io.Discard)
}

// SetDebugLogging sends Go's standard log to stderr (logcat) when enabled.
func SetDebugLogging(enabled bool) {
	if enabled {
		log.SetOutput(os.Stderr)
	} else {
		log.SetOutput(io.Discard)
	}
}

// SetStateStore must be called before the first StartNode.
func SetStateStore(store StateStore) {
	defaultBridge.mu.Lock()
	defaultBridge.store = store
	defaultBridge.mu.Unlock()
}

// SetLogsDir sets the private directory tsnet's log policy requires; once per process.
func SetLogsDir(dir string) error { return defaultBridge.setLogsDir(dir) }

// SetTrustAnchors replaces the roots HTTPS proxies are verified against (PEM).
func SetTrustAnchors(pem string) error { return defaultBridge.setTrustAnchors(pem) }

// Initialize takes the first interface snapshot and starts the loopback SOCKS server.
func Initialize(snapshotJSON string) error { return defaultBridge.initialize(snapshotJSON) }

// ReplaceInterfaces reports a network change; call it as soon as it happens.
func ReplaceInterfaces(snapshotJSON string) error {
	return defaultBridge.replaceInterfaces(snapshotJSON)
}

// StartNode starts a tailnet node: {"id","dir","controlURL","authKey","hostname"}.
func StartNode(configJSON string) error { return defaultBridge.startNode(configJSON) }

// StartProxy registers an external proxy: {"id","type","host","port","username","password",...}.
func StartProxy(configJSON string) error { return defaultBridge.startProxy(configJSON) }

// Stop stops a node or proxy. It fails with BUSY while connections, relays or peer reads use it.
func Stop(id string) error { return defaultBridge.stop(id) }

// Leases is how many connections, pending dials, relays and peer reads use network id.
func Leases(id string) int { return defaultBridge.leaseCount(id) }

// SweepTickets releases dial tickets that were never consumed and have expired. The idle reaper
// calls it so an abandoned ticket cannot pin a network instance forever.
func SweepTickets() { defaultBridge.sweepTickets() }

type statusJSON struct {
	ID        string   `json:"id"`
	State     string   `json:"state"`
	IPs       []string `json:"ips"`
	Tailnet   string   `json:"tailnet"`
	DNSName   string   `json:"dnsName"`
	LoginName string   `json:"loginName"`
	KeyExpiry int64    `json:"keyExpiry"` // Unix seconds; 0 if none.
	Expired   bool     `json:"expired"`
	// HaveNodeKey: logged in before; NeedsLogin then means the login expired or was revoked.
	HaveNodeKey bool `json:"haveNodeKey"`
	Leases      int  `json:"leases"`
}

func knownState(state string) string {
	switch state {
	case "NoState", "NeedsLogin", "NeedsMachineAuth", "Stopped", "Starting", "Running":
		return state
	default:
		return "Unknown"
	}
}

// NodeStatus returns the node's state as JSON. It never includes the login URL.
func NodeStatus(id string) (string, error) {
	s, err := defaultBridge.nodeStatus(id)
	if err != nil {
		return "", err
	}
	out := statusJSON{ID: id, State: knownState(s.State), IPs: []string{}, Tailnet: s.Tailnet, DNSName: s.DNSName,
		LoginName: s.LoginName, Expired: s.Expired, HaveNodeKey: s.HaveNodeKey, Leases: defaultBridge.leaseCount(id)}
	for _, ip := range s.IPs {
		if ip.IsValid() {
			out.IPs = append(out.IPs, ip.String())
		}
	}
	if !s.KeyExpiry.IsZero() {
		out.KeyExpiry = s.KeyExpiry.Unix()
	}
	data, err := json.Marshal(out)
	return string(data), err
}

// NodePeers returns {"peers":[...]} from this node's current visible netmap,
// without probing peers. tailscaleSsh is an advertisement, not login authorization.
func NodePeers(id string) (string, error) {
	peers, err := defaultBridge.nodePeers(id)
	if err != nil {
		return "", err
	}
	if peers == nil {
		peers = []tailnet.Peer{}
	}
	data, err := json.Marshal(struct {
		Peers []tailnet.Peer `json:"peers"`
	}{Peers: peers})
	return string(data), err
}

// LoginURL starts an interactive login and returns the URL to open; "" if none is needed.
func LoginURL(id string) (string, error) { return defaultBridge.loginURL(id) }

// Logout logs the node out at the control server.
func Logout(id string) error { return defaultBridge.logout(id) }

// ResolveTarget resolves "host:port" inside tailnet id to a literal "ip:port".
func ResolveTarget(id, target string) (string, error) { return defaultBridge.resolveTarget(id, target) }

// OpenDial issues single-use SOCKS5 credentials for one connection on network
// id: {"addr","username","password"}. They expire unused after a minute.
func OpenDial(id string) (string, error) {
	info, err := defaultBridge.openDial(id)
	if err != nil {
		return "", err
	}
	data, err := json.Marshal(info)
	return string(data), err
}

// CancelDial returns the lease of credentials that will not be used.
func CancelDial(username string) { defaultBridge.cancelDial(username) }

// DialError returns why the connection made with these credentials failed, as
// {"code","detail"}, or "" if no failure was recorded. Reading clears it.
func DialError(username string) string {
	if e := defaultBridge.dialErrorOf(username); e != nil {
		return e.Error()
	}
	return ""
}

// OpenUDPRelay opens a mosh relay before the bootstrap: {"id","networkID","targetHost"}.
// targetHost is a literal IP (a tailnet address from ResolveTarget), or for a
// SOCKS5 proxy also a name the proxy resolves. Nothing is forwarded until
// StartUDPRelay; it fails with PROXY_NO_UDP when the network cannot carry UDP.
func OpenUDPRelay(configJSON string) (string, error) {
	info, err := defaultBridge.openUDPRelay(configJSON)
	if err != nil {
		return "", err
	}
	data, err := json.Marshal(info)
	return string(data), err
}

// StartUDPRelay starts forwarding to mosh-server's UDP port, accepting only
// datagrams that authenticate with the session key (MOSH_KEY).
func StartUDPRelay(id string, port int, key string) error {
	return defaultBridge.startUDPRelay(id, port, key)
}

func CloseUDPRelay(id string) error { return defaultBridge.closeUDPRelay(id) }

// Stats is diagnostic JSON without any configuration value.
func Stats() string {
	b := defaultBridge
	b.mu.Lock()
	networks, tickets := len(b.networks), len(b.tickets)
	b.mu.Unlock()
	flow := b.relays.Flow()
	data, _ := json.Marshal(map[string]any{
		"networks": networks, "pendingDials": tickets, "relays": b.relays.Count(),
		"relayDropped": b.relays.Dropped(), "relayRebinds": b.relays.Rebinds(),
		"relayRejected": b.relays.Rejected(), "relayReopens": b.relays.Reopens(),
		"relayFlow": flow, "at": time.Now().Unix(),
	})
	return string(data)
}
