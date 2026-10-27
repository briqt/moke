package tailnet

import (
	"encoding/json"
	"net/netip"
	"reflect"
	"strings"
	"testing"
	"time"

	"github.com/briqt/moke/netbridge/internal/codes"
	"tailscale.com/ipn/ipnstate"
	"tailscale.com/tailcfg"
	"tailscale.com/types/key"
)

func TestPeersFiltersCurrentVisibleNetmap(t *testing.T) {
	selfKey := key.NewNode().Public()
	self := &ipnstate.PeerStatus{
		ID: "self", PublicKey: selfKey, InNetworkMap: true,
		TailscaleIPs: []netip.Addr{netip.MustParseAddr("100.64.0.1")},
	}
	st := &ipnstate.Status{BackendState: "Running", Self: self,
		Peer: map[key.NodePublic]*ipnstate.PeerStatus{
			key.NewNode().Public(): nil,
			key.NewNode().Public(): self,
			key.NewNode().Public(): {ID: "self", InNetworkMap: true, TailscaleIPs: self.TailscaleIPs},
			selfKey:                {InNetworkMap: true, TailscaleIPs: self.TailscaleIPs},
			key.NewNode().Public(): {PublicKey: selfKey, InNetworkMap: true, TailscaleIPs: self.TailscaleIPs},
			key.NewNode().Public(): {ID: "hidden", InNetworkMap: true, ShareeNode: true, TailscaleIPs: self.TailscaleIPs},
			key.NewNode().Public(): {ID: "old-netmap", InEngine: true, InMagicSock: true, Online: true, TailscaleIPs: self.TailscaleIPs},
			key.NewNode().Public(): {ID: "no-address", InNetworkMap: true},
			key.NewNode().Public(): {ID: "invalid-addresses", InNetworkMap: true, TailscaleIPs: []netip.Addr{
				{}, netip.MustParseAddr("0.0.0.0"), netip.MustParseAddr("::"),
				netip.MustParseAddr("192.168.1.2"), netip.MustParseAddr("2001:db8::1"),
				netip.MustParseAddr("fd7a:115c:a1e0::2%eth0"), netip.MustParseAddr("::ffff:100.64.0.2%eth0"),
			}},
			key.NewNode().Public(): {ID: "visible-offline", HostName: "offline", InNetworkMap: true,
				TailscaleIPs: []netip.Addr{netip.MustParseAddr("100.64.0.2")}},
			// A shared-to-us peer is still visible; only ShareeNode is hidden.
			key.NewNode().Public(): {ID: "visible-shared", HostName: "shared", AltSharerUserID: 42, InNetworkMap: true,
				Online: true, TailscaleIPs: []netip.Addr{netip.MustParseAddr("100.64.0.3")}},
		},
	}
	got, err := peersOf(st, time.Now())
	want := []Peer{
		{ID: "visible-offline", Hostname: "offline", IPs: []string{"100.64.0.2"}},
		{ID: "visible-shared", Hostname: "shared", IPs: []string{"100.64.0.3"}, Online: true},
	}
	if err != nil || !reflect.DeepEqual(got, want) {
		t.Fatalf("peers = %+v, %v; want %+v", got, err, want)
	}
}

func TestPeersMapsAddressesAndLeavesMissingInformationEmpty(t *testing.T) {
	st := &ipnstate.Status{BackendState: "Running", Peer: map[key.NodePublic]*ipnstate.PeerStatus{
		key.NewNode().Public(): {ID: "dual", HostName: "build-box", DNSName: "build-box.tail.example.",
			OS: "linux", Online: true, InNetworkMap: true, SSH_HostKeys: []string{"ssh-ed25519 advertised-host-key"},
			TailscaleIPs: []netip.Addr{
				netip.MustParseAddr("fd7a:115c:a1e0::10"), netip.MustParseAddr("100.64.0.10"), {},
				netip.MustParseAddr("fd7a:115c:a1e0::2"), netip.MustParseAddr("100.64.0.2"),
				netip.MustParseAddr("::ffff:100.64.0.2"),
			}},
		key.NewNode().Public(): {ID: "v6-only", InNetworkMap: true,
			TailscaleIPs: []netip.Addr{netip.MustParseAddr("fd7a:115c:a1e0::3")}},
		key.NewNode().Public(): {ID: "dns-only", DNSName: "alpha.tail.example.", InNetworkMap: true,
			TailscaleIPs: []netip.Addr{netip.MustParseAddr("100.64.0.4")}},
	}}
	got, err := peersOf(st, time.Now())
	want := []Peer{
		{ID: "dns-only", DNSName: "alpha.tail.example", IPs: []string{"100.64.0.4"}},
		{ID: "dual", Hostname: "build-box", DNSName: "build-box.tail.example", OS: "linux", Online: true, TailscaleSSH: true,
			IPs: []string{"100.64.0.2", "100.64.0.10", "fd7a:115c:a1e0::2", "fd7a:115c:a1e0::10"}},
		{ID: "v6-only", IPs: []string{"fd7a:115c:a1e0::3"}},
	}
	if err != nil || !reflect.DeepEqual(got, want) {
		t.Fatalf("peers = %+v, %v; want %+v", got, err, want)
	}
	if len(st.Peer) != 3 {
		t.Fatal("mapping changed the original status")
	}
}

