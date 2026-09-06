// Package config manages persistent configuration for the pambio daemon.
//
// Two files are stored under the config directory (default /etc/pambio):
//   - config.json  — server identity (hostname, Ed25519 keypair)
//   - paired_devices.json — list of paired Android devices
//
// File permissions are restrictive (0700 dirs, 0600 files) because
// private keys are stored on disk.
package config

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"sync"
	"time"
)

const (
	// DefaultConfigDir is the standard config path when running as root.
	DefaultConfigDir     = "/etc/pambio"
	configFile           = "config.json"
	pairedDevicesFile    = "paired_devices.json"
)

// ──────────────────────────────────────────────
// Server config
// ──────────────────────────────────────────────

// ServerConfig holds the daemon's identity.
type ServerConfig struct {
	Hostname    string `json:"hostname"`
	ServiceName string `json:"service_name"` // mDNS instance name
	PrivateKey  string `json:"private_key"`  // base64 Ed25519 seed (32 bytes)
	PublicKey   string `json:"public_key"`   // base64 Ed25519 public key
}

// LoadServerConfig reads the server config from disk.
func LoadServerConfig(configDir string) (*ServerConfig, error) {
	data, err := os.ReadFile(filepath.Join(configDir, configFile))
	if err != nil {
		return nil, err
	}
	var cfg ServerConfig
	if err := json.Unmarshal(data, &cfg); err != nil {
		return nil, fmt.Errorf("parse %s: %w", configFile, err)
	}
	return &cfg, nil
}

// SaveServerConfig writes the server config to disk.
func SaveServerConfig(configDir string, cfg *ServerConfig) error {
	if err := os.MkdirAll(configDir, 0700); err != nil {
		return fmt.Errorf("mkdir %s: %w", configDir, err)
	}
	data, err := json.MarshalIndent(cfg, "", "  ")
	if err != nil {
		return err
	}
	return os.WriteFile(filepath.Join(configDir, configFile), data, 0600)
}

// ──────────────────────────────────────────────
// Paired devices
// ──────────────────────────────────────────────

// PairedDevice represents a single paired Android device.
type PairedDevice struct {
	DeviceName string `json:"device_name"`
	PublicKey  string `json:"public_key"` // base64 Ed25519 public key
	PairedAt   int64  `json:"paired_at"`  // unix timestamp
}

// PairedDevicesStore is a thread-safe store for paired devices backed by a
// JSON file on disk.
type PairedDevicesStore struct {
	Devices []PairedDevice `json:"devices"`
	mu      sync.RWMutex
	path    string
}

// NewPairedDevicesStore creates a store that reads/writes the given config dir.
func NewPairedDevicesStore(configDir string) *PairedDevicesStore {
	return &PairedDevicesStore{
		path: filepath.Join(configDir, pairedDevicesFile),
	}
}

// Load reads paired devices from disk. If the file does not exist the store
// starts empty (this is not an error).
func (s *PairedDevicesStore) Load() error {
	s.mu.Lock()
	defer s.mu.Unlock()

	data, err := os.ReadFile(s.path)
	if os.IsNotExist(err) {
		s.Devices = []PairedDevice{}
		return nil
	}
	if err != nil {
		return fmt.Errorf("read %s: %w", s.path, err)
	}
	return json.Unmarshal(data, &s.Devices)
}

// save persists the current device list (caller must hold mu).
func (s *PairedDevicesStore) save() error {
	data, err := json.MarshalIndent(s.Devices, "", "  ")
	if err != nil {
		return err
	}
	dir := filepath.Dir(s.path)
	if err := os.MkdirAll(dir, 0700); err != nil {
		return err
	}
	return os.WriteFile(s.path, data, 0600)
}

// AddDevice registers a new paired device or updates an existing one.
func (s *PairedDevicesStore) AddDevice(name, pubKey string) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	for i, d := range s.Devices {
		if d.PublicKey == pubKey {
			s.Devices[i].DeviceName = name
			s.Devices[i].PairedAt = time.Now().Unix()
			return s.save()
		}
	}
	s.Devices = append(s.Devices, PairedDevice{
		DeviceName: name,
		PublicKey:  pubKey,
		PairedAt:   time.Now().Unix(),
	})
	return s.save()
}

// RemoveDevice deletes a device by name.
func (s *PairedDevicesStore) RemoveDevice(name string) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	for i, d := range s.Devices {
		if d.DeviceName == name {
			s.Devices = append(s.Devices[:i], s.Devices[i+1:]...)
			return s.save()
		}
	}
	return fmt.Errorf("device not found: %s", name)
}

// FindByPublicKey looks up a device by its base64-encoded public key.
// Returns nil if not found.
func (s *PairedDevicesStore) FindByPublicKey(pubKey string) *PairedDevice {
	s.mu.RLock()
	defer s.mu.RUnlock()

	for i := range s.Devices {
		if s.Devices[i].PublicKey == pubKey {
			return &s.Devices[i]
		}
	}
	return nil
}

// GetAll returns a snapshot copy of all paired devices.
func (s *PairedDevicesStore) GetAll() []PairedDevice {
	s.mu.RLock()
	defer s.mu.RUnlock()

	out := make([]PairedDevice, len(s.Devices))
	copy(out, s.Devices)
	return out
}
