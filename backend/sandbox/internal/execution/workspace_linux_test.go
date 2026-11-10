package execution_test

import (
	"context"
	"net"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"codecraft.local/sandbox/internal/execution"
	rpc "codecraft.local/sandbox/internal/gen"
	"codecraft.local/sandbox/internal/workspace"
	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials/insecure"
	"google.golang.org/grpc/metadata"
)

func TestGRPCStableCopyIsTheActualDockerInput(t *testing.T) {
	reference, _ := os.ReadFile("/fixture/reference/src/main/java/interview/CartTotal.java")
	initial, _ := os.ReadFile("/fixture/initial/src/main/java/interview/CartTotal.java")
	runner, input, _, _ := fixture(t, string(reference))
	// Replace the adapter-only fixture with a real gRPC-published stable copy.
	if err := os.RemoveAll(filepath.Join("/state", input.WorkspaceID)); err != nil {
		t.Fatal(err)
	}
	key := strings.Repeat("a", 64)
	identity := &rpc.ProblemIdentity{ProblemId: "fixture", Version: "1.0.0", PackageSha256: input.PackageSHA256, ExecutionImageDigest: input.Image, Platform: input.Platform}
	service, err := workspace.NewService("/state", []workspace.Publication{{Identity: identity, InitialFiles: map[string]string{"pom.xml": "<project/>", "src/main/java/interview/CartTotal.java": string(reference)}}}, key)
	if err != nil {
		t.Fatal(err)
	}
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	server := grpc.NewServer()
	rpc.RegisterWorkspaceStorageServer(server, service)
	serving := make(chan error, 1)
	go func() { serving <- server.Serve(listener) }()
	defer func() { server.Stop(); <-serving }()
	connection, err := grpc.NewClient(listener.Addr().String(), grpc.WithTransportCredentials(insecure.NewCredentials()))
	if err != nil {
		t.Fatal(err)
	}
	defer connection.Close()
	client := rpc.NewWorkspaceStorageClient(connection)
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()
	authorized := metadata.AppendToOutgoingContext(ctx, "x-codecraft-internal-key", key)
	if _, err = client.PrepareWorkspace(authorized, &rpc.PrepareWorkspaceRequest{WorkspaceInstanceId: input.WorkspaceID, OperationId: identifier(), Problem: identity, NotAfterUnixMillis: time.Now().Add(time.Minute).UnixMilli()}); err != nil {
		t.Fatal(err)
	}
	transition := identifier()
	closed, err := client.CloseGate(authorized, &rpc.CloseGateRequest{WorkspaceInstanceId: input.WorkspaceID, TransitionId: transition})
	if err != nil {
		t.Fatal(err)
	}
	opened, err := client.OpenGate(authorized, &rpc.CloseGateRequest{WorkspaceInstanceId: input.WorkspaceID, TransitionId: transition, ExpectedEpoch: closed.Epoch})
	if err != nil {
		t.Fatal(err)
	}
	copied, err := client.PrepareTestCopy(authorized, &rpc.PrepareTestCopyRequest{WorkspaceInstanceId: input.WorkspaceID, ExecutionInstanceId: input.ExecutionID, GateEpoch: opened.Epoch, NotAfterUnixMillis: time.Now().Add(time.Minute).UnixMilli()})
	if err != nil {
		t.Fatal(err)
	}
	input.Subpath = copied.RelativePath
	input.Revision = copied.Revision
	type outcome struct {
		result execution.Result
		err    error
	}
	done := make(chan outcome, 1)
	go func() { result, err := runner.Run(ctx, input); done <- outcome{result, err} }()
	// Observe public durable task state, then save while the real container runs.
	ticker := time.NewTicker(20 * time.Millisecond)
	defer ticker.Stop()
	for {
		observed, err := runner.Query(ctx, input.ExecutionID)
		if err == nil && observed.State == "RUNNING" {
			break
		}
		select {
		case <-ctx.Done():
			<-done
			t.Fatal(ctx.Err())
		case result := <-done:
			t.Fatalf("run ended before save: %s %v", result.result.State, result.err)
		case <-ticker.C:
		}
	}
	requestID := identifier()
	receipt, err := client.ApplyBatch(authorized, &rpc.ApplyBatchRequest{WorkspaceInstanceId: input.WorkspaceID, RequestId: requestID, OperationId: requestID, ExpectedRevision: 0, GateEpoch: opened.Epoch, NotAfterUnixMillis: time.Now().Add(time.Minute).UnixMilli(), Changes: []*rpc.FileChange{{Path: "src/main/java/interview/CartTotal.java", Kind: "UPDATE", Content: string(initial)}}})
	if err != nil {
		cancel()
		<-done
		t.Fatal(err)
	}
	if receipt.Revision != 1 {
		cancel()
		<-done
		t.Fatalf("save did not advance revision: %v", receipt)
	}
	result := <-done
	if result.err != nil || result.result.State != "PASSED" || result.result.Input.Revision != 0 || !result.result.Quiescent || result.result.Cleanup != "SUCCEEDED" {
		t.Fatalf("stable input changed: state=%s revision=%d cleanup=%s err=%v", result.result.State, result.result.Input.Revision, result.result.Cleanup, result.err)
	}
	current, err := client.ReadWorkspaceFile(authorized, &rpc.ReadWorkspaceFileRequest{WorkspaceInstanceId: input.WorkspaceID, ExpectedRevision: 1, Path: "src/main/java/interview/CartTotal.java"})
	if err != nil || current.Content != string(initial) {
		t.Fatalf("execution changed current workspace: %v", err)
	}
}
