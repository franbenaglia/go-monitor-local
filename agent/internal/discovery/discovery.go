// Package discovery announces the agent on the local network with mDNS, so
// the app can list it without the user typing an IP. Discovery is a
// convenience: routers and hotspots may filter multicast, and manual entry
// always remains available.
package discovery

import (
	"context"
	"fmt"
	"strconv"

	"github.com/brutella/dnssd"

	"gomonitor/agent/internal/models"
	"gomonitor/agent/internal/netinfo"
)

// ServiceType is the DNS-SD type clients browse for.
const ServiceType = "_gomonitor._tcp"

// Service describes the announcement, as specified in docs/PROTOCOL.md.
func Service(agentID, hostname string, port int, interfaces []netinfo.Interface) dnssd.Config {
	return dnssd.Config{
		Name: agentID,
		Type: ServiceType,
		Port: port,
		Text: map[string]string{
			"agent_id": agentID,
			"name":     hostname,
			"port":     strconv.Itoa(port),
			"version":  strconv.Itoa(models.ProtocolVersion),
		},
		Ifaces: announceInterfaces(interfaces),
	}
}

// announceInterfaces limits the announcement to the interfaces a phone can
// actually reach, leaving out Docker bridges and VPN tunnels. With none
// recommended it returns nil, which means every interface.
func announceInterfaces(interfaces []netinfo.Interface) []string {
	var names []string
	seen := map[string]bool{}
	for _, i := range interfaces {
		if i.Recommended && !seen[i.Name] {
			seen[i.Name] = true
			names = append(names, i.Name)
		}
	}
	return names
}

// Run announces the service until ctx is cancelled. It returns an error only
// if the announcement could not be started.
func Run(ctx context.Context, cfg dnssd.Config) error {
	service, err := dnssd.NewService(cfg)
	if err != nil {
		return fmt.Errorf("create mDNS service: %w", err)
	}
	responder, err := dnssd.NewResponder()
	if err != nil {
		return fmt.Errorf("open mDNS socket: %w", err)
	}
	if _, err := responder.Add(service); err != nil {
		return fmt.Errorf("register mDNS service: %w", err)
	}
	if err := responder.Respond(ctx); err != nil && ctx.Err() == nil {
		return fmt.Errorf("mDNS responder: %w", err)
	}
	return nil
}
