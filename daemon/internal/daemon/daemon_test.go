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

package daemon

import (
	"crypto/ed25519"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"net"
	"path/filepath"
	"testing"
	"time"

	"pambio/internal/crypto"
	"pambio/internal/protocol"
)

type testPhoneClient struct {
	pubKey     ed25519.PublicKey
	privKey    ed25519.PrivateKey
	pubKeyB64  string
	deviceName string

	conn       net.Conn
	sessionKey []byte
	sessionID  []byte
}

func newTestPhone(t *testing.T, name string) *testPhoneClient {
	t.Helper()
	pub, priv, err := crypto.GenerateEd25519Keypair()
	if err != nil {
		t.Fatalf("phone keygen: %v", err)
	}
	return &testPhoneClient{
		pubKey:     pub,
		privKey:    priv,
		pubKeyB64:  crypto.EncodePublicKey(pub),
		deviceName: name,
	}
}

func (p *testPhoneClient) connectAndHandshake(t *testing.T, addr string) {
	t.Helper()
	conn, err := net.DialTimeout("tcp", addr, 3*time.Second)
	if err != nil {
		t.Fatalf("dial %s: %v", addr, err)
	}
	p.conn = conn

	kp, err := crypto.GenerateECDHKeypair()
	if err != nil {
		t.Fatal(err)
	}
	if err := protocol.WriteJSON(conn, protocol.HelloMessage{
		Type:    protocol.TypeHello,
		ECDHPub: crypto.EncodeECDHPublicKey(kp.PublicKey),
	}); err != nil {
		t.Fatal(err)
	}
	var ack protocol.HelloMessage
	if err := protocol.ReadJSON(conn, &ack); err != nil {
		t.Fatal(err)
	}
	if ack.Type != protocol.TypeHelloAck {
		t.Fatalf("expected hello_ack, got %s", ack.Type)
	}
	remotePub, err := crypto.DecodeECDHPublicKey(ack.ECDHPub)
	if err != nil {
		t.Fatal(err)
	}
	shared, err := crypto.ComputeSharedSecret(kp.PrivateKey, remotePub)
	if err != nil {
		t.Fatal(err)
	}
	key, err := crypto.DeriveSessionKey(shared)
	if err != nil {
		t.Fatal(err)
	}
	p.sessionKey = key
	p.sessionID = crypto.ComputeSessionID(shared)
}

func (p *testPhoneClient) writeEnc(t *testing.T, v any) {
	t.Helper()
	raw, err := json.Marshal(v)
	if err != nil {
		t.Fatal(err)
	}
	ct, err := crypto.Encrypt(p.sessionKey, raw)
	if err != nil {
		t.Fatal(err)
	}
	if err := protocol.WriteMessage(p.conn, ct); err != nil {
		t.Fatal(err)
	}
}

func (p *testPhoneClient) readEnc(t *testing.T, v any) {
	t.Helper()
	_ = p.conn.SetReadDeadline(time.Now().Add(5 * time.Second))
	defer func() { _ = p.conn.SetReadDeadline(time.Time{}) }()
	ct, err := protocol.ReadMessage(p.conn)
	if err != nil {
		t.Fatal(err)
	}
	pt, err := crypto.Decrypt(p.sessionKey, ct)
	if err != nil {
		t.Fatal(err)
	}
	if err := json.Unmarshal(pt, v); err != nil {
		t.Fatal(err)
	}
}

func (p *testPhoneClient) close() {
	if p.conn != nil {
		_ = p.conn.Close()
		p.conn = nil
	}
}

func startTestDaemon(t *testing.T, dir string, tcpPort int, discoveryPort int, knockPort int) *Daemon {
	t.Helper()
	t.Setenv("PAMBIO_DISABLE_MDNS", "1")
	t.Setenv("PAMBIO_PORT", fmt.Sprintf("%d", tcpPort))
	t.Setenv("PAMBIO_DISCOVERY_PORT", fmt.Sprintf("%d", discoveryPort))
	t.Setenv("PAMBIO_IPC_SOCKET", filepath.Join(dir, "pambio.sock"))

	d, err := New(dir)
	if err != nil {
		t.Fatalf("daemon.New: %v", err)
	}
	if knockPort > 0 {
		d.knockPort = knockPort
	}
	if err := d.Start(); err != nil {
		t.Fatalf("daemon.Start: %v", err)
	}
	t.Cleanup(func() { d.Stop() })
	return d
}

