package netstate

import (
	"net"
	"sync"
	"testing"
)

const validSnapshot = `{"interfaces":[{"index":1,"name":"lo","mtu":65536,"up":true,"loopback":true,"addresses":[{"ip":"127.0.0.1","prefix":8},{"ip":"::1","prefix":128}]},{"index":2,"name":"wlan0","mtu":1500,"up":true,"multicast":true,"addresses":[]}]}`

func TestParseAndNonNilEmptyAddresses(t *testing.T) {
	values, err := Parse(validSnapshot)
	if err != nil {
		t.Fatal(err)
	}
	if len(values) != 2 || values[0].Flags&net.FlagLoopback == 0 || values[1].Addrs == nil {
		t.Fatalf("invalid snapshot result: %#v", values)
	}
	var store Store
	if err := store.Update(validSnapshot); err != nil {
		t.Fatal(err)
	}
	copy := store.Snapshot()
	copy[0].Addrs[0].(*net.IPNet).IP[0] = 99
	if store.Snapshot()[0].Addrs[0].(*net.IPNet).IP.String() != "127.0.0.1" {
		t.Fatal("snapshot exposes mutable state")
	}
	if store.Snapshot()[1].Addrs == nil {
		t.Fatal("empty address override became nil")
	}
}

func TestInvalidSnapshotKeepsPreviousState(t *testing.T) {
	for _, data := range []string{`{}`, `{"interfaces":null}`, `{"interfaces":[{"index":0,"name":"x"}]}`, `{"interfaces":[{"index":1,"name":"x","addresses":[{"ip":"127.0.0.1","prefix":33}]}]}`, `{"interfaces":[{"index":1,"name":"x"},{"index":1,"name":"y"}]}`} {
		var store Store
		if err := store.Update(validSnapshot); err != nil {
			t.Fatal(err)
		}
		if err := store.Update(data); err == nil {
			t.Errorf("accepted invalid snapshot: %s", data)
		}
		if len(store.Snapshot()) != 2 {
			t.Fatal("invalid update destroyed previous snapshot")
		}
	}
}

func TestConcurrentSnapshotReplacement(t *testing.T) {
	var store Store
	if err := store.Update(validSnapshot); err != nil {
		t.Fatal(err)
	}
	var group sync.WaitGroup
	for range 8 {
		group.Add(1)
		go func() {
			defer group.Done()
			for range 100 {
				if err := store.Update(validSnapshot); err != nil {
					t.Error(err)
				}
				if len(store.Snapshot()) != 2 {
					t.Error("lost interfaces")
				}
			}
		}()
	}
	group.Wait()
}

func TestDefaultInterfaceMustBeListed(t *testing.T) {
	var s Store
	ok := `{"defaultInterface":"wlan0","interfaces":[{"index":3,"name":"wlan0","mtu":1500,"up":true,"addresses":[]}]}`
	if err := s.Update(ok); err != nil || s.DefaultInterface() != "wlan0" {
		t.Fatalf("default not kept: %v %q", err, s.DefaultInterface())
	}
	if err := s.Update(`{"defaultInterface":"rmnet0","interfaces":[{"index":3,"name":"wlan0","mtu":1500,"up":true,"addresses":[]}]}`); err == nil {
		t.Fatal("accepted an unlisted default interface")
	}
	if s.DefaultInterface() != "wlan0" {
		t.Fatal("rejected update replaced state")
	}
	if err := s.Update(`{"interfaces":[]}`); err != nil || s.DefaultInterface() != "" {
		t.Fatalf("absent default not cleared: %v %q", err, s.DefaultInterface())
	}
}
