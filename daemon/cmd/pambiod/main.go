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


// pambiod is the PamBio daemon and CLI tool for biometric authentication
// via an Android smartphone over the local network.
//
// Usage:
//
//	pambiod serve    — run the daemon
//	pambiod pair     — initiate device pairing
//	pambiod unpair   — remove a paired device
//	pambiod status   — show daemon & device status
package main

import (
	"encoding/base64"
	"fmt"
	"log"
	"net"
	"os"

	"pambio/internal/daemon"
	"pambio/internal/protocol"

	qrterminal "github.com/mdp/qrterminal/v3"
)

const defaultConfigDir = "/etc/pambio"

func main() {
	log.SetFlags(log.LstdFlags | log.Lshortfile)

	if len(os.Args) < 2 {
		printUsage()
		os.Exit(1)
	}

	configDir := os.Getenv("PAMBIO_CONFIG_DIR")
	if configDir == "" {
		configDir = defaultConfigDir
	}

	switch os.Args[1] {
	case "serve":
		cmdServe(configDir)
	case "pair":
		cmdPair()
	case "unpair":
		if len(os.Args) < 3 {
			fmt.Fprintln(os.Stderr, "Usage: pambiod unpair <device_name>")
			os.Exit(1)
		}
		cmdUnpair(os.Args[2])
	case "status":
		cmdStatus()
	case "help", "-h", "--help":
		printUsage()
	default:
		fmt.Fprintf(os.Stderr, "Unknown command: %s\n", os.Args[1])
		printUsage()
		os.Exit(1)
	}
}

func printUsage() {
	fmt.Print(`pambiod — PamBio daemon for biometric authentication

Usage:
  pambiod <command> [arguments]

Commands:
  serve            Start the daemon (TCP + Unix socket + mDNS)
  pair             Initiate device pairing (displays QR code in terminal)
  unpair <name>    Remove a paired device by name
  status           Show daemon and device status
  help             Show this help message

Environment:
  PAMBIO_CONFIG_DIR   Config directory (default: /etc/pambio)

Repository: https://github.com/ndenissov/pam_bio
License:    Apache-2.0
Copyright:  2026 Nikita Denissov
`)
}



// ──────────────────────────────────────────────
// pair
// ──────────────────────────────────────────────

func cmdPair() {
	if err := platformPrePairing(); err != nil {
		fmt.Fprintf(os.Stderr, "Pre-pairing setup failed: %v\n", err)
		os.Exit(1)
	}

	conn, err := connectDaemon()
	if err != nil {
		fmt.Fprintln(os.Stderr, "Error: cannot connect to daemon. Is 'pambiod serve' running?")
		fmt.Fprintf(os.Stderr, "  %v\n", err)
		os.Exit(1)
	}
	defer func() { _ = conn.Close() }()

	if err := protocol.WriteJSON(conn, protocol.IPCRequest{Action: "start_pairing"}); err != nil {
		log.Fatalf("send: %v", err)
	}

	var resp protocol.IPCResponse
	if err := protocol.ReadJSON(conn, &resp); err != nil {
		log.Fatalf("read: %v", err)
	}
	if resp.Status != "ready" {
		fmt.Fprintf(os.Stderr, "Pairing error: %s\n", resp.Reason)
		os.Exit(1)
	}

	// Show human-readable payload
	rawPayload, _ := base64.StdEncoding.DecodeString(resp.QRData)
	serviceNameLen := int(rawPayload[0])
	serviceName := string(rawPayload[1 : 1+serviceNameLen])
	fmt.Printf("\nService name: %s\n", serviceName)
	fmt.Println("Waiting for device to connect…")

	fmt.Println()
	fmt.Println("╔══════════════════════════════════════════════╗")
	fmt.Println("║  Scan this QR code with the PamBio Android   ║")
	fmt.Println("║  app to pair your device.                    ║")
	fmt.Println("╚══════════════════════════════════════════════╝")
	fmt.Println()
	
	// Print base64 for console copying
	fmt.Println("Manual copy base64:", resp.QRData)
	fmt.Println()

	// Generate compact QR code with raw binary payload
	qrterminal.GenerateHalfBlock(string(rawPayload), qrterminal.L, os.Stdout)

	var result protocol.IPCResponse
	if err := protocol.ReadJSON(conn, &result); err != nil {
		log.Fatalf("read result: %v", err)
	}

	if result.Status == "paired" {
		fmt.Printf("\n✓ Successfully paired with: %s\n", result.DeviceName)
	} else {
		fmt.Fprintf(os.Stderr, "\n✗ Pairing failed: %s\n", result.Reason)
		os.Exit(1)
	}
}

// ──────────────────────────────────────────────
// unpair
// ──────────────────────────────────────────────

func cmdUnpair(deviceName string) {
	conn, err := connectDaemon()
	if err != nil {
		fmt.Fprintln(os.Stderr, "Error: cannot connect to daemon.")
		os.Exit(1)
	}
	defer func() { _ = conn.Close() }()

	_ = protocol.WriteJSON(conn, protocol.IPCRequest{Action: "unpair", Device: deviceName})

	var resp protocol.IPCResponse
	_ = protocol.ReadJSON(conn, &resp)

	if resp.Status == "success" {
		fmt.Printf("✓ Device '%s' unpaired\n", deviceName)
	} else {
		fmt.Fprintf(os.Stderr, "✗ Failed: %s\n", resp.Reason)
		os.Exit(1)
	}
}

// ──────────────────────────────────────────────
// status
// ──────────────────────────────────────────────

func cmdStatus() {
	conn, err := connectDaemon()
	if err != nil {
		fmt.Fprintln(os.Stderr, "Daemon is not running.")
		os.Exit(1)
	}
	defer func() { _ = conn.Close() }()

	_ = protocol.WriteJSON(conn, protocol.IPCRequest{Action: "status"})

	var resp protocol.IPCResponse
	_ = protocol.ReadJSON(conn, &resp)

	fmt.Println("PamBio Daemon Status")
	fmt.Println("════════════════════")

	if len(resp.PairedDevices) == 0 {
		fmt.Println("\nNo paired devices.")
		return
	}

	fmt.Printf("\nPaired devices (%d):\n", len(resp.PairedDevices))
	for _, dev := range resp.PairedDevices {
		icon := "○"
		status := "offline"
		if dev.Connected {
			icon = "●"
			status = "connected"
		}
		fmt.Printf("  %s %s  [%s]\n", icon, dev.Name, status)
	}
}

// connectDaemon dials the pambiod IPC socket.
func connectDaemon() (net.Conn, error) {
	return daemon.DialIPC()
}
