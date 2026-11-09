package workspace_test

import (
	"context"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"syscall"
	"testing"
	"time"

	rpc "codecraft.local/sandbox/internal/gen"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"
	"google.golang.org/protobuf/proto"
)

const transition = "33333333-3333-4333-8333-333333333333"
const mutation = "44444444-4444-4444-8444-444444444444"
const nextTransition = "55555555-5555-4555-8555-555555555555"
const sourcePath = "src/main/java/Main.java"

func writable(t *testing.T, client rpc.WorkspaceStorageClient) uint64 {
	t.Helper()
	ctx := authorized(t)
	if _, err := client.PrepareWorkspace(ctx, &rpc.PrepareWorkspaceRequest{WorkspaceInstanceId: instance, OperationId: operation, Problem: publication().Identity}); err != nil {
		t.Fatal(err)
	}
	closed, err := client.CloseGate(ctx, &rpc.CloseGateRequest{WorkspaceInstanceId: instance, TransitionId: transition})
	if err != nil {
		t.Fatal(err)
	}
	opened, err := client.OpenGate(ctx, &rpc.CloseGateRequest{WorkspaceInstanceId: instance, TransitionId: transition, ExpectedEpoch: closed.Epoch})
	if err != nil {
		t.Fatal(err)
	}
	return opened.Epoch
}

func update(epoch uint64) *rpc.ApplyBatchRequest {
	return &rpc.ApplyBatchRequest{WorkspaceInstanceId: instance, RequestId: mutation, OperationId: mutation,
		GateEpoch: epoch, NotAfterUnixMillis: time.Now().Add(time.Minute).UnixMilli(),
		Changes: []*rpc.FileChange{{Path: sourcePath, Kind: "UPDATE", Content: "class Main { int value; }\n"}}}
}

func TestMutationReceiptSurvivesLostResponseAndRestart(t *testing.T) {
	root := t.TempDir()
	client, close := connect(t, root)
	request := update(writable(t, client))
	receipt, err := client.ApplyBatch(authorized(t), request)
	close()
	if err != nil {
		t.Fatal(err)
	}
	if receipt.Revision != 1 || !receipt.Changed {
		t.Fatalf("wrong receipt: %v", receipt)
	}
	// Discard the response as a lost-response fixture, then recreate the service.
	client, close = connect(t, root)
	defer close()
	recovered, err := client.QueryMutation(authorized(t), &rpc.QueryMutationRequest{WorkspaceInstanceId: instance, RequestId: mutation})
	if err != nil || !proto.Equal(receipt, recovered) {
		t.Fatalf("receipt not recovered: %v %v", recovered, err)
	}
	request.NotAfterUnixMillis = 1 // Replay is a historical observation, not a new write.
	replay, err := client.ApplyBatch(authorized(t), request)
	if err != nil || !proto.Equal(receipt, replay) {
		t.Fatalf("replay changed result: %v %v", replay, err)
	}
	request.Changes[0].Content = "different"
	if _, err = client.ApplyBatch(authorized(t), request); status.Code(err) != codes.AlreadyExists {
		t.Fatalf("key reused: %v", err)
	}
	file, err := client.ReadWorkspaceFile(authorized(t), &rpc.ReadWorkspaceFileRequest{WorkspaceInstanceId: instance, ExpectedRevision: 1, Path: sourcePath})
	if err != nil || file.Content != "class Main { int value; }\n" {
		t.Fatalf("content not committed: %v %v", file, err)
	}
}

func TestNoChangeReceiptAndWorkspaceWideConflict(t *testing.T) {
	client, close := connect(t, t.TempDir())
	defer close()
	request := update(writable(t, client))
	request.Changes[0].Content = publication().InitialFiles[sourcePath]
	receipt, err := client.ApplyBatch(authorized(t), request)
	if err != nil || receipt.GetChanged() || receipt.GetRevision() != 0 {
		t.Fatalf("no-op advanced revision: %v %v", receipt, err)
	}
	changed := update(request.GateEpoch)
	changed.RequestId = nextTransition
	if _, err = client.ApplyBatch(authorized(t), changed); err != nil {
		t.Fatal(err)
	}
	replay, err := client.ApplyBatch(authorized(t), request)
	if err != nil || !proto.Equal(receipt, replay) {
		t.Fatalf("no-op replay reinterpreted: %v %v", replay, err)
	}
	changed.RequestId = operation
	changed.Changes = []*rpc.FileChange{{Path: "src/test/java/New.java", Kind: "CREATE", Content: "class New {}"}}
	if _, err = client.ApplyBatch(authorized(t), changed); status.Code(err) != codes.Aborted {
		t.Fatalf("different file bypassed workspace conflict: %v", err)
	}
}

