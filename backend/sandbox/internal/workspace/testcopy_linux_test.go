package workspace_test

import (
	"context"
	"os"
	"path/filepath"
	"testing"
	"time"

	rpc "codecraft.local/sandbox/internal/gen"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"
	"google.golang.org/protobuf/proto"
)

const execution = "66666666-6666-4666-8666-666666666666"

func copyRequest(epoch uint64) *rpc.PrepareTestCopyRequest {
	return &rpc.PrepareTestCopyRequest{WorkspaceInstanceId: instance, ExecutionInstanceId: execution,
		GateEpoch: epoch, NotAfterUnixMillis: time.Now().Add(time.Minute).UnixMilli()}
}

func TestStableTestCopySurvivesSaveAndRestart(t *testing.T) {
	root := t.TempDir()
	client, close := connect(t, root)
	epoch := writable(t, client)
	request := copyRequest(epoch)
	receipt, err := client.PrepareTestCopy(authorized(t), request)
	if err != nil {
		close()
		t.Fatal(err)
	}
	if receipt.Revision != 0 || !proto.Equal(receipt.Problem, publication().Identity) {
		close()
		t.Fatalf("wrong input identity: %v", receipt)
	}
	if _, err = client.ApplyBatch(authorized(t), update(epoch)); err != nil {
		close()
		t.Fatal(err)
	}
	close()
	client, close = connect(t, root)
	defer close()
	recovered, err := client.QueryTestCopy(authorized(t), &rpc.QueryTestCopyRequest{WorkspaceInstanceId: instance, ExecutionInstanceId: execution})
	if err != nil || !proto.Equal(receipt, recovered) {
		t.Fatalf("copy receipt not recovered: %v %v", recovered, err)
	}
	// The execution input, unlike the current workspace, must still contain revision zero.
	content, err := os.ReadFile(filepath.Join(root, receipt.RelativePath, sourcePath))
	if err != nil || string(content) != "class Main {}\n" {
		t.Fatalf("test input changed: %q %v", content, err)
	}
	request.NotAfterUnixMillis = 1
	replay, err := client.PrepareTestCopy(authorized(t), request)
	if err != nil || !proto.Equal(receipt, replay) {
		t.Fatalf("historical copy replay failed: %v %v", replay, err)
	}
	request.ExpectedRevision = 1
	if _, err = client.PrepareTestCopy(authorized(t), request); status.Code(err) != codes.AlreadyExists {
		t.Fatalf("execution identity reused: %v", err)
	}
}

func TestPreparationCancellationCannotDiscardTestCopyEvidence(t *testing.T) {
	client, disconnect := connect(t, t.TempDir())
	defer disconnect()
	epoch := writable(t, client)
	if _, err := client.PrepareTestCopy(authorized(t), copyRequest(epoch)); err != nil {
		t.Fatal(err)
	}
	if _, err := client.CloseGate(authorized(t), &rpc.CloseGateRequest{WorkspaceInstanceId: instance, TransitionId: nextTransition, ExpectedEpoch: epoch}); err != nil {
		t.Fatal(err)
	}
	if _, err := client.CancelPreparation(authorized(t), &rpc.CancelPreparationRequest{WorkspaceInstanceId: instance, OperationId: operation}); status.Code(err) != codes.FailedPrecondition {
		t.Fatalf("cancellation discarded evidence or falsely confirmed cleanup: %v", err)
	}
	if _, err := client.QueryWorkspace(authorized(t), &rpc.QueryWorkspaceRequest{WorkspaceInstanceId: instance}); err != nil {
		t.Fatal(err)
	}
	if _, err := client.QueryTestCopy(authorized(t), &rpc.QueryTestCopyRequest{WorkspaceInstanceId: instance, ExecutionInstanceId: execution}); err != nil {
		t.Fatal(err)
	}
}

func TestTestCopyRejectsUnauthorizedStaleAndExpiredAdmission(t *testing.T) {
	client, close := connect(t, t.TempDir())
	defer close()
	epoch := writable(t, client)
	if _, err := client.PrepareTestCopy(context.Background(), copyRequest(epoch)); status.Code(err) != codes.Unauthenticated {
		t.Fatalf("missing authentication accepted: %v", err)
	}
	request := copyRequest(epoch)
	request.ExpectedRevision = 1
	if _, err := client.PrepareTestCopy(authorized(t), request); status.Code(err) != codes.Aborted {
		t.Fatalf("stale revision accepted: %v", err)
	}
	request = copyRequest(epoch)
	request.NotAfterUnixMillis = 1
	if _, err := client.PrepareTestCopy(authorized(t), request); status.Code(err) != codes.FailedPrecondition {
		t.Fatalf("expired copy accepted: %v", err)
	}
	if _, err := client.CloseGate(authorized(t), &rpc.CloseGateRequest{WorkspaceInstanceId: instance, TransitionId: nextTransition, ExpectedEpoch: epoch}); err != nil {
		t.Fatal(err)
	}
	if _, err := client.PrepareTestCopy(authorized(t), copyRequest(epoch)); status.Code(err) != codes.FailedPrecondition {
		t.Fatalf("closed gate admitted copy: %v", err)
	}
	if _, err := client.QueryTestCopy(authorized(t), &rpc.QueryTestCopyRequest{WorkspaceInstanceId: instance, ExecutionInstanceId: execution}); status.Code(err) != codes.NotFound {
		t.Fatalf("rejected copy was published: %v", err)
	}
}
