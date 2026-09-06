package daemon

import (
	"encoding/json"
	"fmt"
	"log"
	"net"
	"time"

	"pambio/internal/crypto"
	"pambio/internal/protocol"
)

// ──────────────────────────────────────────────
// TCP accept loop
// ──────────────────────────────────────────────

func (d *Daemon) acceptTCP() {
	for {
		conn, err := d.tcpListener.Accept()
		if err != nil {
			select {
			case <-d.ctx.Done():
				return
			default:
				log.Printf("tcp accept: %v", err)
				continue
			}
		}
		go d.handleTCPConnection(conn)
	}
}

func (d *Daemon) handleTCPConnection(conn net.Conn) {
	defer conn.Close()
	remote := conn.RemoteAddr().String()
	log.Printf("TCP connect from %s", remote)

	// Step 1 — ECDH handshake (plaintext)
	sessionKey, sessionID, err := d.performHandshake(conn)
	if err != nil {
		log.Printf("handshake %s: %v", remote, err)
		return
	}

	// Step 2 — first encrypted message reveals intent
	data, err := d.readEncrypted(conn, sessionKey)
	if err != nil {
		log.Printf("first msg %s: %v", remote, err)
		return
	}

	msgType, err := protocol.ParseMessageType(data)
	if err != nil {
		log.Printf("parse type %s: %v", remote, err)
		return
	}

	switch msgType {
	case protocol.TypePairRequest:
		d.handlePairRequest(conn, sessionKey, data)
	case protocol.TypeIdentify:
		d.handleIdentify(conn, sessionKey, sessionID, data)
	default:
		log.Printf("unexpected type %q from %s", msgType, remote)
	}
}

// ──────────────────────────────────────────────
// ECDH handshake
// ──────────────────────────────────────────────

func (d *Daemon) performHandshake(conn net.Conn) (sessionKey, sessionID []byte, err error) {
	conn.SetDeadline(time.Now().Add(10 * time.Second))
	defer conn.SetDeadline(time.Time{})

	// Generate ephemeral X25519 keypair
	kp, err := crypto.GenerateECDHKeypair()
	if err != nil {
		return nil, nil, err
	}

	// Expect phone's hello first
	var phoneHello protocol.HelloMessage
	if err := protocol.ReadJSON(conn, &phoneHello); err != nil {
		return nil, nil, fmt.Errorf("read hello: %w", err)
	}
	if phoneHello.Type != protocol.TypeHello {
		return nil, nil, fmt.Errorf("expected %s, got %s", protocol.TypeHello, phoneHello.Type)
	}

	// Reply with our public key
	ack := protocol.HelloMessage{
		Type:    protocol.TypeHelloAck,
		ECDHPub: crypto.EncodeECDHPublicKey(kp.PublicKey),
	}
	if err := protocol.WriteJSON(conn, ack); err != nil {
		return nil, nil, fmt.Errorf("write hello_ack: %w", err)
	}

	// Derive shared secret → session key
	remotePub, err := crypto.DecodeECDHPublicKey(phoneHello.ECDHPub)
	if err != nil {
		return nil, nil, fmt.Errorf("decode ecdh pub: %w", err)
	}

	shared, err := crypto.ComputeSharedSecret(kp.PrivateKey, remotePub)
	if err != nil {
		return nil, nil, err
	}

	sessionKey, err = crypto.DeriveSessionKey(shared)
	if err != nil {
		return nil, nil, err
	}

	sessionID = crypto.ComputeSessionID(shared)
	return sessionKey, sessionID, nil
}

// ──────────────────────────────────────────────
// Pairing flow
// ──────────────────────────────────────────────

