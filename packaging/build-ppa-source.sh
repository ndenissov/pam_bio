#!/usr/bin/env bash
set -e

VERSION="${1:-${GITHUB_REF_NAME#v}}"
VERSION="${VERSION:-0.33.3}"
GPG_KEY_ID="4D1D6F0A172CFDF7873816F325C9B7D23C38E819"

echo "=== Preparing Debian package structure for PPA ($VERSION) ==="

# Clean old debian directory if present
rm -rf debian

mkdir -p debian/source

# 1. debian/source/format
echo "3.0 (native)" > debian/source/format

# 2. debian/control
cat <<EOF > debian/control
Source: pam-bio
Section: admin
Priority: optional
Maintainer: Nikita Denissov <nikita@dep.ovh>
Build-Depends: debhelper-compat (= 13), libpam0g-dev, golang-go (>= 1.22~) | golang-1.22
Standards-Version: 4.6.2
Homepage: https://github.com/ndenissov/pam_bio

Package: pambiod
Architecture: any
Depends: \${shlibs:Depends}, \${misc:Depends}
Description: PamBio daemon — biometric auth relay
 The daemon component for the PamBio biometric authentication system.
 Relays PAM authentication requests to paired smartphones via mDNS and TCP.

Package: pam-bio
Architecture: any
Depends: pambiod (>= 0.33.0), libpam0g (>= 1.1.8-3.1~), \${shlibs:Depends}, \${misc:Depends}
Description: PamBio PAM module
 PAM module that delegates authentication to the PamBio daemon,
 enabling smartphone biometric unlock for Linux.
EOF

# 3. debian/rules
cat <<'EOF' > debian/rules
#!/usr/bin/make -f
%:
	dh $@

override_dh_auto_build:
	cd daemon && go build -o ../pambiod ./cmd/pambiod
	cd pam && make

override_dh_auto_install:
	install -D -m 0755 pambiod debian/pambiod/usr/bin/pambiod
	install -D -m 0644 packaging/pambiod/lib/systemd/system/pambiod.service debian/pambiod/lib/systemd/system/pambiod.service
	install -D -m 0755 pam/pam_bio.so debian/pam-bio/usr/lib/security/pam_bio.so
	install -D -m 0644 packaging/pam-bio/usr/share/pam-configs/pam-bio debian/pam-bio/usr/share/pam-configs/pam-bio

override_dh_auto_test:
	# Tests skipped for packaging
EOF

chmod +x debian/rules

# 4. debian/changelog (Native format uses VERSION without revision)
DATE_STR=$(date -R)
cat <<EOF > debian/changelog
pam-bio (${VERSION}) noble; urgency=medium

  * Release version ${VERSION}

 -- Nikita Denissov <nikita@dep.ovh>  ${DATE_STR}
EOF

# 5. Maintainer scripts for pam-bio
cat <<'EOF' > debian/pam-bio.postinst
#!/bin/sh
set -e

if [ "$1" = "configure" ]; then
    pam-auth-update --package
fi

exit 0
EOF
chmod 755 debian/pam-bio.postinst

cat <<'EOF' > debian/pam-bio.prerm
#!/bin/sh
set -e

if [ "$1" = "remove" ]; then
    pam-auth-update --package --remove pam-bio
fi

exit 0
EOF
chmod 755 debian/pam-bio.prerm

echo "=== Building Source Package ==="
# Import GPG key if provided in env
if [ -n "$GPG_PRIVATE_KEY" ]; then
    echo "Importing GPG private key..."
    echo "$GPG_PRIVATE_KEY" | base64 -d 2>/dev/null | gpg --batch --import 2>/dev/null || \
    echo "$GPG_PRIVATE_KEY" | gpg --batch --import 2>/dev/null || true
fi

# Build source package without checking dependencies (-d)
dpkg-buildpackage -S -d -k"$GPG_KEY_ID" || debuild -S -sa -d -k"$GPG_KEY_ID"

echo "=== Uploading to Launchpad PPA ==="
CHANGES_FILE="../pam-bio_${VERSION}_source.changes"
if [ ! -f "$CHANGES_FILE" ]; then
    CHANGES_FILE="../pam-bio_${VERSION}-1_source.changes"
fi

if [ -f "$CHANGES_FILE" ]; then
    dput -f ppa:ndenissov/ppa "$CHANGES_FILE"
    echo "PPA upload completed successfully!"
else
    echo "ERROR: Source changes file $CHANGES_FILE not found!" >&2
    exit 1
fi
