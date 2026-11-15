package workspace_test

import (
	"fmt"
	"os"
	"path/filepath"
	"testing"
	"time"

	rpc "codecraft.local/sandbox/internal/gen"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"
	"google.golang.org/protobuf/proto"
)

func historyChange(t *testing.T, client rpc.WorkspaceStorageClient, epoch, revision uint64, changes ...*rpc.FileChange) {
	t.Helper()
	id := fmt.Sprintf("%08x-7777-4777-8777-777777777777", revision+7)
	receipt, err := client.ApplyBatch(authorized(t), &rpc.ApplyBatchRequest{WorkspaceInstanceId: instance, RequestId: id, OperationId: id,
		GateEpoch: epoch, ExpectedRevision: revision, NotAfterUnixMillis: time.Now().Add(time.Minute).UnixMilli(), Changes: changes})
	if err != nil || !receipt.Changed || receipt.Revision != revision+1 {
		t.Fatalf("history fixture write: %v %v", receipt, err)
	}
}

func historyRequest(revision uint64, mode string) *rpc.WorkspaceHistoryRequest {
	request := &rpc.WorkspaceHistoryRequest{WorkspaceInstanceId: instance, BaseRevision: 0, ResultRevision: 1, ExpectedRevision: revision, Mode: mode}
	if mode == "ROLLBACK" {
		request.TargetRevision = 1
	}
	return request
}

func TestWorkspaceHistoryUndoPreservesUnrelatedChangesAndPreviewIsStable(t *testing.T) {
	client, close := connect(t, t.TempDir())
	defer close()
	epoch := writable(t, client)
	historyChange(t, client, epoch, 0, &rpc.FileChange{Path: sourcePath, Kind: "UPDATE", Content: "class Main { int x; }\n"})
	extra := "src/main/java/Extra.java"
	historyChange(t, client, epoch, 1, &rpc.FileChange{Path: extra, Kind: "CREATE", Content: "class Extra {}\n"})
	preview, err := client.PreviewWorkspaceHistory(authorized(t), historyRequest(2, "UNDO"))
	if err != nil || len(preview.Conflicts) != 0 || len(preview.Changes) != 1 || preview.Changes[0].Change.Path != sourcePath || preview.Changes[0].Change.Content != publication().InitialFiles[sourcePath] {
		t.Fatalf("undo did not preserve unrelated code: %v %v", preview, err)
	}
	repeated, err := client.PreviewWorkspaceHistory(authorized(t), historyRequest(2, "UNDO"))
	if err != nil || len(preview.Fingerprint) != 64 || !proto.Equal(preview, repeated) {
		t.Fatalf("preview not stable: %v %v", repeated, err)
	}
	rollback, err := client.PreviewWorkspaceHistory(authorized(t), historyRequest(2, "ROLLBACK"))
	if err != nil || len(rollback.Changes) != 1 || rollback.Changes[0].Change.Path != extra || rollback.Changes[0].Change.Kind != "DELETE" || rollback.Fingerprint == preview.Fingerprint {
		t.Fatalf("rollback did not describe the full selected version: %v %v", rollback, err)
	}
	initialRequest := historyRequest(2, "ROLLBACK")
	initialRequest.TargetRevision = 0
	initial, err := client.PreviewWorkspaceHistory(authorized(t), initialRequest)
	if err != nil || len(initial.Changes) != 2 || initial.Fingerprint == rollback.Fingerprint {
		t.Fatalf("initial retained baseline is not selectable: %v %v", initial, err)
	}
	// Publish using the existing batch path, not a new history writer.
	historyChange(t, client, epoch, 2, preview.Changes[0].Change)
	file, err := client.ReadWorkspaceFile(authorized(t), &rpc.ReadWorkspaceFileRequest{WorkspaceInstanceId: instance, ExpectedRevision: 3, Path: extra})
	if err != nil || file.Content != "class Extra {}\n" {
		t.Fatalf("undo damaged unrelated file: %v %v", file, err)
	}
	original, err := client.PreviewWorkspaceHistory(authorized(t), historyRequest(3, "DIFF"))
	if err != nil || len(original.Changes) != 1 || original.Changes[0].PreviousContent != publication().InitialFiles[sourcePath] {
		t.Fatalf("restoration erased original history: %v %v", original, err)
	}
}