func TestPeersSortIsStableForDuplicateNames(t *testing.T) {
	st := &ipnstate.Status{BackendState: "Running", Peer: map[key.NodePublic]*ipnstate.PeerStatus{}}
	for _, id := range []tailcfg.StableNodeID{"d", "b", "a", "c"} {
		st.Peer[key.NewNode().Public()] = &ipnstate.PeerStatus{
			ID: id, HostName: "same", DNSName: "same.tail.example.", InNetworkMap: true,
			TailscaleIPs: []netip.Addr{netip.MustParseAddr("100.64.0.2")},
		}
	}
	st.Peer[key.NewNode().Public()] = &ipnstate.PeerStatus{
		ID: "dns-order", HostName: "same", DNSName: "alpha.tail.example.", InNetworkMap: true,
		TailscaleIPs: []netip.Addr{netip.MustParseAddr("100.64.0.2")},
	}
	st.Peer[key.NewNode().Public()] = &ipnstate.PeerStatus{
		ID: "ip-order", HostName: "same", DNSName: "same.tail.example.", InNetworkMap: true,
		TailscaleIPs: []netip.Addr{netip.MustParseAddr("100.64.0.1")},
	}
	st.Peer[key.NewNode().Public()] = &ipnstate.PeerStatus{
		ID: "ip-fallback", InNetworkMap: true, TailscaleIPs: []netip.Addr{netip.MustParseAddr("100.64.0.9")},
	}
	want := []string{"ip-fallback", "dns-order", "ip-order", "a", "b", "c", "d"}
	for range 100 {
		got, err := peersOf(st, time.Now())
		if err != nil {
			t.Fatal(err)
		}
		ids := make([]string, len(got))
		for i, p := range got {
			ids[i] = p.ID
		}
		if !reflect.DeepEqual(ids, want) {
			t.Fatalf("map iteration changed order: %v; want %v", ids, want)
		}
	}
}

func TestPeersSSHIsOnlyAnAdvertisement(t *testing.T) {
	for _, tc := range []struct {
		name string
		keys []string
		want bool
	}{
		{"unknown", nil, false}, {"empty", []string{}, false},
		{"advertised", []string{"ssh-ed25519 host-key"}, true}, {"unparsed", []string{""}, true},
	} {
		t.Run(tc.name, func(t *testing.T) {
			st := &ipnstate.Status{BackendState: "Running", Peer: map[key.NodePublic]*ipnstate.PeerStatus{
				key.NewNode().Public(): {ID: "p", InNetworkMap: true, SSH_HostKeys: tc.keys,
					TailscaleIPs: []netip.Addr{netip.MustParseAddr("100.64.0.2")}},
			}}
			peers, err := peersOf(st, time.Now())
			if err != nil || len(peers) != 1 || peers[0].TailscaleSSH != tc.want || peers[0].Online {
				t.Fatalf("SSH advertisement or offline state changed: %+v, %v", peers, err)
			}
		})
	}
}