func TestAtomicRenameAndBatchFailure(t *testing.T) {
	client, close := connect(t, t.TempDir())
	defer close()
	request := update(writable(t, client))
	request.Changes = []*rpc.FileChange{{Path: sourcePath, Kind: "DELETE"}, {Path: "src/main/java/Renamed.java", Kind: "CREATE", Content: "class Renamed {}"}, {Path: "src/main/java/Missing.java", Kind: "DELETE"}}
	if _, err := client.ApplyBatch(authorized(t), request); status.Code(err) != codes.NotFound {
		t.Fatalf("missing delete not rejected: %v", err)
	}
	view, err := client.QueryWorkspace(authorized(t), &rpc.QueryWorkspaceRequest{WorkspaceInstanceId: instance})
	if err != nil || view.Revision != 0 || len(view.Paths) != 2 {
		t.Fatalf("partial batch published: %v %v", view, err)
	}
	request.Changes = request.Changes[:2]
	receipt, err := client.ApplyBatch(authorized(t), request)
	if err != nil || receipt.Revision != 1 {
		t.Fatalf("rename not atomic: %v %v", receipt, err)
	}
	if _, err = client.ReadWorkspaceFile(authorized(t), &rpc.ReadWorkspaceFileRequest{WorkspaceInstanceId: instance, ExpectedRevision: 1, Path: sourcePath}); status.Code(err) != codes.NotFound {
		t.Fatalf("old file survived rename: %v", err)
	}
}

func TestClosedGateFencesDelayedMutationAndStaleOpen(t *testing.T) {
	root := t.TempDir()
	client, close := connect(t, root)
	request := update(writable(t, client))
	closed, err := client.CloseGate(authorized(t), &rpc.CloseGateRequest{WorkspaceInstanceId: instance, TransitionId: nextTransition, ExpectedEpoch: request.GateEpoch})
	close()
	if err != nil {
		t.Fatal(err)
	}
	client, close = connect(t, root)
	defer close()
	if _, err = client.ApplyBatch(authorized(t), request); status.Code(err) != codes.FailedPrecondition {
		t.Fatalf("late write crossed fence: %v", err)
	}
	// Replaying the old Open returns its receipt but must not reopen the new gate.
	if _, err = client.OpenGate(authorized(t), &rpc.CloseGateRequest{WorkspaceInstanceId: instance, TransitionId: transition, ExpectedEpoch: 1}); err != nil {
		t.Fatal(err)
	}
	gate, err := client.QueryGate(authorized(t), &rpc.QueryWorkspaceRequest{WorkspaceInstanceId: instance})
	if err != nil || !proto.Equal(closed, gate) {
		t.Fatalf("stale open changed authority: %v %v", gate, err)
	}
	if _, err = client.QueryMutation(authorized(t), &rpc.QueryMutationRequest{WorkspaceInstanceId: instance, RequestId: mutation}); status.Code(err) != codes.NotFound {
		t.Fatalf("rejected request has receipt: %v", err)
	}
}