func (d *Daemon) handlePairRequest(conn net.Conn, sessionKey, data []byte) {
	if !d.isPairingActive() {
		log.Printf("pair request rejected: pairing mode not active")
		d.writeEncrypted(conn, sessionKey, protocol.PairResponseMessage{
			Type: protocol.TypePairResponse, Status: protocol.PairRejected,
		})
		return
	}

	var req protocol.PairRequestMessage
	if err := json.Unmarshal(data, &req); err != nil {
		log.Printf("parse pair request: %v", err)
		return
	}

	// Decode the phone's Ed25519 public key
	devicePub, err := crypto.DecodePublicKey(req.DevicePubKey)
	if err != nil {
		log.Printf("decode device key: %v", err)
		return
	}

	// Verify proof: phone must have signed our public key
	if !crypto.Verify(devicePub, d.pubKey, req.Proof) {
		log.Printf("invalid pairing proof from %s", req.DeviceName)
		d.writeEncrypted(conn, sessionKey, protocol.PairResponseMessage{
			Type: protocol.TypePairResponse, Status: protocol.PairRejected,
		})
		return
	}

	// Persist the device
	if err := d.devices.AddDevice(req.DeviceName, req.DevicePubKey); err != nil {
		log.Printf("save device: %v", err)
		// Still try to notify, might be a duplicate
		d.writeEncrypted(conn, sessionKey, protocol.PairResponseMessage{
			Type: protocol.TypePairResponse, Status: protocol.PairRejected,
		})
		return
	}

	d.writeEncrypted(conn, sessionKey, protocol.PairResponseMessage{
		Type: protocol.TypePairResponse, Status: protocol.PairAccepted,
	})

	log.Printf("✓ Device paired: %s", req.DeviceName)
	d.completePairing(req.DeviceName)
}

// ──────────────────────────────────────────────
// Identify + persistent auth loop
// ──────────────────────────────────────────────

func (d *Daemon) handleIdentify(conn net.Conn, sessionKey, sessionID, data []byte) {
	var msg protocol.IdentifyMessage
	if err := json.Unmarshal(data, &msg); err != nil {
		log.Printf("parse identify: %v", err)
		return
	}

	device := d.devices.FindByPublicKey(msg.DevicePubKey)
	if device == nil {
		log.Printf("unknown device key: %.20s…", msg.DevicePubKey)
		d.writeEncrypted(conn, sessionKey, protocol.IdentifyAckMessage{
			Type: protocol.TypeIdentifyAck, Status: "rejected",
		})
		return
	}

	devicePub, err := crypto.DecodePublicKey(msg.DevicePubKey)
	if err != nil {
		log.Printf("decode device key: %v", err)
		return
	}

	if !crypto.Verify(devicePub, sessionID, msg.Signature) {
		log.Printf("invalid identify proof from %s", device.DeviceName)
		d.writeEncrypted(conn, sessionKey, protocol.IdentifyAckMessage{
			Type: protocol.TypeIdentifyAck, Status: "rejected",
		})
		return
	}

	d.writeEncrypted(conn, sessionKey, protocol.IdentifyAckMessage{
		Type: protocol.TypeIdentifyAck, Status: "accepted",
	})

	phone := &PhoneConnection{
		conn:       conn,
		sessionKey: sessionKey,
		deviceKey:  devicePub,
		deviceName: device.DeviceName,
		sessionID:  sessionID,
	}

	pubKeyStr := crypto.EncodePublicKey(devicePub)
	d.registerPhone(pubKeyStr, phone)
	defer d.unregisterPhone(pubKeyStr)

	// Block here — listen for auth responses until the connection drops
	d.listenForAuthResponses(phone)
}

