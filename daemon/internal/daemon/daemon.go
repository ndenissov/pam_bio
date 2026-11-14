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


// Package daemon implements the core pambiod service: TCP server for phone
// connections, Unix socket / Named Pipe for PAM/CLI requests, mDNS & UDP
// discovery, and orchestration of pairing and auth flows.
package daemon

import (
	"context"
	"crypto/ed25519"
	"fmt"
	"log"
	"net"
	"os"
	"os/signal"
	"sync"
	"syscall"
	"time"

	"pambio/internal/config"
	"pambio/internal/crypto"
	"pambio/internal/protocol"
)

const (

	// PairingTimeout limits how long the daemon waits for a phone to pair.
	PairingTimeout = 5 * time.Minute

	// AuthTimeout limits how long the daemon waits for a biometric response.
	AuthTimeout = 15 * time.Second
)

// AuthResult carries the outcome of an authentication attempt.
type AuthResult struct {
	Success bool
	Reason  string
}

// PairingResult carries the outcome of a pairing attempt.
type PairingResult struct {
	Success    bool
	DeviceName string
	Error      error
}

// PhoneConnection represents an authenticated phone session.
type PhoneConnection struct {
	conn       net.Conn
	sessionKey []byte            // AES-256-GCM key for this session
	deviceKey  ed25519.PublicKey // long-term identity key of the device
	deviceName string
	sessionID  []byte     // SHA-256 of the ECDH shared secret
	mu         sync.Mutex // guards writes to conn
}

type pendingAuthEntry struct {
	ch   chan AuthResult
	req  protocol.AuthRequestMessage
	sent map[*PhoneConnection]struct{}
}

// Daemon is the main pambiod service.
type Daemon struct {
	cfg       *config.ServerConfig
	configDir string
	devices   *config.PairedDevicesStore
	privKey   ed25519.PrivateKey
	pubKey    ed25519.PublicKey

	tcpListener   net.Listener
	ipcListener   net.Listener
	tcpPort       int
	discoveryConn *net.UDPConn
	discoveryMu   sync.Mutex
	discoveryPort int
	knockPort     int // UDP target port on paired phones (default 34907; overridable in tests)

	mdns       mdnsPublisher
	netChanged chan struct{}

	// Connected phones keyed by base64 public key
	phones   map[string]*PhoneConnection
	phonesMu sync.RWMutex

	// Pending auth requests keyed by nonce
	pendingAuth map[string]*pendingAuthEntry
	authMu      sync.Mutex

	// Pairing state
	pairingActive bool
	pairingDone   chan PairingResult
	pairingMu     sync.Mutex

	ctx    context.Context
	cancel context.CancelFunc
}

// New creates a Daemon, loading or generating its identity from configDir.
func New(configDir string) (*Daemon, error) {
	cfg, err := config.LoadServerConfig(configDir)
	if err != nil {
		if !os.IsNotExist(err) {
			return nil, fmt.Errorf("load config: %w", err)
		}
		// First run — generate identity with stable default TCP port
		pub, priv, err := crypto.GenerateEd25519Keypair()
		if err != nil {
			return nil, err
		}
		hostname, _ := os.Hostname()
		cfg = &config.ServerConfig{
			Hostname:      hostname,
			ServiceName:   hostname,
			Port:          config.DefaultPort,
			ShowWatermark: true,
			PrivateKey:    crypto.EncodePrivateKey(priv),
			PublicKey:     crypto.EncodePublicKey(pub),
		}
		if err := config.SaveServerConfig(configDir, cfg); err != nil {
			return nil, fmt.Errorf("save config: %w", err)
		}
		if p, ok := config.PortFromEnv(); ok {
			cfg.Port = p
		}
		log.Printf("Generated new identity for %s", hostname)
	}

	privKey, err := crypto.DecodePrivateKey(cfg.PrivateKey)
	if err != nil {
		return nil, fmt.Errorf("decode private key: %w", err)
	}
	pubKey, err := crypto.DecodePublicKey(cfg.PublicKey)
	if err != nil {
		return nil, fmt.Errorf("decode public key: %w", err)
	}

	devices := config.NewPairedDevicesStore(configDir)
	if err := devices.Load(); err != nil {
		return nil, fmt.Errorf("load paired devices: %w", err)
	}

	ctx, cancel := context.WithCancel(context.Background())

	return &Daemon{
		cfg:         cfg,
		configDir:   configDir,
		devices:     devices,
		privKey:     privKey,
		pubKey:      pubKey,
		knockPort:   config.DefaultDiscoveryPort,
		netChanged:  make(chan struct{}, 1),
		phones:      make(map[string]*PhoneConnection),
		pendingAuth: make(map[string]*pendingAuthEntry),
		ctx:         ctx,
		cancel:      cancel,
	}, nil
}

