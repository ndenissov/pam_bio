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
	"strconv"
	"strings"
	"sync"
	"time"
)

const (
	// DefaultConfigDir is the standard config path when running as root.
	DefaultConfigDir     = "/etc/pambio"
	configFile           = "config.json"
	pairedDevicesFile    = "paired_devices.json"

	// DefaultPort is the well-known TCP port used when the config does not
	// pin a specific one. Keeping the port stable across restarts is
	// essential: phones cache the last known endpoint (and Android's mDNS
	// cache keeps SRV records for a long time), so a random port on every
	// restart caused multi-minute reconnection delays.
	DefaultPort = 34907

	// DefaultDiscoveryPort is the UDP port used for the lightweight
	// broadcast/unicast discovery protocol (KDE Connect style). It works
	// even on networks where multicast DNS is filtered.
	DefaultDiscoveryPort = 34907

	// maxLastAddrs bounds how many recent phone addresses are remembered.
	maxLastAddrs = 4
)

// PortFromEnv returns the PAMBIO_PORT override, if set and valid.
func PortFromEnv() (int, bool) {
	envPort := os.Getenv("PAMBIO_PORT")
	if envPort == "" {
		return 0, false
	}
	p, err := strconv.Atoi(envPort)
	if err != nil || p < 0 || p > 65535 {
		return 0, false
	}
	return p, true
}

// DiscoveryPortFromEnv returns the UDP discovery port, honouring the
// PAMBIO_DISCOVERY_PORT override (0 disables UDP discovery).
func DiscoveryPortFromEnv() int {
	if v := os.Getenv("PAMBIO_DISCOVERY_PORT"); v != "" {
		if p, err := strconv.Atoi(v); err == nil && p >= 0 && p <= 65535 {
			return p
		}
	}
	return DefaultDiscoveryPort
}

// PersistPort stores the TCP port in config.json without touching any other
// field (unknown fields and formatting of values are preserved).
func PersistPort(configDir string, port int) error {
	path := filepath.Join(configDir, configFile)
	data, err := os.ReadFile(path)
	if err != nil {
		return err
	}
	var raw map[string]json.RawMessage
	if err := json.Unmarshal(data, &raw); err != nil {
		return fmt.Errorf("parse %s: %w", configFile, err)
	}
	raw["port"] = json.RawMessage(strconv.Itoa(port))
	out, err := json.MarshalIndent(raw, "", "  ")
	if err != nil {
		return err
	}
	return writeFileAtomic(path, out, 0600)
}

// writeFileAtomic writes data to a temp file and renames it over path so a
// crash or power loss never leaves a truncated config behind.
func writeFileAtomic(path string, data []byte, perm os.FileMode) error {
	dir := filepath.Dir(path)
	tmp, err := os.CreateTemp(dir, ".tmp-"+filepath.Base(path)+"-*")
	if err != nil {
		return err
	}
	tmpName := tmp.Name()
	cleanup := func() { _ = os.Remove(tmpName) }
	if _, err := tmp.Write(data); err != nil {
		_ = tmp.Close()
		cleanup()
		return err
	}
	if err := tmp.Chmod(perm); err != nil {
		_ = tmp.Close()
		cleanup()
		return err
	}
	if err := tmp.Close(); err != nil {
		cleanup()
		return err
	}
	if err := os.Rename(tmpName, path); err != nil {
		cleanup()
		return err
	}
	return nil
}

// ──────────────────────────────────────────────
// Server config
// ──────────────────────────────────────────────

