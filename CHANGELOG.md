# Changelog

All notable changes to this project will be documented in this file.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.31.7]
* fix: handle biometric enrollments removal causing app crash on startup

## [0.31.6]
* fix: address remaining implicit Intent warning for BiometricAuthActivity

## [0.31.5]
* fix: migrate crypto to AES/GCM and use explicit intents for CodeQL compliance

## [0.31.4]
* fix: reliable service restart action
* feat: improved permission flows (notifications, full-screen alerts, camera)

## [0.31.3]
* fix: initial resolution of CodeQL security alerts (cryptography, intents)

## [0.31.0]
* feat(android): add GitHub release update checker UI

## [0.30.0]
* fix: handle null crypto signatures to prevent application crash during network requests
* feat: add dynamic Approve button to foreground service persistent notification

## [0.29.0]
* feat: persistent background service and UI tweaks
* fix: build signing issue and update github review UX
* chore: update Hall of Fame [skip ci]

## [0.28.0]
* feat: google play readiness and split apk builds
* fix: restore accidentally truncated files
* feat(android): add Settings screen and fix history sync
* chore: UX follow-ups for viral features
* feat: add PAM watermark, GitHub star UX, Hall of Fame

## [0.27.0]
* docs: add firewall instructions for linux distributions
* fix: change debian PAM config from success=ok to sufficient
* feat: add port option to NixOS module and support PAMBIO_PORT env variable
* chore: bump version to 0.27.0 based on commit count

## [0.26.0]
* fix: use wildcard for unsigned apk in release action

## [0.25.0]
* fix: create missing directories in build-deb.sh and update crypto

## [0.24.0]
* chore: Refactor for distribution (Debian, Nix), update translations and About screen

## [0.23.0]
* fix: remove pambio_ prefix from computer name everywhere

## [0.22.0]
* remove --out parameter from pair command

## [0.21.0]
* refactor: compact QR code and binary pairing payload

## [0.20.0]
* fix: separate notification channels and improve home screen device list UI

## [0.19.0]
* fix: resolve biometric launch crash and home screen ui layout issue

## [0.18.0]
* feat: biometric prompt improvements, instant fallback, and history logging

## [0.17.0]
* Fix mDNS discovery and Android 12 foreground service crash

## [0.16.0]
* Allow non-root PAM clients (sddm) while restricting pairing via SO_PEERCRED

## [0.15.0]
* Fix Unix socket permissions and remove broken network broadcast listener

## [0.14.0]
* Fix mDNS broadcasting and implement TCP heartbeats

## [0.13.0]
* Show exact connection error on UI

## [0.12.0]
* Fix connection stability and pairing

## [0.11.0]
* feat: various cosmetic and behavioral improvements

## [0.10.0]
* feat: alternative pairing methods and UI fixes for missing camera permission

## [0.9.0]
* fix: BiometricAuthActivity imports syntax error

## [0.8.0]
* feat: rename to PAM Bio, fix locale switcher, add camera permission revoke dialog

## [0.7.0]
* feat: i18n support, fix UI service toggle, clean up notifications

## [0.6.0]
* feat: add pam_bio to kde screen locker PAM config

## [0.5.0]
* fix: set activeTcpClient for auth response and prevent instant close on BiometricPrompt cancel

## [0.4.0]
* fix: enable encodeDefaults in Json to send type fields correctly

## [0.3.0]
* fix: change BiometricAuthActivity base class to FragmentActivity for BiometricPrompt compatibility

## [0.2.0]
* chore: sync go vendor

## [0.1.0]
* Initial commit: PamBio monorepo structure with complete implementation
