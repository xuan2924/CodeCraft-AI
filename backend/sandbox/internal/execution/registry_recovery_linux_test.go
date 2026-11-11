package execution_test

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"codecraft.local/sandbox/internal/execution"
	"github.com/docker/docker/client"
)

// This Engine substitute exposes inventory only. Unexpected execution or cleanup
// is rejected: recovery tests cannot accidentally launch candidate code.
func registryEngine(t *testing.T, containers any, missingContainers ...string) *client.Client {
	t.Helper()
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		switch r.URL.Path {
		case "/v1.45/info":
			_, _ = w.Write([]byte(`{"SecurityOptions":["name=seccomp,profile=builtin"]}`))
		case "/v1.45/containers/json":
			if err := json.NewEncoder(w).Encode(containers); err != nil {
				t.Error(err)
			}
		default:
			for _, id := range missingContainers {
				if r.Method == http.MethodGet && r.URL.Path == "/v1.45/containers/"+id+"/json" {
					w.WriteHeader(http.StatusNotFound)
					_, _ = w.Write([]byte(`{"message":"no such container"}`))
					return
				}
			}
			t.Errorf("unexpected Engine side effect: %s %s", r.Method, r.URL.Path)
			http.Error(w, "unexpected operation", http.StatusBadRequest)
		}
	}))
	t.Cleanup(server.Close)
	engine, err := client.NewClientWithOpts(client.WithHost(server.URL), client.WithVersion("1.45"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = engine.Close() })
	return engine
}

func recoveryInput() execution.Input {
	workspace, id := identifier(), identifier()
	return execution.Input{ExecutionID: id, WorkspaceID: workspace,
		Subpath: workspace + "/copy-" + id + "/tree", Revision: 1,
		Image: "sha256:" + strings.Repeat("a", 64), PackageSHA256: strings.Repeat("b", 64),
		Platform: "linux/amd64", Target: "base", NotAfter: time.Date(2030, 1, 1, 0, 0, 0, 0, time.UTC),
		Suite: "interview.Test", Tests: []string{"example"},
		Limits: execution.Limits{CPUs: 1, MemoryMiB: 64, PIDs: 16, WorkMiB: 1, TmpMiB: 1, ShmMiB: 1, TimeoutSeconds: 1, LogBytes: 1024}}
}

func writeRegistryFixture(t *testing.T, root, name string, value any) {
	t.Helper()
	data, err := json.Marshal(value)
	if err != nil {
		t.Fatal(err)
	}
	if err = os.WriteFile(filepath.Join(root, name), data, 0600); err != nil {
		t.Fatal(err)
	}
}

func TestRegistryRecoveryRetriesCopyCleanupWithoutChangingTerminalOutcome(t *testing.T) {
	root, input, owner := t.TempDir(), recoveryInput(), identifier()
	input.TaskID, input.ExerciseID = identifier(), identifier()
	input.ProblemID, input.ProblemVersion = "cart-total", "1.0.1"
	now := time.Date(2026, 1, 1, 0, 0, 0, 0, time.UTC)
	containerID := strings.Repeat("c", 64)
	writeRegistryFixture(t, root, input.ExecutionID+".json", execution.Result{
		Input: input, State: "STOPPED", StartedAt: now, FinishedAt: &now, Version: 1,
		ContainerID: containerID, Quiescent: true, Cleanup: "SUCCEEDED", CopyCleanup: "FAILED"})
	engine := registryEngine(t, []any{}, containerID)
	for attempt := 0; attempt < 2; attempt++ {
		runner, err := execution.New(context.Background(), engine, root, "fixture-volume", owner)
		if err != nil {
			t.Fatal(err)
		}
		err = runner.Recover(context.Background(), input.ExecutionID, func(_ context.Context, result execution.Result) error {
			if result.Input.ExecutionID != input.ExecutionID || !result.Quiescent {
				t.Error("cleanup lacks exact execution ownership and quiescence")
			}
			if attempt == 0 {
				return errors.New("injected storage failure")
			}
			return nil
		})
		if err != nil {
			t.Fatal(err)
		}
		runner.Wait()
		result, err := runner.Query(context.Background(), input.ExecutionID)
		want := "FAILED"
		if attempt == 1 {
			want = "SUCCEEDED"
		}
		if err != nil || result.State != "STOPPED" || !result.Quiescent || result.CopyCleanup != want {
			t.Fatalf("cleanup retry changed outcome or lost progress: %+v err=%v", result, err)
		}
		if err = runner.Close(); err != nil {
			t.Fatal(err)
		}
	}
}

