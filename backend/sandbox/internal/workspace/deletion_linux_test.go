package workspace_test

import (
	"os"
	"path/filepath"
	"testing"

	rpc "codecraft.local/sandbox/internal/gen"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"
)

func TestDeletionFencesReplaysAndSurvivesRestart(t *testing.T) {
	root := t.TempDir()
	client, close := connect(t, root)
	prepare := &rpc.PrepareWorkspaceRequest{WorkspaceInstanceId: instance, OperationId: operation, Problem: publication().Identity}
	if _, err := client.PrepareWorkspace(authorized(t), prepare); err != nil {
		close()
		t.Fatal(err)
	}
	receipt, err := client.DeleteWorkspace(authorized(t), &rpc.QueryWorkspaceRequest{WorkspaceInstanceId: instance})
	close()
	if err != nil || !receipt.GetComplete() {
		t.Fatalf("delete: %v %v", receipt, err)
	}
	if _, err = os.Stat(filepath.Join(root, instance, "commit-00000000000000000000")); !os.IsNotExist(err) {
		t.Fatalf("code remains: %v", err)
	}
	client, close = connect(t, root)
	defer close()
	if _, err = client.PrepareWorkspace(authorized(t), prepare); status.Code(err) != codes.FailedPrecondition {
		t.Fatalf("deleted instance reopened: %v", err)
	}
	if _, err = client.ReadWorkspaceFile(authorized(t), &rpc.ReadWorkspaceFileRequest{WorkspaceInstanceId: instance, Path: "pom.xml"}); status.Code(err) != codes.FailedPrecondition {
		t.Fatalf("deleted code accessible: %v", err)
	}
	receipt, err = client.DeleteWorkspace(authorized(t), &rpc.QueryWorkspaceRequest{WorkspaceInstanceId: instance})
	if err != nil || !receipt.GetComplete() {
		t.Fatalf("replay: %v %v", receipt, err)
	}
}
