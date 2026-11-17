package main

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"strconv"
	"time"

	"codecraft.local/sandbox/internal/security"
	"github.com/docker/docker/api/types/network"

	"codecraft.local/sandbox/internal/execution"
	rpc "codecraft.local/sandbox/internal/gen"
	"codecraft.local/sandbox/internal/workspace"
	"github.com/docker/docker/client"
	"google.golang.org/grpc"
)

func registerWorkspace(server *grpc.Server, key, volume string) (func() error, error) {
	close := func() error { return nil }
	file, err := os.Open("/publications.json")
	if err != nil {
		return close, fmt.Errorf("open published inputs: %w", err)
	}
	defer file.Close()
	data, err := io.ReadAll(io.LimitReader(file, 1024*1024+1))
	if err != nil {
		return close, fmt.Errorf("read published inputs: %w", err)
	}
	if len(data) > 1024*1024 {
		return close, fmt.Errorf("published input artifact exceeds limit")
	}
	decoder := json.NewDecoder(bytes.NewReader(data))
	decoder.DisallowUnknownFields()
	var publications []workspace.Publication
	if err := decoder.Decode(&publications); err != nil {
		return close, fmt.Errorf("invalid published input artifact")
	}
	if err := decoder.Decode(new(any)); err != io.EOF {
		return close, fmt.Errorf("trailing published input data")
	}
	if len(publications) == 0 {
		return close, nil
	} // Explicit status-only installation, not workspace readiness.
	var runtime *workspace.ExecutionRuntime
	if volume != "" {
		docker, err := client.NewClientWithOpts(client.WithHost("unix:///docker.sock"), client.WithAPIVersionNegotiation())
		if err != nil {
			return close, err
		}
		owner := sha256.Sum256([]byte("codecraft-execution:" + volume))
		ownerID := fmt.Sprintf("%x-%x-%x-%x-%x", owner[:4], owner[4:6], owner[6:8], owner[8:10], owner[10:16])
		worker, cancel := context.WithCancel(context.Background())
		runner, err := execution.NewWorkspaceRunner(worker, docker, "/var/lib/codecraft/executions", volume, ownerID)
		if err != nil {
			cancel()
			return close, errors.Join(err, docker.Close())
		}
		close = func() error { cancel(); runner.Wait(); return errors.Join(runner.Close(), docker.Close()) }
		concurrency := 4
		if value := os.Getenv("SANDBOX_EXECUTION_CONCURRENCY"); value != "" {
			concurrency, err = strconv.Atoi(value)
			if err != nil {
				return close, fmt.Errorf("invalid execution concurrency: %w", err)
			}
		}
		commandNetwork, proxyURL := os.Getenv("SANDBOX_COMMAND_NETWORK"), os.Getenv("SANDBOX_EGRESS_PROXY_URL")
		if err = runner.Configure(concurrency, commandNetwork, proxyURL); err != nil {
			return close, err
		}
		if commandNetwork != "" {
			rpcBind := net.ParseIP(os.Getenv("SANDBOX_RPC_BIND"))
			if rpcBind == nil || rpcBind.IsUnspecified() || os.Getenv("SANDBOX_EGRESS_LISTEN") == "" {
				return close, errors.New("egress requires an explicit RPC interface and proxy listener")
			}
			coordinator, hostErr := os.Hostname()
			if hostErr != nil {
				return close, hostErr
			}
			setup, stopSetup := context.WithTimeout(worker, 15*time.Second)
			setupErr := runner.ConfigureEgress(setup, coordinator)
			stopSetup()
			if setupErr != nil {
				return close, setupErr
			}
			check, stop := context.WithTimeout(worker, 5*time.Second)
			inspected, inspectErr := docker.NetworkInspect(check, commandNetwork, network.InspectOptions{})
			stop()
			if inspectErr != nil || !inspected.Internal || inspected.Driver != "bridge" {
				return close, errors.New("command network must be an internal Docker bridge")
			}
			allowlist := os.Getenv("SANDBOX_EGRESS_ALLOWLIST")
			if allowlist == "" {
				allowlist = security.DefaultAllowlist
			}
			proxy, proxyErr := security.NewProxy(allowlist)
			if proxyErr != nil {
				return close, proxyErr
			}
			listener, listenErr := net.Listen("tcp", os.Getenv("SANDBOX_EGRESS_LISTEN"))
			if listenErr != nil {
				return close, listenErr
			}
			server := &http.Server{Handler: proxy, ReadHeaderTimeout: 5 * time.Second, ReadTimeout: 30 * time.Second, WriteTimeout: 5 * time.Minute, IdleTimeout: 30 * time.Second, MaxHeaderBytes: 16384,
				BaseContext: func(net.Listener) context.Context { return worker }}
			done := make(chan struct{})
			go func() { defer func() { done <- struct{}{} }(); _ = server.Serve(listener); cancel() }()
			closeRunner := close
			close = func() error {
				cancel()
				serverErr := server.Close()
				<-done
				return errors.Join(serverErr, closeRunner())
			}
		}
		runtime = &workspace.ExecutionRuntime{Runner: runner, Worker: worker}
	}
	service, err := workspace.NewService("/var/lib/codecraft/workspaces", publications, key, runtime)
	if err != nil {
		return close, fmt.Errorf("initialize workspace storage: %w", err)
	}
	rpc.RegisterWorkspaceStorageServer(server, service)
	return close, nil
}
