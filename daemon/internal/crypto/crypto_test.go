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

package crypto

import (
	"bytes"
	"testing"
)

func TestEd25519KeypairSignVerify(t *testing.T) {
	pub, priv, err := GenerateEd25519Keypair()
	if err != nil {
		t.Fatalf("GenerateEd25519Keypair: %v", err)
	}

	pubEnc := EncodePublicKey(pub)
	privEnc := EncodePrivateKey(priv)

	pubDec, err := DecodePublicKey(pubEnc)
	if err != nil {
		t.Fatalf("DecodePublicKey: %v", err)
	}
	privDec, err := DecodePrivateKey(privEnc)
	if err != nil {
		t.Fatalf("DecodePrivateKey: %v", err)
	}

	msg := []byte("pambio-auth-challenge-nonce")
	sig := Sign(privDec, msg)
	if !Verify(pubDec, msg, sig) {
		t.Fatal("Verify failed for valid signature")
	}

	if Verify(pubDec, []byte("tampered"), sig) {
		t.Fatal("Verify succeeded for tampered message")
	}
	if Verify(pubDec, msg, "not-valid-base64!") {
		t.Fatal("Verify succeeded for invalid base64")
	}

	otherPub, _, err := GenerateEd25519Keypair()
	if err != nil {
		t.Fatalf("GenerateEd25519Keypair(2): %v", err)
	}
	if Verify(otherPub, msg, sig) {
		t.Fatal("Verify succeeded with wrong public key")
	}
}

func TestECDHAndAESGCMRoundTrip(t *testing.T) {
	kpA, err := GenerateECDHKeypair()
	if err != nil {
		t.Fatalf("GenerateECDHKeypair A: %v", err)
	}
	kpB, err := GenerateECDHKeypair()
	if err != nil {
		t.Fatalf("GenerateECDHKeypair B: %v", err)
	}

	pubA, err := DecodeECDHPublicKey(EncodeECDHPublicKey(kpA.PublicKey))
	if err != nil {
		t.Fatalf("DecodeECDHPublicKey A: %v", err)
	}
	pubB, err := DecodeECDHPublicKey(EncodeECDHPublicKey(kpB.PublicKey))
	if err != nil {
		t.Fatalf("DecodeECDHPublicKey B: %v", err)
	}

	secA, err := ComputeSharedSecret(kpA.PrivateKey, pubB)
	if err != nil {
		t.Fatalf("ComputeSharedSecret A: %v", err)
	}
	secB, err := ComputeSharedSecret(kpB.PrivateKey, pubA)
	if err != nil {
		t.Fatalf("ComputeSharedSecret B: %v", err)
	}
	if !bytes.Equal(secA, secB) {
		t.Fatal("ECDH shared secrets differ")
	}

	keyA, err := DeriveSessionKey(secA)
	if err != nil {
		t.Fatalf("DeriveSessionKey A: %v", err)
	}
	keyB, err := DeriveSessionKey(secB)
	if err != nil {
		t.Fatalf("DeriveSessionKey B: %v", err)
	}
	if !bytes.Equal(keyA, keyB) || len(keyA) != 32 {
		t.Fatal("Derived session keys mismatch or wrong length")
	}

	sidA := ComputeSessionID(secA)
	sidB := ComputeSessionID(secB)
	if !bytes.Equal(sidA, sidB) || len(sidA) != 32 {
		t.Fatal("Session IDs mismatch")
	}

	plaintext := []byte(`{"type":"auth_request","nonce":"abc"}`)
	ct, err := Encrypt(keyA, plaintext)
	if err != nil {
		t.Fatalf("Encrypt: %v", err)
	}
	dec, err := Decrypt(keyB, ct)
	if err != nil {
		t.Fatalf("Decrypt: %v", err)
	}
	if !bytes.Equal(dec, plaintext) {
		t.Fatalf("Decrypt got %q, want %q", dec, plaintext)
	}

	// Tampering ciphertext must fail GCM authentication
	ct[len(ct)-1] ^= 0xff
	if _, err := Decrypt(keyB, ct); err == nil {
		t.Fatal("Decrypt succeeded on tampered ciphertext")
	}
	if _, err := Decrypt(keyB, []byte{1, 2, 3}); err == nil {
		t.Fatal("Decrypt succeeded on short ciphertext")
	}
}

func TestGenerateNonceUniqueness(t *testing.T) {
	seen := make(map[string]struct{}, 100)
	for i := 0; i < 100; i++ {
		n := GenerateNonce()
		if len(n) != 36 {
			t.Fatalf("expected 36-char UUID nonce, got %d (%q)", len(n), n)
		}
		if _, dup := seen[n]; dup {
			t.Fatalf("duplicate nonce: %s", n)
		}
		seen[n] = struct{}{}
	}
}
