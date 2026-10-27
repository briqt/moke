package tailnet

import (
	"cmp"
	"context"
	"net/netip"
	"slices"
	"strings"
	"time"

	"github.com/briqt/moke/netbridge/internal/codes"
	"tailscale.com/ipn/ipnstate"
	"tailscale.com/net/tsaddr"
)

// Peer is the visible part of a node's current netmap. TailscaleSSH only means
// that SSH host keys are advertised; it does not imply permission to log in.
type Peer struct {
	ID           string   `json:"id"`
	Hostname     string   `json:"hostname"`
	DNSName      string   `json:"dnsName"`
	IPs          []string `json:"ips"`
	OS           string   `json:"os"`
	Online       bool     `json:"online"`
	TailscaleSSH bool     `json:"tailscaleSsh"`
}

// Peers reads this node's local status only; it never probes the peers.
func (n *Node) Peers(ctx context.Context) ([]Peer, error) {
	client, err := n.server.LocalClient()
	if err != nil {
		return nil, err
	}
	return readPeers(ctx, client.Status, netmapGrace)
}

// A resumed node can report Running before its initial peer update arrives.
// Reuse the bounded netmap grace used by dialing instead of presenting that
// transient empty snapshot as a finished discovery result.
func readPeers(ctx context.Context, status func(context.Context) (*ipnstate.Status, error), grace time.Duration) ([]Peer, error) {
	deadline := time.Now().Add(grace)
	for {
		st, err := status(ctx)
		if err != nil {
			return nil, err
		}
		peers, err := peersOf(st, time.Now())
		left := time.Until(deadline)
		if err != nil || len(peers) > 0 || left <= 0 {
			return peers, err
		}
		timer := time.NewTimer(min(200*time.Millisecond, left))
		select {
		case <-ctx.Done():
			timer.Stop()
			return nil, ctx.Err()
		case <-timer.C:
		}
	}
}

func peersOf(st *ipnstate.Status, now time.Time) ([]Peer, error) {
	if st == nil {
		return nil, codes.New(codes.NotReady, "node has no status yet")
	}
	if err := readiness(st, now); err != nil {
		return nil, err
	}
	peers := make([]Peer, 0, len(st.Peer))
	for publicKey, p := range st.Peer {
		if p == nil || !p.InNetworkMap || p.ShareeNode {
			continue
		}
		if self := st.Self; self != nil && (p == self ||
			(self.ID != "" && p.ID == self.ID) ||
			(!self.PublicKey.IsZero() && (publicKey == self.PublicKey || p.PublicKey == self.PublicKey))) {
			continue
		}
		ips := make([]netip.Addr, 0, len(p.TailscaleIPs))
		for _, ip := range p.TailscaleIPs {
			if ip.IsValid() && ip.Zone() == "" && tsaddr.IsTailscaleIP(ip) {
				ips = append(ips, ip.Unmap())
			}
		}
		// Addr.Compare puts IPv4 before IPv6 and orders each family numerically.
		slices.SortFunc(ips, netip.Addr.Compare)
		ips = slices.Compact(ips)
		if len(ips) == 0 {
			continue
		}
		peer := Peer{
			ID: string(p.ID), Hostname: p.HostName, DNSName: strings.TrimSuffix(p.DNSName, "."),
			IPs: make([]string, len(ips)), OS: p.OS, Online: p.Online, TailscaleSSH: len(p.SSH_HostKeys) > 0,
		}
		for i, ip := range ips {
			peer.IPs[i] = ip.String()
		}
		peers = append(peers, peer)
	}
	// Use the available name (hostname, DNS, then IP); never synthesize a name
	// in the returned data. The stable node ID breaks ties between namesakes.
	slices.SortFunc(peers, func(a, b Peer) int {
		return cmp.Or(strings.Compare(peerSortName(a), peerSortName(b)),
			strings.Compare(a.DNSName, b.DNSName), slices.Compare(a.IPs, b.IPs), strings.Compare(a.ID, b.ID))
	})
	return peers, nil
}

func peerSortName(p Peer) string {
	if p.Hostname != "" {
		return p.Hostname
	}
	if p.DNSName != "" {
		return p.DNSName
	}
	return p.IPs[0]
}
