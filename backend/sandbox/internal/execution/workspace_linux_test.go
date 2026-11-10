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
	"github.com/docker/docker/api/types/container"
	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials/insecure"
	"google.golang.org/grpc/metadata"
)

func TestGRPCStableCopyIsTheActualDockerInput(t *testing.T) {
	if os.Getenv("CODECRAFT_EXECUTION_TEST") != "1" {
		t.Skip("requires scripts/test-execution.mjs")
	}
	reference, err := os.ReadFile("/fixture/reference/src/main/java/interview/CartTotal.java")
	if err != nil {
		t.Fatal(err)
	}
	// A durable marker proves candidate code reached the barrier. Only the test
	// releases it, after the concurrent save has been acknowledged.
	old := strings.Replace(string(reference), "public final class CartTotal {", `public final class CartTotal {
 static {
   System.out.println("OLD");
   try {
     while (!java.nio.file.Files.exists(java.nio.file.Path.of("/tmp/release-input"))) { Thread.sleep(10); }
   } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
 }`, 1)
	newSource := strings.Replace(string(reference), "public final class CartTotal {", `public final class CartTotal { static { System.out.println("NEW"); }`, 1)
	runner, input, docker, _ := fixture(t, old)
	// Replace the adapter-only fixture with a real gRPC-published stable copy.
	if err := os.RemoveAll(filepath.Join("/state", input.WorkspaceID)); err != nil {
		t.Fatal(err)
	}
	key := strings.Repeat("a", 64)
	identity := &rpc.ProblemIdentity{ProblemId: "fixture", Version: "1.0.0", PackageSha256: input.PackageSHA256, ExecutionImageDigest: input.Image, Platform: input.Platform}
	service, err := workspace.NewService("/state", []workspace.Publication{{Identity: identity, InitialFiles: map[string]string{"pom.xml": "<project/>", "src/main/java/interview/CartTotal.java": old}}}, key, nil)
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
	// Wait for the persistent OLD marker, not the transient RUNNING state.
	ticker := time.NewTicker(20 * time.Millisecond)
	defer ticker.Stop()
	var executionContainer string
	for {
		observed, err := runner.Query(ctx, input.ExecutionID)
		var output strings.Builder
		for _, chunk := range observed.Logs {
			output.Write(chunk.Data)
		}
		if err == nil && strings.Contains(output.String(), "OLD") {
			executionContainer = observed.ContainerID
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
	receipt, err := client.ApplyBatch(authorized, &rpc.ApplyBatchRequest{WorkspaceInstanceId: input.WorkspaceID, RequestId: requestID, OperationId: requestID, ExpectedRevision: 0, GateEpoch: opened.Epoch, NotAfterUnixMillis: time.Now().Add(time.Minute).UnixMilli(), Changes: []*rpc.FileChange{{Path: "src/main/java/interview/CartTotal.java", Kind: "UPDATE", Content: newSource}}})
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
	// Check the container's actual mounted source after saving too: an OLD class
	// already loaded into the JVM alone would not detect a mistakenly live mount.
	release, err := docker.ContainerExecCreate(ctx, executionContainer, container.ExecOptions{User: "10001:10001", Cmd: []string{"sh", "-c",
		`grep -q '"OLD"' /input/src/main/java/interview/CartTotal.java && ! grep -q '"NEW"' /input/src/main/java/interview/CartTotal.java && touch /tmp/release-input`}})
	if err != nil {
		cancel()
		<-done
		t.Fatal(err)
	}
	if err = docker.ContainerExecStart(ctx, release.ID, container.ExecStartOptions{}); err != nil {
		cancel()
		<-done
		t.Fatal(err)
	}
	result := <-done
	var output strings.Builder
	for _, chunk := range result.result.Logs {
		output.Write(chunk.Data)
	}
	if !strings.Contains(output.String(), "OLD") || strings.Contains(output.String(), "NEW") {
		t.Fatalf("wrong version executed: %s", output.String())
	}
	if result.err != nil || result.result.State != "PASSED" || result.result.Input.Revision != 0 || !result.result.Quiescent || result.result.Cleanup != "SUCCEEDED" {
		t.Fatalf("stable input changed: state=%s revision=%d cleanup=%s err=%v", result.result.State, result.result.Input.Revision, result.result.Cleanup, result.err)
	}
	current, err := client.ReadWorkspaceFile(authorized, &rpc.ReadWorkspaceFileRequest{WorkspaceInstanceId: input.WorkspaceID, ExpectedRevision: 1, Path: "src/main/java/interview/CartTotal.java"})
	if err != nil || current.Content != newSource {
		t.Fatalf("execution changed current workspace: %v", err)
	}
}
