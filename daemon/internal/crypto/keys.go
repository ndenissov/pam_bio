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


// Package crypto provides Ed25519 identity keys and AES-256-GCM session
// encryption for the pambio protocol.
package crypto

import (
	"crypto/ed25519"
	"crypto/rand"
	"encoding/base64"
	"fmt"

	"github.com/google/uuid"
)

// ──────────────────────────────────────────────
// Ed25519 key management
// ──────────────────────────────────────────────

// GenerateEd25519Keypair creates a new random Ed25519 signing keypair.
func GenerateEd25519Keypair() (ed25519.PublicKey, ed25519.PrivateKey, error) {
	pub, priv, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		return nil, nil, fmt.Errorf("ed25519 keygen: %w", err)
	}
	return pub, priv, nil
}

// EncodePublicKey returns the base64 (standard, no padding) representation of
// an Ed25519 public key.
func EncodePublicKey(pub ed25519.PublicKey) string {
	return base64.StdEncoding.EncodeToString(pub)
}

// DecodePublicKey parses a base64-encoded Ed25519 public key.
func DecodePublicKey(encoded string) (ed25519.PublicKey, error) {
	data, err := base64.StdEncoding.DecodeString(encoded)
	if err != nil {
		return nil, fmt.Errorf("decode public key: %w", err)
	}
	if len(data) != ed25519.PublicKeySize {
		return nil, fmt.Errorf("invalid public key length: got %d, want %d", len(data), ed25519.PublicKeySize)
	}
	return ed25519.PublicKey(data), nil
}

// EncodePrivateKey encodes the 32-byte seed of an Ed25519 private key.
func EncodePrivateKey(priv ed25519.PrivateKey) string {
	return base64.StdEncoding.EncodeToString(priv.Seed())
}

// DecodePrivateKey reconstructs an Ed25519 private key from its base64 seed.
func DecodePrivateKey(encoded string) (ed25519.PrivateKey, error) {
	seed, err := base64.StdEncoding.DecodeString(encoded)
	if err != nil {
		return nil, fmt.Errorf("decode private key seed: %w", err)
	}
	if len(seed) != ed25519.SeedSize {
		return nil, fmt.Errorf("invalid seed length: got %d, want %d", len(seed), ed25519.SeedSize)
	}
	return ed25519.NewKeyFromSeed(seed), nil
}

// ──────────────────────────────────────────────
// Signing & verification
// ──────────────────────────────────────────────

// Sign produces a base64-encoded Ed25519 signature of message.
func Sign(priv ed25519.PrivateKey, message []byte) string {
	sig := ed25519.Sign(priv, message)
	return base64.StdEncoding.EncodeToString(sig)
}

// Verify checks a base64-encoded Ed25519 signature against message.
func Verify(pub ed25519.PublicKey, message []byte, signatureB64 string) bool {
	sig, err := base64.StdEncoding.DecodeString(signatureB64)
	if err != nil {
		return false
	}
	return ed25519.Verify(pub, message, sig)
}

// ──────────────────────────────────────────────
// Nonce generation
// ──────────────────────────────────────────────

// GenerateNonce returns a random UUID string suitable for auth challenges.
func GenerateNonce() string {
	return uuid.New().String()
}