func TestRegistryRecoveryCancellationSurvivesRestartAndRejectsLateStart(t *testing.T) {
	root, owner := t.TempDir(), identifier()
	engine := registryEngine(t, []any{})
	input := recoveryInput()
	input.TaskID, input.ExerciseID = identifier(), identifier()
	input.ProblemID, input.ProblemVersion = "cart-total", "1.0.1"
	identity := execution.TaskIdentity{ExerciseID: input.ExerciseID, TaskID: input.TaskID,
		ExecutionID: input.ExecutionID, WorkspaceID: input.WorkspaceID}
	runner, err := execution.New(context.Background(), engine, root, "fixture-volume", owner)
	if err != nil {
		t.Fatal(err)
	}
	if result, err := runner.Stop(context.Background(), identity); err != nil || result != nil {
		t.Fatalf("cancel before registration: result=%v error=%v", result, err)
	}
	if err = runner.Close(); err != nil {
		t.Fatal(err)
	}
	runner, err = execution.New(context.Background(), engine, root, "fixture-volume", owner)
	if err != nil {
		t.Fatal(err)
	}
	defer runner.Close()
	if _, cancelled, err := runner.QueryStop(context.Background(), identity); err != nil || !cancelled {
		t.Fatalf("lost cancellation receipt: cancelled=%v error=%v", cancelled, err)
	}
	if _, err = runner.Run(context.Background(), input); !errors.Is(err, execution.ErrCancelled) {
		t.Fatalf("late dispatch escaped cancellation: %v", err)
	}
}

func TestRegistryRecoveryPreservesEarlierTerminalOrTimeoutWhenStopped(t *testing.T) {
	for _, state := range []string{"RUNNING", "STOPPING", "ENVIRONMENT_ERROR"} {
		t.Run(state, func(t *testing.T) {
			root, input := t.TempDir(), recoveryInput()
			input.TaskID, input.ExerciseID = identifier(), identifier()
			input.ProblemID, input.ProblemVersion = "cart-total", "1.0.1"
			now := time.Date(2026, 1, 1, 0, 0, 0, 0, time.UTC)
			record := execution.Result{Input: input, State: state, StartedAt: now, Version: 1,
				Cleanup: "PENDING", CopyCleanup: "PENDING"}
			if state == "STOPPING" {
				record.Intent = "TIMED_OUT"
			}
			if state == "ENVIRONMENT_ERROR" {
				record.FinishedAt = &now
			}
			writeRegistryFixture(t, root, input.ExecutionID+".json", record)
			runner, err := execution.New(context.Background(), registryEngine(t, []any{}), root, "fixture-volume", identifier())
			if err != nil {
				t.Fatal(err)
			}
			defer runner.Close()
			result, err := runner.Stop(context.Background(), execution.TaskIdentity{ExerciseID: input.ExerciseID,
				TaskID: input.TaskID, ExecutionID: input.ExecutionID, WorkspaceID: input.WorkspaceID})
			if err != nil {
				t.Fatal(err)
			}
			wantState, wantIntent := "STOPPING", "STOPPED"
			if state == "STOPPING" {
				wantIntent = "TIMED_OUT"
			} else if state == "ENVIRONMENT_ERROR" {
				wantState, wantIntent = state, ""
			}
			if result.State != wantState || result.Intent != wantIntent || result.Quiescent {
				t.Fatalf("stop changed committed ordering or invented exit: %+v", result)
			}
		})
	}
}

func TestRegistryRecoveryFencesPreviousHostWithoutStartingWork(t *testing.T) {
	root, owner := t.TempDir(), identifier()
	engine := registryEngine(t, []any{})
	first, err := execution.New(context.Background(), engine, root, "fixture-volume", owner)
	if err != nil {
		t.Fatal(err)
	}
	previous := first.HostEpoch()
	if err = first.Close(); err != nil {
		t.Fatal(err)
	}
	second, err := execution.New(context.Background(), engine, root, "fixture-volume", owner)
	if err != nil {
		t.Fatal(err)
	}
	defer second.Close()
	if previous == "" || previous == second.HostEpoch() {
		t.Fatal("restart reused dispatch generation")
	}
	input := recoveryInput()
	input.HostEpoch = previous
	if _, err = second.Run(context.Background(), input); !errors.Is(err, execution.ErrConflict) {
		t.Fatalf("old dispatch was not fenced: %v", err)
	}
	if _, err = second.Query(context.Background(), input.ExecutionID); !errors.Is(err, os.ErrNotExist) {
		t.Fatalf("rejected dispatch created a registration: %v", err)
	}
}

func TestRegistryRecoveryKeepsMissingAndDamagedResultsIsolated(t *testing.T) {
	for _, damage := range []string{"missing", "corrupt"} {
		t.Run(damage, func(t *testing.T) {
			root, input := t.TempDir(), recoveryInput()
			started := time.Date(2026, 1, 1, 0, 0, 0, 0, time.UTC)
			writeRegistryFixture(t, root, input.ExecutionID+".meta", map[string]any{
				"input": input, "startedAt": started, "containerId": "", "version": 8})
			if damage == "corrupt" {
				writeRegistryFixture(t, root, input.ExecutionID+".json", "broken")
			}
			runner, err := execution.New(context.Background(), registryEngine(t, []any{}), root, "fixture-volume", identifier())
			if err != nil {
				t.Fatal(err)
			}
			defer runner.Close()
			result, err := runner.Query(context.Background(), input.ExecutionID)
			if err != nil || result.State != "INTERRUPTED" || result.Quiescent || result.Version != 9 || !result.LogsIncomplete {
				t.Fatalf("lost result became trustworthy execution evidence: result=%+v err=%v", result, err)
			}
			if _, err = runner.Run(context.Background(), recoveryInput()); !errors.Is(err, execution.ErrBusy) {
				t.Fatalf("unconfirmed execution released capacity: %v", err)
			}
		})
	}
}

