package workspace_test

import (
	"os"
	"path/filepath"
	"testing"

	rpc "codecraft.local/sandbox/internal/gen"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"
)

func TestCollectionResumesDurablyMarkedPartialRemovalWithoutDiscardingLiveAuthority(t *testing.T) {
	root := t.TempDir()
	client, close := connect(t, root)
	request := update(writable(t, client))
	if _, err := client.ApplyBatch(authorized(t), request); err != nil {
		close()
		t.Fatal(err)
	}
	close()
	// Persisted fixture at the crash seam: the unreferenced commit was renamed,
	// then only part of its contents was removed before process ownership ended.
	old := filepath.Join(root, instance, "commit-00000000000000000000")
	garbage := filepath.Join(root, instance, "gc-commit-00000000000000000000")
	if err := os.Rename(old, garbage); err != nil {
		t.Fatal(err)
	}
	if err := os.Remove(filepath.Join(garbage, "manifest.json")); err != nil {
		t.Fatal(err)
	}
	client, close = connect(t, root)
	defer close()
	receipt, err := client.CollectWorkspace(authorized(t), &rpc.CollectWorkspaceRequest{WorkspaceInstanceId: instance, ExpectedRevision: 1})
	if err != nil || receipt.GetReclaimedBytes() == 0 {
		t.Fatalf("partial removal not recovered: %v %v", receipt, err)
	}
	if _, err = os.Stat(garbage); !os.IsNotExist(err) {
		t.Fatalf("garbage remains: %v", err)
	}
	if _, err = client.QueryMutation(authorized(t), &rpc.QueryMutationRequest{WorkspaceInstanceId: instance, RequestId: mutation}); err != nil {
		t.Fatal(err)
	}
	if _, err = client.CollectWorkspace(authorized(t), &rpc.CollectWorkspaceRequest{WorkspaceInstanceId: instance, ExpectedRevision: 1, RetainedRevisions: []uint64{0}}); status.Code(err) != codes.DataLoss {
		t.Fatalf("missing live reference was ignored: %v", err)
	}
}

func TestCollectionProtectsReferencesAndLatestReceiptsAcrossRestart(t *testing.T) {
	root := t.TempDir()
	client, close := connect(t, root)
	request := update(writable(t, client))
	if _, err := client.ApplyBatch(authorized(t), request); err != nil {
		close()
		t.Fatal(err)
	}
	request.RequestId = nextTransition
	request.OperationId = nextTransition
	request.ExpectedRevision = 1
	request.Changes[0].Content = "class Main { int second; }\n"
	if _, err := client.ApplyBatch(authorized(t), request); err != nil {
		close()
		t.Fatal(err)
	}
	request.RequestId = "66666666-6666-4666-8666-666666666666"
	request.OperationId = request.RequestId
	request.ExpectedRevision = 2
	if receipt, err := client.ApplyBatch(authorized(t), request); err != nil || receipt.Changed {
		close()
		t.Fatalf("no change: %v %v", receipt, err)
	}
	stale := &rpc.CollectWorkspaceRequest{WorkspaceInstanceId: instance, ExpectedRevision: 1, RetainedRevisions: []uint64{1}}
	if _, err := client.CollectWorkspace(authorized(t), stale); status.Code(err) != codes.Aborted {
		close()
		t.Fatalf("stale collection admitted: %v", err)
	}
	for _, sequence := range []string{"00", "01", "02", "03"} {
		if _, err := os.Stat(filepath.Join(root, instance, "commit-000000000000000000"+sequence)); err != nil {
			close()
			t.Fatal(err)
		}
	}
	stale.ExpectedRevision = 2
	receipt, err := client.CollectWorkspace(authorized(t), stale)
	close()
	if err != nil || receipt.GetRemovedVersions() != 2 || receipt.GetReclaimedBytes() == 0 {
		t.Fatalf("collection: %v %v", receipt, err)
	}
	for _, sequence := range []string{"00", "02"} {
		if _, err := os.Stat(filepath.Join(root, instance, "commit-000000000000000000"+sequence)); !os.IsNotExist(err) {
			t.Fatalf("unreferenced commit remains: %v", err)
		}
	}
	for _, sequence := range []string{"01", "03"} {
		if _, err := os.Stat(filepath.Join(root, instance, "commit-000000000000000000"+sequence)); err != nil {
			t.Fatalf("referenced commit removed: %v", err)
		}
	}
	client, close = connect(t, root)
	defer close()
	if _, err = client.QueryMutation(authorized(t), &rpc.QueryMutationRequest{WorkspaceInstanceId: instance, RequestId: nextTransition}); err != nil {
		t.Fatalf("receipt lost: %v", err)
	}
	again, err := client.CollectWorkspace(authorized(t), stale)
	if err != nil || again.GetRemovedVersions() != 0 || again.GetReclaimedBytes() != 0 {
		t.Fatalf("collection replay: %v %v", again, err)
	}
}
