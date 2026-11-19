package execution

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/docker/docker/client"
)

func captureStageLogs(t *testing.T) *bytes.Buffer {
	t.Helper()
	var output bytes.Buffer
	previous := slog.Default()
	slog.SetDefault(slog.New(slog.NewJSONHandler(&output, nil)))
	t.Cleanup(func() { slog.SetDefault(previous) })
	return &output
}

func TestExecutionStageUsesIndependentClockAndSafeFields(t *testing.T) {
	output := captureStageLogs(t)
	now := time.Date(2026, 1, 1, 0, 0, 0, 0, time.UTC)
	input := Input{ExecutionID: "execution", TaskID: "task", Subpath: "secret-path", Command: &CommandSpec{Text: "secret-command"}}
	for _, name := range []string{"input_preparation", "container_creation", "attach_start", "command_execution", "quiescent_cleanup"} {
		stage := newExecutionStage(input, name, func() time.Time { return now })
		now = now.Add(1250 * time.Microsecond)
		stage.end("succeeded")
	}
	lines := strings.Split(strings.TrimSpace(output.String()), "\n")
	if len(lines) != 5 {
		t.Fatalf("observations: %s", output)
	}
	for _, line := range lines {
		var fields map[string]any
		if err := json.Unmarshal([]byte(line), &fields); err != nil {
			t.Fatal(err)
		}
		if fields["duration_ms"] != 1.25 || fields["execution_id"] != "execution" || fields["task_id"] != "task" || fields["outcome"] != "succeeded" {
			t.Fatalf("observation: %v", fields)
		}
		if len(fields) != 8 {
			t.Fatalf("unexpected operational fields: %v", fields)
		}
	}
	if strings.Contains(output.String(), "secret") {
		t.Fatal("sensitive input leaked")
	}
}

func TestExecutionStageCleanupOutcomeDoesNotConflateQuiescenceAndSuccess(t *testing.T) {
	for _, test := range []struct {
		result Result
		err    error
		want   string
	}{
		{Result{}, nil, "unconfirmed"},
		{Result{Quiescent: true, Cleanup: "FAILED"}, nil, "failed"},
		{Result{Quiescent: true, Cleanup: "SUCCEEDED"}, errors.New("secret-error"), "failed"},
		{Result{Quiescent: true, Cleanup: "SUCCEEDED"}, nil, "succeeded"},
	} {
		if got := cleanupOutcome(test.result, test.err); got != test.want {
			t.Fatalf("outcome=%s want=%s", got, test.want)
		}
	}
}

func TestRunnerStageCreateFailureRemainsUnconfirmed(t *testing.T) {
	output := captureStageLogs(t)
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
		if req.URL.Path != "/v1.45/containers/create" {
			t.Errorf("unexpected Docker call: %s", req.URL.Path)
		}
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusInternalServerError)
		_, _ = w.Write([]byte(`{"message":"secret-daemon-error"}`))
	}))
	defer server.Close()
	docker, err := client.NewClientWithOpts(client.WithHost(server.URL), client.WithVersion("1.45"))
	if err != nil {
		t.Fatal(err)
	}
	defer docker.Close()
	runner := &Runner{root: t.TempDir(), docker: docker, now: time.Now}
	id := "11111111-1111-4111-8111-111111111111"
	record := Result{Input: Input{ExecutionID: id, WorkspaceID: id,
		Subpath: id + "/copy-" + id + "/tree", Revision: 1,
		Image: "sha256:" + strings.Repeat("a", 64), PackageSHA256: strings.Repeat("b", 64),
		Platform: "linux/amd64", Target: "base", NotAfter: time.Now().Add(time.Hour),
		Suite: "interview.Test", Tests: []string{"example"},
		Limits: Limits{CPUs: 1, MemoryMiB: 64, PIDs: 16, WorkMiB: 1, TmpMiB: 1, ShmMiB: 1, TimeoutSeconds: 1, LogBytes: 1024}}, State: "STARTING", Cleanup: "PENDING", StartedAt: time.Now()}
	if err := runner.persist(record); err != nil {
		t.Fatal(err)
	}
	result, err := runner.execute(context.Background(), record, nil)
	if err == nil || result.Quiescent || result.Cleanup != "FAILED" || result.Reason != "container_create_unconfirmed" {
		t.Fatalf("result=%+v err=%v", result, err)
	}
	if !strings.Contains(output.String(), `"stage":"container_creation"`) || !strings.Contains(output.String(), `"outcome":"failed"`) || strings.Contains(output.String(), "secret") {
		t.Fatalf("unsafe or missing observation: %s", output)
	}
	if strings.Contains(output.String(), "command_execution") || strings.Contains(output.String(), "quiescent_cleanup") {
		t.Fatal("unreached stages must not claim timing or cleanup")
	}
}

func TestRunnerStageCancellationPreservesResultAndMeasuresCleanup(t *testing.T) {
	output := captureStageLogs(t)
	// A cancelled worker still owns a bounded, uncancelled cleanup attempt.
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		if req.URL.Path != "/v1.45/containers/container/json" {
			t.Errorf("unexpected Docker call: %s", req.URL.Path)
		}
		_, _ = w.Write([]byte(`{"State":{"Running":true},"Config":{"Labels":{}},"Image":"wrong-owner"}`))
	}))
	defer server.Close()
	docker, err := client.NewClientWithOpts(client.WithHost(server.URL), client.WithVersion("1.45"))
	if err != nil {
		t.Fatal(err)
	}
	defer docker.Close()
	runner := &Runner{root: t.TempDir(), docker: docker, now: time.Now}
	record := Result{Input: Input{ExecutionID: "11111111-1111-4111-8111-111111111111", Limits: Limits{TimeoutSeconds: 30}}, ContainerID: "container", StartedAt: time.Now(), State: "RUNNING"}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	joined := false
	result, err := runner.observeExecution(ctx, record, &tailLog{limit: 1024}, func() error { joined = true; return nil }, nil, waitStatusLock)
	if err == nil || !joined || result.State != "INTERRUPTED" || result.Quiescent || result.Reason != "execution_quiescence_unconfirmed" {
		t.Fatalf("cancellation/ownership safety changed: %+v %v", result, err)
	}
	lines := strings.Split(strings.TrimSpace(output.String()), "\n")
	if len(lines) != 2 {
		t.Fatalf("observations: %s", output)
	}
	for index, want := range []struct{ stage, outcome string }{{"command_execution", "INTERRUPTED"}, {"quiescent_cleanup", "unconfirmed"}} {
		var fields map[string]any
		if err := json.Unmarshal([]byte(lines[index]), &fields); err != nil {
			t.Fatal(err)
		}
		if fields["stage"] != want.stage || fields["outcome"] != want.outcome {
			t.Fatalf("stage: %v", fields)
		}
	}
}
