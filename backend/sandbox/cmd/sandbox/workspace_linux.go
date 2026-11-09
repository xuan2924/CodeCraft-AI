package main

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"os"

	rpc "codecraft.local/sandbox/internal/gen"
	"codecraft.local/sandbox/internal/workspace"
	"google.golang.org/grpc"
)

func registerWorkspace(server *grpc.Server, key string) error {
	file, err := os.Open("/publications.json")
	if err != nil {
		return fmt.Errorf("open published inputs: %w", err)
	}
	defer file.Close()
	data, err := io.ReadAll(io.LimitReader(file, 1024*1024+1))
	if err != nil {
		return fmt.Errorf("read published inputs: %w", err)
	}
	if len(data) > 1024*1024 {
		return fmt.Errorf("published input artifact exceeds limit")
	}
	decoder := json.NewDecoder(bytes.NewReader(data))
	decoder.DisallowUnknownFields()
	var publications []workspace.Publication
	if err := decoder.Decode(&publications); err != nil {
		return fmt.Errorf("invalid published input artifact")
	}
	if err := decoder.Decode(new(any)); err != io.EOF {
		return fmt.Errorf("trailing published input data")
	}
	if len(publications) == 0 {
		return nil
	} // Explicit status-only installation, not workspace readiness.
	service, err := workspace.NewService("/var/lib/codecraft/workspaces", publications, key)
	if err != nil {
		return fmt.Errorf("initialize workspace storage: %w", err)
	}
	rpc.RegisterWorkspaceStorageServer(server, service)
	return nil
}
