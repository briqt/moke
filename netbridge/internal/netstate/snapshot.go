package netstate

import (
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"net/netip"
	"strings"
	"sync"
)

type Interface struct {
	Index int
	Name  string
	MTU   int
	Flags net.Flags
	Addrs []net.Addr
}

type addressJSON struct {
	IP     string `json:"ip"`
	Prefix int    `json:"prefix"`
}

type interfaceJSON struct {
	Index        int           `json:"index"`
	Name         string        `json:"name"`
	MTU          int           `json:"mtu"`
	Up           bool          `json:"up"`
	Loopback     bool          `json:"loopback"`
	PointToPoint bool          `json:"pointToPoint"`
	Multicast    bool          `json:"multicast"`
	Addresses    []addressJSON `json:"addresses"`
}

type Store struct {
	mu               sync.RWMutex
	interfaces       []Interface
	defaultInterface string
}

func Parse(data string) ([]Interface, error) {
	values, _, err := ParseWithDefault(data)
	return values, err
}

// ParseWithDefault also returns the optional Android default-network interface name
// (empty when absent). It must name one of the listed interfaces.
func ParseWithDefault(data string) ([]Interface, string, error) {
	values, err := parseInterfaces(data)
	if err != nil {
		return nil, "", err
	}
	var extra struct {
		DefaultInterface string `json:"defaultInterface"`
	}
	_ = json.Unmarshal([]byte(data), &extra) // Already validated as JSON by parseInterfaces.
	if extra.DefaultInterface != "" {
		found := false
		for _, value := range values {
			found = found || value.Name == extra.DefaultInterface
		}
		if !found {
			return nil, "", errors.New("defaultInterface is not a listed interface")
		}
	}
	return values, extra.DefaultInterface, nil
}

func parseInterfaces(data string) ([]Interface, error) {
	if len(data) > 1024*1024 {
		return nil, errors.New("interface snapshot exceeds size limit")
	}
	var snapshot struct {
		Interfaces []interfaceJSON `json:"interfaces"`
	}
	if err := json.Unmarshal([]byte(data), &snapshot); err != nil {
		return nil, errors.New("invalid interface snapshot JSON")
	}
	if snapshot.Interfaces == nil || len(snapshot.Interfaces) > 128 {
		return nil, errors.New("missing or excessive interface list")
	}
	result := make([]Interface, 0, len(snapshot.Interfaces))
	indices := make(map[int]bool)
	names := make(map[string]bool)
	for _, raw := range snapshot.Interfaces {
		if raw.Index <= 0 || raw.Name == "" || len(raw.Name) > 128 || strings.ContainsAny(raw.Name, "\x00\n\r") || raw.MTU < 0 {
			return nil, errors.New("invalid interface metadata")
		}
		if indices[raw.Index] || names[raw.Name] {
			return nil, errors.New("duplicate interface")
		}
		indices[raw.Index], names[raw.Name] = true, true
		if len(raw.Addresses) > 128 {
			return nil, errors.New("excessive interface address count")
		}
		value := Interface{Index: raw.Index, Name: raw.Name, MTU: raw.MTU, Addrs: make([]net.Addr, 0, len(raw.Addresses))}
		if raw.Up {
			value.Flags |= net.FlagUp
		}
		if raw.Loopback {
			value.Flags |= net.FlagLoopback
		}
		if raw.PointToPoint {
			value.Flags |= net.FlagPointToPoint
		}
		if raw.Multicast {
			value.Flags |= net.FlagMulticast
		}
		for _, item := range raw.Addresses {
			ip, err := netip.ParseAddr(item.IP)
			if err != nil || ip.Zone() != "" || item.Prefix < 0 || item.Prefix > ip.BitLen() {
				return nil, fmt.Errorf("invalid address on interface index %d", raw.Index)
			}
			value.Addrs = append(value.Addrs, &net.IPNet{IP: append(net.IP(nil), ip.AsSlice()...), Mask: net.CIDRMask(item.Prefix, ip.BitLen())})
		}
		result = append(result, value)
	}
	return result, nil
}

func clone(values []Interface) []Interface {
	result := make([]Interface, len(values))
	for index, value := range values {
		result[index] = value
		result[index].Addrs = make([]net.Addr, 0, len(value.Addrs))
		for _, address := range value.Addrs {
			network := address.(*net.IPNet)
			result[index].Addrs = append(result[index].Addrs, &net.IPNet{
				IP: append(net.IP(nil), network.IP...), Mask: append(net.IPMask(nil), network.Mask...),
			})
		}
	}
	return result
}

func (s *Store) Update(data string) error {
	values, defaultInterface, err := ParseWithDefault(data)
	if err != nil {
		return err
	}
	s.mu.Lock()
	s.interfaces = values
	s.defaultInterface = defaultInterface
	s.mu.Unlock()
	return nil
}

func (s *Store) DefaultInterface() string {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.defaultInterface
}

func (s *Store) Snapshot() []Interface {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return clone(s.interfaces)
}