func TestMutationRejectsUnsafePathsAndInvalidBatches(t *testing.T) {
	cases := []struct {
		name    string
		changes []*rpc.FileChange
		code    codes.Code
	}{
		{"protected", []*rpc.FileChange{{Path: "pom.xml", Kind: "UPDATE", Content: "override"}}, codes.PermissionDenied},
		{"traversal", []*rpc.FileChange{{Path: "src/main/java/../secret", Kind: "CREATE"}}, codes.InvalidArgument},
		{"prefix", []*rpc.FileChange{{Path: "src/main/java-other/X.java", Kind: "CREATE"}}, codes.PermissionDenied},
		{"binary", []*rpc.FileChange{{Path: sourcePath, Kind: "UPDATE", Content: "\x00"}}, codes.InvalidArgument},
		{"duplicate", []*rpc.FileChange{{Path: sourcePath, Kind: "DELETE"}, {Path: sourcePath, Kind: "CREATE"}}, codes.InvalidArgument},
		{"exists", []*rpc.FileChange{{Path: sourcePath, Kind: "CREATE"}}, codes.AlreadyExists},
		{"missing", []*rpc.FileChange{{Path: "src/main/java/Missing.java", Kind: "UPDATE"}}, codes.NotFound},
		{"delete-content", []*rpc.FileChange{{Path: sourcePath, Kind: "DELETE", Content: "ignored?"}}, codes.InvalidArgument},
		{"empty", nil, codes.InvalidArgument},
		{"oversized", []*rpc.FileChange{{Path: sourcePath, Kind: "UPDATE", Content: strings.Repeat("x", 1024*1024+1)}}, codes.ResourceExhausted},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			client, close := connect(t, t.TempDir())
			defer close()
			request := update(writable(t, client))
			request.Changes = tc.changes
			if _, err := client.ApplyBatch(authorized(t), request); status.Code(err) != tc.code {
				t.Fatalf("expected %v got %v", tc.code, err)
			}
			view, err := client.QueryWorkspace(authorized(t), &rpc.QueryWorkspaceRequest{WorkspaceInstanceId: instance})
			if err != nil || view.Revision != 0 {
				t.Fatalf("rejected batch changed workspace: %v %v", view, err)
			}
		})
	}
}

func TestMutationExpiredOrCancelledCannotPublish(t *testing.T) {
	client, close := connect(t, t.TempDir())
	defer close()
	request := update(writable(t, client))
	request.NotAfterUnixMillis = time.Now().Add(-time.Second).UnixMilli()
	if _, err := client.ApplyBatch(authorized(t), request); status.Code(err) != codes.FailedPrecondition {
		t.Fatalf("expired accepted: %v", err)
	}
	request.NotAfterUnixMillis = time.Now().Add(time.Minute).UnixMilli()
	ctx, cancel := context.WithCancel(authorized(t))
	cancel()
	if _, err := client.ApplyBatch(ctx, request); status.Code(err) != codes.Canceled {
		t.Fatalf("cancelled accepted: %v", err)
	}
	if _, err := client.QueryMutation(authorized(t), &rpc.QueryMutationRequest{WorkspaceInstanceId: instance, RequestId: mutation}); status.Code(err) != codes.NotFound {
		t.Fatalf("cancelled published: %v", err)
	}
}

func TestMissingReceiptDoesNotCancelADelayedWrite(t *testing.T) {
	client, close := connect(t, t.TempDir())
	defer close()
	request := update(writable(t, client))
	if _, err := client.QueryMutation(authorized(t), &rpc.QueryMutationRequest{WorkspaceInstanceId: instance, RequestId: mutation}); status.Code(err) != codes.NotFound {
		t.Fatal(err)
	}
	// No CloseGate occurred: the not-yet-arrived request is still authorized.
	if receipt, err := client.ApplyBatch(authorized(t), request); err != nil || receipt.Revision != 1 {
		t.Fatalf("missing receipt was treated as cancellation: %v %v", receipt, err)
	}
}

func TestConcurrentSameRequestPublishesOneReceipt(t *testing.T) {
	client, close := connect(t, t.TempDir())
	defer close()
	epoch := writable(t, client)
	ctx := authorized(t)
	results := make(chan error, 8)
	for range 8 {
		go func() {
			receipt, err := client.ApplyBatch(ctx, update(epoch))
			if err == nil && (receipt.Revision != 1 || !receipt.Changed) {
				err = fmt.Errorf("inconsistent duplicate receipt")
			}
			results <- err
		}()
	}
	for range 8 {
		if err := <-results; err != nil {
			t.Fatal(err)
		}
	}
	view, err := client.QueryWorkspace(ctx, &rpc.QueryWorkspaceRequest{WorkspaceInstanceId: instance})
	if err != nil || view.Revision != 1 {
		t.Fatalf("duplicate wrote more than once: %v %v", view, err)
	}
}