func TestPeersReadiness(t *testing.T) {
	now := time.Now()
	past, future := now.Add(-time.Hour), now.Add(time.Hour)
	for _, tc := range []struct {
		name  string
		state string
		self  *ipnstate.PeerStatus
		key   bool
		want  codes.Code
	}{
		{name: "running", state: "Running"},
		{name: "future-expiry", state: "Running", self: &ipnstate.PeerStatus{KeyExpiry: &future}},
		{name: "new-login", state: "NeedsLogin", want: codes.NeedsLogin},
		{name: "login-again", state: "NeedsLogin", key: true, want: codes.LoginExpired},
		{name: "approval", state: "NeedsMachineAuth", want: codes.NeedsMachineAuth},
		{name: "starting", state: "Starting", want: codes.NotReady},
		{name: "stopped", state: "Stopped", want: codes.NotReady},
		{name: "expired-flag", state: "Running", self: &ipnstate.PeerStatus{Expired: true}, want: codes.LoginExpired},
		{name: "expired-time", state: "Running", self: &ipnstate.PeerStatus{KeyExpiry: &past}, want: codes.LoginExpired},
	} {
		t.Run(tc.name, func(t *testing.T) {
			st := &ipnstate.Status{BackendState: tc.state, Self: tc.self, HaveNodeKey: tc.key,
				Peer: map[key.NodePublic]*ipnstate.PeerStatus{
					key.NewNode().Public(): {ID: "stale", InNetworkMap: true,
						TailscaleIPs: []netip.Addr{netip.MustParseAddr("100.64.0.2")}},
				},
			}
			peers, err := peersOf(st, now)
			if tc.want == "" {
				if err != nil || len(peers) != 1 {
					t.Fatalf("ready node: %+v, %v", peers, err)
				}
			} else if err == nil || codes.Of(err, codes.Internal).Code != tc.want || peers != nil {
				t.Fatalf("unready node returned peers or wrong error: %+v, %v; want %s", peers, err, tc.want)
			}
		})
	}
	if peers, err := peersOf(nil, now); peers != nil || err == nil || codes.Of(err, codes.Internal).Code != codes.NotReady {
		t.Fatalf("missing status: %+v, %v", peers, err)
	}
	for _, st := range []*ipnstate.Status{
		{BackendState: "Running"},
		{BackendState: "Running", Peer: map[key.NodePublic]*ipnstate.PeerStatus{
			key.NewNode().Public(): {ID: "hidden", ShareeNode: true, InNetworkMap: true},
		}},
	} {
		peers, err := peersOf(st, now)
		if err != nil || peers == nil || len(peers) != 0 {
			t.Fatalf("empty netmap must return non-nil empty slice: %+v, %v", peers, err)
		}
	}
}

func TestPeersJSONExposesOnlyPeerSummary(t *testing.T) {
	publicKey := key.NewNode().Public()
	st := &ipnstate.Status{
		BackendState: "Running", AuthURL: "https://control.example/a/private-login-token",
		User: map[tailcfg.UserID]tailcfg.UserProfile{
			42: {LoginName: "private-login-name", DisplayName: "private-display-name"},
		},
		Peer: map[key.NodePublic]*ipnstate.PeerStatus{
			publicKey: {ID: "stable-id", InNetworkMap: true, PublicKey: publicKey,
				UserID: 42, SSH_HostKeys: []string{"ssh-ed25519 private-host-key"},
				Addrs: []string{"192.168.1.99:12345"}, PeerAPIURL: []string{"http://private-peer-api"},
				CapMap:       tailcfg.NodeCapMap{"private-admin-token": []tailcfg.RawMessage{`"tskey-api-private-token"`}},
				TailscaleIPs: []netip.Addr{netip.MustParseAddr("100.64.0.2")}},
		},
	}
	peers, err := peersOf(st, time.Now())
	if err != nil {
		t.Fatal(err)
	}
	data, err := json.Marshal(peers)
	if err != nil {
		t.Fatal(err)
	}
	for _, secret := range []string{"private-", "tskey-", publicKey.String(), "192.168.1.99", "UserID", "sshHostKeys"} {
		if strings.Contains(string(data), secret) {
			t.Fatalf("JSON leaked %q: %s", secret, data)
		}
	}
	var fields []map[string]any
	if err := json.Unmarshal(data, &fields); err != nil || len(fields) != 1 || len(fields[0]) != 7 {
		t.Fatalf("unexpected peer fields: %s, %v", data, err)
	}
	for _, name := range []string{"id", "hostname", "dnsName", "ips", "os", "online", "tailscaleSsh"} {
		if _, ok := fields[0][name]; !ok {
			t.Errorf("missing field %s: %s", name, data)
		}
	}
	if fields[0]["id"] != "stable-id" || fields[0]["tailscaleSsh"] != true {
		t.Fatalf("stable ID or SSH hint lost: %s", data)
	}
}
