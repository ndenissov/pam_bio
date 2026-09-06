package daemon

import (
	"log"

	"github.com/grandcat/zeroconf"
)

var mdnsServer *zeroconf.Server

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
		nil, // all network interfaces
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
