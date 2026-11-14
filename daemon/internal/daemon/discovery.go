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
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"net"
	"strconv"
	"time"

	"pambio/internal/config"
	"pambio/internal/protocol"
)

// startDiscovery binds the UDP discovery socket and begins answering
// discover probes from phones. Returns nil when disabled via env.
func (d *Daemon) startDiscovery() error {
	port := config.DiscoveryPortFromEnv()
	if port == 0 {
		log.Println("UDP discovery disabled via PAMBIO_DISCOVERY_PORT=0")
		return nil
	}

	addr := &net.UDPAddr{IP: net.IPv4zero, Port: port}
	conn, err := net.ListenUDP("udp", addr)
	if err != nil {
		return fmt.Errorf("udp listen :%d: %w", port, err)
	}
	d.discoveryMu.Lock()
	d.discoveryConn = conn
	d.discoveryPort = conn.LocalAddr().(*net.UDPAddr).Port
	boundPort := d.discoveryPort
	d.discoveryMu.Unlock()

	log.Printf("UDP discovery listening on port %d", boundPort)

	go d.acceptDiscovery(conn)
	return nil
}

func (d *Daemon) stopDiscovery() {
	d.discoveryMu.Lock()
	conn := d.discoveryConn
	d.discoveryConn = nil
	d.discoveryMu.Unlock()

	if conn != nil {
		_ = conn.Close()
	}
}

func (d *Daemon) acceptDiscovery(conn *net.UDPConn) {
	buf := make([]byte, protocol.MaxDiscoveryDatagram)
	for {
		n, remote, err := conn.ReadFromUDP(buf)
		if err != nil {
			if errors.Is(err, net.ErrClosed) {
				return
			}
			select {
			case <-d.ctx.Done():
				return
			default:
				log.Printf("udp discovery read: %v", err)
				continue
			}
		}

		var msg protocol.DiscoveryMessage
		if err := json.Unmarshal(buf[:n], &msg); err != nil {
			continue
		}
		if msg.Type != protocol.TypeDiscover {
			continue
		}

		payload, err := d.marshalAnnounce()
		if err != nil {
			continue
		}
		if _, err := conn.WriteToUDP(payload, remote); err != nil && !errors.Is(err, net.ErrClosed) {
			log.Printf("udp announce reply to %s: %v", remote, err)
		}
	}
}

func (d *Daemon) marshalAnnounce() ([]byte, error) {
	return json.Marshal(protocol.DiscoveryMessage{
		Type:        protocol.TypeAnnounce,
		Version:     protocol.DiscoveryVersion,
		ServiceName: d.cfg.ServiceName,
		Port:        d.tcpPort,
		PublicKey:   d.cfg.PublicKey,
	})
}

func (d *Daemon) initialKnock() {
	d.knockPairedDevices()
	timer := time.NewTimer(500 * time.Millisecond)
	defer timer.Stop()
	select {
	case <-timer.C:
		d.knockPairedDevices()
	case <-d.ctx.Done():
	}
}

// knockPairedDevices sends unsolicited UDP announce packets to every paired
// phone's recently observed IP addresses and to LAN subnet broadcasts so
// phones reconnect immediately after a PC/daemon restart or auth wakeup.
func (d *Daemon) knockPairedDevices() {
	if d.ctx.Err() != nil || d.tcpPort == 0 {
		return
	}

	paired := d.devices.GetAll()
	if len(paired) == 0 && !d.isPairingActive() {
		return
	}

	payload, err := d.marshalAnnounce()
	if err != nil {
		return
	}

	knockPort := d.knockPort
	if knockPort <= 0 {
		knockPort = config.DefaultDiscoveryPort
	}

	seen := make(map[string]struct{})
	var targets []*net.UDPAddr
	addTarget := func(ip net.IP) {
		if ip == nil {
			return
		}
		key := net.JoinHostPort(ip.String(), strconv.Itoa(knockPort))
		if _, ok := seen[key]; ok {
			return
		}
		seen[key] = struct{}{}
		targets = append(targets, &net.UDPAddr{IP: ip, Port: knockPort})
	}

	for _, dev := range paired {
		for _, ipStr := range dev.LastAddrs {
			addTarget(net.ParseIP(ipStr))
		}
	}
	for _, bcast := range getBroadcastAddrs() {
		addTarget(bcast)
	}

	if len(targets) == 0 {
		return
	}

	d.discoveryMu.Lock()
	conn := d.discoveryConn
	d.discoveryMu.Unlock()
	var tempConn *net.UDPConn
	if conn == nil {
		c, err := net.ListenUDP("udp4", nil)
		if err != nil {
			return
		}
		tempConn = c
		conn = c
	}
	if tempConn != nil {
		defer func() { _ = tempConn.Close() }()
	}

	for _, target := range targets {
		if _, err := conn.WriteToUDP(payload, target); err != nil {
			log.Printf("udp knock to %s: %v", target, err)
		}
	}
}

// getBroadcastAddrs returns IPv4 directed broadcast addresses for all active
// physical/LAN interfaces plus 255.255.255.255.
func getBroadcastAddrs() []net.IP {
	var addrs []net.IP
	for _, iface := range getValidInterfaces() {
		ifAddrs, err := iface.Addrs()
		if err != nil {
			continue
		}
		for _, a := range ifAddrs {
			ipnet, ok := a.(*net.IPNet)
			if !ok {
				continue
			}
			ip4 := ipnet.IP.To4()
			if ip4 == nil || len(ipnet.Mask) != net.IPv4len {
				continue
			}
			bcast := make(net.IP, net.IPv4len)
			for i := 0; i < net.IPv4len; i++ {
				bcast[i] = ip4[i] | ^ipnet.Mask[i]
			}
			addrs = append(addrs, bcast)
		}
	}
	addrs = append(addrs, net.IPv4bcast)
	return addrs
}