func TestConcurrentMutationHasOneWinner(t *testing.T) {
	client, close := connect(t, t.TempDir())
	defer close()
	epoch := writable(t, client)
	ctx := authorized(t)
	results := make(chan error, 8)
	for i := range 8 {
		go func() {
			request := update(epoch)
			request.RequestId = fmt.Sprintf("%08d-4444-4444-8444-444444444444", i)
			_, err := client.ApplyBatch(ctx, request)
			results <- err
		}()
	}
	successes := 0
	for range 8 {
		err := <-results
		if err == nil {
			successes++
		} else if status.Code(err) != codes.Aborted {
			t.Fatal(err)
		}
	}
	if successes != 1 {
		t.Fatalf("got %d winners", successes)
	}
}

func TestMutationRejectsDamagedOrLinkedSource(t *testing.T) {
	for _, kind := range []string{"symlink", "hardlink", "fifo", "directory-link"} {
		t.Run(kind, func(t *testing.T) {
			root := t.TempDir()
			client, close := connect(t, root)
			defer close()
			request := update(writable(t, client))
			path := filepath.Join(root, instance, "commit-00000000000000000000", "tree", sourcePath)
			outside := filepath.Join(t.TempDir(), "outside.java")
			if err := os.WriteFile(outside, []byte(publication().InitialFiles[sourcePath]), 0600); err != nil {
				t.Fatal(err)
			}
			var err error
			if kind == "directory-link" {
				parent := filepath.Dir(path)
				err = os.Rename(parent, parent+"-original")
				if err == nil {
					err = os.Symlink(parent+"-original", parent)
				}
			} else {
				if err = os.Remove(path); err != nil {
					t.Fatal(err)
				}
				switch kind {
				case "symlink":
					err = os.Symlink(outside, path)
				case "hardlink":
					err = os.Link(outside, path)
				case "fifo":
					err = syscall.Mkfifo(path, 0600)
				}
			}
			if err != nil {
				t.Fatal(err)
			}
			if _, err = client.ApplyBatch(authorized(t), request); status.Code(err) != codes.Unavailable {
				t.Fatalf("unsafe source accepted: %v", err)
			}
			if _, err = os.Stat(filepath.Join(root, instance, "commit-00000000000000000001")); !os.IsNotExist(err) {
				t.Fatalf("unsafe source published: %v", err)
			}
		})
	}
}

func TestWorkspaceContentLimitsRejectWholeBatch(t *testing.T) {
	t.Run("file-count", func(t *testing.T) {
		client, close := connect(t, t.TempDir())
		defer close()
		request := update(writable(t, client))
		request.Changes = nil
		for i := range 198 {
			request.Changes = append(request.Changes, &rpc.FileChange{Path: fmt.Sprintf("src/test/java/F%d.java", i), Kind: "CREATE", Content: "test"})
		}
		if _, err := client.ApplyBatch(authorized(t), request); err != nil {
			t.Fatal(err)
		}
		request.RequestId = nextTransition
		request.ExpectedRevision = 1
		request.Changes = []*rpc.FileChange{{Path: "src/test/java/Overflow.java", Kind: "CREATE"}}
		if _, err := client.ApplyBatch(authorized(t), request); status.Code(err) != codes.ResourceExhausted {
			t.Fatalf("count limit not enforced: %v", err)
		}
		view, err := client.QueryWorkspace(authorized(t), &rpc.QueryWorkspaceRequest{WorkspaceInstanceId: instance})
		if err != nil || view.Revision != 1 || len(view.Paths) != 200 {
			t.Fatalf("over-limit changed tree: %v %v", view, err)
		}
	})
	t.Run("total-bytes", func(t *testing.T) {
		client, close := connect(t, t.TempDir())
		defer close()
		epoch := writable(t, client)
		initialBytes := 0
		for _, content := range publication().InitialFiles {
			initialBytes += len(content)
		}
		for i := range 10 {
			size := 1024 * 1024
			if i == 9 {
				size -= initialBytes
			}
			request := update(epoch)
			request.RequestId = fmt.Sprintf("%08d-4444-4444-8444-444444444444", i)
			request.ExpectedRevision = uint64(i)
			request.Changes = []*rpc.FileChange{{Path: fmt.Sprintf("src/test/java/F%d.java", i), Kind: "CREATE", Content: strings.Repeat("x", size)}}
			if _, err := client.ApplyBatch(authorized(t), request); err != nil {
				t.Fatalf("exact limit rejected at %d: %v", i, err)
			}
		}
		request := update(epoch)
		request.ExpectedRevision = 10
		request.Changes = []*rpc.FileChange{{Path: "src/test/java/Overflow.java", Kind: "CREATE", Content: "x"}}
		if _, err := client.ApplyBatch(authorized(t), request); status.Code(err) != codes.ResourceExhausted {
			t.Fatalf("byte limit not enforced: %v", err)
		}
		view, err := client.QueryWorkspace(authorized(t), &rpc.QueryWorkspaceRequest{WorkspaceInstanceId: instance})
		if err != nil || view.Revision != 10 {
			t.Fatalf("over-limit changed revision: %v %v", view, err)
		}
	})
}

