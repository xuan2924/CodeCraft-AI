package execution_test

import (
	"context"
	"net"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	rpc "codecraft.local/sandbox/internal/gen"
	"codecraft.local/sandbox/internal/workspace"
	"google.golang.org/grpc"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/credentials/insecure"
	"google.golang.org/grpc/metadata"
	"google.golang.org/grpc/status"
	"google.golang.org/protobuf/proto"
)

func TestGRPCTestTaskSurvivesCallerCancellationAndSave(t *testing.T) {
	reference, _ := os.ReadFile("/fixture/reference/src/main/java/interview/CartTotal.java")
	initial, _ := os.ReadFile("/fixture/initial/src/main/java/interview/CartTotal.java")
	runner, input, _, _ := fixture(t, string(reference))
	if err := os.RemoveAll(filepath.Join("/state", input.WorkspaceID)); err != nil {
		t.Fatal(err)
	}
	key := strings.Repeat("a", 64)
	identity := &rpc.ProblemIdentity{ProblemId: "fixture", Version: "1.0.1", PackageSha256: input.PackageSHA256, ExecutionImageDigest: input.Image, Platform: input.Platform}
	owner, stop := context.WithCancel(context.Background())
	defer func() { stop(); runner.Wait() }()
	service, err := workspace.NewService("/state", []workspace.Publication{{Identity: identity,
		InitialFiles: map[string]string{"pom.xml": "<project/>", "src/main/java/interview/CartTotal.java": string(reference)},
		Execution:    &workspace.ExecutionPolicy{Limits: input.Limits, Suite: input.Suite, Tests: input.Tests}}}, key, &workspace.ExecutionRuntime{Runner: runner, Worker: owner})
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
	ctx, cancel := context.WithTimeout(context.Background(), time.Minute)
	defer cancel()
	ctx = metadata.AppendToOutgoingContext(ctx, "x-codecraft-internal-key", key)
	if _, err = client.PrepareWorkspace(ctx, &rpc.PrepareWorkspaceRequest{WorkspaceInstanceId: input.WorkspaceID, OperationId: identifier(), Problem: identity, NotAfterUnixMillis: time.Now().Add(time.Minute).UnixMilli()}); err != nil {
		t.Fatal(err)
	}
	transition := identifier()
	closed, err := client.CloseGate(ctx, &rpc.CloseGateRequest{WorkspaceInstanceId: input.WorkspaceID, TransitionId: transition})
	if err != nil {
		t.Fatal(err)
	}
	opened, err := client.OpenGate(ctx, &rpc.CloseGateRequest{WorkspaceInstanceId: input.WorkspaceID, TransitionId: transition, ExpectedEpoch: closed.Epoch})
	if err != nil {
		t.Fatal(err)
	}
	start := &rpc.StartTestRequest{ExerciseId: identifier(), TaskId: identifier(), ExecutionInstanceId: input.ExecutionID, WorkspaceInstanceId: input.WorkspaceID,
		Target: "base", Purpose: "MANUAL", GateEpoch: opened.Epoch, NotAfterUnixMillis: time.Now().Add(time.Minute).UnixMilli()}
	call, cancelCall := context.WithCancel(ctx)
	accepted, err := client.StartTest(call, start)
	cancelCall()
	if err != nil || accepted.State != "STARTING" {
		t.Fatalf("start: %v %v", accepted, err)
	}
	other := proto.Clone(start).(*rpc.StartTestRequest)
	other.TaskId, other.ExecutionInstanceId = identifier(), identifier()
	if _, err = client.StartTest(ctx, other); status.Code(err) != codes.ResourceExhausted {
		t.Fatalf("concurrent start queued: %v", err)
	}
	replayed, err := client.StartTest(ctx, start)
	if err != nil || replayed.ExecutionInstanceId != input.ExecutionID {
		t.Fatalf("start replay: %v", err)
	}
	changed := proto.Clone(start).(*rpc.StartTestRequest)
	changed.ExpectedRevision = 1
	if _, err = client.StartTest(ctx, changed); status.Code(err) != codes.AlreadyExists {
		t.Fatalf("identity reuse: %v", err)
	}
	operation := identifier()
	if _, err = client.ApplyBatch(ctx, &rpc.ApplyBatchRequest{WorkspaceInstanceId: input.WorkspaceID, OperationId: operation, RequestId: operation, GateEpoch: opened.Epoch,
		NotAfterUnixMillis: time.Now().Add(time.Minute).UnixMilli(), Changes: []*rpc.FileChange{{Kind: "UPDATE", Path: "src/main/java/interview/CartTotal.java", Content: string(initial)}}}); err != nil {
		t.Fatal(err)
	}
	takeover := identifier()
	closedAfterStart, err := client.CloseGate(ctx, &rpc.CloseGateRequest{WorkspaceInstanceId: input.WorkspaceID, TransitionId: takeover, ExpectedEpoch: opened.Epoch})
	if err != nil {
		t.Fatal(err)
	}
	query := &rpc.QueryTaskRequest{ExerciseId: start.ExerciseId, TaskId: start.TaskId, ExecutionInstanceId: start.ExecutionInstanceId, WorkspaceInstanceId: start.WorkspaceInstanceId}
	if _, err = client.StopTask(ctx, &rpc.StopTaskRequest{Task: query, GateEpoch: opened.Epoch, NotAfterUnixMillis: start.NotAfterUnixMillis}); status.Code(err) != codes.FailedPrecondition {
		t.Fatalf("old page stopped a task after takeover: %v", err)
	}
	wrong := proto.Clone(query).(*rpc.QueryTaskRequest)
	wrong.ExerciseId = identifier()
	if _, err = client.QueryTask(ctx, wrong); status.Code(err) != codes.NotFound {
		t.Fatalf("cross-exercise task: %v", err)
	}
	for {
		result, err := client.QueryTask(ctx, query)
		if err != nil {
			t.Fatal(err)
		}
		if result.CopyCleanup == "SUCCEEDED" {
			if result.State != "PASSED" || result.Revision != 0 || !result.Quiescent || result.Cleanup != "SUCCEEDED" || !proto.Equal(result.Problem, identity) {
				t.Fatalf("incorrect terminal: %v", result)
			}
			break
		}
		select {
		case <-ctx.Done():
			t.Fatal(ctx.Err())
		case <-time.After(50 * time.Millisecond):
		}
	}
	if _, err = os.Stat(filepath.Join("/state", input.Subpath)); !os.IsNotExist(err) {
		t.Fatalf("copy not removed: %v", err)
	}
	if _, err = client.PrepareTestCopy(ctx, &rpc.PrepareTestCopyRequest{WorkspaceInstanceId: input.WorkspaceID, ExecutionInstanceId: input.ExecutionID, GateEpoch: opened.Epoch, NotAfterUnixMillis: start.NotAfterUnixMillis}); err == nil {
		t.Fatal("released input recreated")
	}
	if replayed, err = client.StartTest(ctx, start); err != nil || replayed.State != "PASSED" {
		t.Fatalf("terminal replay lost: %v", err)
	}
	openedAgain, err := client.OpenGate(ctx, &rpc.CloseGateRequest{WorkspaceInstanceId: input.WorkspaceID, TransitionId: takeover, ExpectedEpoch: closedAfterStart.Epoch})
	if err != nil {
		t.Fatal(err)
	}
	completedStop, err := client.StopTask(ctx, &rpc.StopTaskRequest{Task: query, GateEpoch: openedAgain.Epoch, NotAfterUnixMillis: start.NotAfterUnixMillis})
	if err != nil || completedStop.GetSnapshot().GetState() != "PASSED" {
		t.Fatalf("stop overwrote natural completion: %v %v", completedStop, err)
	}
	reusedTask := proto.Clone(start).(*rpc.StartTestRequest)
	reusedTask.ExecutionInstanceId, reusedTask.ExpectedRevision, reusedTask.GateEpoch = identifier(), 1, openedAgain.Epoch
	if _, err = client.StartTest(ctx, reusedTask); status.Code(err) != codes.AlreadyExists {
		t.Fatalf("same task reused for a new execution: %v", err)
	}
}
