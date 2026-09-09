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


//go:build windows

package daemon

import (
	"encoding/base64"
	"encoding/json"
	"log"
	"net"

	"github.com/Microsoft/go-winio"
	"pambio/internal/protocol"
)

const ipcSocketPath = `\\.\pipe\pambio`

// DialIPC connects to the daemon IPC pipe.
func DialIPC() (net.Conn, error) {
	return winio.DialPipe(ipcSocketPath, nil)
}

func (d *Daemon) startIPC() error {
	// D:(A;;GA;;;BA)(A;;GA;;;SY) -> Generic All to Built-in Admins and SYSTEM.
	cfg := &winio.PipeConfig{
		SecurityDescriptor: "D:(A;;GA;;;BA)(A;;GA;;;SY)",
	}
	ln, err := winio.ListenPipe(ipcSocketPath, cfg)
	if err != nil {
		return err
	}
	d.ipcListener = ln
	log.Printf("Named Pipe: %s", ipcSocketPath)
	return nil
}

func (d *Daemon) cleanupIPC() {
	// Winio pipes are cleaned up automatically on close.
}

// ──────────────────────────────────────────────
// IPC pipe accept loop
// ──────────────────────────────────────────────

func (d *Daemon) acceptIPC() {
	for {
		conn, err := d.ipcListener.Accept()
		if err != nil {
			select {
			case <-d.ctx.Done():
				return
			default:
				log.Printf("ipc accept: %v", err)
				continue
			}
		}
		go d.handleIPCConnection(conn)
	}
}

func (d *Daemon) handleIPCConnection(conn net.Conn) {
	defer func() { _ = conn.Close() }()

	// Trigger a status check to all connected devices on any IPC interaction
	go d.TriggerPing()

	data, err := protocol.ReadMessage(conn)
	if err != nil {
		log.Printf("ipc read: %v", err)
		return
	}

	var req protocol.IPCRequest
	if err := json.Unmarshal(data, &req); err != nil {
		log.Printf("ipc parse: %v", err)
		return
	}

	switch req.Action {
	case "auth_request":
		d.handlePAMAuth(conn, &req)
	case "start_pairing":
		// Only admins/SYSTEM can connect due to SDDL, so no uid check needed.
		d.handleStartPairing(conn)
	case "unpair":
		d.handleUnpair(conn, &req)
	case "status":
		d.handleStatus(conn)
	default:
		log.Printf("unknown ipc action: %s", req.Action)
		_ = protocol.WriteJSON(conn, protocol.IPCResponse{Status: "error", Reason: "unknown action"})
	}
}

// ──────────────────────────────────────────────
// PAM/CP auth request
// ──────────────────────────────────────────────

func (d *Daemon) handlePAMAuth(conn net.Conn, req *protocol.IPCRequest) {
	log.Printf("IPC auth: user=%s service=%s", req.User, req.Service)

	result := d.sendAuthRequest(req.User, req.Service)

	resp := protocol.IPCResponse{}
	if result.Success {
		resp.Status = "success"
		if d.cfg.ShowWatermark {
			resp.Watermark = "yes"
		}
		log.Printf("IPC auth: approved for %s (%s)", req.User, req.Service)
	} else {
		resp.Status = "denied"
		resp.Reason = result.Reason
		log.Printf("IPC auth: denied for %s (%s): %s", req.User, req.Service, result.Reason)
	}

	_ = protocol.WriteJSON(conn, resp)
}

// ──────────────────────────────────────────────
// Pairing
// ──────────────────────────────────────────────

func (d *Daemon) handleStartPairing(conn net.Conn) {
	log.Println("Pairing mode activated by CLI")

	// Build QR payload: [1 byte name_len][N bytes service_name][32 bytes pc_pub_key]
	pubKeyBytes, _ := base64.StdEncoding.DecodeString(d.cfg.PublicKey)
	serviceNameBytes := []byte(d.cfg.ServiceName)
	
	rawPayload := make([]byte, 1+len(serviceNameBytes)+len(pubKeyBytes))
	rawPayload[0] = byte(len(serviceNameBytes))
	copy(rawPayload[1:], serviceNameBytes)
	copy(rawPayload[1+len(serviceNameBytes):], pubKeyBytes)
	
	qrData := base64.StdEncoding.EncodeToString(rawPayload)

	// Activate pairing mode (daemon will accept the next pair_request)
	pairingCh := d.startPairing()

	// Send QR data to CLI so it can display it
	if err := protocol.WriteJSON(conn, protocol.IPCResponse{
		Status: "ready",
		QRData: qrData,
	}); err != nil {
		log.Printf("send qr data: %v", err)
		return
	}

	// Block until pairing completes or times out
	result := <-pairingCh

	if result.Success {
		_ = protocol.WriteJSON(conn, protocol.IPCResponse{
			Status:     "paired",
			DeviceName: result.DeviceName,
		})
	} else {
		errMsg := "unknown error"
		if result.Error != nil {
			errMsg = result.Error.Error()
		}
		_ = protocol.WriteJSON(conn, protocol.IPCResponse{
			Status: "error",
			Reason: errMsg,
		})
	}
}

// ──────────────────────────────────────────────
// Unpair
// ──────────────────────────────────────────────

func (d *Daemon) handleUnpair(conn net.Conn, req *protocol.IPCRequest) {
	if err := d.devices.RemoveDevice(req.Device); err != nil {
		_ = protocol.WriteJSON(conn, protocol.IPCResponse{Status: "error", Reason: err.Error()})
		return
	}
	log.Printf("Device unpaired: %s", req.Device)
	_ = protocol.WriteJSON(conn, protocol.IPCResponse{Status: "success"})
}

// ──────────────────────────────────────────────
// Status
// ──────────────────────────────────────────────

func (d *Daemon) handleStatus(conn net.Conn) {
	allDevices := d.devices.GetAll()
	connectedPhones := d.getConnectedPhones()

	// Build a set of connected device names for fast lookup
	connSet := make(map[string]bool, len(connectedPhones))
	for _, p := range connectedPhones {
		connSet[p.deviceName] = true
	}

	infos := make([]protocol.DeviceInfo, len(allDevices))
	for i, dev := range allDevices {
		infos[i] = protocol.DeviceInfo{
			Name:      dev.DeviceName,
			PublicKey: dev.PublicKey,
			PairedAt:  dev.PairedAt,
			Connected: connSet[dev.DeviceName],
		}
	}

	_ = protocol.WriteJSON(conn, protocol.IPCResponse{
		Status:        "ok",
		PairedDevices: infos,
	})
}
