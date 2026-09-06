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
            version = "0.1.0";
            src = ./daemon;
            vendorHash = null; # FIXME: set after first successful build with deps
            subPackages = [ "cmd/pambiod" ];
            meta = {
              description = "PamBio daemon — biometric auth relay";
              license = pkgs.lib.licenses.mit;
              mainProgram = "pambiod";
            };
          };

          pam_bio = pkgs.stdenv.mkDerivation {
            pname = "pam_bio";
            version = "0.1.0";
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
              license = pkgs.lib.licenses.mit;
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
          };

          config = lib.mkIf cfg.enable {
            # Install pambiod + pam_bio.so
            environment.systemPackages = [ pambioPkgs.pambiod ];

            # Systemd service
            systemd.services.pambiod = {
              description = "PamBio Daemon";
              after = [ "network.target" "avahi-daemon.service" ];
              wants = [ "avahi-daemon.service" ];
              wantedBy = [ "multi-user.target" ];

              serviceConfig = {
                Type = "simple";
                ExecStart = "${pambioPkgs.pambiod}/bin/pambiod serve";
                Environment = "PAMBIO_CONFIG_DIR=${cfg.configDir}";
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
              })
            ];
          };
        };
    };
}
