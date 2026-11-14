// Copyright 2026 Nikita Denissov
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.


package daemon

import (
	"log"
	"net"
	"os"
	"sort"
	"strings"
	"sync"
	"time"

	"github.com/grandcat/zeroconf"
)

// mdnsCheckInterval is how often the daemon re-checks the network
// configuration and re-publishes the mDNS record if it changed.
const mdnsCheckInterval = 10 * time.Second

// mdnsPublisher keeps the _pambio._tcp record published and in sync with the
// host's current network interfaces and addresses.
//
// Previously the record was registered exactly once at start-up. When the
// daemon started before the network was fully configured (typical at boot:
// Wi-Fi/DHCP not done yet) the record advertised no or stale addresses
// forever, so phones could not find the PC until the daemon was restarted.
type mdnsPublisher struct {
	mu          sync.Mutex
	server      *zeroconf.Server
	fingerprint string
}

// isVirtualInterface reports whether an interface name looks like a
// container/VPN/virtual bridge that phones cannot reach.
func isVirtualInterface(name string) bool {
	name = strings.ToLower(name)
	for _, p := range []string{"docker", "veth", "br-", "tailscale", "tun", "tap", "virbr", "vmnet", "vboxnet", "zt", "wg", "lxc", "lxd", "podman", "cni", "flannel"} {
		if strings.HasPrefix(name, p) {
			return true
		}
	}
	return false
}

// getValidInterfaces returns physical/LAN interfaces that are up, support
// multicast and are not loopback / point-to-point / virtual.
func getValidInterfaces() []net.Interface {
	var valid []net.Interface
	ifaces, err := net.Interfaces()
	if err != nil {
		return nil
	}
	for _, iface := range ifaces {
		if iface.Flags&net.FlagLoopback != 0 || iface.Flags&net.FlagUp == 0 {
			continue
		}
		if iface.Flags&net.FlagPointToPoint != 0 {
			continue
		}
		if iface.Flags&net.FlagMulticast == 0 {
			continue
		}
		if isVirtualInterface(iface.Name) {
			continue
		}
		valid = append(valid, iface)
	}
	return valid
}

// interfacesWithAddrs filters ifaces to those that currently have at least
// one unicast IP address and returns them together with a stable fingerprint
// of the (interface, address) set. The fingerprint changes whenever an
// address appears/disappears (DHCP lease, Wi-Fi reconnect, cable plugged…).
func interfacesWithAddrs(ifaces []net.Interface) ([]net.Interface, string) {
	var out []net.Interface
	var parts []string
	for _, iface := range ifaces {
		addrs, err := iface.Addrs()
		if err != nil || len(addrs) == 0 {
			continue
		}
		var ips []string
		for _, a := range addrs {
			ipnet, ok := a.(*net.IPNet)
			if !ok || ipnet.IP == nil || ipnet.IP.IsLoopback() {
				continue
			}
			ips = append(ips, ipnet.IP.String())
		}
		if len(ips) == 0 {
			continue
		}
		sort.Strings(ips)
		out = append(out, iface)
		parts = append(parts, iface.Name+"="+strings.Join(ips, ","))
	}
	sort.Strings(parts)
	return out, strings.Join(parts, ";")
}

func mdnsDisabled() bool {
	v := os.Getenv("PAMBIO_DISABLE_MDNS")
	return v == "1" || v == "true"
}

// startMDNS performs the first registration attempt and launches the
// background watcher. It never fails: if the network is not ready yet the
// watcher keeps retrying.
func (d *Daemon) startMDNS() {
	if mdnsDisabled() {
		log.Println("mDNS disabled via PAMBIO_DISABLE_MDNS")
		return
	}
	d.refreshMDNS()
	go func() {
		ticker := time.NewTicker(mdnsCheckInterval)
		defer ticker.Stop()
		for {
			select {
			case <-ticker.C:
				d.refreshMDNS()
			case <-d.netChanged:
				d.refreshMDNS()
			case <-d.ctx.Done():
				return
			}
		}
	}()
}

// refreshMDNS (re-)registers the service if the network configuration
// changed since the last successful registration.
func (d *Daemon) refreshMDNS() {
	ifaces, fp := interfacesWithAddrs(getValidInterfaces())

	d.mdns.mu.Lock()
	defer d.mdns.mu.Unlock()

	if d.ctx.Err() != nil {
		return
	}
	if d.mdns.server != nil && fp == d.mdns.fingerprint {
		return // nothing changed
	}

	if d.mdns.server != nil {
		// Shutdown sends a goodbye packet so phones drop the stale record.
		d.mdns.server.Shutdown()
		d.mdns.server = nil
	}

	if len(ifaces) == 0 {
		if d.mdns.fingerprint != "<none>" {
			log.Println("mDNS: no usable network interface yet, will retry")
		}
		d.mdns.fingerprint = "<none>"
		return
	}

	server, err := zeroconf.Register(
		d.cfg.ServiceName, // instance name, e.g. "mypc"
		"_pambio._tcp",    // service type
		"local.",          // domain
		d.tcpPort,         // port
		[]string{
			"v=1",                       // protocol version
			"pk=" + d.cfg.PublicKey[:16], // first 16 chars of pub key for quick visual check
		},
		ifaces,
	)
	if err != nil {
		log.Printf("mDNS: register failed (will retry): %v", err)
		d.mdns.fingerprint = ""
		return
	}
	d.mdns.server = server
	d.mdns.fingerprint = fp
	log.Printf("mDNS: %s._pambio._tcp published (port %d) on [%s]", d.cfg.ServiceName, d.tcpPort, fp)
	go d.knockPairedDevices()
}

// stopMDNS unregisters the mDNS service.
func (d *Daemon) stopMDNS() {
	d.mdns.mu.Lock()
	defer d.mdns.mu.Unlock()
	if d.mdns.server != nil {
		d.mdns.server.Shutdown()
		d.mdns.server = nil
		log.Println("mDNS service unregistered")
	}
}
