#!/usr/bin/env bash
set -e

echo "Building Windows Go Daemon (pambiod.exe)..."
cd daemon
GOOS=windows GOARCH=amd64 go build -o pambiod.exe ./cmd/pambiod
cd ..

echo "Building Windows Credential Provider (pambio_cp.dll)..."
mkdir -p windows_cp/build
cd windows_cp/build
cmake -DCMAKE_SYSTEM_NAME=Windows -DCMAKE_C_COMPILER=x86_64-w64-mingw32-gcc -DCMAKE_CXX_COMPILER=x86_64-w64-mingw32-g++ ..
cmake --build .
cd ../..

echo "Done! The files are ready:"
ls -lh daemon/pambiod.exe windows_cp/build/pambio_cp.dll