// ServerConfig holds the daemon's identity.
type ServerConfig struct {
	Hostname      string `json:"hostname"`
	ServiceName   string `json:"service_name"` // mDNS instance name
	Port          int    `json:"port"`         // TCP port
	ShowWatermark bool   `json:"show_watermark"`
	PrivateKey    string `json:"private_key"` // base64 Ed25519 seed (32 bytes)
	PublicKey     string `json:"public_key"`  // base64 Ed25519 public key
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
	cfg.ServiceName = strings.TrimPrefix(cfg.ServiceName, "pambio_")

	if p, ok := PortFromEnv(); ok {
		cfg.Port = p
	}

	// Default watermark to true unless explicitly disabled
	cfg.ShowWatermark = true
	if strings.Contains(string(data), `"show_watermark":false`) || strings.Contains(string(data), `"show_watermark": false`) {
		cfg.ShowWatermark = false
	}
	if envWM := os.Getenv("PAMBIO_SHOW_WATERMARK"); envWM != "" {
		switch envWM {
		case "0", "false":
			cfg.ShowWatermark = false
		case "1", "true":
			cfg.ShowWatermark = true
		}
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
	return writeFileAtomic(filepath.Join(configDir, configFile), data, 0600)
}

// ──────────────────────────────────────────────
// Paired devices
// ──────────────────────────────────────────────

// PairedDevice represents a single paired Android device.
type PairedDevice struct {
	DeviceName string `json:"device_name"`
	PublicKey  string `json:"public_key"` // base64 Ed25519 public key
	PairedAt   int64  `json:"paired_at"`  // unix timestamp
	// LastAddrs holds the most recent IP addresses (most recent first) the
	// phone connected from. The daemon uses them to "knock" the phone over
	// UDP so it reconnects immediately after a PC/daemon restart.
	LastAddrs []string `json:"last_addrs,omitempty"`
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
	return writeFileAtomic(s.path, data, 0600)
}

// RecordAddr remembers ip as the most recent address of the device with the
// given public key. It is a no-op (and does not touch the disk) when the
// address is already the most recent one.
func (s *PairedDevicesStore) RecordAddr(pubKey, ip string) error {
	if ip == "" {
		return nil
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	for i := range s.Devices {
		d := &s.Devices[i]
		if d.PublicKey != pubKey {
			continue
		}
		if len(d.LastAddrs) > 0 && d.LastAddrs[0] == ip {
			return nil
		}
		addrs := []string{ip}
		for _, a := range d.LastAddrs {
			if a != ip && len(addrs) < maxLastAddrs {
				addrs = append(addrs, a)
			}
		}
		d.LastAddrs = addrs
		return s.save()
	}
	return fmt.Errorf("device not found")
}

// AddDevice registers a new paired device. It removes any existing device
// with the same public key OR the same device name to prevent duplicates.
func (s *PairedDevicesStore) AddDevice(name, pubKey string) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	// Filter out any existing devices with the same name or pubkey
	filtered := []PairedDevice{}
	for _, d := range s.Devices {
		if d.PublicKey != pubKey && d.DeviceName != name {
			filtered = append(filtered, d)
		}
	}
	s.Devices = filtered

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

// clonePairedDevice returns a deep copy of d so callers cannot alias LastAddrs.
func clonePairedDevice(d PairedDevice) PairedDevice {
	if len(d.LastAddrs) > 0 {
		d.LastAddrs = append([]string(nil), d.LastAddrs...)
	}
	return d
}

// FindByPublicKey looks up a device by its base64-encoded public key.
// Returns nil if not found.
func (s *PairedDevicesStore) FindByPublicKey(pubKey string) *PairedDevice {
	s.mu.RLock()
	defer s.mu.RUnlock()

	for i := range s.Devices {
		if s.Devices[i].PublicKey == pubKey {
			dev := clonePairedDevice(s.Devices[i])
			return &dev
		}
	}
	return nil
}

// GetAll returns a snapshot copy of all paired devices.
func (s *PairedDevicesStore) GetAll() []PairedDevice {
	s.mu.RLock()
	defer s.mu.RUnlock()

	out := make([]PairedDevice, len(s.Devices))
	for i, d := range s.Devices {
		out[i] = clonePairedDevice(d)
	}
	return out
}
