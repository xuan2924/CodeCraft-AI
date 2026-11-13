package execution_test

import (
	"context"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"codecraft.local/sandbox/internal/execution"
)

func TestStorageCountsAllOwnedEvidenceCopiesAndStagesButNotAnotherExecution(t *testing.T) {
	root, input, owner := t.TempDir(), recoveryInput(), identifier()
	input.TaskID, input.ExerciseID = identifier(), identifier()
	input.ProblemID, input.ProblemVersion = "cart-total", "1.0.1"
	now := time.Date(2026, 1, 1, 0, 0, 0, 0, time.UTC)
	writeRegistryFixture(t, root, input.ExecutionID+".json", execution.Result{Input: input, State: "STOPPED", StartedAt: now, FinishedAt: &now,
		Version: 1, Quiescent: true, Cleanup: "SUCCEEDED", CopyCleanup: "SUCCEEDED", Logs: []execution.LogChunk{{Channel: "stdout", Data: []byte("private log")}}})
	runner, err := execution.New(context.Background(), registryEngine(t, []any{}), root, "fixture-volume", owner)
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = runner.Close() }()
	for _, name := range []string{".stage-" + input.ExecutionID + ".json-fixture", ".corrupt-" + input.ExecutionID + "-fixture", ".corrupt-meta-" + input.ExecutionID + "-fixture",
		".corrupt-meta-" + identifier() + "-fixture", identifier() + ".json"} {
		if err = os.WriteFile(filepath.Join(root, name), []byte("private partial record"), 0600); err != nil {
			t.Fatal(err)
		}
	}
	identity := execution.TaskIdentity{ExerciseID: input.ExerciseID, TaskID: input.TaskID, ExecutionID: input.ExecutionID, WorkspaceID: input.WorkspaceID}
	actual, err := runner.StorageBytes(context.Background(), []execution.TaskIdentity{identity})
	if err != nil {
		t.Fatal(err)
	}
	entries, err := os.ReadDir(root)
	if err != nil {
		t.Fatal(err)
	}
	var expected uint64
	for _, entry := range entries {
		if strings.Contains(entry.Name(), input.ExecutionID) {
			info, err := entry.Info()
			if err != nil {
				t.Fatal(err)
			}
			expected += uint64(info.Size())
		}
	}
	if actual == 0 || actual != expected {
		t.Fatalf("evidence or temporary bytes omitted: got %d expected %d", actual, expected)
	}
	identity.WorkspaceID = identifier()
	if _, err = runner.StorageBytes(context.Background(), []execution.TaskIdentity{identity}); err == nil {
		t.Fatal("another workspace identity accepted")
	}
}
