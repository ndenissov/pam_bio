# PamBio: Protocol Messages

This document defines the JSON payloads exchanged between the Android client and the `pambiod` Linux daemon. 

## Framing

All messages over both TCP and Unix domain sockets are prefixed with a **4-byte big-endian length header** indicating the size of the following payload (either plaintext JSON or encrypted ciphertext).

## 1. ECDH Handshake (Plaintext JSON over TCP)

Before encrypted communication can begin, the client and server perform an X25519 (ECDH) key exchange.

### `hello` (Client -> Server)
```json
{
  "type": "hello",
  "ecdh_pub": "<Base64-encoded X25519 Ephemeral Public Key>"
}
```

### `hello_ack` (Server -> Client)
```json
{
  "type": "hello_ack",
  "ecdh_pub": "<Base64-encoded X25519 Ephemeral Public Key>"
}
```

*After `hello_ack`, both sides derive a 256-bit AES-GCM session key using HKDF-SHA256. All subsequent TCP messages are encrypted.*

## 2. Pairing (Encrypted JSON over TCP)

### `pair_request` (Client -> Server)
```json
{
  "type": "pair_request",
  "device_pub_key": "<Base64-encoded Ed25519 Public Key>",
  "device_name": "Pixel 7 Pro",
  "proof": "<Base64-encoded Ed25519 Signature of Server's Public Key>"
}
```

### `pair_response` (Server -> Client)
```json
{
  "type": "pair_response",
  "status": "accepted" // or "rejected"
}
```

## 3. Reconnection / Identify (Encrypted JSON over TCP)

### `identify` (Client -> Server)
```json
{
  "type": "identify",
  "device_pub_key": "<Base64-encoded Ed25519 Public Key>",
  "signature": "<Base64-encoded Ed25519 Signature of Session ID>"
}
```

### `identify_ack` (Server -> Client)
```json
{
  "type": "identify_ack",
  "status": "accepted" // or "rejected"
}
```

## 4. Authentication (Encrypted JSON over TCP)

### `auth_request` (Server -> Client)
```json
{
  "type": "auth_request",
  "nonce": "e3b0c442...",
  "timestamp": 1690000000000,
  "user": "alice",
  "service": "sudo" // e.g., "sudo", "sddm"
}
```

### `auth_response` (Client -> Server)
```json
{
  "type": "auth_response",
  "status": "approved", // or "denied"
  "nonce": "e3b0c442...",
  "signature": "<Base64-encoded Ed25519 Signature of Nonce>"
}
```

## 5. Local Communication (Plaintext JSON over Unix Socket)

These messages are used for communication between the PAM module/CLI and the `pambiod` daemon over `/var/run/pambio.sock`.

### PAM Auth Request (PAM Module -> Daemon)
```json
{
  "action": "auth_request",
  "user": "alice",
  "service": "sudo"
}
```

### PAM Auth Response (Daemon -> PAM Module)
```json
{
  "status": "success" // or "denied", "error"
}
```

## 6. LAN UDP Discovery & Wakeup Knock (UDP Port 34907)

To achieve sub-second connection and reconnection without relying solely on Android mDNS cache TTLs or Link-Local IPv6, both `pambiod` (Linux/Windows) and the Android app speak lightweight unauthenticated discovery JSON datagrams over UDP port `34907` (strictly capped at 2048 bytes per datagram; all authentication and trust still happen exclusively over the ECDH + Ed25519 TLS-free TCP session).

### `discover` (Phone -> PC LAN broadcast / cached unicast)
```json
{
  "type": "discover",
  "v": 1
}
```

### `announce` (PC -> Phone reply OR unsolicited wakeup knock on startup / network change / pending auth)
```json
{
  "type": "announce",
  "v": 1,
  "service_name": "pambio_hostname",
  "port": 34907,
  "pk": "<Base64-encoded PC Ed25519 Public Key>"
}
```

- When receiving `announce`, the phone connects directly to the UDP packet's source IPv4/IPv6 address and `port`, then authenticates the server during the encrypted TCP handshake (`identify_ack.server_sig` signs `"pambio-server-identify-v1" || session_id` with the PC's Ed25519 identity key).
