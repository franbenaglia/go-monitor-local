// Package netinfo finds the local addresses a phone on the same network can
// use to reach the agent.
package netinfo

import (
	"net"
	"sort"
	"strings"
)

// Interface is one IPv4 address of a network interface.
type Interface struct {
	Name        string `json:"name"`
	Address     string `json:"address"`
	Recommended bool   `json:"recommended"`
}

// virtualPrefixes are interface name prefixes of containers, bridges, VPNs
// and hypervisors. A phone on the Wi-Fi cannot reach the agent through them.
var virtualPrefixes = []string{
	"docker", "br-", "veth", "virbr", "vmnet", "vboxnet", "vethernet", "lxc", "lxd", "cni", "flannel", "podman",
	"tun", "tap", "wg", "tailscale", "zt", "utun", "ppp", "ipsec", "nordlynx",
}

// IsVirtualName reports whether an interface name looks like a virtual,
// container or VPN interface.
func IsVirtualName(name string) bool {
	lower := strings.ToLower(name)
	for _, p := range virtualPrefixes {
		if strings.HasPrefix(lower, p) {
			return true
		}
	}
	return false
}

// IsLoopbackName is a fallback for platforms where interface flags are not
// available next to the traffic counters.
func IsLoopbackName(name string) bool {
	lower := strings.ToLower(name)
	return lower == "lo" || lower == "lo0" || strings.Contains(lower, "loopback")
}

// IsRecommended reports whether an address is one the user should type into
// the phone: a private IPv4 address on a physical, non point-to-point interface.
func IsRecommended(name string, flags net.Flags, ip net.IP) bool {
	ip4 := ip.To4()
	if ip4 == nil || !ip4.IsPrivate() {
		return false
	}
	if flags&net.FlagLoopback != 0 || flags&net.FlagPointToPoint != 0 {
		return false
	}
	return !IsVirtualName(name)
}

// List returns every IPv4 address of the interfaces that are up, recommended
// ones first.
func List() ([]Interface, error) {
	ifaces, err := net.Interfaces()
	if err != nil {
		return nil, err
	}
	var out []Interface
	for _, iface := range ifaces {
		if iface.Flags&net.FlagUp == 0 {
			continue
		}
		addrs, err := iface.Addrs()
		if err != nil {
			continue
		}
		for _, addr := range addrs {
			ipNet, ok := addr.(*net.IPNet)
			if !ok {
				continue
			}
			ip4 := ipNet.IP.To4()
			if ip4 == nil {
				continue
			}
			out = append(out, Interface{
				Name:        iface.Name,
				Address:     ip4.String(),
				Recommended: IsRecommended(iface.Name, iface.Flags, ip4),
			})
		}
	}
	sort.SliceStable(out, func(i, j int) bool { return out[i].Recommended && !out[j].Recommended })
	return out, nil
}

// Suggested picks the addresses to show at startup: the recommended ones, or
// if there are none, any non-loopback address, or as a last resort loopback.
func Suggested(all []Interface) []Interface {
	var recommended, others, loopback []Interface
	for _, i := range all {
		switch {
		case i.Recommended:
			recommended = append(recommended, i)
		case net.ParseIP(i.Address).IsLoopback():
			loopback = append(loopback, i)
		default:
			others = append(others, i)
		}
	}
	switch {
	case len(recommended) > 0:
		return recommended
	case len(others) > 0:
		return others
	default:
		return loopback
	}
}

// LoopbackNames returns the names of the loopback interfaces.
func LoopbackNames() map[string]bool {
	names := map[string]bool{}
	ifaces, err := net.Interfaces()
	if err != nil {
		return names
	}
	for _, iface := range ifaces {
		if iface.Flags&net.FlagLoopback != 0 {
			names[iface.Name] = true
		}
	}
	return names
}
