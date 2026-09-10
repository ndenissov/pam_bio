#!/usr/bin/env bash
set -e

echo "Starting Ubuntu container for testing PamBio..."
docker run --rm -it --network host --name pambio-test ubuntu:latest /bin/bash -c "
echo '>>> Updating and installing prerequisites...'
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq >/dev/null
apt-get install -y -qq sudo curl gpg ca-certificates >/dev/null 2>&1

echo '>>> Adding GitHub Pages APT repository...'
curl -fsSL https://ndenissov.github.io/pam_bio/apt/public.key | gpg --dearmor -o /usr/share/keyrings/pambio-archive-keyring.gpg
echo 'deb [signed-by=/usr/share/keyrings/pambio-archive-keyring.gpg] https://ndenissov.github.io/pam_bio/apt stable main' > /etc/apt/sources.list.d/pambio.list
apt-get update -qq >/dev/null

echo '>>> Installing pam-bio...'
apt-get install -y -qq pam-bio

echo '>>> Creating a test user (tester) with sudo rights...'
useradd -m -s /bin/bash tester
usermod -aG sudo tester
echo 'tester:password' | chpasswd

echo '>>> Starting pambiod daemon in the background...'
export PAMBIO_PORT=34907
pambiod serve &
sleep 2

echo '==================================================='
echo 'Environment is ready!'
echo 'To test, run the following commands manually:'
echo '1. pambiod pair       # To pair your phone'
echo '2. su - tester        # Switch to the test user'
echo '3. sudo ls            # Trigger a sudo command (should prompt on phone)'
echo '==================================================='
exec /bin/bash
"
