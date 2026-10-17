package tailnet

import (
	"net"
	"net/netip"
	"strconv"
	"strings"

	"github.com/briqt/moke/netbridge/internal/codes"
	"tailscale.com/ipn/ipnstate"
)

// lookupName resolves a MagicDNS name against this node's view of its tailnet,
// with the same keys as the backend's own resolver (FQDN, plus the short name
// when the FQDN carries the tailnet suffix). Nothing else is consulted: a name
// that is not a node of this tailnet is reported, never sent to system DNS.
//
// DNS.ExtraRecords (service VIPs) are not part of ipnstate and are not resolved.
func lookupName(status *ipnstate.Status, host, network string) (netip.Addr, bool) {
	if status == nil {
		return netip.Addr{}, false
	}
	want := strings.ToLower(strings.TrimSuffix(host, "."))
	suffix := ""
	if status.CurrentTailnet != nil {
		suffix = strings.ToLower(strings.TrimSuffix(status.CurrentTailnet.MagicDNSSuffix, "."))
	}
	match := func(p *ipnstate.PeerStatus) bool {
		if p == nil || p.DNSName == "" {
			return false
		}
		canon := strings.ToLower(strings.TrimSuffix(p.DNSName, "."))
		if canon == want {
			return true
		}
		return suffix != "" && strings.HasSuffix(canon, "."+suffix) && strings.TrimSuffix(canon, "."+suffix) == want
	}
	if match(status.Self) {
		return addrFor(status.Self.TailscaleIPs, network)
	}
	for _, p := range status.Peer {
		if match(p) {
			return addrFor(p.TailscaleIPs, network)
		}
	}
	return netip.Addr{}, false
}

// addrFor mirrors the backend's family choice: v4 for "…4", v6 for "…6",
// otherwise v4 when the node has one.
func addrFor(addrs []netip.Addr, network string) (netip.Addr, bool) {
	want4, want6 := strings.HasSuffix(network, "4"), strings.HasSuffix(network, "6")
	var v6 netip.Addr
	for _, ip := range addrs {
		switch {
		case want4 && ip.Is4(), want6 && ip.Is6():
			return ip, true
		case !want4 && !want6 && ip.Is4():
			return ip, true
		case !want4 && !want6 && ip.Is6() && !v6.IsValid():
			v6 = ip
		}
	}
	return v6, v6.IsValid()
}

// resolveTarget turns "host:port" into a literal address on this tailnet.
// inTailnet reports whether the backend routes an address over the tailnet.
func resolveTarget(status *ipnstate.Status, target, network string, inTailnet func(netip.Addr) bool) (netip.AddrPort, error) {
	host, portText, err := net.SplitHostPort(target)
	if err != nil {
		return netip.AddrPort{}, codes.New(codes.InvalidArgument, "target must be host:port")
	}
	port, err := strconv.ParseUint(portText, 10, 16)
	if err != nil || port == 0 || host == "" {
		return netip.AddrPort{}, codes.New(codes.InvalidArgument, "invalid target port")
	}
	ip, err := netip.ParseAddr(host)
	if err != nil {
		var ok bool
		if ip, ok = lookupName(status, host, network); !ok {
			return netip.AddrPort{}, codes.New(codes.NameNotFound, "name is not a node of this tailnet")
		}
	}
	ip = ip.Unmap()
	if ip.Zone() != "" || !inTailnet(ip) {
		return netip.AddrPort{}, codes.New(codes.NotInTailnet, "address is not routed by this tailnet")
	}
	return netip.AddrPortFrom(ip, uint16(port)), nil
}
