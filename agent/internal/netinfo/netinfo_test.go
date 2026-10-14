package netinfo

import (
	"net"
	"testing"
)

func TestIsRecommended(t *testing.T) {
	up := net.FlagUp | net.FlagBroadcast
	cases := []struct {
		name  string
		iface string
		flags net.Flags
		ip    string
		want  bool
	}{
		{"wifi 192.168", "wlp3s0", up, "192.168.1.50", true},
		{"ethernet 10.x", "eth0", up, "10.0.0.7", true},
		{"172.16 range", "enp2s0", up, "172.20.1.2", true},
		{"windows wifi", "Wi-Fi", up, "192.168.0.24", true},
		{"loopback", "lo", net.FlagUp | net.FlagLoopback, "127.0.0.1", false},
		{"docker bridge", "docker0", up, "172.17.0.1", false},
		{"compose bridge", "br-3f2a1c", up, "172.18.0.1", false},
		{"veth", "veth12ab", up, "172.17.0.2", false},
		{"libvirt", "virbr0", up, "192.168.122.1", false},
		{"vpn tun", "tun0", net.FlagUp | net.FlagPointToPoint, "10.8.0.2", false},
		{"wireguard", "wg0", up, "10.9.0.2", false},
		{"tailscale cgnat", "tailscale0", up, "100.101.102.103", false},
		{"public address", "eth0", up, "8.8.8.8", false},
		{"link local", "eth0", up, "169.254.10.1", false},
		{"172 outside private", "eth0", up, "172.32.0.1", false},
		{"ipv6", "eth0", up, "fd00::1", false},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			if got := IsRecommended(c.iface, c.flags, net.ParseIP(c.ip)); got != c.want {
				t.Fatalf("IsRecommended(%s, %s) = %v, want %v", c.iface, c.ip, got, c.want)
			}
		})
	}
}

func TestSuggested(t *testing.T) {
	wifi := Interface{Name: "wlan0", Address: "192.168.1.50", Recommended: true}
	docker := Interface{Name: "docker0", Address: "172.17.0.1"}
	lo := Interface{Name: "lo", Address: "127.0.0.1"}

	if got := Suggested([]Interface{lo, docker, wifi}); len(got) != 1 || got[0] != wifi {
		t.Fatalf("expected only the wifi address, got %v", got)
	}
	if got := Suggested([]Interface{lo, docker}); len(got) != 1 || got[0] != docker {
		t.Fatalf("expected the docker address as fallback, got %v", got)
	}
	if got := Suggested([]Interface{lo}); len(got) != 1 || got[0] != lo {
		t.Fatalf("expected loopback as last resort, got %v", got)
	}
	if got := Suggested(nil); len(got) != 0 {
		t.Fatalf("expected nothing, got %v", got)
	}
}

func TestListDoesNotFail(t *testing.T) {
	list, err := List()
	if err != nil {
		t.Fatal(err)
	}
	seenOther := false
	for _, i := range list {
		if net.ParseIP(i.Address).To4() == nil {
			t.Errorf("non IPv4 address listed: %v", i)
		}
		if !i.Recommended {
			seenOther = true
		} else if seenOther {
			t.Errorf("recommended addresses must come first: %v", list)
		}
	}
}
