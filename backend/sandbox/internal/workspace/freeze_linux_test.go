package workspace_test

import (
	"context"
	"os"
	"path/filepath"
	"sync"
	"testing"
	"time"

	rpc "codecraft.local/sandbox/internal/gen"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"
	"google.golang.org/protobuf/proto"
)

func TestFreezeRejectsUnauthenticatedAndDamagedEvidence(t *testing.T) {
	root := t.TempDir()
	client, close := connect(t, root)
	defer close()
	epoch := writable(t, client)
	request := &rpc.CloseGateRequest{WorkspaceInstanceId: instance, TransitionId: nextTransition, ExpectedEpoch: epoch}
	if _, err := client.Freeze(context.Background(), request); status.Code(err) != codes.Unauthenticated {
		t.Fatalf("unauthorized freeze: %v", err)
	}
	if _, err := client.QueryFreeze(context.Background(), &rpc.QueryWorkspaceRequest{WorkspaceInstanceId: instance}); status.Code(err) != codes.Unauthenticated {
		t.Fatalf("unauthorized query: %v", err)
	}
	if _, err := client.QueryFreeze(authorized(t), &rpc.QueryWorkspaceRequest{WorkspaceInstanceId: instance}); status.Code(err) != codes.NotFound {
		t.Fatalf("premature freeze receipt: %v", err)
	}
	gate, err := client.CloseGate(authorized(t), request)
	if err != nil {
		t.Fatal(err)
	}
	request.ExpectedEpoch = gate.Epoch
	if _, err := client.Freeze(authorized(t), request); err != nil {
		t.Fatal(err)
	}
	changed := proto.Clone(request).(*rpc.CloseGateRequest)
	changed.TransitionId = operation
	if _, err := client.Freeze(authorized(t), changed); status.Code(err) != codes.AlreadyExists {
		t.Fatalf("changed freeze payload: %v", err)
	}
	path := filepath.Join(root, instance, "commit-00000000000000000000", "tree", sourcePath)
	if err := os.WriteFile(path, []byte("corrupt"), 0600); err != nil {
		t.Fatal(err)
	}
	if _, err := client.QueryFreeze(authorized(t), &rpc.QueryWorkspaceRequest{WorkspaceInstanceId: instance}); status.Code(err) != codes.Unavailable && status.Code(err) != codes.DataLoss {
		t.Fatalf("damaged final tree confirmed: %v", err)
	}
	if _, err := client.Freeze(authorized(t), request); status.Code(err) != codes.Unavailable && status.Code(err) != codes.DataLoss {
		t.Fatalf("damaged replay confirmed: %v", err)
	}
}

func TestFrozenCopyRequiresExactSystemAuthority(t *testing.T) {
	client, close := connect(t, t.TempDir())
	defer close()
	epoch := writable(t, client)
	closed, err := client.CloseGate(authorized(t), &rpc.CloseGateRequest{WorkspaceInstanceId: instance, TransitionId: nextTransition, ExpectedEpoch: epoch})
	if err != nil {
		t.Fatal(err)
	}
	frozen, err := client.Freeze(authorized(t), &rpc.CloseGateRequest{WorkspaceInstanceId: instance, TransitionId: nextTransition, ExpectedEpoch: closed.Epoch})
	if err != nil {
		t.Fatal(err)
	}
	request := copyRequest(frozen.Epoch)
	request.FinalVerification = &rpc.FinalVerificationAuthority{FinalizationId: operation, AttemptId: mutation, FreezeOperationId: nextTransition, DeadlineUnixMillis: time.Now().Add(time.Minute).UnixMilli()}
	request.NotAfterUnixMillis = request.FinalVerification.DeadlineUnixMillis
	invalid := proto.Clone(request).(*rpc.PrepareTestCopyRequest)
	invalid.FinalVerification.FreezeOperationId = operation
	if _, err := client.PrepareTestCopy(authorized(t), invalid); status.Code(err) != codes.FailedPrecondition {
		t.Fatalf("wrong frozen identity: %v", err)
	}
	invalid = proto.Clone(request).(*rpc.PrepareTestCopyRequest)
	invalid.NotAfterUnixMillis++
	if _, err := client.PrepareTestCopy(authorized(t), invalid); status.Code(err) != codes.FailedPrecondition {
		t.Fatalf("extended budget: %v", err)
	}
	receipt, err := client.PrepareTestCopy(authorized(t), request)
	if err != nil || receipt.Revision != frozen.FrozenRevision {
		t.Fatalf("system copy: %v %v", receipt, err)
	}
	gate, err := client.QueryGate(authorized(t), &rpc.QueryWorkspaceRequest{WorkspaceInstanceId: instance})
	if err != nil || gate.State != "FROZEN" {
		t.Fatalf("system copy reopened gate: %v %v", gate, err)
	}
}

func TestFreezeSurvivesRestartAndPermanentlyRejectsOpen(t *testing.T) {
	root := t.TempDir()
	client, close := connect(t, root)
	close = sync.OnceFunc(close)
	t.Cleanup(close)
	epoch := writable(t, client)
	if _, err := client.ApplyBatch(authorized(t), update(epoch)); err != nil {
		t.Fatal(err)
	}
	request := &rpc.CloseGateRequest{WorkspaceInstanceId: instance, TransitionId: nextTransition, ExpectedEpoch: epoch}
	if _, err := client.Freeze(authorized(t), request); status.Code(err) != codes.FailedPrecondition {
		t.Fatalf("freeze of open gate: %v", err)
	}
	gate, err := client.CloseGate(authorized(t), request)
	if err != nil {
		t.Fatal(err)
	}
	request.ExpectedEpoch = gate.Epoch
	frozen, err := client.Freeze(authorized(t), request)
	close()
	if err != nil {
		t.Fatal(err)
	}
	if frozen.State != "FROZEN" || frozen.FrozenRevision != 1 || frozen.Epoch != gate.Epoch+1 {
		t.Fatalf("wrong freeze receipt: %v", frozen)
	}
	client, close = connect(t, root)
	defer close()
	replay, err := client.Freeze(authorized(t), request)
	if err != nil || !proto.Equal(frozen, replay) {
		t.Fatalf("freeze replay: %v %v", replay, err)
	}
	queried, err := client.QueryFreeze(authorized(t), &rpc.QueryWorkspaceRequest{WorkspaceInstanceId: instance})
	if err != nil || !proto.Equal(frozen, queried) {
		t.Fatalf("freeze query: %v %v", queried, err)
	}
	for _, open := range []*rpc.CloseGateRequest{
		{WorkspaceInstanceId: instance, TransitionId: transition, ExpectedEpoch: 1},
		request,
	} {
		if _, err := client.OpenGate(authorized(t), open); status.Code(err) != codes.FailedPrecondition {
			t.Fatalf("frozen gate reopened: %v", err)
		}
	}
	late := update(epoch)
	late.RequestId = operation
	late.OperationId = operation
	late.ExpectedRevision = 1
	if _, err := client.ApplyBatch(authorized(t), late); status.Code(err) != codes.FailedPrecondition {
		t.Fatalf("late write: %v", err)
	}
	if _, err := client.CloseGate(authorized(t), &rpc.CloseGateRequest{WorkspaceInstanceId: instance, TransitionId: operation, ExpectedEpoch: frozen.Epoch}); status.Code(err) != codes.FailedPrecondition {
		t.Fatalf("freeze superseded: %v", err)
	}
}