// listenForAuthResponses reads messages from the phone until disconnect.
func (d *Daemon) listenForAuthResponses(phone *PhoneConnection) {
	for {
		select {
		case <-d.ctx.Done():
			return
		default:
		}

		data, err := d.readEncrypted(phone.conn, phone.sessionKey)
		if err != nil {
			log.Printf("connection lost with %s: %v", phone.deviceName, err)
			return
		}

		msgType, err := protocol.ParseMessageType(data)
		if err != nil {
			log.Printf("bad message from %s: %v", phone.deviceName, err)
			continue
		}

		if msgType == protocol.TypeAuthResponse {
			var resp protocol.AuthResponseMessage
			if err := json.Unmarshal(data, &resp); err != nil {
				log.Printf("parse auth_response: %v", err)
				continue
			}
			d.handleAuthResponse(phone, &resp)
		} else {
			log.Printf("unexpected message type %q from %s", msgType, phone.deviceName)
		}
	}
}

// handleAuthResponse matches a nonce to a pending request and verifies the
// biometric signature.
func (d *Daemon) handleAuthResponse(phone *PhoneConnection, resp *protocol.AuthResponseMessage) {
	d.authMu.Lock()
	ch, ok := d.pendingAuth[resp.Nonce]
	if ok {
		delete(d.pendingAuth, resp.Nonce)
	}
	d.authMu.Unlock()

	if !ok {
		log.Printf("auth response for unknown nonce %.8s… from %s", resp.Nonce, phone.deviceName)
		return
	}

	if resp.Status != protocol.StatusApproved {
		ch <- AuthResult{Success: false, Reason: "denied by user"}
		return
	}

	// Verify the Ed25519 signature over the nonce
	if !crypto.Verify(phone.deviceKey, []byte(resp.Nonce), resp.Signature) {
		log.Printf("invalid auth signature from %s", phone.deviceName)
		ch <- AuthResult{Success: false, Reason: "invalid signature"}
		return
	}

	ch <- AuthResult{Success: true}
}

// sendAuthRequest broadcasts an auth challenge to all connected phones and
// waits for the first valid response.
func (d *Daemon) sendAuthRequest(user, service string) AuthResult {
	phones := d.getConnectedPhones()
	if len(phones) == 0 {
		return AuthResult{Success: false, Reason: "no phones connected"}
	}

	nonce := crypto.GenerateNonce()

	ch := make(chan AuthResult, 1)
	d.authMu.Lock()
	d.pendingAuth[nonce] = ch
	d.authMu.Unlock()

	defer func() {
		d.authMu.Lock()
		delete(d.pendingAuth, nonce)
		d.authMu.Unlock()
	}()

	req := protocol.AuthRequestMessage{
		Type:      protocol.TypeAuthRequest,
		Nonce:     nonce,
		Timestamp: time.Now().UnixMilli(),
		User:      user,
		Service:   service,
	}

	sent := 0
	for _, p := range phones {
		p.mu.Lock()
		err := d.writeEncrypted(p.conn, p.sessionKey, req)
		p.mu.Unlock()
		if err != nil {
			log.Printf("send auth to %s: %v", p.deviceName, err)
			continue
		}
		sent++
	}

	if sent == 0 {
		return AuthResult{Success: false, Reason: "failed to reach any phone"}
	}

	select {
	case result := <-ch:
		return result
	case <-time.After(AuthTimeout):
		return AuthResult{Success: false, Reason: "timeout"}
	case <-d.ctx.Done():
		return AuthResult{Success: false, Reason: "daemon stopping"}
	}
}

// ──────────────────────────────────────────────
// Encrypted message helpers
// ──────────────────────────────────────────────

func (d *Daemon) readEncrypted(conn net.Conn, key []byte) ([]byte, error) {
	ct, err := protocol.ReadMessage(conn)
	if err != nil {
		return nil, err
	}
	return crypto.Decrypt(key, ct)
}

func (d *Daemon) writeEncrypted(conn net.Conn, key []byte, v any) error {
	data, err := json.Marshal(v)
	if err != nil {
		return fmt.Errorf("marshal: %w", err)
	}
	ct, err := crypto.Encrypt(key, data)
	if err != nil {
		return fmt.Errorf("encrypt: %w", err)
	}
	return protocol.WriteMessage(conn, ct)
}
