# PamBio

[![Go](https://img.shields.io/badge/Go-1.22-00ADD8?logo=go&logoColor=white)](https://go.dev)
[![C](https://img.shields.io/badge/C-PAM%20Module-A8B9CC?logo=c&logoColor=white)](https://en.wikipedia.org/wiki/C_(programming_language))
[![Android](https://img.shields.io/badge/Android-Compose-3DDC84?logo=android&logoColor=white)](https://developer.android.com/jetpack/compose)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![GitHub stars](https://img.shields.io/github/stars/ndenissov/pam_bio?style=social)](https://github.com/ndenissov/pam_bio/stargazers)

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

## Download Android App

The Android app is currently in preparation for a **Google Play Store** release!
While it is not yet available, you can follow my [Google Play Developer Page](https://play.google.com/store/apps/dev?id=7887460610476705873) for updates. I am also looking for volunteers to participate in the closed beta test on Google Play — if you're interested, please open an issue or reach out.

In the meantime, you can download the pre-compiled **Split APKs** from the GitHub Releases page. 
To minimize app size and improve performance, the release APKs are obfuscated and split by CPU architecture:
*   **`arm64-v8a`**: This is the architecture for **almost all modern smartphones**. If you are unsure, download this one.
*   **`armeabi-v7a`**: For older 32-bit devices.
*   **`x86` / `x86_64`**: Mostly used for Android emulators.
*(You can check your exact architecture using apps like AIDA64, or simply try installing `arm64-v8a` first.)*

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
        # port = 34907;        # Set static port (default is 34907)
        # openFirewall = true; # Open port in firewall automatically
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
4. **Firewall:** Ensure the default TCP port `34907` is open on your firewall (e.g., UFW).
   ```bash
   sudo ufw allow 34907/tcp
   ```
5. Enable and start the daemon:
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

#### Configure Firewall
By default, `pambiod` listens on TCP port `34907`. You must allow this port through your system's firewall. If you wish to use a dynamic port (by setting `port: 0` in `/etc/pambio/config.json`), you will need to allow local subnet traffic entirely.

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

---

## Watermark

By default, successful authentications via pam_bio print a subtle message in the terminal:
```
Authenticated via pam_bio (by @ndenissov)
```

To disable this on **NixOS**, set:
```nix
services.pambio.showWatermark = false;
```

On other systems, set the environment variable before starting the daemon:
```bash
export PAMBIO_SHOW_WATERMARK=0
```

Or set `"show_watermark": false` in the daemon config JSON.

---

## Hall of Fame

A big thank you to everyone who supports this project! ⭐

<!-- HOF:START -->
<p align="center">
<a href="https://github.com/ndenissov"><img src="https://avatars.githubusercontent.com/u/70575593?v=4" width="48" height="48" alt="@ndenissov" title="@ndenissov" style="border-radius:50%"></a> <a href="https://github.com/WHYCRASH"><img src="https://avatars.githubusercontent.com/u/6760226?v=4" width="48" height="48" alt="@WHYCRASH" title="@WHYCRASH" style="border-radius:50%"></a>
</p>

*2 amazing people — thank you!*
<!-- HOF:END -->

---

## Contributing

This project is in active development. Bugs are expected and any feedback is genuinely appreciated.
Please [open an issue](https://github.com/ndenissov/pam_bio/issues/new) for bug reports or feature requests.

## License

Apache License 2.0 — see [LICENSE](LICENSE) for details.
