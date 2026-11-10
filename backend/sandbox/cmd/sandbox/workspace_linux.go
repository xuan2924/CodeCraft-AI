package main

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"

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
		runtime = &workspace.ExecutionRuntime{Runner: runner, Worker: worker}
		close = func() error { cancel(); runner.Wait(); return errors.Join(runner.Close(), docker.Close()) }
	}
	service, err := workspace.NewService("/var/lib/codecraft/workspaces", publications, key, runtime)
	if err != nil {
		return close, fmt.Errorf("initialize workspace storage: %w", err)
	}
	rpc.RegisterWorkspaceStorageServer(server, service)
	return close, nil
}
