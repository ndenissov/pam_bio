// Package daemon implements the core pambiod service: TCP server for phone
// connections, Unix socket for PAM/CLI requests, mDNS publishing, and
// orchestration of pairing and auth flows.
package daemon

import (
	"context"
	"crypto/ed25519"
	"fmt"
	"log"
	"net"
	"os"
	"os/signal"
	"strings"
	"sync"
	"syscall"
	"time"

	"pambio/internal/config"
	"pambio/internal/crypto"
)

const (
	// UnixSocketPath is the well-known IPC path used by the PAM module and CLI.
	UnixSocketPath = "/var/run/pambio.sock"

	// PairingTimeout limits how long the daemon waits for a phone to pair.
	PairingTimeout = 5 * time.Minute

	// AuthTimeout limits how long the daemon waits for a biometric response.
	AuthTimeout = 30 * time.Second
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
	deviceKey  ed25519.PublicKey  // long-term identity key of the device
	deviceName string
	sessionID  []byte            // SHA-256 of the ECDH shared secret
	mu         sync.Mutex        // guards writes to conn
}

// Daemon is the main pambiod service.
type Daemon struct {
	cfg       *config.ServerConfig
	configDir string
	devices   *config.PairedDevicesStore
	privKey   ed25519.PrivateKey
	pubKey    ed25519.PublicKey

	tcpListener  net.Listener
	unixListener net.Listener
	tcpPort      int

	// Connected phones keyed by base64 public key
	phones   map[string]*PhoneConnection
	phonesMu sync.RWMutex

	// Pending auth requests keyed by nonce
	pendingAuth map[string]chan AuthResult
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
		// First run — generate identity
		pub, priv, err := crypto.GenerateEd25519Keypair()
		if err != nil {
			return nil, err
		}
		hostname, _ := os.Hostname()
		cfg = &config.ServerConfig{
			Hostname:    hostname,
			ServiceName: hostname,
			Port:        42715,
			PrivateKey:  crypto.EncodePrivateKey(priv),
			PublicKey:   crypto.EncodePublicKey(pub),
		}
		if err := config.SaveServerConfig(configDir, cfg); err != nil {
			return nil, fmt.Errorf("save config: %w", err)
		}
		log.Printf("Generated new identity for %s", hostname)
	}

	// For backwards compatibility, strip 'pambio_' prefix from existing configs
	cfg.ServiceName = strings.TrimPrefix(cfg.ServiceName, "pambio_")

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
		phones:      make(map[string]*PhoneConnection),
		pendingAuth: make(map[string]chan AuthResult),
		ctx:         ctx,
		cancel:      cancel,
	}, nil
}

// Start binds listeners, registers the mDNS service, and begins accepting
// connections. Returns once listeners are up.
func (d *Daemon) Start() error {
	// TCP: bind to configured port on all interfaces
	addr := fmt.Sprintf(":%d", d.cfg.Port)
	tcpLn, err := net.Listen("tcp", addr)
	if err != nil {
		return fmt.Errorf("tcp listen: %w", err)
	}
	d.tcpListener = tcpLn
	d.tcpPort = tcpLn.Addr().(*net.TCPAddr).Port
	log.Printf("TCP server on port %d", d.tcpPort)

	// mDNS
	if err := d.startMDNS(); err != nil {
		tcpLn.Close()
		return fmt.Errorf("mdns: %w", err)
	}
	log.Printf("mDNS: %s._pambio._tcp (port %d)", d.cfg.ServiceName, d.tcpPort)

	// Unix socket
	os.Remove(UnixSocketPath) // clean up stale socket
	unixLn, err := net.Listen("unix", UnixSocketPath)
	if err != nil {
		tcpLn.Close()
		d.stopMDNS()
		return fmt.Errorf("unix listen: %w", err)
	}
	d.unixListener = unixLn
	if err := os.Chmod(UnixSocketPath, 0666); err != nil {
		log.Printf("warning: chmod %s: %v", UnixSocketPath, err)
	}
	log.Printf("Unix socket: %s", UnixSocketPath)

	go d.acceptTCP()
	go d.acceptUnix()

	return nil
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

// Stop gracefully shuts down all listeners and connections.
func (d *Daemon) Stop() {
	d.cancel()

	if d.tcpListener != nil {
		d.tcpListener.Close()
	}
	if d.unixListener != nil {
		d.unixListener.Close()
	}
	d.stopMDNS()
	os.Remove(UnixSocketPath)

	d.phonesMu.Lock()
	for _, p := range d.phones {
		p.conn.Close()
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
		old.conn.Close()
	}
	d.phones[key] = phone
	log.Printf("Phone registered: %s", phone.deviceName)
}

func (d *Daemon) unregisterPhone(key string) {
	d.phonesMu.Lock()
	defer d.phonesMu.Unlock()
	if p, ok := d.phones[key]; ok {
		log.Printf("Phone unregistered: %s", p.deviceName)
		delete(d.phones, key)
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
