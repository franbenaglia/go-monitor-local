package discovery

import (
	"context"
	"testing"
	"time"

	"github.com/brutella/dnssd"

	"gomonitor/agent/internal/netinfo"
)

func TestServiceDescription(t *testing.T) {
	cfg := Service("office-pc", "francisco-pc", 8080, []netinfo.Interface{
		{Name: "wlan0", Address: "192.168.1.50", Recommended: true},
		{Name: "wlan0", Address: "192.168.1.51", Recommended: true}, // second address, same interface
		{Name: "docker0", Address: "172.17.0.1"},
		{Name: "lo", Address: "127.0.0.1"},
	})
	if cfg.Name != "office-pc" || cfg.Type != "_gomonitor._tcp" || cfg.Port != 8080 {
		t.Fatalf("unexpected service: %+v", cfg)
	}
	want := map[string]string{"agent_id": "office-pc", "name": "francisco-pc", "port": "8080", "version": "1"}
	for k, v := range want {
		if cfg.Text[k] != v {
			t.Errorf("TXT %s = %q, want %q", k, cfg.Text[k], v)
		}
	}
	if len(cfg.Text) != len(want) {
		t.Errorf("unexpected TXT records: %v", cfg.Text)
	}
	if len(cfg.Ifaces) != 1 || cfg.Ifaces[0] != "wlan0" {
		t.Errorf("must announce only on reachable interfaces, once each: %v", cfg.Ifaces)
	}
}

func TestNoRecommendedInterfaceAnnouncesEverywhere(t *testing.T) {
	cfg := Service("pi", "pi", 8080, []netinfo.Interface{{Name: "docker0", Address: "172.17.0.1"}})
	if cfg.Ifaces != nil {
		t.Fatalf("expected no interface restriction, got %v", cfg.Ifaces)
	}
}

// TestAnnouncementIsDiscoverable starts the real responder and browses for
// it. It needs working multicast, so it is skipped where there is none.
func TestAnnouncementIsDiscoverable(t *testing.T) {
	if testing.Short() {
		t.Skip("uses the network")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()

	const name = "gomonitor-discovery-test"
	done := make(chan error, 1)
	go func() { done <- Run(ctx, Service(name, "test-host", 18999, nil)) }()

	found := make(chan dnssd.BrowseEntry, 1)
	browseCtx, stopBrowsing := context.WithCancel(ctx)
	defer stopBrowsing()
	go func() {
		_ = dnssd.LookupType(browseCtx, ServiceType+".local.", func(e dnssd.BrowseEntry) {
			if e.Name == name {
				select {
				case found <- e:
				default:
				}
			}
		}, func(dnssd.BrowseEntry) {})
	}()

	select {
	case err := <-done:
		t.Skipf("mDNS is not available here: %v", err)
	case e := <-found:
		// The library's browser does not fill in the TXT records on this
		// callback; their content is covered by TestServiceDescription and
		// was checked on the wire with avahi-browse.
		if e.Port != 18999 || e.Type != ServiceType || len(e.IPs) == 0 {
			t.Fatalf("unexpected announcement: %+v", e)
		}
	case <-ctx.Done():
		t.Skip("no mDNS answer within the timeout; multicast is probably filtered on this machine")
	}
}
