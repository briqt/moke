package tailnet

import (
	"errors"
	"net/netip"
	"testing"
	"time"

	"github.com/briqt/moke/netbridge/internal/codes"
	"tailscale.com/ipn"
	"tailscale.com/ipn/ipnstate"
	"tailscale.com/types/key"
)

func status() *ipnstate.Status {
	return &ipnstate.Status{
		BackendState:   "Running",
		CurrentTailnet: &ipnstate.TailnetStatus{Name: "example.com", MagicDNSSuffix: "tail1234.ts.net"},
		Self:           &ipnstate.PeerStatus{DNSName: "phone.tail1234.ts.net.", TailscaleIPs: []netip.Addr{netip.MustParseAddr("100.64.0.1")}},
		Peer: map[key.NodePublic]*ipnstate.PeerStatus{
			key.NewNode().Public(): {DNSName: "Build-Box.tail1234.ts.net.", Online: true,
				TailscaleIPs: []netip.Addr{netip.MustParseAddr("fd7a:115c:a1e0::2"), netip.MustParseAddr("100.64.0.2")}},
			key.NewNode().Public(): {DNSName: "v6only.tail1234.ts.net.", TailscaleIPs: []netip.Addr{netip.MustParseAddr("fd7a:115c:a1e0::3")}},
			key.NewNode().Public(): {DNSName: "shared.other.ts.net.", TailscaleIPs: []netip.Addr{netip.MustParseAddr("100.64.0.4")}},
		},
	}
}

func TestLookupNameMatchesBackendKeys(t *testing.T) {
	st := status()
	cases := map[string]string{
		"build-box":                  "100.64.0.2", // short name, case-insensitive
		"BUILD-BOX.tail1234.ts.net":  "100.64.0.2",
		"build-box.tail1234.ts.net.": "100.64.0.2",
		"phone":                      "100.64.0.1", // self
		"v6only":                     "fd7a:115c:a1e0::3",
		"shared.other.ts.net":        "100.64.0.4", // FQDN outside the suffix
	}
	for host, want := range cases {
		ip, ok := lookupName(st, host, "tcp")
		if !ok || ip.String() != want {
			t.Errorf("%s -> %v %v, want %s", host, ip, ok, want)
		}
	}
	for _, host := range []string{"shared", "example.com", "build", "tail1234.ts.net", "github.com"} {
		if ip, ok := lookupName(st, host, "tcp"); ok {
			t.Errorf("%s must not resolve, got %v", host, ip)
		}
	}
	if ip, _ := lookupName(st, "build-box", "tcp6"); ip.String() != "fd7a:115c:a1e0::2" {
		t.Errorf("tcp6 family: %v", ip)
	}
}

func TestResolveTargetRefusesOutsideTailnet(t *testing.T) {
	st := status()
	routed := func(ip netip.Addr) bool { return netip.MustParsePrefix("100.64.0.0/10").Contains(ip) }
	ap, err := resolveTarget(st, "build-box:22", "tcp", routed)
	if err != nil || ap.String() != "100.64.0.2:22" {
		t.Fatalf("got %v %v", ap, err)
	}
	if _, err := resolveTarget(st, "100.64.9.9:22", "tcp", routed); err != nil {
		t.Fatalf("routed literal refused: %v", err)
	}
	expect := func(target string, code codes.Code) {
		t.Helper()
		_, err := resolveTarget(st, target, "tcp", routed)
		var e *codes.Error
		if !errors.As(err, &e) || e.Code != code {
			t.Fatalf("%s: got %v, want %s", target, err, code)
		}
	}
	expect("192.168.1.10:22", codes.NotInTailnet)
	expect("[2001:db8::1]:22", codes.NotInTailnet)
	expect("github.com:22", codes.NameNotFound)
	expect("build-box", codes.InvalidArgument)
	expect("build-box:0", codes.InvalidArgument)
}

func TestReadiness(t *testing.T) {
	now := time.Now()
	past, future := now.Add(-time.Hour), now.Add(time.Hour)
	check := func(st *ipnstate.Status, want codes.Code) {
		t.Helper()
		err := readiness(st, now)
		if want == "" {
			if err != nil {
				t.Fatalf("unexpected %v", err)
			}
			return
		}
		var e *codes.Error
		if !errors.As(err, &e) || e.Code != want {
			t.Fatalf("got %v, want %s", err, want)
		}
	}
	check(&ipnstate.Status{BackendState: "Running", Self: &ipnstate.PeerStatus{KeyExpiry: &future}}, "")
	check(&ipnstate.Status{BackendState: "Running", Self: &ipnstate.PeerStatus{KeyExpiry: &past}}, codes.LoginExpired)
	check(&ipnstate.Status{BackendState: "NeedsLogin"}, codes.NeedsLogin)
	check(&ipnstate.Status{BackendState: "NeedsLogin", HaveNodeKey: true}, codes.LoginExpired)
	check(&ipnstate.Status{BackendState: "NeedsMachineAuth"}, codes.NeedsMachineAuth)
	check(&ipnstate.Status{BackendState: "Starting"}, codes.NotReady)
}

type mapStore map[string][]byte

func (m mapStore) Read(key string) ([]byte, error) { return m[key], nil }
func (m mapStore) Write(key string, value []byte) error {
	if value == nil {
		delete(m, key)
	} else {
		m[key] = value
	}
	return nil
}

func TestStateStoreAdapter(t *testing.T) {
	backing := mapStore{}
	s := stateStore{backing}
	if _, err := s.ReadState("k"); !errors.Is(err, ipn.ErrStateNotExist) {
		t.Fatalf("missing: %v", err)
	}
	if err := s.WriteState("k", []byte{}); err != nil {
		t.Fatal(err)
	}
	if v, err := s.ReadState("k"); err != nil || v == nil || len(v) != 0 {
		t.Fatalf("empty value must round-trip as empty, got %v %v", v, err)
	}
	s.WriteState("k", []byte("state"))
	if string(backing["k"]) != "\x01state" {
		t.Fatalf("stored %q", backing["k"])
	}
	if v, _ := s.ReadState("k"); string(v) != "state" {
		t.Fatalf("read %q", v)
	}
	s.WriteState("k", nil)
	if _, ok := backing["k"]; ok {
		t.Fatal("nil must delete")
	}
	backing["k"] = []byte{9, 1}
	if _, err := s.ReadState("k"); err == nil {
		t.Fatal("unknown encoding accepted")
	}
}
