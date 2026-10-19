//go:build moshlab

package relay

// Interop with real mosh (go test -tags moshlab ./internal/relay): a local
// mosh-server and mosh-client talk through the relay. Verifies that genuine
// mosh datagrams authenticate under the relay's OCB, that the relay follows
// mosh-client's port hop, and that foreign datagrams are rejected mid-session.
// Needs mosh-server, mosh-client and script(1) on PATH.

import (
	"bytes"
	"net"
	"os"
	"os/exec"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"syscall"
	"testing"
	"time"
)

type lockedBuffer struct {
	mu  sync.Mutex
	buf bytes.Buffer
}

func (b *lockedBuffer) Write(p []byte) (int, error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.buf.Write(p)
}

func (b *lockedBuffer) String() string {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.buf.String()
}

// gatedConn drops everything it reads while paused (a path outage toward the client).
type gatedConn struct {
	net.PacketConn
	paused atomic.Bool
}

func (g *gatedConn) ReadFrom(p []byte) (int, net.Addr, error) {
	for {
		n, addr, err := g.PacketConn.ReadFrom(p)
		if err != nil || !g.paused.Load() {
			return n, addr, err
		}
	}
}

func waitFor(t *testing.T, what string, timeout time.Duration, ok func() bool) {
	t.Helper()
	deadline := time.Now().Add(timeout)
	for !ok() {
		if time.Now().After(deadline) {
			t.Fatalf("timed out waiting for %s", what)
		}
		time.Sleep(100 * time.Millisecond)
	}
}

func TestRealMoshThroughRelay(t *testing.T) {
	server := exec.Command("mosh-server", "new", "-i", "127.0.0.1", "-p", "62000:62100", "--", "sh", "-c", "while :; do date; sleep 1; done")
	server.Env = append(os.Environ(), "MOSH_SERVER_NETWORK_TMOUT=60")
	var stdout, stderr bytes.Buffer
	server.Stdout, server.Stderr = &stdout, &stderr
	if err := server.Run(); err != nil {
		t.Fatalf("mosh-server: %v\n%s", err, stderr.String())
	}
	m := regexp.MustCompile(`MOSH CONNECT (\d+) ([A-Za-z0-9/+]{22})`).FindStringSubmatch(stdout.String())
	pidMatch := regexp.MustCompile(`pid = (\d+)`).FindStringSubmatch(stderr.String())
	if m == nil || pidMatch == nil {
		t.Fatalf("unexpected mosh-server output: %q %q", stdout.String(), stderr.String())
	}
	serverPID, _ := strconv.Atoi(pidMatch[1])
	defer syscall.Kill(serverPID, syscall.SIGKILL) // SIGTERM waits for the client to acknowledge.
	port, _ := strconv.Atoi(m[1])

	relays := NewManager()
	defer relays.CloseAll()
	var gate *gatedConn
	info, err := relays.Open(Config{ID: "lab", NetworkID: "lab", TargetHost: "127.0.0.1"}, Network{ID: "lab", AnySource: true,
		ListenPacket: func(string, string) (net.PacketConn, error) {
			c, err := net.ListenPacket("udp4", "127.0.0.1:0")
			if err != nil {
				return nil, err
			}
			gate = &gatedConn{PacketConn: c}
			return gate, nil
		}})
	if err != nil {
		t.Fatal(err)
	}
	if err := relays.Start("lab", port, m[2]); err != nil {
		t.Fatal(err)
	}

	client := exec.Command("script", "-qfec", "stty rows 24 cols 80; exec mosh-client 127.0.0.1 "+strconv.Itoa(info.LocalPort), "/dev/null")
	client.Env = append(os.Environ(), "MOSH_KEY="+m[2], "TERM=xterm-256color", "LANG=C.UTF-8")
	client.SysProcAttr = &syscall.SysProcAttr{Setpgid: true}
	var screen lockedBuffer
	client.Stdout = &screen
	if err := client.Start(); err != nil {
		t.Fatal(err)
	}
	// script(1) starts mosh-client in a session of its own.
	defer func() {
		_ = exec.Command("pkill", "-KILL", "-f", "^mosh-client 127.0.0.1 "+strconv.Itoa(info.LocalPort)+"$").Run()
		_ = syscall.Kill(-client.Process.Pid, syscall.SIGKILL)
		_ = client.Wait()
	}()

	flow := func() [4]int64 { return relays.Flow() }
	defer func() {
		if t.Failed() {
			t.Logf("flow %v rejected %d rebinds %d dropped %d; client screen: %q", flow(), relays.Rejected(), relays.Rebinds(), relays.Dropped(), screen.String())
		}
	}()
	waitFor(t, "traffic both ways", 10*time.Second, func() bool { f := flow(); return f[0] > 3 && f[3] > 3 })
	if relays.Rejected() != 0 {
		t.Fatalf("genuine mosh datagrams rejected: %d", relays.Rejected())
	}
	t.Logf("authenticated flow %v", flow())

	// A foreign sender mid-session: garbage and a wrong-key datagram are dropped.
	intruder, err := net.Dial("udp4", "127.0.0.1:"+strconv.Itoa(info.LocalPort))
	if err != nil {
		t.Fatal(err)
	}
	intruder.Write(bytes.Repeat([]byte{1}, 64))
	intruder.Write(moshDatagram(t, "AAAAAAAAAAAAAAAAAAAAAA", 1<<40, false, "hijack"))
	intruder.Close()
	waitFor(t, "intruder rejected", 5*time.Second, func() bool { return relays.Rejected() == 2 })
	if relays.Rebinds() != 0 {
		t.Fatal("intruder moved the session")
	}

	// Outage toward the client: after PORT_HOP_INTERVAL (10 s) without a round
	// trip mosh-client sends from a new port; the relay must follow it.
	gate.paused.Store(true)
	waitFor(t, "client port hop", 25*time.Second, func() bool { return relays.Rebinds() >= 1 })
	gate.paused.Store(false)
	before := flow()[3]
	waitFor(t, "replies to the new port", 10*time.Second, func() bool { return flow()[3] > before+3 })
	t.Logf("rebinds %d, rejected %d, flow %v", relays.Rebinds(), relays.Rejected(), flow())
	if relays.Rejected() != 2 {
		t.Fatalf("rejected = %d after the hop; genuine datagrams were dropped", relays.Rejected())
	}
	if output := screen.String(); !strings.Contains(output, "20") {
		t.Fatalf("no server output reached the client screen (%d bytes)", len(output))
	}
}
