#!/usr/bin/env bash
set -e

echo "Starting Ubuntu container for testing PamBio..."
docker run --rm -it --network host --name pambio-test ubuntu:latest /bin/bash -c "
echo '>>> Updating and installing prerequisites...'
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq >/dev/null
apt-get install -y -qq sudo curl >/dev/null 2>&1

echo '>>> Adding apt.dep.ovh repository...'
echo 'deb [trusted=yes] https://apt.dep.ovh/ /' > /etc/apt/sources.list.d/pambio.list
apt-get update -qq >/dev/null

echo '>>> Installing pam-bio...'
apt-get install -y -qq pam-bio

echo '>>> Creating a test user (tester) with sudo rights...'
useradd -m -s /bin/bash tester
usermod -aG sudo tester
echo 'tester:password' | chpasswd

echo '>>> Starting pambiod daemon in the background...'
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
