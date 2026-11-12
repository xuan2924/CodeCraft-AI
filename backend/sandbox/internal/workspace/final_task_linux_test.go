package workspace_test

import (
	"context"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	executor "codecraft.local/sandbox/internal/execution"
	rpc "codecraft.local/sandbox/internal/gen"
	"codecraft.local/sandbox/internal/workspace"
	"github.com/docker/docker/client"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"
	"google.golang.org/protobuf/proto"
)

func TestSystemStopRequiresInternalAuthentication(t *testing.T) {
	storage, close := connect(t, t.TempDir())
	defer close()
	if _, err := storage.StopSystemTask(context.Background(), &rpc.QueryTaskRequest{}); status.Code(err) != codes.Unauthenticated {
		t.Fatalf("unauthorized lifecycle stop: %v", err)
	}
}

func TestFinalTaskAdmitsFrozenInputWithoutOpeningAnswerGate(t *testing.T) {
	// Inventory succeeds; the Engine substitute rejects creation. No real
	// container is created; uncertain creation remains isolated as INTERRUPTED.
	engine := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		switch {
		case r.URL.Path == "/v1.45/info":
			_, _ = w.Write([]byte(`{"SecurityOptions":["name=seccomp,profile=builtin"]}`))
		case r.URL.Path == "/v1.45/containers/json":
			_, _ = w.Write([]byte(`[]`))
		case r.Method == http.MethodPost && r.URL.Path == "/v1.45/containers/create":
			w.WriteHeader(http.StatusServiceUnavailable)
			_, _ = w.Write([]byte(`{"message":"fixture engine unavailable"}`))
		default:
			t.Errorf("unexpected execution side effect: %s %s", r.Method, r.URL.Path)
			w.WriteHeader(http.StatusBadRequest)
		}
	}))
	defer engine.Close()
	docker, err := client.NewClientWithOpts(client.WithHost(engine.URL), client.WithVersion("1.45"))
	if err != nil {
		t.Fatal(err)
	}
	defer docker.Close()
	runner, err := executor.New(context.Background(), docker, t.TempDir(), "fixture-volume", operation)
	if err != nil {
		t.Fatal(err)
	}
	worker, cancel := context.WithCancel(context.Background())
	defer func() {
		cancel()
		runner.Wait()
		if err := runner.Close(); err != nil {
			t.Error(err)
		}
	}()
	published := publication()
	published.Execution = &workspace.ExecutionPolicy{Limits: executor.Limits{CPUs: 1, MemoryMiB: 64, PIDs: 16, WorkMiB: 1, TmpMiB: 1, ShmMiB: 1, TimeoutSeconds: 1, LogBytes: 1024}, Suite: "interview.Test", Tests: []string{"example"}}
	storage, close := connectRuntime(t, t.TempDir(), published, &workspace.ExecutionRuntime{Runner: runner, Worker: worker})
	defer close()
	epoch := writable(t, storage)
	closed, err := storage.CloseGate(authorized(t), &rpc.CloseGateRequest{WorkspaceInstanceId: instance, TransitionId: nextTransition, ExpectedEpoch: epoch})
	if err != nil {
		t.Fatal(err)
	}
	frozen, err := storage.Freeze(authorized(t), &rpc.CloseGateRequest{WorkspaceInstanceId: instance, TransitionId: nextTransition, ExpectedEpoch: closed.Epoch})
	if err != nil {
		t.Fatal(err)
	}
	deadline := time.Now().Add(time.Minute).UnixMilli()
	request := &rpc.StartTestRequest{ExerciseId: operation, TaskId: mutation, ExecutionInstanceId: executionIDForFinalTest, WorkspaceInstanceId: instance,
		Target: "base", Purpose: "FINAL_VERIFICATION", GateEpoch: frozen.Epoch, ExpectedRevision: frozen.FrozenRevision, HostEpoch: runner.HostEpoch(), NotAfterUnixMillis: deadline,
		FinalVerification: &rpc.FinalVerificationAuthority{FinalizationId: operation, AttemptId: mutation, FreezeOperationId: nextTransition, DeadlineUnixMillis: deadline}}
	for _, alter := range []func(*rpc.StartTestRequest){
		func(input *rpc.StartTestRequest) { input.GateEpoch = epoch },
		func(input *rpc.StartTestRequest) { input.ExpectedRevision++ },
		func(input *rpc.StartTestRequest) { input.FinalVerification.FreezeOperationId = operation },
		func(input *rpc.StartTestRequest) { input.NotAfterUnixMillis = time.Now().Add(-time.Minute).UnixMilli() },
		func(input *rpc.StartTestRequest) { input.Purpose = "MANUAL"; input.FinalVerification = nil },
	} {
		invalid := proto.Clone(request).(*rpc.StartTestRequest)
		alter(invalid)
		if _, err := storage.StartTest(authorized(t), invalid); status.Code(err) != codes.FailedPrecondition {
			t.Fatalf("invalid frozen admission: %v", err)
		}
	}
	result, err := storage.StartTest(authorized(t), request)
	if err != nil || result.TaskId != mutation {
		t.Fatalf("final task admission: %v %v", result, err)
	}
	runner.Wait()
	queried, err := storage.QueryTask(authorized(t), &rpc.QueryTaskRequest{ExerciseId: operation, TaskId: mutation, ExecutionInstanceId: executionIDForFinalTest, WorkspaceInstanceId: instance})
	if err != nil || queried.State != "INTERRUPTED" || queried.Quiescent {
		t.Fatalf("environment result: %v %v", queried, err)
	}
	stopIdentity := &rpc.QueryTaskRequest{ExerciseId: operation, TaskId: transition, ExecutionInstanceId: nextTransition, WorkspaceInstanceId: instance}
	stopped, err := storage.StopSystemTask(authorized(t), stopIdentity)
	if err != nil || !stopped.CancelledBeforeRegistration || stopped.HostEpoch != runner.HostEpoch() {
		t.Fatalf("system cancellation: %v %v", stopped, err)
	}
	receipt, err := storage.QueryStop(authorized(t), stopIdentity)
	if err != nil || !receipt.CancelledBeforeRegistration || receipt.HostEpoch != runner.HostEpoch() {
		t.Fatalf("durable cancellation: %v %v", receipt, err)
	}
	request.FinalVerification.AttemptId = nextTransition
	if _, err := storage.StartTest(authorized(t), request); status.Code(err) != codes.AlreadyExists {
		t.Fatalf("changed attempt reused execution: %v", err)
	}
	gate, err := storage.QueryGate(authorized(t), &rpc.QueryWorkspaceRequest{WorkspaceInstanceId: instance})
	if err != nil || gate.State != "FROZEN" {
		t.Fatalf("answer gate reopened: %v %v", gate, err)
	}
}

const executionIDForFinalTest = "66666666-6666-4666-8666-666666666666"
