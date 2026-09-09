//go:build windows

package main

import (
	"log"
	"pambio/internal/daemon"

	"golang.org/x/sys/windows/svc"
)

type pambioService struct {
	d *daemon.Daemon
}

func (m *pambioService) Execute(args []string, r <-chan svc.ChangeRequest, changes chan<- svc.Status) (ssec bool, errno uint32) {
	const cmdsAccepted = svc.AcceptStop | svc.AcceptShutdown
	changes <- svc.Status{State: svc.StartPending}

	if err := m.d.Start(); err != nil {
		log.Printf("start: %v", err)
		return
	}
	
	changes <- svc.Status{State: svc.Running, Accepts: cmdsAccepted}
	log.Println("pambiod is running as a Windows Service.")

	for {
		select {
		case c := <-r:
			switch c.Cmd {
			case svc.Interrogate:
				changes <- c.CurrentStatus
			case svc.Stop, svc.Shutdown:
				changes <- svc.Status{State: svc.StopPending}
				m.d.Stop()
				return
			default:
				log.Printf("unexpected control request #%d", c)
			}
		}
	}
}

func cmdServe(configDir string) {
	d, err := daemon.New(configDir)
	if err != nil {
		log.Fatalf("init: %v", err)
	}

	isInteractive, err := svc.IsAnInteractiveSession()
	if err != nil {
		log.Fatalf("failed to determine if session is interactive: %v", err)
	}

	if isInteractive {
		// Run normally in console
		if err := d.Start(); err != nil {
			log.Fatalf("start: %v", err)
		}
		log.Println("pambiod is running. Press Ctrl+C to stop.")
		d.Wait()
		return
	}

	// Run as Windows Service
	if err := svc.Run("pambiod", &pambioService{d}); err != nil {
		log.Fatalf("service execution failed: %v", err)
	}
}