func TestPreparationCancellationCannotEraseMutationEvidence(t *testing.T) {
	client, close := connect(t, t.TempDir())
	defer close()
	if _, err := client.ApplyBatch(authorized(t), update(writable(t, client))); err != nil {
		t.Fatal(err)
	}
	if _, err := client.CancelPreparation(authorized(t), &rpc.CancelPreparationRequest{WorkspaceInstanceId: instance, OperationId: operation}); status.Code(err) != codes.FailedPrecondition {
		t.Fatalf("preparation cancellation erased active tree: %v", err)
	}
	if _, err := client.QueryMutation(authorized(t), &rpc.QueryMutationRequest{WorkspaceInstanceId: instance, RequestId: mutation}); err != nil {
		t.Fatal(err)
	}
}

func TestMutationAndCloseRaceHasOneDurableOrder(t *testing.T) {
	for range 12 {
		root := t.TempDir()
		client, close := connect(t, root)
		request := update(writable(t, client))
		ctx := authorized(t)
		results := make(chan error, 1)
		go func() { _, err := client.ApplyBatch(ctx, request); results <- err }()
		_, err := client.CloseGate(ctx, &rpc.CloseGateRequest{WorkspaceInstanceId: instance, TransitionId: nextTransition, ExpectedEpoch: request.GateEpoch})
		if err != nil {
			close()
			t.Fatal(err)
		}
		writeErr := <-results
		receipt, queryErr := client.QueryMutation(ctx, &rpc.QueryMutationRequest{WorkspaceInstanceId: instance, RequestId: mutation})
		close()
		if writeErr == nil {
			if queryErr != nil || receipt.Revision != 1 {
				t.Fatalf("committed write lost: %v", queryErr)
			}
		} else {
			if status.Code(writeErr) != codes.FailedPrecondition || status.Code(queryErr) != codes.NotFound {
				t.Fatalf("no deterministic order: %v / %v", writeErr, queryErr)
			}
		}
	}
}

func TestDamagedMutationReceiptIsNotReportedMissing(t *testing.T) {
	for _, damage := range []string{"broken-json", "missing-receipts", "invalid-receipt"} {
		t.Run(damage, func(t *testing.T) {
			root := t.TempDir()
			client, close := connect(t, root)
			if _, err := client.ApplyBatch(authorized(t), update(writable(t, client))); err != nil {
				close()
				t.Fatal(err)
			}
			close()
			path := filepath.Join(root, instance, "commit-00000000000000000001", "manifest.json")
			data, err := os.ReadFile(path)
			if err != nil {
				t.Fatal(err)
			}
			var fields map[string]json.RawMessage
			if err = json.Unmarshal(data, &fields); err != nil {
				t.Fatal(err)
			}
			switch damage {
			case "missing-receipts":
				delete(fields, "mutations")
			case "invalid-receipt":
				fields["mutations"] = json.RawMessage(`{"` + mutation + `":null}`)
			}
			data, err = json.Marshal(fields)
			if err != nil {
				t.Fatal(err)
			}
			if damage == "broken-json" {
				data = []byte("broken")
			}
			if err = os.WriteFile(path, data, 0600); err != nil {
				t.Fatal(err)
			}
			client, close = connect(t, root)
			defer close()
			if _, err = client.QueryMutation(authorized(t), &rpc.QueryMutationRequest{WorkspaceInstanceId: instance, RequestId: mutation}); status.Code(err) != codes.Unavailable {
				t.Fatalf("corruption concealed: %v", err)
			}
		})
	}
}