// Start binds listeners, registers mDNS + UDP discovery, and begins accepting
// connections. Returns once listeners are up.
func (d *Daemon) Start() error {
	tcpLn, err := d.listenTCP()
	if err != nil {
		return fmt.Errorf("tcp listen: %w", err)
	}
	d.tcpListener = tcpLn
	d.tcpPort = tcpLn.Addr().(*net.TCPAddr).Port
	log.Printf("TCP server on port %d", d.tcpPort)

	// mDNS (non-fatal; background watcher keeps refreshing when network changes)
	d.startMDNS()

	// UDP discovery (fast KDE-Connect-style LAN discovery + knock)
	if err := d.startDiscovery(); err != nil {
		log.Printf("UDP discovery disabled: %v", err)
	}

	// IPC socket/pipe
	if err := d.startIPC(); err != nil {
		d.cancel()
		_ = tcpLn.Close()
		d.stopDiscovery()
		d.stopMDNS()
		return fmt.Errorf("ipc listen: %w", err)
	}

	go d.acceptTCP()
	go d.acceptIPC()
	go d.initialKnock()

	return nil
}

// listenTCP binds the TCP server socket. Existing configs that still have
// port=0 are migrated to DefaultPort (34907) unless PAMBIO_PORT explicitly
// requests 0, so restarts keep the same endpoint.
func (d *Daemon) listenTCP() (net.Listener, error) {
	_, envForced := config.PortFromEnv()
	if d.cfg.Port == 0 && !envForced {
		if ln, err := net.Listen("tcp", fmt.Sprintf("0.0.0.0:%d", config.DefaultPort)); err == nil {
			d.cfg.Port = config.DefaultPort
			if err := config.PersistPort(d.configDir, config.DefaultPort); err != nil {
				log.Printf("warning: failed to persist default port: %v", err)
			}
			return ln, nil
		}
		ln, err := net.Listen("tcp", "0.0.0.0:0")
		if err != nil {
			return nil, err
		}
		bound := ln.Addr().(*net.TCPAddr).Port
		d.cfg.Port = bound
		if err := config.PersistPort(d.configDir, bound); err != nil {
			log.Printf("warning: failed to persist bound port: %v", err)
		}
		return ln, nil
	}

	return net.Listen("tcp", fmt.Sprintf("0.0.0.0:%d", d.cfg.Port))
}

// TCPPort returns the bound TCP port once Start has succeeded.
func (d *Daemon) TCPPort() int {
	return d.tcpPort
}

// DiscoveryPort returns the bound UDP discovery port (0 if disabled).
func (d *Daemon) DiscoveryPort() int {
	return d.discoveryPort
}

// NotifyNetworkChanged asks the mDNS watcher to re-check network interfaces
// immediately.
func (d *Daemon) NotifyNetworkChanged() {
	select {
	case d.netChanged <- struct{}{}:
	default:
	}
}

// Wait blocks until SIGINT/SIGTERM or context cancellation, then stops.
func (d *Daemon) Wait() {
	sigCh := make(chan os.Signal, 1)
	signal.Notify(sigCh, syscall.SIGINT, syscall.SIGTERM)

	select {
	case sig := <-sigCh:
		log.Printf("Signal %v received, shutting down…", sig)
	case <-d.ctx.Done():
	}

	d.Stop()
}

