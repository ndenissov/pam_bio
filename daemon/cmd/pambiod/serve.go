//go:build !windows

package main

import (
	"log"
	"pambio/internal/daemon"
)

func cmdServe(configDir string) {
	d, err := daemon.New(configDir)
	if err != nil {
		log.Fatalf("init: %v", err)
	}
	if err := d.Start(); err != nil {
		log.Fatalf("start: %v", err)
	}
	log.Println("pambiod is running. Press Ctrl+C to stop.")
	d.Wait()
}
