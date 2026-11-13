package workspace_test

import (
	"context"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
	"time"

	executor "codecraft.local/sandbox/internal/execution"
	rpc "codecraft.local/sandbox/internal/gen"
	"codecraft.local/sandbox/internal/workspace"
	"github.com/docker/docker/client"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"
)

func TestPruningRemovesOrphanCopyAndCancelledIdentityFencesDelayedPreparation(t *testing.T) {
	engine := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		switch r.URL.Path {
		case "/v1.45/info":
			_, _ = w.Write([]byte(`{"SecurityOptions":["name=seccomp,profile=builtin"]}`))
		case "/v1.45/containers/json":
			_, _ = w.Write([]byte(`[]`))
		default:
			t.Errorf("unexpected execution side effect: %s %s", r.Method, r.URL.Path)
			w.WriteHeader(400)
		}
	}))
	defer engine.Close()
	docker, err := client.NewClientWithOpts(client.WithHost(engine.URL), client.WithVersion("1.45"))
	if err != nil {
		t.Fatal(err)
	}
	defer docker.Close()
	evidenceRoot := t.TempDir()
	runner, err := executor.New(context.Background(), docker, evidenceRoot, "fixture-volume", operation)
	if err != nil {
		t.Fatal(err)
	}
	defer func() {
		if err := runner.Close(); err != nil {
			t.Error(err)
		}
	}()
	root := t.TempDir()
	storage, close := connectRuntime(t, root, publication(), &workspace.ExecutionRuntime{Runner: runner, Worker: context.Background()})
	defer close()
	epoch := writable(t, storage)
	copy := &rpc.PrepareTestCopyRequest{WorkspaceInstanceId: instance, ExecutionInstanceId: executionIDForFinalTest, GateEpoch: epoch, NotAfterUnixMillis: time.Now().Add(time.Minute).UnixMilli()}
	if _, err = storage.PrepareTestCopy(authorized(t), copy); err != nil {
		t.Fatal(err)
	}
	identity := &rpc.QueryTaskRequest{ExerciseId: operation, TaskId: mutation, ExecutionInstanceId: executionIDForFinalTest, WorkspaceInstanceId: instance}
	receipt, err := storage.PruneTestEvidence(authorized(t), identity)
	if err != nil || !receipt.GetComplete() {
		t.Fatalf("pruning: %v %v", receipt, err)
	}
	entries, err := os.ReadDir(filepath.Join(root, instance, "copy-"+executionIDForFinalTest))
	if err != nil || len(entries) != 1 || entries[0].Name() != "released" {
		t.Fatalf("private copy remains: %v %v", entries, err)
	}
	// Equivalent durable seam to a crash after registry cancellation but before
	// publishing the copy marker. The cancellation alone must fence preparation.
	if err = os.RemoveAll(filepath.Join(root, instance, "copy-"+executionIDForFinalTest)); err != nil {
		t.Fatal(err)
	}
	if _, err = storage.PrepareTestCopy(authorized(t), copy); status.Code(err) != codes.FailedPrecondition {
		t.Fatalf("cancelled input resurrected: %v", err)
	}
	if _, err = storage.PruneTestEvidence(authorized(t), identity); err != nil {
		t.Fatalf("pruning retry: %v", err)
	}
	measured, err := storage.MeasureExerciseStorage(authorized(t), &rpc.MeasureExerciseStorageRequest{ExerciseId: operation,
		WorkspaceInstanceIds: []string{instance}, Executions: []*rpc.QueryTaskRequest{identity}})
	if err != nil {
		t.Fatal(err)
	}
	cancellation, err := os.Stat(filepath.Join(evidenceRoot, executionIDForFinalTest+".cancel"))
	if err != nil {
		t.Fatal(err)
	}
	if measured.GetBytes() != physicalBytes(t, root)+uint64(cancellation.Size()) {
		t.Fatalf("execution residual omitted: %v", measured)
	}
}
