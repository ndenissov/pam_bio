# PamBio

**PamBio** is a biometric authentication bridge that allows you to unlock your Linux PC or authenticate `sudo` requests using your Android smartphone's biometric sensors (fingerprint/face unlock) over your local network.

## Architecture

This repository is structured as a monorepo containing three tightly integrated components:

*   **`daemon/` (Go):** The `pambiod` service. It runs on your PC, announces itself on the local network via mDNS, manages secure TCP connections with your phone, and listens for authentication requests from the local system.
*   **`pam/` (C):** A lightweight PAM (Pluggable Authentication Module) written in pure C. It communicates with `pambiod` over a Unix domain socket to intercept and fulfill authentication requests (like `sudo` or `sddm`).
*   **`android/` (Kotlin/Compose):** The Android application. It discovers the PC on the network, maintains an encrypted connection, and displays full-screen biometric prompts when the PC requests authentication.
*   **`protocol/`**: Documentation of the JSON-based communication protocol shared across all components.

## Security

PamBio is designed with a strong focus on security:
*   **Local Network Only:** Communication happens strictly over the local network via mDNS discovery and direct TCP connections. No external servers or cloud services are involved.
*   **Perfect Forward Secrecy:** Every TCP connection begins with an X25519 ECDH key exchange to generate a unique session key.
*   **AES-256-GCM:** All communication after the handshake is encrypted and authenticated using AES-256-GCM.
*   **Ed25519 Identity:** Devices are paired using Ed25519 public keys. The Android device signs a cryptographic nonce during every authentication request to prove identity, preventing replay attacks.
*   **Minimal Attack Surface:** The PAM module (`pam_bio.so`) is written in pure C without external dependencies to guarantee stability and prevent garbage-collection pauses in critical system auth paths.

---

## Installation & Setup

### 1. NixOS (Recommended)

PamBio comes with a Nix Flake that makes installation on NixOS trivial.

1.  Add PamBio to your `flake.nix` inputs:
    ```nix
    inputs.pambio.url = "github:ndenissov/pam_bio";
    ```
2.  Import the module in your NixOS configuration and enable it:
    ```nix
    { inputs, ... }: {
      imports = [ inputs.pambio.nixosModules.default ];

      services.pambio = {
        enable = true;
        enableSudoAuth = true; # Use biometrics for sudo
        enableSddmAuth = true; # Use biometrics for SDDM login
      };
    }
    ```
3.  Rebuild your system: `sudo nixos-rebuild switch --flake .#your_host`

### 2. Debian / Ubuntu (APT)

You can install PamBio via the official APT repository:

1. Add the repository to your sources list:
   ```bash
   echo "deb [trusted=yes] https://apt.dep.ovh/ /" | sudo tee /etc/apt/sources.list.d/pambio.list
   ```
2. Update and install:
   ```bash
   sudo apt update
   sudo apt install pam-bio
   ```
3. Enable and start the daemon:
   ```bash
   sudo systemctl enable --now pambiod
   ```
   *Note: The `pam-bio` package automatically configures PAM via `pam-auth-update`.*

### 3. Manual Installation (Other Linux Distributions)

#### Prerequisites
*   Go 1.22+
*   GCC or Clang
*   PAM development headers (e.g., `libpam0g-dev` on Debian/Ubuntu)
*   Avahi daemon (for mDNS)

#### Build and Install Daemon
```bash
cd daemon
go build -o pambiod ./cmd/pambiod
sudo cp pambiod /usr/local/bin/
sudo cp packaging/pambiod/lib/systemd/system/pambiod.service /etc/systemd/system/
sudo systemctl enable --now pambiod
```

#### Build and Install PAM Module
```bash
cd pam
make
sudo make install
```

#### Configure PAM
Edit your PAM configuration files (e.g., `/etc/pam.d/sudo`) and add the following line **at the top** of the `auth` section:
```text
auth sufficient pam_bio.so
```

---

## Pairing Your Device

1.  **On your PC**, initiate the pairing process using the daemon CLI:
    ```bash
    pambiod pair
    ```
    This will display a QR code in your terminal.
2.  **On your Android device**, open the PamBio app and tap **"Добавить ПК"** (Add PC).
3.  Scan the QR code displayed on your PC screen.
4.  Once paired, ensure the service switch in the Android app is turned on.

You can now test the integration by running a command that requires authentication, such as `sudo ls`. You should receive a biometric prompt on your phone.

---

## Managing Devices

You can view the status of the daemon and connected devices:
```bash
pambiod status
```

To unpair a device from your PC:
```bash
pambiod unpair <device_name>
```
