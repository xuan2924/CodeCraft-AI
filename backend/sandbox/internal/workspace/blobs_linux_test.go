package workspace_test

import (
	"context"
	"os"
	"path/filepath"
	"testing"

	rpc "codecraft.local/sandbox/internal/gen"
	"codecraft.local/sandbox/internal/workspace"
)

func TestWorkspaceLockIsolationAndDeduplicatedBlobs(t *testing.T) {
	root := t.TempDir()
	staged := make(chan struct{})
	resume := make(chan struct{})
	client, stop := connect(t, root, func(s *workspace.Service) {
		workspace.BeforeMutationPublishForTest(s, func(ctx context.Context) {
			close(staged)
			select {
			case <-resume:
			case <-ctx.Done():
			}
		})
	})
	defer stop()
	ctx := authorized(t)
	q := update(writable(t, client))
	q.Changes[0].Content = publication().InitialFiles["pom.xml"]
	done := make(chan error, 1)
	go func() { _, err := client.ApplyBatch(ctx, q); done <- err }()
	select {
	case <-staged:
	case <-ctx.Done():
		t.Fatal(ctx.Err())
	}
	_, err := client.PrepareWorkspace(ctx, &rpc.PrepareWorkspaceRequest{WorkspaceInstanceId: nextTransition, OperationId: operation, Problem: publication().Identity})
	_, statusErr := client.NodeStatus(ctx, &rpc.NodeStatusRequest{})
	close(resume)
	if err != nil || statusErr != nil {
		t.Fatalf("unrelated prepare/status blocked: %v %v", err, statusErr)
	}
	if err = <-done; err != nil {
		t.Fatal(err)
	}
	check := func(want int) {
		t.Helper()
		files, err := os.ReadDir(filepath.Join(root, instance, "blobs"))
		if err != nil || len(files) != want {
			t.Fatalf("blobs=%v want %d: %v", files, want, err)
		}
	}
	check(2)
	if _, err = os.Stat(filepath.Join(root, instance, "commit-00000000000000000001", "tree")); !os.IsNotExist(err) {
		t.Fatalf("commit materialized: %v", err)
	}
	gc := &rpc.CollectWorkspaceRequest{WorkspaceInstanceId: instance, ExpectedRevision: 1, RetainedRevisions: []uint64{0}}
	if _, err = client.CollectWorkspace(ctx, gc); err != nil {
		t.Fatal(err)
	}
	check(2)
	gc.RetainedRevisions = nil
	if _, err = client.CollectWorkspace(ctx, gc); err != nil {
		t.Fatal(err)
	}
	check(1)
}
