package daemon

import (
	"log"
	"net"
	"strings"

	"github.com/grandcat/zeroconf"
)

var mdnsServer *zeroconf.Server

// getValidInterfaces returns a list of physical/LAN interfaces, excluding
// loopback, docker, and virtual interfaces.
func getValidInterfaces() []net.Interface {
	var valid []net.Interface
	ifaces, err := net.Interfaces()
	if err != nil {
		return nil
	}

	for _, iface := range ifaces {
		// Skip loopback or down interfaces
		if iface.Flags&net.FlagLoopback != 0 || iface.Flags&net.FlagUp == 0 {
			continue
		}
		// Skip point-to-point (e.g. VPN)
		if iface.Flags&net.FlagPointToPoint != 0 {
			continue
		}
		// Skip Docker, veth, tailscale, etc.
		name := strings.ToLower(iface.Name)
		if strings.HasPrefix(name, "docker") || strings.HasPrefix(name, "veth") ||
			strings.HasPrefix(name, "br-") || strings.HasPrefix(name, "tailscale") ||
			strings.HasPrefix(name, "tun") || strings.HasPrefix(name, "virbr") {
			continue
		}
		valid = append(valid, iface)
	}
	return valid
}

// startMDNS publishes the _pambio._tcp service via mDNS/DNS-SD.
func (d *Daemon) startMDNS() error {
	var err error
	mdnsServer, err = zeroconf.Register(
		d.cfg.ServiceName, // instance name, e.g. "pambio_mypc"
		"_pambio._tcp",    // service type
		"local.",          // domain
		d.tcpPort,         // port
		[]string{
			"v=1",                           // protocol version
			"pk=" + d.cfg.PublicKey[:16],     // first 16 chars of pub key for quick visual check
		},
		getValidInterfaces(), // Only publish on valid LAN interfaces
	)
	if err != nil {
		return err
	}
	return nil
}

// stopMDNS unregisters the mDNS service.
func (d *Daemon) stopMDNS() {
	if mdnsServer != nil {
		mdnsServer.Shutdown()
		log.Println("mDNS service unregistered")
	}
}
