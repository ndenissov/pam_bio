#!/usr/bin/env bash
set -e

REPO="ndenissov/pam_bio"
REPO_DIR="/var/www/apt-pambio"

echo "Fetching latest release tag for $REPO..."
LATEST_TAG=$(curl -s "https://api.github.com/repos/$REPO/releases/latest" | grep '"tag_name":' | sed -E 's/.*"([^"]+)".*/\1/')

if [ -z "$LATEST_TAG" ]; then
    echo "Error: Could not determine latest tag."
    exit 1
fi

VERSION="${LATEST_TAG#v}"
echo "Latest version is $VERSION (tag: $LATEST_TAG)"

if [ "$EUID" -ne 0 ]; then
    echo "Re-running script as sudo..."
    exec sudo "$0" "$@"
fi

mkdir -p "$REPO_DIR"

echo "Downloading packages to $REPO_DIR..."
curl -L "https://github.com/$REPO/releases/download/$LATEST_TAG/pam-bio_${VERSION}_amd64.deb" -o "$REPO_DIR/pam-bio_${VERSION}_amd64.deb"
curl -L "https://github.com/$REPO/releases/download/$LATEST_TAG/pambiod_${VERSION}_amd64.deb" -o "$REPO_DIR/pambiod_${VERSION}_amd64.deb"

echo "Updating Packages index..."
cd "$REPO_DIR"

# Since we might be running inside sudo where nix-shell behavior differs, ensure it runs correctly
if command -v nix-shell >/dev/null 2>&1; then
    nix-shell -p dpkg --run "dpkg-scanpackages . /dev/null > Packages.new && mv Packages.new Packages"
else
    dpkg-scanpackages . /dev/null > Packages.new && mv Packages.new Packages
fi

echo "Repository updated successfully to $VERSION!"
