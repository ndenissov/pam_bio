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
	"encoding/json"
	"flag"
	"fmt"
	"log"
	"net"
	"os"

	"pambio/internal/daemon"
	"pambio/internal/protocol"

	"github.com/mdp/qrterminal/v3"
	"github.com/skip2/go-qrcode"
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
		cmdPair(os.Args[2:])
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
  pair [--out F] [--qr-out F] [--raw] Initiate device pairing
  unpair <name>    Remove a paired device by name
  status           Show daemon and device status
  help             Show this help message

Environment:
  PAMBIO_CONFIG_DIR   Config directory (default: /etc/pambio)
`)
}

// ──────────────────────────────────────────────
// serve
// ──────────────────────────────────────────────

func cmdServe(configDir string) {
	d, err := daemon.New(configDir)
	if err != nil {
		log.Fatalf("init: %v", err)
	}
	if err := d.Start(); err != nil {
		log.Fatalf("start: %v", err)
	}
	log.Println("pambiod is running. Press Ctrl+C to stop.")
	d.Wait()
}

// ──────────────────────────────────────────────
// pair
// ──────────────────────────────────────────────

func cmdPair(args []string) {
	pairCmd := flag.NewFlagSet("pair", flag.ExitOnError)
	outFile := pairCmd.String("out", "", "Output raw pairing key to file instead of displaying QR code")
	qrOutFile := pairCmd.String("qr-out", "", "Output QR code as a PNG file")
	rawOut := pairCmd.Bool("raw", false, "Print raw base64 pairing key to stdout")
	pairCmd.Parse(args)

	conn, err := connectDaemon()
	if err != nil {
		fmt.Fprintln(os.Stderr, "Error: cannot connect to daemon. Is 'pambiod serve' running?")
		fmt.Fprintf(os.Stderr, "  %v\n", err)
		os.Exit(1)
	}
	defer conn.Close()

	// Request pairing mode
	if err := protocol.WriteJSON(conn, protocol.UnixRequest{Action: "start_pairing"}); err != nil {
		log.Fatalf("send: %v", err)
	}

	// First response: QR data
	var resp protocol.UnixResponse
	if err := protocol.ReadJSON(conn, &resp); err != nil {
		log.Fatalf("read: %v", err)
	}
	if resp.Status != "ready" {
		fmt.Fprintf(os.Stderr, "Pairing error: %s\n", resp.Reason)
		os.Exit(1)
	}

	if *outFile != "" {
		if err := os.WriteFile(*outFile, []byte(resp.QRData), 0600); err != nil {
			log.Fatalf("Failed to write to file: %v", err)
		}
		fmt.Printf("✓ Pairing key successfully saved to %s\n", *outFile)
	} else if *qrOutFile != "" {
		if err := qrcode.WriteFile(resp.QRData, qrcode.Medium, 256, *qrOutFile); err != nil {
			log.Fatalf("Failed to write QR code: %v", err)
		}
		fmt.Printf("✓ QR code image saved to %s\n", *qrOutFile)
	} else if *rawOut {
		fmt.Println(resp.QRData)
		os.Stdout.Close() // Close stdout so piped commands like wl-copy receive EOF
	} else {
		fmt.Fprintln(os.Stderr)
		fmt.Fprintln(os.Stderr, "╔══════════════════════════════════════════════╗")
		fmt.Fprintln(os.Stderr, "║  Scan this QR code with the PamBio Android  ║")
		fmt.Fprintln(os.Stderr, "║  app to pair your device.                    ║")
		fmt.Fprintln(os.Stderr, "╚══════════════════════════════════════════════╝")
		fmt.Fprintln(os.Stderr)

		qrterminal.GenerateWithConfig(resp.QRData, qrterminal.Config{
			Level:     qrterminal.M,
			Writer:    os.Stderr,
			BlackChar: qrterminal.BLACK,
			WhiteChar: qrterminal.WHITE,
			QuietZone: 2,
		})
	}

	// Show human-readable payload
	decoded, _ := base64.StdEncoding.DecodeString(resp.QRData)
	var payload map[string]interface{}
	json.Unmarshal(decoded, &payload)
	fmt.Fprintf(os.Stderr, "\nService name: %s\n", payload["service_name"])
	fmt.Fprintln(os.Stderr, "Waiting for device to connect…")

	// Second response: pairing result
	var result protocol.UnixResponse
	if err := protocol.ReadJSON(conn, &result); err != nil {
		log.Fatalf("read: %v", err)
	}

	if result.Status == "paired" {
		fmt.Fprintf(os.Stderr, "✓ Successfully paired with %s\n", result.DeviceName)
	} else {
		fmt.Fprintf(os.Stderr, "✗ Pairing failed: %s\n", result.Reason)
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
	defer conn.Close()

	protocol.WriteJSON(conn, protocol.UnixRequest{Action: "unpair", Device: deviceName})

	var resp protocol.UnixResponse
	protocol.ReadJSON(conn, &resp)

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
	defer conn.Close()

	protocol.WriteJSON(conn, protocol.UnixRequest{Action: "status"})

	var resp protocol.UnixResponse
	protocol.ReadJSON(conn, &resp)

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

// connectDaemon dials the pambiod Unix socket.
func connectDaemon() (net.Conn, error) {
	return net.Dial("unix", daemon.UnixSocketPath)
}
