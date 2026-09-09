//go:build !windows

package main

func platformPrePairing() error {
	// No pre-pairing steps required on Linux
	return nil
}