func TestRegistryRecoveryRejectsAmbiguousOrphanReceipt(t *testing.T) {
	input, owner := recoveryInput(), identifier()
	input.HostEpoch = "previous-host"
	started := time.Date(2026, 1, 1, 0, 0, 0, 0, time.UTC)
	receipt, err := json.Marshal(map[string]any{"input": input, "startedAt": started, "version": 1})
	if err != nil {
		t.Fatal(err)
	}
	engine := registryEngine(t, []map[string]any{{"Id": strings.Repeat("c", 64), "ImageID": input.Image,
		"Labels": map[string]string{
			"codecraft.execution.id": input.ExecutionID, "codecraft.execution.owner": owner,
			"codecraft.workspace.id": input.WorkspaceID, "codecraft.host.epoch": input.HostEpoch,
			"codecraft.execution.receipt": string(receipt) + `{ "unexpected": true }`,
		}}})
	runner, err := execution.New(context.Background(), engine, t.TempDir(), "fixture-volume", owner)
	if err == nil {
		_ = runner.Close()
		t.Fatal("ambiguous owner evidence was accepted for reconstruction")
	}
}

func TestRegistryRecoveryRetainsContainerCreatedBeforeIdentityWasPersisted(t *testing.T) {
	root, owner := t.TempDir(), identifier()
	input := recoveryInput()
	started := time.Date(2026, 1, 1, 0, 0, 0, 0, time.UTC)
	containerID := strings.Repeat("c", 64)
	receipt := map[string]any{"input": input, "startedAt": started, "containerId": "", "version": 1}
	writeRegistryFixture(t, root, input.ExecutionID+".json", execution.Result{
		Input: input, State: "STARTING", StartedAt: started, Version: 1, Cleanup: "PENDING"})
	writeRegistryFixture(t, root, input.ExecutionID+".meta", receipt)
	encoded, err := json.Marshal(receipt)
	if err != nil {
		t.Fatal(err)
	}
	engine := registryEngine(t, []map[string]any{{"Id": containerID, "ImageID": input.Image, "Labels": map[string]string{
		"codecraft.execution.id": input.ExecutionID, "codecraft.execution.owner": owner,
		"codecraft.workspace.id": input.WorkspaceID, "codecraft.execution.receipt": string(encoded)}}})
	runner, err := execution.New(context.Background(), engine, root, "fixture-volume", owner)
	if err != nil {
		t.Fatalf("recoverable Create response gap prevented reconciliation: %v", err)
	}
	defer runner.Close()
	result, err := runner.Query(context.Background(), input.ExecutionID)
	if err != nil || result.ContainerID != containerID || result.State != "INTERRUPTED" || result.Quiescent || result.Version <= 1 {
		t.Fatalf("created container lost its recovery owner: result=%+v err=%v", result, err)
	}
}

func TestRegistryRecoveryRetainsContainerWhenReceiptPrecedesResult(t *testing.T) {
	root, owner := t.TempDir(), identifier()
	input := recoveryInput()
	started := time.Date(2026, 1, 1, 0, 0, 0, 0, time.UTC)
	containerID := strings.Repeat("c", 64)
	writeRegistryFixture(t, root, input.ExecutionID+".json", execution.Result{
		Input: input, State: "STARTING", StartedAt: started, Version: 1, Cleanup: "PENDING"})
	writeRegistryFixture(t, root, input.ExecutionID+".meta", map[string]any{
		"input": input, "startedAt": started, "containerId": containerID, "version": 2})
	engine := registryEngine(t, []map[string]any{{"Id": containerID, "Labels": map[string]string{
		"codecraft.execution.id": input.ExecutionID, "codecraft.execution.owner": owner}}})
	runner, err := execution.New(context.Background(), engine, root, "fixture-volume", owner)
	if err != nil {
		t.Fatalf("recoverable container registration prevented reconciliation: %v", err)
	}
	defer runner.Close()
	result, err := runner.Query(context.Background(), input.ExecutionID)
	if err != nil || result.ContainerID != containerID || result.State != "INTERRUPTED" || result.Quiescent || result.Version <= 2 {
		t.Fatalf("lost uncertain execution ownership: result=%+v err=%v", result, err)
	}
	if _, err := runner.Run(context.Background(), recoveryInput()); !errors.Is(err, execution.ErrBusy) {
		t.Fatalf("unresolved container released execution capacity: %v", err)
	}
}
