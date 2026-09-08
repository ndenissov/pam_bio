#!/usr/bin/env bash
set -e

VERSION="0.31.8"
ARCH=$(dpkg --print-architecture)
BUILD_DIR="packaging"

echo "Creating directories..."
mkdir -p $BUILD_DIR/pambiod/DEBIAN $BUILD_DIR/pambiod/usr/bin $BUILD_DIR/pambiod/lib/systemd/system
mkdir -p $BUILD_DIR/pam-bio/DEBIAN $BUILD_DIR/pam-bio/usr/lib/security $BUILD_DIR/pam-bio/usr/share/pam-configs

echo "Building pambiod..."
cd daemon
go build -o ../$BUILD_DIR/pambiod/usr/bin/pambiod ./cmd/pambiod
cd ..

echo "Building pam_bio.so..."
cd pam
make
cp pam_bio.so ../$BUILD_DIR/pam-bio/usr/lib/security/
cd ..

echo "Fixing permissions..."
chmod 755 $BUILD_DIR/pambiod/DEBIAN/control
chmod 755 $BUILD_DIR/pambiod/usr/bin/pambiod
chmod 644 $BUILD_DIR/pambiod/lib/systemd/system/pambiod.service
chmod 755 $BUILD_DIR/pam-bio/DEBIAN/control
chmod 755 $BUILD_DIR/pam-bio/DEBIAN/postinst
chmod 755 $BUILD_DIR/pam-bio/DEBIAN/prerm
chmod 644 $BUILD_DIR/pam-bio/usr/share/pam-configs/pam-bio
chmod 755 $BUILD_DIR/pam-bio/usr/lib/security/pam_bio.so

echo "Building deb packages..."
dpkg-deb --build $BUILD_DIR/pambiod pambiod_${VERSION}_${ARCH}.deb
dpkg-deb --build $BUILD_DIR/pam-bio pam-bio_${VERSION}_${ARCH}.deb

echo "Done!"
