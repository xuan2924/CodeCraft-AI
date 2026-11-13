package execution_test

import (
	"context"
	"errors"
	"os"
	"path/filepath"
	"testing"
	"time"

	"codecraft.local/sandbox/internal/execution"
)

func TestOrdinaryEvidencePruningPreservesFinalAndUnknownExecutions(t *testing.T) {
	root, input, owner := t.TempDir(), recoveryInput(), identifier()
	input.TaskID, input.ExerciseID = identifier(), identifier()
	input.ProblemID, input.ProblemVersion = "cart-total", "1.0.1"
	input.Purpose = "MANUAL"
	now := time.Date(2026, 1, 1, 0, 0, 0, 0, time.UTC)
	result := execution.Result{Input: input, State: "STOPPED", StartedAt: now, FinishedAt: &now, Version: 1, Quiescent: true, Cleanup: "SUCCEEDED", CopyCleanup: "SUCCEEDED"}
	writeRegistryFixture(t, root, input.ExecutionID+".json", result)
	final := input
	final.ExecutionID, final.TaskID, final.Purpose = identifier(), identifier(), "FINAL_VERIFICATION"
	final.Subpath = final.WorkspaceID + "/copy-" + final.ExecutionID + "/tree"
	result.Input = final
	writeRegistryFixture(t, root, final.ExecutionID+".json", result)
	runner, err := execution.New(context.Background(), registryEngine(t, []any{}), root, "fixture-volume", owner)
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = runner.Close() }()
	identity := execution.TaskIdentity{ExerciseID: input.ExerciseID, TaskID: input.TaskID, ExecutionID: input.ExecutionID, WorkspaceID: input.WorkspaceID}
	if err = runner.PruneExecution(context.Background(), identity); err != nil {
		t.Fatal(err)
	}
	if _, err = runner.Query(context.Background(), input.ExecutionID); !errors.Is(err, os.ErrNotExist) {
		t.Fatalf("ordinary evidence remains: %v", err)
	}
	if err = runner.PruneExecution(context.Background(), identity); err != nil {
		t.Fatalf("pruning replay: %v", err)
	}
	if _, cancelled, err := runner.QueryStop(context.Background(), identity); err != nil || !cancelled {
		t.Fatalf("pruning lost replay fence: %v %v", cancelled, err)
	}
	identity.ExecutionID, identity.TaskID = final.ExecutionID, final.TaskID
	identity.WorkspaceID = identifier()
	if err = runner.PruneExecution(context.Background(), identity); err == nil {
		t.Fatal("cross-workspace evidence was pruned")
	}
	identity.WorkspaceID = final.WorkspaceID
	if err = runner.PruneExecution(context.Background(), identity); err == nil {
		t.Fatal("final verification was pruned")
	}
	if _, err = runner.Query(context.Background(), final.ExecutionID); err != nil {
		t.Fatal(err)
	}
	unknown := input
	unknown.ExecutionID, unknown.TaskID = identifier(), identifier()
	unknown.Subpath = unknown.WorkspaceID + "/copy-" + unknown.ExecutionID + "/tree"
	result.Input, result.Quiescent, result.Cleanup, result.CopyCleanup = unknown, false, "PENDING", "PENDING"
	writeRegistryFixture(t, root, unknown.ExecutionID+".json", result)
	identity.ExecutionID, identity.TaskID = unknown.ExecutionID, unknown.TaskID
	if err = runner.PruneExecution(context.Background(), identity); err == nil {
		t.Fatal("unknown execution was pruned")
	}
	if _, err = runner.Query(context.Background(), unknown.ExecutionID); err != nil {
		t.Fatalf("unknown evidence lost: %v", err)
	}
}

func TestDeletionDoesNotHideMissingPrimaryRecoveryEvidence(t *testing.T) {
	root, input, owner := t.TempDir(), recoveryInput(), identifier()
	input.TaskID, input.ExerciseID = identifier(), identifier()
	input.ProblemID, input.ProblemVersion = "cart-total", "1.0.1"
	runner, err := execution.New(context.Background(), registryEngine(t, []any{}), root, "fixture-volume", owner)
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = runner.Close() }()
	now := time.Date(2026, 1, 1, 0, 0, 0, 0, time.UTC)
	writeRegistryFixture(t, root, input.ExecutionID+".meta", map[string]any{"input": input, "startedAt": now, "containerId": "", "version": 1})
	if err = runner.DeleteWorkspace(context.Background(), input.WorkspaceID); err == nil {
		t.Fatal("missing primary treated as no execution; recovery evidence orphaned")
	}
	record, err := runner.Query(context.Background(), input.ExecutionID)
	if err != nil || record.Quiescent || record.State != "INTERRUPTED" {
		t.Fatalf("recovery evidence not preserved: %+v %v", record, err)
	}
}

func TestDeletionPurgesEvidenceAndOwnedFailureStagesButPreservesCancellationOnRestart(t *testing.T) {
	root, input, owner := t.TempDir(), recoveryInput(), identifier()
	input.TaskID, input.ExerciseID = identifier(), identifier()
	input.ProblemID, input.ProblemVersion = "cart-total", "1.0.1"
	now := time.Date(2026, 1, 1, 0, 0, 0, 0, time.UTC)
	writeRegistryFixture(t, root, input.ExecutionID+".json", execution.Result{Input: input, State: "STOPPED", StartedAt: now, FinishedAt: &now,
		Version: 1, Quiescent: true, Cleanup: "SUCCEEDED", CopyCleanup: "SUCCEEDED", Logs: []execution.LogChunk{{Channel: "stdout", Data: []byte("private log")}}})
	// A failed atomic replacement is private payload too, not free/unowned data.
	stage := ".stage-" + input.ExecutionID + ".json-fixture"
	engine := registryEngine(t, []any{})
	runner, err := execution.New(context.Background(), engine, root, "fixture-volume", owner)
	if err != nil {
		t.Fatal(err)
	}
	defer func() {
		if runner != nil {
			_ = runner.Close()
		}
	}()
	if err = os.WriteFile(filepath.Join(root, stage), []byte("private partial log"), 0600); err != nil {
		t.Fatal(err)
	}
	if err = runner.DeleteWorkspace(context.Background(), input.WorkspaceID); err != nil {
		t.Fatal(err)
	}
	if _, err = os.Stat(filepath.Join(root, stage)); !os.IsNotExist(err) {
		t.Fatalf("failure stage survived deletion: %v", err)
	}
	if _, err = runner.Query(context.Background(), input.ExecutionID); !errors.Is(err, os.ErrNotExist) {
		t.Fatalf("evidence remains: %v", err)
	}
	if err = runner.Close(); err != nil {
		t.Fatal(err)
	}
	runner = nil
	runner, err = execution.New(context.Background(), engine, root, "fixture-volume", owner)
	if err != nil {
		t.Fatal(err)
	}
	_, cancelled, err := runner.QueryStop(context.Background(), execution.TaskIdentity{ExerciseID: input.ExerciseID, TaskID: input.TaskID, ExecutionID: input.ExecutionID, WorkspaceID: input.WorkspaceID})
	if err != nil || !cancelled {
		t.Fatalf("cancellation lost: %v %v", cancelled, err)
	}
}