func TestEndToEndPairingIdentifyAuthAndRestartKnock(t *testing.T) {
	dir := t.TempDir()

	// Spin up a local UDP listener pretending to be the phone's discovery socket
	phoneUDP, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: 0})
	if err != nil {
		t.Fatalf("listen phone UDP: %v", err)
	}
	defer func() { _ = phoneUDP.Close() }()
	phoneUDPPort := phoneUDP.LocalAddr().(*net.UDPAddr).Port

	// Pick an unused UDP discovery port by temporarily probing port 0 on UDP
	probeSock, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4zero, Port: 0})
	if err != nil {
		t.Fatal(err)
	}
	udpPort := probeSock.LocalAddr().(*net.UDPAddr).Port
	_ = probeSock.Close()

	d := startTestDaemon(t, dir, 0, udpPort, phoneUDPPort)

	// 1. UDP discovery probe -> immediate announce reply
	discoverBytes, _ := json.Marshal(protocol.DiscoveryMessage{Type: protocol.TypeDiscover, Version: protocol.DiscoveryVersion})
	if _, err := phoneUDP.WriteToUDP(discoverBytes, &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: d.DiscoveryPort()}); err != nil {
		t.Fatalf("send discover: %v", err)
	}
	_ = phoneUDP.SetReadDeadline(time.Now().Add(2 * time.Second))
	buf := make([]byte, protocol.MaxDiscoveryDatagram)
	n, _, err := phoneUDP.ReadFromUDP(buf)
	if err != nil {
		t.Fatalf("read UDP announce: %v", err)
	}
	var announce protocol.DiscoveryMessage
	if err := json.Unmarshal(buf[:n], &announce); err != nil {
		t.Fatal(err)
	}
	if announce.Type != protocol.TypeAnnounce || announce.Port != d.TCPPort() || announce.PublicKey != d.cfg.PublicKey {
		t.Fatalf("unexpected UDP announce: %+v", announce)
	}

	// 2. Pair phone over TCP
	phone := newTestPhone(t, "Pixel-Test")
	pairingCh := d.startPairing()

	tcpAddr := fmt.Sprintf("127.0.0.1:%d", d.TCPPort())
	phone.connectAndHandshake(t, tcpAddr)

	pcPubBytes, _ := base64.StdEncoding.DecodeString(d.cfg.PublicKey)
	phone.writeEnc(t, protocol.PairRequestMessage{
		Type:         protocol.TypePairRequest,
		DevicePubKey: phone.pubKeyB64,
		DeviceName:   phone.deviceName,
		Proof:        crypto.Sign(phone.privKey, pcPubBytes),
	})
	var pairResp protocol.PairResponseMessage
	phone.readEnc(t, &pairResp)
	if pairResp.Status != protocol.PairAccepted {
		t.Fatalf("pair status = %s, want accepted", pairResp.Status)
	}
	phone.close()

	select {
	case res := <-pairingCh:
		if !res.Success || res.DeviceName != "Pixel-Test" {
			t.Fatalf("unexpected pairing result: %+v", res)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("pairingCh did not resolve")
	}

	// Verify peer address was recorded on disk
	devs := d.devices.GetAll()
	if len(devs) != 1 || len(devs[0].LastAddrs) == 0 || devs[0].LastAddrs[0] != "127.0.0.1" {
		t.Fatalf("expected 127.0.0.1 recorded in LastAddrs, got %+v", devs)
	}

	// 3. Reconnect via Identify and verify ServerSignature
	phone.connectAndHandshake(t, tcpAddr)
	phone.writeEnc(t, protocol.IdentifyMessage{
		Type:         protocol.TypeIdentify,
		DevicePubKey: phone.pubKeyB64,
		Signature:    crypto.Sign(phone.privKey, phone.sessionID),
	})
	var idAck protocol.IdentifyAckMessage
	phone.readEnc(t, &idAck)
	if idAck.Status != "accepted" {
		t.Fatalf("identify status = %s", idAck.Status)
	}
	expectedSigPayload := append([]byte(protocol.ServerIdentifyContext), phone.sessionID...)
	if !crypto.Verify(d.pubKey, expectedSigPayload, idAck.ServerSignature) {
		t.Fatal("server_sig in identify_ack failed cryptographic verification")
	}

	// 4. Auth approval flow
	authDone := make(chan AuthResult, 1)
	go func() {
		authDone <- d.sendAuthRequest("nikita", "sudo")
	}()

	var authReq protocol.AuthRequestMessage
	phone.readEnc(t, &authReq)
	if authReq.Type != protocol.TypeAuthRequest || authReq.User != "nikita" || authReq.Service != "sudo" {
		t.Fatalf("unexpected auth_request: %+v", authReq)
	}

	phone.writeEnc(t, protocol.AuthResponseMessage{
		Type:      protocol.TypeAuthResponse,
		Status:    protocol.StatusApproved,
		Nonce:     authReq.Nonce,
		Signature: crypto.Sign(phone.privKey, []byte(authReq.Nonce)),
	})

	select {
	case res := <-authDone:
		if !res.Success {
			t.Fatalf("expected auth success, got: %+v", res)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("sendAuthRequest timed out")
	}

	// 5. Auth denial flow
	go func() {
		authDone <- d.sendAuthRequest("nikita", "sudo")
	}()
	phone.readEnc(t, &authReq)
	phone.writeEnc(t, protocol.AuthResponseMessage{
		Type:   protocol.TypeAuthResponse,
		Status: protocol.StatusDenied,
		Nonce:  authReq.Nonce,
	})
	select {
	case res := <-authDone:
		if res.Success || res.Reason != "denied by user" {
			t.Fatalf("expected denied, got: %+v", res)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("denied sendAuthRequest timed out")
	}

	// 6. Restart daemon and verify unsolicited UDP knock arrives at phone's LastAddrs
	phone.close()
	d.Stop()

	// Drain any prior packets on phoneUDP
	_ = phoneUDP.SetReadDeadline(time.Now().Add(50 * time.Millisecond))
	for {
		if _, _, err := phoneUDP.ReadFromUDP(buf); err != nil {
			break
		}
	}

	d2 := startTestDaemon(t, dir, 0, 0, phoneUDPPort)

	_ = phoneUDP.SetReadDeadline(time.Now().Add(2 * time.Second))
	n, _, err = phoneUDP.ReadFromUDP(buf)
	if err != nil {
		t.Fatalf("expected unsolicited UDP knock on daemon restart: %v", err)
	}
	var knock protocol.DiscoveryMessage
	if err := json.Unmarshal(buf[:n], &knock); err != nil {
		t.Fatal(err)
	}
	if knock.Type != protocol.TypeAnnounce || knock.Port != d2.TCPPort() || knock.PublicKey != d2.cfg.PublicKey {
		t.Fatalf("unexpected knock packet: %+v", knock)
	}

	// Reconnect immediately to d2
	phone.connectAndHandshake(t, fmt.Sprintf("127.0.0.1:%d", d2.TCPPort()))
	phone.writeEnc(t, protocol.IdentifyMessage{
		Type:         protocol.TypeIdentify,
		DevicePubKey: phone.pubKeyB64,
		Signature:    crypto.Sign(phone.privKey, phone.sessionID),
	})
	phone.readEnc(t, &idAck)
	if idAck.Status != "accepted" {
		t.Fatalf("expected accepted after daemon restart, got %s", idAck.Status)
	}
	phone.close()
}

func TestReconnectDuringPendingAuthDeliversPrompt(t *testing.T) {
	dir := t.TempDir()

	phoneUDP, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: 0})
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = phoneUDP.Close() }()
	phoneUDPPort := phoneUDP.LocalAddr().(*net.UDPAddr).Port

	d := startTestDaemon(t, dir, 0, 0, phoneUDPPort)

	phone := newTestPhone(t, "ReconnectPhone")
	if err := d.devices.AddDevice(phone.deviceName, phone.pubKeyB64); err != nil {
		t.Fatal(err)
	}
	if err := d.devices.RecordAddr(phone.pubKeyB64, "127.0.0.1"); err != nil {
		t.Fatal(err)
	}

	// Start auth while phone is disconnected -> daemon knocks & waits up to 3s
	authDone := make(chan AuthResult, 1)
	go func() {
		authDone <- d.sendAuthRequest("alice", "sddm")
	}()

	// Wait for the knock
	_ = phoneUDP.SetReadDeadline(time.Now().Add(2 * time.Second))
	buf := make([]byte, protocol.MaxDiscoveryDatagram)
	if _, _, err := phoneUDP.ReadFromUDP(buf); err != nil {
		t.Fatalf("expected UDP knock during sendAuthRequest: %v", err)
	}

	// Now phone connects & identifies; pending auth must be delivered right away
	phone.connectAndHandshake(t, fmt.Sprintf("127.0.0.1:%d", d.TCPPort()))
	phone.writeEnc(t, protocol.IdentifyMessage{
		Type:         protocol.TypeIdentify,
		DevicePubKey: phone.pubKeyB64,
		Signature:    crypto.Sign(phone.privKey, phone.sessionID),
	})
	var idAck protocol.IdentifyAckMessage
	phone.readEnc(t, &idAck)
	if idAck.Status != "accepted" {
		t.Fatalf("identify status = %s", idAck.Status)
	}

	var authReq protocol.AuthRequestMessage
	phone.readEnc(t, &authReq)
	if authReq.User != "alice" || authReq.Service != "sddm" {
		t.Fatalf("unexpected delivered auth_request: %+v", authReq)
	}
	phone.writeEnc(t, protocol.AuthResponseMessage{
		Type:      protocol.TypeAuthResponse,
		Status:    protocol.StatusApproved,
		Nonce:     authReq.Nonce,
		Signature: crypto.Sign(phone.privKey, []byte(authReq.Nonce)),
	})

	select {
	case res := <-authDone:
		if !res.Success {
			t.Fatalf("expected auth success on reconnect, got %+v", res)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("timed out waiting for auth result")
	}
	phone.close()
}

