package main

import (
	"fmt"
	"log"
	"net"
	"os"
	"os/signal"
	"strconv"
	"syscall"

	"codecraft.local/sandbox/internal/status"
)

func main() {
	if err := run(); err != nil {
		log.Fatal(err)
	}
}

func run() error {
	port := os.Getenv("SANDBOX_PORT")
	if port == "" {
		port = "9090"
	}
	number, err := strconv.Atoi(port)
	if err != nil || number < 1 || number > 65535 {
		return fmt.Errorf("SANDBOX_PORT must be an integer from 1 to 65535")
	}
	// Deliberately not configurable: this unauthenticated tracer is local-only.
	listener, err := net.Listen("tcp", net.JoinHostPort("127.0.0.1", port))
	if err != nil {
		return fmt.Errorf("listen: %w", err)
	}
	server := status.NewServer()
	stop := make(chan os.Signal, 1)
	signal.Notify(stop, os.Interrupt, syscall.SIGTERM)
	defer signal.Stop(stop)
	done := make(chan error, 1)
	go func() { done <- server.Serve(listener) }()
	log.Print("sandbox liveness listening on loopback; execution is not implemented")
	select {
	case err := <-done:
		server.Stop()
		return err
	case <-stop:
		server.Stop()
		return <-done
	}
}
