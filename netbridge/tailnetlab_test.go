//go:build tailnetlab

// A manual integration test against a real control server. It registers one
// node and removes it again; run it only in a lab:
//
//	MOKE_LAB_CONTROL=https://… MOKE_LAB_REGISTER='ssh host headscale nodes register --user u --key %s -o json' \
//	MOKE_LAB_DELETE='ssh host headscale nodes delete --force -i %d' MOKE_LAB_TARGET=peer:22 \
//	go test -tags tailnetlab -run TestRealTailnet -v .
package netbridge

import (
	"bufio"
	"encoding/json"
	"fmt"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/briqt/moke/netbridge/internal/codes"
	"tailscale.com/envknob"
	"tailscale.com/logtail"
	"tailscale.com/net/netmon"
)

// hostSnapshot describes this host's interfaces in the app's snapshot format.
func hostSnapshot(t *testing.T) string {
	ifaces, err := net.Interfaces()
	if err != nil {
		t.Fatal(err)
	}
	type addr struct {
		IP     string `json:"ip"`
		Prefix int    `json:"prefix"`
	}
	type iface struct {
		Index        int    `json:"index"`
		Name         string `json:"name"`
		MTU          int    `json:"mtu"`
		Up           bool   `json:"up"`
		Loopback     bool   `json:"loopback"`
		PointToPoint bool   `json:"pointToPoint"`
		Multicast    bool   `json:"multicast"`
		Addresses    []addr `json:"addresses"`
	}
	out := struct {
		Interfaces []iface `json:"interfaces"`
	}{}
	for _, i := range ifaces {
		addrs, _ := i.Addrs()
		v := iface{Index: i.Index, Name: i.Name, MTU: i.MTU, Up: i.Flags&net.FlagUp != 0, Loopback: i.Flags&net.FlagLoopback != 0,
			PointToPoint: i.Flags&net.FlagPointToPoint != 0, Multicast: i.Flags&net.FlagMulticast != 0, Addresses: []addr{}}
		for _, a := range addrs {
			if n, ok := a.(*net.IPNet); ok && n.IP.To16() != nil && !n.IP.IsLinkLocalUnicast() {
				ones, _ := n.Mask.Size()
				ip := n.IP
				if ip4 := ip.To4(); ip4 != nil {
					ip = ip4
				}
				v.Addresses = append(v.Addresses, addr{IP: ip.String(), Prefix: ones})
			}
		}
		out.Interfaces = append(out.Interfaces, v)
	}
	data, _ := json.Marshal(out)
	return string(data)
}

func TestRealTailnet(t *testing.T) {
	control, target := os.Getenv("MOKE_LAB_CONTROL"), os.Getenv("MOKE_LAB_TARGET")
	register, remove := os.Getenv("MOKE_LAB_REGISTER"), os.Getenv("MOKE_LAB_DELETE")
	if control == "" || target == "" || register == "" || remove == "" {
		t.Skip("lab environment not configured")
	}
	b := newBridge()
	b.setup = func(getter func() ([]netmon.Interface, error)) {
		envknob.SetNoLogsNoSupport()
		logtail.Disable()
		netmon.RegisterInterfaceGetter(getter)
	}
	store := &memStore{}
	b.store = store
	dir := t.TempDir()
	if err := b.setLogsDir(filepath.Join(dir, "logs")); err != nil {
		t.Fatal(err)
	}
	if err := b.initialize(hostSnapshot(t)); err != nil {
		t.Fatal(err)
	}
	nodeDir := filepath.Join(dir, "node")
	host := fmt.Sprintf("moke-lab-%d", time.Now().Unix()%100000)
	if err := b.startNode(fmt.Sprintf(`{"id":"lab","dir":%q,"controlURL":%q,"hostname":%q}`, nodeDir, control, host)); err != nil {
		t.Fatal(err)
	}
	defer b.stop("lab")

	url, err := b.loginURL("lab")
	if err != nil || url == "" {
		t.Fatalf("login URL: %v", err)
	}
	key := url[strings.LastIndex(url, "/")+1:]
	out, err := exec.Command("sh", "-c", fmt.Sprintf(register, key)).Output()
	if err != nil {
		t.Fatalf("register: %v", err)
	}
	var node struct {
		ID uint64 `json:"id"`
	}
	if json.Unmarshal(out, &node) != nil || node.ID == 0 {
		t.Fatalf("register output not understood")
	}
	t.Logf("registered node %d (%s)", node.ID, host)
	defer func() {
		if err := exec.Command("sh", "-c", fmt.Sprintf(remove, node.ID)).Run(); err != nil {
			t.Errorf("CLEANUP NEEDED: delete node %d: %v", node.ID, err)
		} else {
			t.Logf("deleted node %d", node.ID)
		}
	}()

	deadline := time.Now().Add(60 * time.Second)
	for {
		s, err := b.nodeStatus("lab")
		if err == nil && s.State == "Running" && len(s.IPs) > 0 {
			t.Logf("running: tailnet=%q dns=%q ips=%v", s.Tailnet, s.DNSName, s.IPs)
			break
		}
		if time.Now().After(deadline) {
			t.Fatalf("node never ran: %+v %v", s, err)
		}
		time.Sleep(500 * time.Millisecond)
	}

	// 1. A tailnet target by MagicDNS name: expect an SSH banner.
	var banner string
	for attempt := 0; attempt < 10 && banner == ""; attempt++ {
		info, _ := b.openDial("lab")
		conn, err := dialVia(t, info, target)
		if err != nil {
			t.Logf("attempt %d: %v %v", attempt, err, b.dialErrorOf(info.Username))
			time.Sleep(2 * time.Second)
			continue
		}
		conn.SetReadDeadline(time.Now().Add(10 * time.Second))
		banner, _ = bufio.NewReader(conn).ReadString('\n')
		conn.Close()
	}
	if !strings.HasPrefix(banner, "SSH-2.0") {
		t.Fatalf("no SSH banner via tailnet: %q", banner)
	}
	t.Logf("banner via tailnet: %s", strings.TrimSpace(banner))

	// 2. Off-tailnet targets are refused, never dialled directly or resolved by system DNS.
	for want, off := range map[codes.Code]string{codes.NotInTailnet: "192.0.2.10:80", codes.NameNotFound: "github.com:443"} {
		info, _ := b.openDial("lab")
		if conn, err := dialVia(t, info, off); err == nil {
			conn.Close()
			t.Fatalf("%s reached outside the tailnet", off)
		}
		if e := b.dialErrorOf(info.Username); e == nil || e.Code != want {
			t.Fatalf("%s: got %+v, want %s", off, e, want)
		}
	}

	// 3. State lives in the app's store; no plaintext state file in the node dir.
	store.mu.Lock()
	keys := len(store.m)
	store.mu.Unlock()
	if keys == 0 {
		t.Fatal("nothing written to the app's state store")
	}
	if _, err := os.Stat(filepath.Join(nodeDir, "tailscaled.state")); err == nil {
		t.Fatal("plaintext tailscaled.state written")
	}
	entries, _ := os.ReadDir(nodeDir)
	var names []string
	for _, e := range entries {
		names = append(names, e.Name())
	}
	t.Logf("store keys=%d, node dir=%v", keys, names)

	// 4. Stop waits for leases; logout then stop succeeds.
	if err := b.logout("lab"); err != nil {
		t.Fatalf("logout: %v", err)
	}
	waitUntil(t, func() bool { return b.leaseCount("lab") == 0 })
	if err := b.stop("lab"); err != nil {
		t.Fatalf("stop: %v", err)
	}
}