func TestUnknownDeviceRejectedWithValidServerSig(t *testing.T) {
	dir := t.TempDir()
	d := startTestDaemon(t, dir, 0, 0, 0)

	stranger := newTestPhone(t, "Stranger")
	stranger.connectAndHandshake(t, fmt.Sprintf("127.0.0.1:%d", d.TCPPort()))
	defer stranger.close()

	stranger.writeEnc(t, protocol.IdentifyMessage{
		Type:         protocol.TypeIdentify,
		DevicePubKey: stranger.pubKeyB64,
		Signature:    crypto.Sign(stranger.privKey, stranger.sessionID),
	})
	var ack protocol.IdentifyAckMessage
	stranger.readEnc(t, &ack)
	if ack.Status != "rejected" {
		t.Fatalf("expected rejected, got %s", ack.Status)
	}
	payload := append([]byte(protocol.ServerIdentifyContext), stranger.sessionID...)
	if !crypto.Verify(d.pubKey, payload, ack.ServerSignature) {
		t.Fatal("expected valid server signature even on rejection")
	}
}

func TestMDNSInterfaceHelpers(t *testing.T) {
	if !isVirtualInterface("docker0") || !isVirtualInterface("veth12ab") || !isVirtualInterface("tailscale0") {
		t.Fatal("expected virtual interfaces to be filtered")
	}
	if isVirtualInterface("wlan0") || isVirtualInterface("enp7s0") {
		t.Fatal("physical interfaces should not be marked virtual")
	}
	ifaces, fp := interfacesWithAddrs(getValidInterfaces())
	if len(ifaces) > 0 && fp == "" {
		t.Fatal("expected non-empty fingerprint when interfaces have addrs")
	}
}
