package main

import (
	"flag"
	"fmt"
	"log"
	"net"
	"os"
	"os/signal"
	"runtime"
	"strconv"
	"syscall"

	"codecraft.local/sandbox/internal/status"
	"codecraft.local/sandbox/internal/workspace"
	"google.golang.org/grpc"
)

func main() {
	containerMode := flag.Bool("container", false, "listen inside a Linux container; publish only on host loopback")
	flag.Parse()
	if err := run(*containerMode); err != nil {
		log.Fatal(err)
	}
}

func run(containerMode bool) error {
	port := os.Getenv("SANDBOX_PORT")
	if port == "" {
		port = "9090"
	}
	number, err := strconv.Atoi(port)
	if err != nil || number < 1 || number > 65535 {
		return fmt.Errorf("SANDBOX_PORT must be an integer from 1 to 65535")
	}
	host := "127.0.0.1"
	if containerMode {
		if runtime.GOOS != "linux" {
			return fmt.Errorf("container mode requires Linux")
		}
		storage, err := acquireStorage()
		if err != nil {
			return err
		}
		defer storage.Close()
		// Docker publishes this container port only on host loopback.
		host = "0.0.0.0"
	}
	listener, err := net.Listen("tcp", net.JoinHostPort(host, port))
	if err != nil {
		return fmt.Errorf("listen: %w", err)
	}
	defer listener.Close()
	var options []grpc.ServerOption
	if containerMode {
		options = append(options, grpc.MaxRecvMsgSize(workspace.MaxBatchRequestBytes))
	}
	server := status.NewServer(options...)
	if containerMode {
		if err := registerWorkspace(server, os.Getenv("SANDBOX_RPC_KEY")); err != nil {
			return err
		}
	}
	stop := make(chan os.Signal, 1)
	signal.Notify(stop, os.Interrupt, syscall.SIGTERM)
	defer signal.Stop(stop)
	done := make(chan error, 1)
	go func() { done <- server.Serve(listener) }()
	log.Print("sandbox liveness listening; workspace and execution readiness are not certified")
	select {
	case err := <-done:
		server.Stop()
		return err
	case <-stop:
		server.Stop()
		return <-done
	}
}
