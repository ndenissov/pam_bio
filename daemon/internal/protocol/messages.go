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


// Package protocol defines all message types and framing for pambio communication.
//
// Framing: all messages use 4-byte big-endian length prefix + payload.
// TCP messages are AES-256-GCM encrypted after the ECDH handshake.
// Unix socket messages are plaintext JSON (local IPC only).
package protocol

import (
	"encoding/binary"
	"encoding/json"
	"fmt"
	"io"
	"net"
)

// MaxMessageSize is the upper bound for a single framed message (1 MB).
const MaxMessageSize = 1 << 20

// ──────────────────────────────────────────────
// Message type constants
// ──────────────────────────────────────────────

const (
	TypeHello        = "hello"
	TypeHelloAck     = "hello_ack"
	TypeIdentify     = "identify"
	TypeIdentifyAck  = "identify_ack"
	TypePairRequest  = "pair_request"
	TypePairResponse = "pair_response"
	TypeAuthRequest  = "auth_request"
	TypeAuthResponse = "auth_response"
	TypePing         = "ping"
	TypePong         = "pong"
)

// Status constants for auth responses.
const (
	StatusApproved = "approved"
	StatusDenied   = "denied"
	StatusTimeout  = "timeout"
	StatusError    = "error"
)

// Pair response statuses.
const (
	PairAccepted = "accepted"
	PairRejected = "rejected"
)

// ──────────────────────────────────────────────
// TCP protocol messages (PC ↔ Phone)
// ──────────────────────────────────────────────

// BaseMessage is used to peek at the "type" field of incoming JSON.
type BaseMessage struct {
	Type string `json:"type"`
}

// HelloMessage is the first plaintext message in the ECDH handshake.
type HelloMessage struct {
	Type    string `json:"type"`
	ECDHPub string `json:"ecdh_pub"` // base64 X25519 ephemeral public key
}

// IdentifyMessage is sent by a previously-paired phone after the encrypted
// channel is established.
type IdentifyMessage struct {
	Type         string `json:"type"`
	DevicePubKey string `json:"device_pub_key"` // base64 Ed25519 public key
	Signature    string `json:"signature"`       // Ed25519 sign(session_id)
}

// IdentifyAckMessage is the server's response to an Identify.
type IdentifyAckMessage struct {
	Type   string `json:"type"`
	Status string `json:"status"` // "accepted" or "rejected"
}

// PairRequestMessage is sent by a new phone to pair with the PC.
type PairRequestMessage struct {
	Type         string `json:"type"`
	DevicePubKey string `json:"device_pub_key"` // base64 Ed25519 public key
	DeviceName   string `json:"device_name"`
	Proof        string `json:"proof"` // Ed25519 sign(pc_pub_key) — proves key ownership
}

// PairResponseMessage is the server's response to a PairRequest.
type PairResponseMessage struct {
	Type   string `json:"type"`
	Status string `json:"status"` // "accepted" or "rejected"
}

// AuthRequestMessage is sent from PC → Phone when PAM requests auth.
type AuthRequestMessage struct {
	Type      string `json:"type"`
	Nonce     string `json:"nonce"`
	Timestamp int64  `json:"timestamp"` // unix millis
	User      string `json:"user"`
	Service   string `json:"service"` // "sudo", "sddm", etc.
}

// AuthResponseMessage is sent from Phone → PC after biometric check.
type AuthResponseMessage struct {
	Type      string `json:"type"`
	Status    string `json:"status"` // "approved" or "denied"
	Nonce     string `json:"nonce"`
	Signature string `json:"signature"` // Ed25519 sign(nonce)
}

// PingMessage is sent by PC to verify connection status.
type PingMessage struct {
	Type string `json:"type"`
}

// PongMessage is sent by Phone in response to PingMessage.
type PongMessage struct {
	Type   string `json:"type"`
	Status string `json:"status"` // "ok" or "unpaired"
}

// ──────────────────────────────────────────────
// Unix socket messages (PAM / CLI ↔ Daemon)
// ──────────────────────────────────────────────

// UnixRequest is sent by the PAM module or CLI tool over the Unix socket.
type UnixRequest struct {
	Action  string `json:"action"`            // "auth_request", "start_pairing", "unpair", "status"
	User    string `json:"user,omitempty"`     // for auth_request
	Service string `json:"service,omitempty"`  // for auth_request
	Device  string `json:"device,omitempty"`   // for unpair
}

// UnixResponse is sent by the daemon back over the Unix socket.
type UnixResponse struct {
	Status         string       `json:"status"`
	Reason         string       `json:"reason,omitempty"`
	Watermark      string       `json:"watermark,omitempty"`        // "yes" if PAM module should print watermark
	QRData         string       `json:"qr_data,omitempty"`          // for start_pairing
	DeviceName     string       `json:"device_name,omitempty"`      // for pairing_complete
	PairedDevices  []DeviceInfo `json:"paired_devices,omitempty"`   // for status
}

// DeviceInfo carries device metadata in status responses.
type DeviceInfo struct {
	Name      string `json:"name"`
	PublicKey string `json:"public_key"`
	PairedAt  int64  `json:"paired_at"`
	Connected bool   `json:"connected"`
}

// ──────────────────────────────────────────────
// Framing helpers
// ──────────────────────────────────────────────

// WriteMessage writes a length-prefixed binary message.
func WriteMessage(conn net.Conn, data []byte) error {
	header := make([]byte, 4)
	binary.BigEndian.PutUint32(header, uint32(len(data)))
	buf := make([]byte, 0, 4+len(data))
	buf = append(buf, header...)
	buf = append(buf, data...)
	_, err := conn.Write(buf)
	return err
}

// ReadMessage reads a length-prefixed binary message.
func ReadMessage(conn net.Conn) ([]byte, error) {
	header := make([]byte, 4)
	if _, err := io.ReadFull(conn, header); err != nil {
		return nil, err
	}
	length := binary.BigEndian.Uint32(header)
	if length > MaxMessageSize {
		return nil, fmt.Errorf("message too large: %d bytes (max %d)", length, MaxMessageSize)
	}
	data := make([]byte, length)
	if _, err := io.ReadFull(conn, data); err != nil {
		return nil, err
	}
	return data, nil
}

// WriteJSON marshals v to JSON and writes it as a length-prefixed message.
func WriteJSON(conn net.Conn, v any) error {
	data, err := json.Marshal(v)
	if err != nil {
		return fmt.Errorf("marshal: %w", err)
	}
	return WriteMessage(conn, data)
}

// ReadJSON reads a length-prefixed message and unmarshals it into v.
func ReadJSON(conn net.Conn, v any) error {
	data, err := ReadMessage(conn)
	if err != nil {
		return err
	}
	return json.Unmarshal(data, v)
}

// ParseMessageType peeks at the "type" field of a JSON message.
func ParseMessageType(data []byte) (string, error) {
	var base BaseMessage
	if err := json.Unmarshal(data, &base); err != nil {
		return "", fmt.Errorf("parse type: %w", err)
	}
	if base.Type == "" {
		return "", fmt.Errorf("missing 'type' field in message")
	}
	return base.Type, nil
}
