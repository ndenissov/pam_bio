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
	"crypto/aes"
	"crypto/cipher"
	"crypto/ecdh"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"fmt"
	"io"

	"golang.org/x/crypto/hkdf"
)

// ──────────────────────────────────────────────
// ECDH (X25519)
// ──────────────────────────────────────────────

// ECDHKeypair holds an ephemeral X25519 keypair used for session establishment.
type ECDHKeypair struct {
	PrivateKey *ecdh.PrivateKey
	PublicKey  *ecdh.PublicKey
}

// GenerateECDHKeypair creates a new ephemeral X25519 keypair.
func GenerateECDHKeypair() (*ECDHKeypair, error) {
	curve := ecdh.X25519()
	priv, err := curve.GenerateKey(rand.Reader)
	if err != nil {
		return nil, fmt.Errorf("ecdh keygen: %w", err)
	}
	return &ECDHKeypair{
		PrivateKey: priv,
		PublicKey:  priv.PublicKey(),
	}, nil
}

// EncodeECDHPublicKey returns the base64 encoding of an X25519 public key.
func EncodeECDHPublicKey(pub *ecdh.PublicKey) string {
	return base64.StdEncoding.EncodeToString(pub.Bytes())
}

// DecodeECDHPublicKey parses a base64-encoded X25519 public key.
func DecodeECDHPublicKey(encoded string) (*ecdh.PublicKey, error) {
	data, err := base64.StdEncoding.DecodeString(encoded)
	if err != nil {
		return nil, fmt.Errorf("decode ecdh pub: %w", err)
	}
	return ecdh.X25519().NewPublicKey(data)
}

// ComputeSharedSecret performs an X25519 Diffie-Hellman exchange.
func ComputeSharedSecret(priv *ecdh.PrivateKey, remotePub *ecdh.PublicKey) ([]byte, error) {
	secret, err := priv.ECDH(remotePub)
	if err != nil {
		return nil, fmt.Errorf("ecdh exchange: %w", err)
	}
	return secret, nil
}

// ──────────────────────────────────────────────
// Key derivation
// ──────────────────────────────────────────────

var (
	hkdfSalt = []byte("pambio-session-v1")
	hkdfInfo = []byte("aes-256-gcm-key")
	sidInfo  = []byte("pambio-session-id")
)

// DeriveSessionKey uses HKDF-SHA256 to derive a 256-bit AES key from the
// ECDH shared secret.
func DeriveSessionKey(sharedSecret []byte) ([]byte, error) {
	r := hkdf.New(sha256.New, sharedSecret, hkdfSalt, hkdfInfo)
	key := make([]byte, 32)
	if _, err := io.ReadFull(r, key); err != nil {
		return nil, fmt.Errorf("hkdf: %w", err)
	}
	return key, nil
}

// ComputeSessionID derives a unique session identifier from the shared secret.
// This is used to bind the ECDH session to Ed25519 identity proofs.
func ComputeSessionID(sharedSecret []byte) []byte {
	h := sha256.Sum256(append(sidInfo, sharedSecret...))
	return h[:]
}

// ──────────────────────────────────────────────
// AES-256-GCM
// ──────────────────────────────────────────────

// Encrypt encrypts plaintext with AES-256-GCM. The returned ciphertext has the
// random nonce prepended: nonce || ciphertext || tag.
func Encrypt(key, plaintext []byte) ([]byte, error) {
	block, err := aes.NewCipher(key)
	if err != nil {
		return nil, fmt.Errorf("aes cipher: %w", err)
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return nil, fmt.Errorf("gcm: %w", err)
	}
	nonce := make([]byte, gcm.NonceSize())
	if _, err := rand.Read(nonce); err != nil {
		return nil, fmt.Errorf("nonce: %w", err)
	}
	// Seal appends ciphertext+tag after nonce
	return gcm.Seal(nonce, nonce, plaintext, nil), nil
}

// Decrypt decrypts an AES-256-GCM ciphertext with prepended nonce.
func Decrypt(key, ciphertext []byte) ([]byte, error) {
	block, err := aes.NewCipher(key)
	if err != nil {
		return nil, fmt.Errorf("aes cipher: %w", err)
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return nil, fmt.Errorf("gcm: %w", err)
	}
	nonceSize := gcm.NonceSize()
	if len(ciphertext) < nonceSize {
		return nil, fmt.Errorf("ciphertext too short: %d < %d", len(ciphertext), nonceSize)
	}
	nonce, ct := ciphertext[:nonceSize], ciphertext[nonceSize:]
	return gcm.Open(nil, nonce, ct, nil)
}
