package tailnet

import (
	"context"
	"errors"
	"net/netip"
	"testing"
	"time"

	"tailscale.com/ipn/ipnstate"
	"tailscale.com/types/key"
)

func TestReadPeersWaitsForInitialPeerUpdate(t *testing.T) {
	calls := 0
	read := func(context.Context) (*ipnstate.Status, error) {
		calls++
		st := &ipnstate.Status{BackendState: "Running"}
		if calls == 2 {
			st.Peer = map[key.NodePublic]*ipnstate.PeerStatus{key.NewNode().Public(): {
				ID: "visible", InNetworkMap: true, TailscaleIPs: []netip.Addr{netip.MustParseAddr("100.64.0.2")},
			}}
		}
		return st, nil
	}
	peers, err := readPeers(context.Background(), read, time.Second)
	if err != nil || len(peers) != 1 || calls != 2 {
		t.Fatalf("initial update: peers=%v calls=%d err=%v", peers, calls, err)
	}
}

func TestReadPeersKeepsRealEmptyResultsBounded(t *testing.T) {
	started := time.Now()
	peers, err := readPeers(context.Background(), func(context.Context) (*ipnstate.Status, error) {
		return &ipnstate.Status{BackendState: "Running"}, nil
	}, 20*time.Millisecond)
	if err != nil || peers == nil || len(peers) != 0 || time.Since(started) > time.Second {
		t.Fatalf("empty discovery was not bounded: %v %v", peers, err)
	}
}

func TestReadPeersDoesNotWaitThroughLoginOrReadFailure(t *testing.T) {
	for _, failure := range []bool{false, true} {
		calls := 0
		_, err := readPeers(context.Background(), func(context.Context) (*ipnstate.Status, error) {
			calls++
			if failure {
				return nil, errors.New("controlled status failure")
			}
			return &ipnstate.Status{BackendState: "NeedsLogin"}, nil
		}, time.Second)
		if err == nil || calls != 1 {
			t.Fatalf("failure=%v calls=%d err=%v", failure, calls, err)
		}
	}
}

func TestReadPeersCancellationInterruptsEmptyGrace(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	calls := 0
	_, err := readPeers(ctx, func(context.Context) (*ipnstate.Status, error) {
		calls++
		cancel()
		return &ipnstate.Status{BackendState: "Running"}, nil
	}, time.Minute)
	if !errors.Is(err, context.Canceled) || calls != 1 {
		t.Fatalf("cancelled discovery: calls=%d err=%v", calls, err)
	}
}