func TestWorkspaceHistoryCreationDeletionAndEmptyFilesRemainDistinct(t *testing.T) {
	client, close := connect(t, t.TempDir())
	defer close()
	epoch := writable(t, client)
	empty := "src/main/java/Empty.java"
	historyChange(t, client, epoch, 0, &rpc.FileChange{Path: sourcePath, Kind: "DELETE"}, &rpc.FileChange{Path: empty, Kind: "CREATE"})
	preview, err := client.PreviewWorkspaceHistory(authorized(t), historyRequest(1, "UNDO"))
	if err != nil || len(preview.Changes) != 2 || preview.Changes[0].Change.Path != empty || preview.Changes[0].Change.Kind != "DELETE" || preview.Changes[1].Change.Kind != "CREATE" || preview.Changes[1].Change.Content != publication().InitialFiles[sourcePath] {
		t.Fatalf("creation/deletion inverse lost existence: %v %v", preview, err)
	}
	noop, err := client.PreviewWorkspaceHistory(authorized(t), historyRequest(1, "ROLLBACK"))
	if err != nil || len(noop.Changes) != 0 {
		t.Fatalf("current version rollback is not a no-op: %v %v", noop, err)
	}
	historyChange(t, client, epoch, 1, &rpc.FileChange{Path: sourcePath, Kind: "CREATE"})
	conflict, err := client.PreviewWorkspaceHistory(authorized(t), historyRequest(2, "UNDO"))
	if err != nil || len(conflict.Conflicts) != 1 || conflict.Conflicts[0] != sourcePath {
		t.Fatalf("new empty file was confused with absence: %v %v", conflict, err)
	}
}

func TestWorkspaceHistoryConflictsStaleBaselinesAndCollectedVersionsAreExplicit(t *testing.T) {
	client, close := connect(t, t.TempDir())
	defer close()
	epoch := writable(t, client)
	historyChange(t, client, epoch, 0, &rpc.FileChange{Path: sourcePath, Kind: "UPDATE", Content: "class Main { int x; }\n"})
	historyChange(t, client, epoch, 1, &rpc.FileChange{Path: sourcePath, Kind: "UPDATE", Content: "class Main { int later; }\n"})
	preview, err := client.PreviewWorkspaceHistory(authorized(t), historyRequest(2, "UNDO"))
	if err != nil || len(preview.Conflicts) != 1 || preview.Conflicts[0] != sourcePath {
		t.Fatalf("later edit was not a conflict: %v %v", preview, err)
	}
	if _, err = client.PreviewWorkspaceHistory(authorized(t), historyRequest(1, "UNDO")); status.Code(err) != codes.Aborted {
		t.Fatalf("stale preview baseline accepted: %v", err)
	}
	if _, err = client.CollectWorkspace(authorized(t), &rpc.CollectWorkspaceRequest{WorkspaceInstanceId: instance, ExpectedRevision: 2, RetainedRevisions: []uint64{0}}); err != nil {
		t.Fatal(err)
	}
	if _, err = client.PreviewWorkspaceHistory(authorized(t), historyRequest(2, "DIFF")); status.Code(err) != codes.NotFound {
		t.Fatalf("collected version became an empty successful diff: %v", err)
	}
}

func TestWorkspaceHistoryFrozenPreviewDoesNotGrantPublication(t *testing.T) {
	client, close := connect(t, t.TempDir())
	defer close()
	epoch := writable(t, client)
	historyChange(t, client, epoch, 0, &rpc.FileChange{Path: sourcePath, Kind: "UPDATE", Content: "class Main { int x; }\n"})
	request := &rpc.CloseGateRequest{WorkspaceInstanceId: instance, TransitionId: nextTransition, ExpectedEpoch: epoch}
	gate, err := client.CloseGate(authorized(t), request)
	if err != nil {
		t.Fatal(err)
	}
	request.ExpectedEpoch = gate.Epoch
	if _, err = client.Freeze(authorized(t), request); err != nil {
		t.Fatal(err)
	}
	preview, err := client.PreviewWorkspaceHistory(authorized(t), historyRequest(1, "UNDO"))
	if err != nil || len(preview.Changes) != 1 {
		t.Fatalf("frozen history is not readable: %v %v", preview, err)
	}
	batch := update(epoch)
	batch.ExpectedRevision = 1
	batch.Changes = []*rpc.FileChange{preview.Changes[0].Change}
	if _, err = client.ApplyBatch(authorized(t), batch); status.Code(err) != codes.FailedPrecondition {
		t.Fatalf("preview bypassed frozen gate: %v", err)
	}
}

func TestWorkspaceHistoryCorruptionNeverBecomesRestoreContent(t *testing.T) {
	root := t.TempDir()
	client, close := connect(t, root)
	defer close()
	epoch := writable(t, client)
	historyChange(t, client, epoch, 0, &rpc.FileChange{Path: sourcePath, Kind: "UPDATE", Content: "class Main { int x; }\n"})
	path := filepath.Join(root, instance, "commit-00000000000000000000", "tree", sourcePath)
	if err := os.WriteFile(path, []byte("corrupt"), 0600); err != nil {
		t.Fatal(err)
	}
	if _, err := client.PreviewWorkspaceHistory(authorized(t), historyRequest(1, "UNDO")); status.Code(err) != codes.DataLoss {
		t.Fatalf("unverified historical bytes exposed: %v", err)
	}
}
