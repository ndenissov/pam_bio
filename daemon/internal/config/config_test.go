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

package config

import (
	"os"
	"path/filepath"
	"testing"
)

func TestServerConfigSaveLoadAndPersistPort(t *testing.T) {
	dir := t.TempDir()
	cfg := &ServerConfig{
		Hostname:      "testhost",
		ServiceName:   "pambio_testhost",
		Port:          0,
		ShowWatermark: false,
		PrivateKey:    "priv",
		PublicKey:     "pub",
	}
	if err := SaveServerConfig(dir, cfg); err != nil {
		t.Fatalf("SaveServerConfig: %v", err)
	}

	loaded, err := LoadServerConfig(dir)
	if err != nil {
		t.Fatalf("LoadServerConfig: %v", err)
	}
	if loaded.ServiceName != "testhost" {
		t.Fatalf("expected prefix trimmed, got %q", loaded.ServiceName)
	}
	if loaded.ShowWatermark {
		t.Fatal("expected ShowWatermark=false to be preserved")
	}

	if err := PersistPort(dir, DefaultPort); err != nil {
		t.Fatalf("PersistPort: %v", err)
	}
	reloaded, err := LoadServerConfig(dir)
	if err != nil {
		t.Fatalf("LoadServerConfig after PersistPort: %v", err)
	}
	if reloaded.Port != DefaultPort {
		t.Fatalf("got port %d, want %d", reloaded.Port, DefaultPort)
	}
	if reloaded.PrivateKey != "priv" || reloaded.ShowWatermark {
		t.Fatal("PersistPort mutated unrelated fields")
	}

	// Ensure no leftover temp files remain
	entries, err := os.ReadDir(dir)
	if err != nil {
		t.Fatal(err)
	}
	if len(entries) != 1 || entries[0].Name() != configFile {
		t.Fatalf("unexpected files in config dir: %v", entries)
	}
}

func TestPortAndDiscoveryEnvOverrides(t *testing.T) {
	t.Setenv("PAMBIO_PORT", "45123")
	if p, ok := PortFromEnv(); !ok || p != 45123 {
		t.Fatalf("PortFromEnv got (%d, %v)", p, ok)
	}
	t.Setenv("PAMBIO_PORT", "invalid")
	if _, ok := PortFromEnv(); ok {
		t.Fatal("expected invalid PAMBIO_PORT to be ignored")
	}

	t.Setenv("PAMBIO_DISCOVERY_PORT", "0")
	if p := DiscoveryPortFromEnv(); p != 0 {
		t.Fatalf("expected 0, got %d", p)
	}
	t.Setenv("PAMBIO_DISCOVERY_PORT", "bad")
	if p := DiscoveryPortFromEnv(); p != DefaultDiscoveryPort {
		t.Fatalf("expected default %d, got %d", DefaultDiscoveryPort, p)
	}
}

func TestPairedDevicesStoreLifecycleAndRecordAddr(t *testing.T) {
	dir := t.TempDir()
	store := NewPairedDevicesStore(dir)
	if err := store.Load(); err != nil {
		t.Fatalf("Load missing file: %v", err)
	}
	if len(store.GetAll()) != 0 {
		t.Fatal("expected empty store")
	}

	if err := store.AddDevice("Pixel", "pk1"); err != nil {
		t.Fatal(err)
	}
	// Adding same device name replaces old key cleanly
	if err := store.AddDevice("Pixel", "pk2"); err != nil {
		t.Fatal(err)
	}
	if len(store.GetAll()) != 1 || store.FindByPublicKey("pk2") == nil {
		t.Fatal("expected single deduplicated device with pk2")
	}

	// Record addresses: most recent first, deduplicated, capped at maxLastAddrs
	for _, ip := range []string{"192.168.1.10", "192.168.1.11", "192.168.1.12", "192.168.1.13", "192.168.1.14", "192.168.1.11"} {
		if err := store.RecordAddr("pk2", ip); err != nil {
			t.Fatalf("RecordAddr(%s): %v", ip, err)
		}
	}

	reloaded := NewPairedDevicesStore(dir)
	if err := reloaded.Load(); err != nil {
		t.Fatalf("reload: %v", err)
	}
	dev := reloaded.FindByPublicKey("pk2")
	if dev == nil {
		t.Fatal("FindByPublicKey returned nil after reload")
	}
	if len(dev.LastAddrs) != maxLastAddrs || dev.LastAddrs[0] != "192.168.1.11" {
		t.Fatalf("unexpected LastAddrs: %v", dev.LastAddrs)
	}
	// Mutating returned pointer must not corrupt internal store
	dev.LastAddrs[0] = "mutated"
	if reloaded.FindByPublicKey("pk2").LastAddrs[0] != "192.168.1.11" {
		t.Fatal("FindByPublicKey aliased internal backing slice")
	}

	if err := reloaded.RemoveDevice("Pixel"); err != nil {
		t.Fatalf("RemoveDevice: %v", err)
	}
	if _, err := os.Stat(filepath.Join(dir, pairedDevicesFile)); err != nil {
		t.Fatalf("expected file to exist: %v", err)
	}
	if len(reloaded.GetAll()) != 0 {
		t.Fatal("expected empty store after RemoveDevice")
	}
}

