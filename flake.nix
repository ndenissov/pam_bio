# Copyright 2026 Nikita Denissov
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

{
  description = "PamBio — unlock your Linux PC with smartphone biometrics";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = { self, nixpkgs, flake-utils }:
    let
      supportedSystems = [ "x86_64-linux" "aarch64-linux" ];
    in
    flake-utils.lib.eachSystem supportedSystems (system:
      let
        pkgs = nixpkgs.legacyPackages.${system};
      in
      {
        # ── Packages ────────────────────────────────────────────
        packages = rec {
          pambiod = pkgs.buildGoModule {
            pname = "pambiod";
            version = "0.28.0";
            src = ./daemon;
            vendorHash = "sha256-eARV70icujfRhxKmorKg5jz1f81fL9pUk/RPGp1/yeQ=";
            subPackages = [ "cmd/pambiod" ];
            meta = {
              description = "PamBio daemon — biometric auth relay";
              license = pkgs.lib.licenses.asl20;
              mainProgram = "pambiod";
            };
          };

          pam_bio = pkgs.stdenv.mkDerivation {
            pname = "pam-bio";
            version = "0.28.0";
            src = ./pam;

            buildInputs = [ pkgs.pam ];
            nativeBuildInputs = [ pkgs.gcc ];

            buildPhase = ''
              make PAM_DIR=${pkgs.pam}
            '';

            installPhase = ''
              mkdir -p $out/lib/security
              cp pam_bio.so $out/lib/security/
            '';

            meta = {
              description = "PamBio PAM module";
              license = pkgs.lib.licenses.asl20;
            };
          };

          default = pambiod;
        };

        # ── Dev shell ───────────────────────────────────────────
        devShells.default = pkgs.mkShell {
          buildInputs = with pkgs; [
            go
            gcc
            pam
            avahi
            pkg-config
          ];
          shellHook = ''
            echo "PamBio dev shell — Go $(go version | cut -d' ' -f3), GCC $(gcc -dumpversion)"
          '';
        };
      }
    )
    //
    {
      # ── NixOS module ────────────────────────────────────────
      nixosModules.default = { config, lib, pkgs, ... }:
        let
          cfg = config.services.pambio;
          pambioPkgs = self.packages.${pkgs.system};
        in
        {
          options.services.pambio = {
            enable = lib.mkEnableOption "PamBio biometric authentication service";

            configDir = lib.mkOption {
              type = lib.types.path;
              default = "/etc/pambio";
              description = "Directory for PamBio configuration and keys.";
            };

            enableSudoAuth = lib.mkOption {
              type = lib.types.bool;
              default = true;
              description = "Add pam_bio to sudo PAM stack.";
            };

            enableSddmAuth = lib.mkOption {
              type = lib.types.bool;
              default = false;
              description = "Add pam_bio to SDDM PAM stack.";
            };

            port = lib.mkOption {
              type = lib.types.port;
              default = 34907;
              description = "TCP port for pambiod to listen on. Set to 0 to allow the OS to assign a random port (useful for multiple instances, but requires manual firewall configuration).";
            };

            openFirewall = lib.mkOption {
              type = lib.types.bool;
              default = true;
              description = "Whether to open the configured port in the firewall (only if port is not 0).";
            };

            showWatermark = lib.mkOption {
              type = lib.types.bool;
              default = true;
              description = "Whether to display 'Authenticated via pam_bio' in the terminal when unlocking.";
            };
          };

          config = lib.mkIf cfg.enable {
            # Install pambiod + pam_bio.so
            environment.systemPackages = [ pambioPkgs.pambiod ];

            # Open firewall port if static port is chosen and openFirewall is true
            networking.firewall.allowedTCPPorts = lib.mkIf (cfg.port != 0 && cfg.openFirewall) [ cfg.port ];

            # Systemd service
            systemd.services.pambiod = {
              description = "PamBio Daemon";
              after = [ "network.target" "avahi-daemon.service" ];
              wants = [ "avahi-daemon.service" ];
              wantedBy = [ "multi-user.target" ];

              serviceConfig = {
                Type = "simple";
                ExecStart = "${pambioPkgs.pambiod}/bin/pambiod serve";
                Environment = [
                  "PAMBIO_CONFIG_DIR=${cfg.configDir}"
                  "PAMBIO_PORT=${toString cfg.port}"
                  "PAMBIO_SHOW_WATERMARK=${if cfg.showWatermark then "1" else "0"}"
                ];
                Restart = "on-failure";
                RestartSec = 5;

                # Security hardening
                NoNewPrivileges = true;
                ProtectSystem = "strict";
                ProtectHome = true;
                ReadWritePaths = [ cfg.configDir "/var/run" ];
                PrivateTmp = true;
              };
            };

            # Ensure config directory exists
            systemd.tmpfiles.rules = [
              "d ${cfg.configDir} 0700 root root -"
            ];

            # Avahi must be enabled for mDNS
            services.avahi = {
              enable = true;
              nssmdns4 = true;
              publish = {
                enable = true;
                userServices = true;
              };
            };

            # PAM integration
            security.pam.services = lib.mkMerge [
              (lib.mkIf cfg.enableSudoAuth {
                sudo.rules.auth.pambio = {
                  order = 100;   # before unix password
                  control = "sufficient";
                  modulePath = "${pambioPkgs.pam_bio}/lib/security/pam_bio.so";
                };
              })
              (lib.mkIf cfg.enableSddmAuth {
                sddm.rules.auth.pambio = {
                  order = 100;
                  control = "sufficient";
                  modulePath = "${pambioPkgs.pam_bio}/lib/security/pam_bio.so";
                };
                kde.rules.auth.pambio = {
                  order = 100;
                  control = "sufficient";
                  modulePath = "${pambioPkgs.pam_bio}/lib/security/pam_bio.so";
                };
              })
            ];
          };
        };
    };
}