// TriggerPing sends a ping message to all connected phones to verify connection
// and pairing status.
func (d *Daemon) TriggerPing() {
	for _, phone := range d.getConnectedPhones() {
		go func(p *PhoneConnection) {
			p.mu.Lock()
			err := d.writeEncrypted(p.conn, p.sessionKey, protocol.PingMessage{Type: protocol.TypePing})
			p.mu.Unlock()
			if err != nil {
				log.Printf("trigger ping to %s failed, dropping connection: %v", p.deviceName, err)
				_ = p.conn.Close()
			}
		}(phone)
	}
}

// Stop gracefully shuts down all listeners and connections.
func (d *Daemon) Stop() {
	d.cancel()

	if d.tcpListener != nil {
		_ = d.tcpListener.Close()
	}
	if d.ipcListener != nil {
		_ = d.ipcListener.Close()
	}
	d.stopDiscovery()
	d.stopMDNS()
	d.cleanupIPC()

	d.phonesMu.Lock()
	for _, p := range d.phones {
		_ = p.conn.Close()
	}
	d.phonesMu.Unlock()

	log.Println("Daemon stopped")
}

// ──────────────────────────────────────────────
// Phone connection bookkeeping
// ──────────────────────────────────────────────

func (d *Daemon) registerPhone(key string, phone *PhoneConnection) {
	d.phonesMu.Lock()
	defer d.phonesMu.Unlock()
	// Close any existing connection from same device
	if old, ok := d.phones[key]; ok {
		_ = old.conn.Close()
	}
	d.phones[key] = phone
	log.Printf("Phone registered: %s", phone.deviceName)
}

func (d *Daemon) unregisterPhone(key string, phone *PhoneConnection) {
	d.phonesMu.Lock()
	defer d.phonesMu.Unlock()
	if cur, ok := d.phones[key]; ok && cur == phone {
		log.Printf("Phone unregistered: %s", cur.deviceName)
		delete(d.phones, key)
	}
}

func (d *Daemon) disconnectDeviceByName(name string) {
	d.phonesMu.Lock()
	defer d.phonesMu.Unlock()
	for key, p := range d.phones {
		if p.deviceName == name {
			_ = p.conn.Close()
			delete(d.phones, key)
		}
	}
}

func (d *Daemon) getConnectedPhones() []*PhoneConnection {
	d.phonesMu.RLock()
	defer d.phonesMu.RUnlock()
	out := make([]*PhoneConnection, 0, len(d.phones))
	for _, p := range d.phones {
		out = append(out, p)
	}
	return out
}

// ──────────────────────────────────────────────
// Pairing state machine
// ──────────────────────────────────────────────

func (d *Daemon) startPairing() chan PairingResult {
	d.pairingMu.Lock()
	defer d.pairingMu.Unlock()

	d.pairingActive = true
	d.pairingDone = make(chan PairingResult, 1)

	go func() {
		timer := time.NewTimer(PairingTimeout)
		defer timer.Stop()
		select {
		case <-timer.C:
			d.pairingMu.Lock()
			if d.pairingActive {
				d.pairingActive = false
				d.pairingDone <- PairingResult{
					Success: false,
					Error:   fmt.Errorf("pairing timed out after %v", PairingTimeout),
				}
			}
			d.pairingMu.Unlock()
		case <-d.ctx.Done():
		}
	}()

	return d.pairingDone
}

func (d *Daemon) completePairing(deviceName string) {
	d.pairingMu.Lock()
	defer d.pairingMu.Unlock()
	if d.pairingActive {
		d.pairingActive = false
		d.pairingDone <- PairingResult{Success: true, DeviceName: deviceName}
	}
}

func (d *Daemon) isPairingActive() bool {
	d.pairingMu.Lock()
	defer d.pairingMu.Unlock()
	return d.pairingActive
}
