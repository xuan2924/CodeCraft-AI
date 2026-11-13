package workspace_test

import (
	"io/fs"
	"os"
	"path/filepath"
	"testing"

	rpc "codecraft.local/sandbox/internal/gen"
)

func physicalBytes(t *testing.T, root string) uint64 {
	t.Helper()
	var total uint64
	if err := filepath.WalkDir(root, func(_ string, entry fs.DirEntry, err error) error {
		if err != nil {
			return err
		}
		if !entry.IsDir() {
			info, err := entry.Info()
			if err != nil {
				return err
			}
			total += uint64(info.Size())
		}
		return nil
	}); err != nil {
		t.Fatal(err)
	}
	return total
}

func TestStorageMeasurementCountsActualContentAndMonotonicScopeAcrossRestart(t *testing.T) {
	root := t.TempDir()
	client, close := connect(t, root)
	if _, err := client.PrepareWorkspace(authorized(t), &rpc.PrepareWorkspaceRequest{WorkspaceInstanceId: instance, OperationId: operation, Problem: publication().Identity}); err != nil {
		close()
		t.Fatal(err)
	}
	request := &rpc.MeasureExerciseStorageRequest{ExerciseId: operation, WorkspaceInstanceIds: []string{instance}}
	first, err := client.MeasureExerciseStorage(authorized(t), request)
	if err != nil {
		close()
		t.Fatal(err)
	}
	if first.GetBytes() != physicalBytes(t, root) || first.GetBytes() > 128*1024 || first.GetMeterEpoch() == "" {
		close()
		t.Fatalf("not actual storage: %v", first)
	}
	if _, err = client.PrepareWorkspace(authorized(t), &rpc.PrepareWorkspaceRequest{WorkspaceInstanceId: nextTransition, OperationId: mutation, Problem: publication().Identity}); err != nil {
		close()
		t.Fatal(err)
	}
	request.WorkspaceInstanceIds = append(request.WorkspaceInstanceIds, nextTransition)
	second, err := client.MeasureExerciseStorage(authorized(t), request)
	if err != nil || second.GetBytes() != physicalBytes(t, root) || second.GetSequence() <= first.GetSequence() {
		close()
		t.Fatalf("expanded scope: %v %v", second, err)
	}
	request.WorkspaceInstanceIds = []string{instance}
	stale, err := client.MeasureExerciseStorage(authorized(t), request)
	if err != nil || stale.GetBytes() != physicalBytes(t, root) || stale.GetBytes() != second.GetBytes() {
		close()
		t.Fatalf("late subset forgot prior instance: %v %v", stale, err)
	}
	close()
	client, close = connect(t, root)
	defer close()
	resumed, err := client.MeasureExerciseStorage(authorized(t), request)
	if err != nil || resumed.GetMeterEpoch() != first.GetMeterEpoch() || resumed.GetSequence() <= stale.GetSequence() || resumed.GetBytes() != physicalBytes(t, root) {
		t.Fatalf("meter restart: %v %v", resumed, err)
	}
	if _, err = client.DeleteWorkspace(authorized(t), &rpc.QueryWorkspaceRequest{WorkspaceInstanceId: nextTransition}); err != nil {
		t.Fatal(err)
	}
	reclaimed, err := client.MeasureExerciseStorage(authorized(t), request)
	if err != nil || reclaimed.GetBytes() != physicalBytes(t, root) || reclaimed.GetBytes() >= resumed.GetBytes() {
		t.Fatalf("deleted payload/residual accounting: %v %v", reclaimed, err)
	}
}

func TestCorruptStorageMeterDoesNotResetToFreeCapacity(t *testing.T) {
	root := t.TempDir()
	client, close := connect(t, root)
	request := &rpc.MeasureExerciseStorageRequest{ExerciseId: operation, WorkspaceInstanceIds: []string{instance}}
	if _, err := client.MeasureExerciseStorage(authorized(t), request); err != nil {
		close()
		t.Fatal(err)
	}
	close()
	meters, err := filepath.Glob(filepath.Join(root, "usage-*.json"))
	if err != nil || len(meters) != 1 {
		t.Fatalf("meter fixture: %v %v", meters, err)
	}
	if err = os.WriteFile(meters[0], []byte(`{"partial":`), 0600); err != nil {
		t.Fatal(err)
	}
	client, close = connect(t, root)
	defer close()
	if snapshot, err := client.MeasureExerciseStorage(authorized(t), request); err == nil {
		t.Fatalf("corrupt authority became new zero usage: %v", snapshot)
	}
}
