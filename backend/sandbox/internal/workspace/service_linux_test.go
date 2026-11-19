package workspace_test

import (
	"context"
	"crypto/sha256"
	"encoding/json"
	"fmt"
	"net"
	"os"
	"path/filepath"
	"strings"
	"syscall"
	"testing"
	"time"

	rpc "codecraft.local/sandbox/internal/gen"
	"codecraft.local/sandbox/internal/workspace"
	"google.golang.org/grpc"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/credentials/insecure"
	"google.golang.org/grpc/metadata"
	"google.golang.org/grpc/status"
	"google.golang.org/protobuf/proto"
)

const fixtureKey = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
const instance = "11111111-1111-4111-8111-111111111111"
const operation = "22222222-2222-4222-8222-222222222222"

func publication() workspace.Publication {
	return workspace.Publication{Identity: &rpc.ProblemIdentity{ProblemId: "fixture", Version: "1.0.0",
		PackageSha256: strings.Repeat("a", 64), ExecutionImageDigest: "sha256:" + strings.Repeat("b", 64), Platform: "linux/amd64"},
		InitialFiles: map[string]string{"pom.xml": "<project/>", "src/main/java/Main.java": "class Main {}\n"}}
}

func connect(t *testing.T, root string, configure ...func(*workspace.Service)) (rpc.WorkspaceStorageClient, func()) {
	t.Helper()
	return connectRuntime(t, root, publication(), nil, configure...)
}

func connectRuntime(t *testing.T, root string, published workspace.Publication, runtime *workspace.ExecutionRuntime, configure ...func(*workspace.Service)) (rpc.WorkspaceStorageClient, func()) {
	t.Helper()
	service, err := workspace.NewService(root, []workspace.Publication{published}, fixtureKey, runtime)
	if err != nil {
		t.Fatal(err)
	}
	for _, option := range configure {
		option(service)
	}
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	server := grpc.NewServer(grpc.MaxRecvMsgSize(workspace.MaxBatchRequestBytes))
	rpc.RegisterWorkspaceStorageServer(server, service)
	done := make(chan error, 1)
	go func() { done <- server.Serve(listener) }()
	connection, err := grpc.NewClient(listener.Addr().String(), grpc.WithTransportCredentials(insecure.NewCredentials()),
		grpc.WithUnaryInterceptor(func(ctx context.Context, method string, request, response any, cc *grpc.ClientConn, invoke grpc.UnaryInvoker, opts ...grpc.CallOption) error {
			// Supply the fixture's initiating authorization, just as the real Java caller does.
			if preparation, ok := request.(*rpc.PrepareWorkspaceRequest); ok && preparation.NotAfterUnixMillis == 0 {
				copy := proto.Clone(preparation).(*rpc.PrepareWorkspaceRequest)
				copy.NotAfterUnixMillis = time.Now().Add(time.Minute).UnixMilli()
				request = copy
			}
			return invoke(ctx, method, request, response, cc, opts...)
		}))
	if err != nil {
		server.Stop()
		t.Fatal(err)
	}
	close := func() { connection.Close(); server.Stop(); <-done }
	return rpc.NewWorkspaceStorageClient(connection), close
}

func authorized(t *testing.T) context.Context {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	t.Cleanup(cancel)
	return metadata.AppendToOutgoingContext(ctx, "x-codecraft-internal-key", fixtureKey)
}

func TestRecoveryNeverConfirmsDamagedCommittedTree(t *testing.T) {
	for _, damage := range []string{"deleted", "modified", "symlink", "hardlink", "fifo", "missing-manifest"} {
		t.Run(damage, func(t *testing.T) {
			root := t.TempDir()
			client, close := connect(t, root)
			request := &rpc.PrepareWorkspaceRequest{WorkspaceInstanceId: instance, OperationId: operation, Problem: publication().Identity}
			_, err := client.PrepareWorkspace(authorized(t), request)
			close()
			if err != nil {
				t.Fatal(err)
			}
			path := filepath.Join(root, instance, "blobs", fixtureBlobHash())
			if damage == "missing-manifest" {
				path = filepath.Join(root, instance, "commit-00000000000000000000", "manifest.json")
			}
			if err = os.Remove(path); err != nil {
				t.Fatal(err)
			}
			switch damage {
			case "modified":
				err = os.WriteFile(path, []byte("corrupt source"), 0600)
			case "symlink", "hardlink":
				outside := filepath.Join(t.TempDir(), "outside.java")
				if err = os.WriteFile(outside, []byte("class Main {}\n"), 0600); err == nil {
					if damage == "symlink" {
						err = os.Symlink(outside, path)
					} else {
						err = os.Link(outside, path)
					}
				}
			case "fifo":
				err = syscall.Mkfifo(path, 0600)
			}
			if err != nil {
				t.Fatal(err)
			}
			client, close = connect(t, root)
			defer close()
			_, err = client.QueryWorkspace(authorized(t), &rpc.QueryWorkspaceRequest{WorkspaceInstanceId: instance})
			if status.Code(err) != codes.Unavailable && status.Code(err) != codes.DataLoss {
				t.Fatalf("damaged recovery was confirmed: %v", err)
			}
			_, err = client.PrepareWorkspace(authorized(t), request)
			if status.Code(err) != codes.Unavailable && status.Code(err) != codes.DataLoss {
				t.Fatalf("damaged preparation replay was confirmed: %v", err)
			}
		})
	}
}

func TestExpiredPreparationAuthorityCannotPublish(t *testing.T) {
	client, close := connect(t, t.TempDir())
	defer close()
	_, err := client.PrepareWorkspace(authorized(t), &rpc.PrepareWorkspaceRequest{
		WorkspaceInstanceId: instance, OperationId: operation, Problem: publication().Identity,
		NotAfterUnixMillis: time.Now().Add(-time.Second).UnixMilli()})
	if status.Code(err) != codes.FailedPrecondition {
		t.Fatalf("expired preparation authority accepted: %v", err)
	}
	_, err = client.QueryWorkspace(authorized(t), &rpc.QueryWorkspaceRequest{WorkspaceInstanceId: instance})
	if status.Code(err) != codes.NotFound {
		t.Fatalf("expired authority published a workspace: %v", err)
	}
}

func TestCancellationCleansTreesAndOrphanStagesButRetainsTombstone(t *testing.T) {
	root := t.TempDir()
	client, close := connect(t, root)
	defer close()
	if _, err := client.PrepareWorkspace(authorized(t), &rpc.PrepareWorkspaceRequest{
		WorkspaceInstanceId: instance, OperationId: operation, Problem: publication().Identity}); err != nil {
		t.Fatal(err)
	}
	orphan := filepath.Join(root, instance, "stage-interrupted")
	if err := os.Mkdir(orphan, 0700); err != nil {
		t.Fatal(err)
	}
	if _, err := client.CancelPreparation(authorized(t), &rpc.CancelPreparationRequest{
		WorkspaceInstanceId: instance, OperationId: operation}); err != nil {
		t.Fatal(err)
	}
	for _, path := range []string{orphan, filepath.Join(root, instance, "commit-00000000000000000000")} {
		if _, err := os.Stat(path); !os.IsNotExist(err) {
			t.Fatalf("cancelled resources remain: %s (%v)", path, err)
		}
	}
	view, err := client.QueryWorkspace(authorized(t), &rpc.QueryWorkspaceRequest{WorkspaceInstanceId: instance})
	if err != nil || !view.GetCancelled() {
		t.Fatalf("durable tombstone missing: %v", err)
	}
}

func TestFailedCleanupIsRetryableAfterServiceRecreation(t *testing.T) {
	root := t.TempDir()
	client, close := connect(t, root)
	if _, err := client.PrepareWorkspace(authorized(t), &rpc.PrepareWorkspaceRequest{
		WorkspaceInstanceId: instance, OperationId: operation, Problem: publication().Identity}); err != nil {
		close()
		t.Fatal(err)
	}
	tree := filepath.Join(root, instance, "blobs")
	if err := os.Chmod(tree, 0); err != nil {
		close()
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = os.Chmod(tree, 0700) })
	_, err := client.CancelPreparation(authorized(t), &rpc.CancelPreparationRequest{WorkspaceInstanceId: instance, OperationId: operation})
	close()
	if status.Code(err) != codes.Unavailable {
		t.Fatalf("cleanup failure was acknowledged: %v", err)
	}
	if err = os.Chmod(tree, 0700); err != nil {
		t.Fatal(err)
	}
	client, close = connect(t, root)
	defer close()
	if _, err = client.CancelPreparation(authorized(t), &rpc.CancelPreparationRequest{WorkspaceInstanceId: instance, OperationId: operation}); err != nil {
		t.Fatal(err)
	}
	if _, err = os.Stat(tree); !os.IsNotExist(err) {
		t.Fatalf("cleanup was not recovered: %v", err)
	}
	_, err = client.PrepareWorkspace(authorized(t), &rpc.PrepareWorkspaceRequest{WorkspaceInstanceId: instance, OperationId: operation, Problem: publication().Identity})
	if status.Code(err) != codes.FailedPrecondition {
		t.Fatalf("late preparation bypassed tombstone: %v", err)
	}
}

func TestCloseGateFencesLatePreparationAcrossRestart(t *testing.T) {
	root := t.TempDir()
	client, close := connect(t, root)
	gate, err := client.CloseGate(authorized(t), &rpc.CloseGateRequest{WorkspaceInstanceId: instance, TransitionId: operation})
	if err != nil || gate.GetEpoch() != 1 || gate.GetState() != "CLOSED" {
		close()
		t.Fatalf("gate close not confirmed: %v", err)
	}
	close()
	client, close = connect(t, root)
	defer close()
	_, err = client.PrepareWorkspace(authorized(t), &rpc.PrepareWorkspaceRequest{
		WorkspaceInstanceId: instance, OperationId: operation, Problem: publication().Identity})
	if status.Code(err) != codes.FailedPrecondition {
		t.Fatalf("late preparation crossed closed gate: %v", err)
	}
	gate, err = client.CloseGate(authorized(t), &rpc.CloseGateRequest{WorkspaceInstanceId: instance, TransitionId: operation})
	if err != nil || gate.GetEpoch() != 1 {
		t.Fatalf("close replay changed epoch: %v", err)
	}
}

func TestIncompleteManifestCannotReportReady(t *testing.T) {
	root := t.TempDir()
	client, close := connect(t, root)
	defer close()
	if _, err := client.PrepareWorkspace(authorized(t), &rpc.PrepareWorkspaceRequest{
		WorkspaceInstanceId: instance, OperationId: operation, Problem: publication().Identity}); err != nil {
		t.Fatal(err)
	}
	manifest := filepath.Join(root, instance, "commit-00000000000000000000", "manifest.json")
	data, err := os.ReadFile(manifest)
	if err != nil {
		t.Fatal(err)
	}
	var fields map[string]json.RawMessage
	if err = json.Unmarshal(data, &fields); err != nil {
		t.Fatal(err)
	}
	fields["hashes"] = json.RawMessage(`{}`)
	data, err = json.Marshal(fields)
	if err != nil {
		t.Fatal(err)
	}
	if err = os.WriteFile(manifest, data, 0600); err != nil {
		t.Fatal(err)
	}
	_, err = client.QueryWorkspace(authorized(t), &rpc.QueryWorkspaceRequest{WorkspaceInstanceId: instance})
	if status.Code(err) != codes.Unavailable {
		t.Fatalf("incomplete manifest reported ready: %v", err)
	}
}

func TestReadRejectsLinksAndSpecialFiles(t *testing.T) {
	for _, kind := range []string{"symlink", "hardlink", "fifo", "directory-link"} {
		t.Run(kind, func(t *testing.T) {
			root := t.TempDir()
			client, close := connect(t, root)
			defer close()
			_, err := client.PrepareWorkspace(authorized(t), &rpc.PrepareWorkspaceRequest{
				WorkspaceInstanceId: instance, OperationId: operation, Problem: publication().Identity})
			if err != nil {
				t.Fatal(err)
			}
			path := filepath.Join(root, instance, "blobs", fixtureBlobHash())
			outside := filepath.Join(t.TempDir(), "outside.java")
			if err := os.WriteFile(outside, []byte("class Main {}\n"), 0600); err != nil {
				t.Fatal(err)
			}
			if kind == "directory-link" {
				parent := filepath.Dir(path)
				if err = os.Rename(parent, parent+"-original"); err == nil {
					err = os.Symlink(parent+"-original", parent)
				}
			} else {
				if err := os.Remove(path); err != nil {
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
			_, err = client.ReadWorkspaceFile(authorized(t), &rpc.ReadWorkspaceFileRequest{
				WorkspaceInstanceId: instance, Path: "src/main/java/Main.java"})
			if status.Code(err) != codes.Unavailable {
				t.Fatalf("unsafe file accepted: %v", err)
			}
		})
	}
}

func TestCorruptIdentityNeverBecomesAReplayReceipt(t *testing.T) {
	root := t.TempDir()
	client, close := connect(t, root)
	defer close()
	request := &rpc.PrepareWorkspaceRequest{WorkspaceInstanceId: instance, OperationId: operation, Problem: publication().Identity}
	if _, err := client.PrepareWorkspace(authorized(t), request); err != nil {
		t.Fatal(err)
	}
	// Explicit on-disk corruption fixture; assertions still use the public gRPC boundary.
	manifest := filepath.Join(root, instance, "commit-00000000000000000000", "manifest.json")
	data, err := os.ReadFile(manifest)
	if err != nil {
		t.Fatal(err)
	}
	if err = os.WriteFile(manifest, []byte(strings.ReplaceAll(string(data), instance, "33333333-3333-4333-8333-333333333333")), 0600); err != nil {
		t.Fatal(err)
	}
	_, err = client.PrepareWorkspace(authorized(t), request)
	if status.Code(err) != codes.Unavailable {
		t.Fatalf("corrupt receipt must fail closed: %v", err)
	}
}

func TestConcurrentPrepareReplaysAndInstanceCannotBeReassigned(t *testing.T) {
	client, close := connect(t, t.TempDir())
	defer close()
	ctx := authorized(t)
	outcomes := make(chan error, 8)
	for range 8 {
		go func() {
			view, err := client.PrepareWorkspace(ctx, &rpc.PrepareWorkspaceRequest{
				WorkspaceInstanceId: instance, OperationId: operation, Problem: publication().Identity})
			if err == nil && (!view.Prepared || view.Revision != 0 || view.OperationId != operation) {
				err = fmt.Errorf("replay changed the receipt")
			}
			outcomes <- err
		}()
	}
	for range 8 {
		if err := <-outcomes; err != nil {
			t.Fatal(err)
		}
	}
	_, err := client.PrepareWorkspace(ctx, &rpc.PrepareWorkspaceRequest{WorkspaceInstanceId: instance,
		OperationId: "33333333-3333-4333-8333-333333333333", Problem: publication().Identity})
	if status.Code(err) != codes.AlreadyExists {
		t.Fatalf("instance reassigned: %v", err)
	}
	_, err = client.ReadWorkspaceFile(ctx, &rpc.ReadWorkspaceFileRequest{WorkspaceInstanceId: instance, Path: "pom.xml", ExpectedRevision: 1})
	if status.Code(err) != codes.Aborted {
		t.Fatalf("wrong revision accepted: %v", err)
	}
}

func TestRPCRejectsUnauthenticatedAndEscapingReads(t *testing.T) {
	client, close := connect(t, t.TempDir())
	defer close()
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	_, err := client.QueryWorkspace(ctx, &rpc.QueryWorkspaceRequest{WorkspaceInstanceId: instance})
	if status.Code(err) != codes.Unauthenticated {
		t.Fatalf("missing internal key: %v", err)
	}
	_, err = client.QueryWorkspace(metadata.AppendToOutgoingContext(ctx, "x-codecraft-internal-key", "wrong"), &rpc.QueryWorkspaceRequest{WorkspaceInstanceId: instance})
	if status.Code(err) != codes.Unauthenticated {
		t.Fatalf("wrong internal key: %v", err)
	}
	for _, path := range []string{"../secret", "/etc/passwd", "src\\main\\Secret.java", "src/./Main.java", "src//Main.java"} {
		_, err = client.ReadWorkspaceFile(authorized(t), &rpc.ReadWorkspaceFileRequest{WorkspaceInstanceId: instance, Path: path})
		if status.Code(err) != codes.InvalidArgument {
			t.Fatalf("path accepted %q: %v", path, err)
		}
	}
}

func TestAbandonedStageIsInvisibleAndDoesNotBlockRecovery(t *testing.T) {
	root := t.TempDir()
	stage := filepath.Join(root, instance, "stage-"+operation)
	if err := os.MkdirAll(stage, 0700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(stage, "partial.txt"), []byte("not committed"), 0600); err != nil {
		t.Fatal(err)
	}
	client, close := connect(t, root)
	defer close()
	_, err := client.QueryWorkspace(authorized(t), &rpc.QueryWorkspaceRequest{WorkspaceInstanceId: instance})
	if status.Code(err) != codes.NotFound {
		t.Fatalf("stage must not be visible: %v", err)
	}
	view, err := client.PrepareWorkspace(authorized(t), &rpc.PrepareWorkspaceRequest{
		WorkspaceInstanceId: instance, OperationId: operation, Problem: publication().Identity})
	if err != nil || !view.GetPrepared() {
		t.Fatalf("recovery must not be blocked by abandoned stage: %v", err)
	}
}

func TestCancelledPreparationCannotBeRevivedAfterRestart(t *testing.T) {
	root := t.TempDir()
	client, close := connect(t, root)
	view, err := client.CancelPreparation(authorized(t), &rpc.CancelPreparationRequest{
		WorkspaceInstanceId: instance, OperationId: operation})
	if err != nil || !view.GetCancelled() {
		close()
		t.Fatalf("cancel must persist even before prepare arrives: %v", err)
	}
	close()
	client, close = connect(t, root)
	defer close()
	_, err = client.PrepareWorkspace(authorized(t), &rpc.PrepareWorkspaceRequest{
		WorkspaceInstanceId: instance, OperationId: operation, Problem: publication().Identity})
	if status.Code(err) != codes.FailedPrecondition {
		t.Fatalf("late prepare: %v", err)
	}
	_, err = client.ReadWorkspaceFile(authorized(t), &rpc.ReadWorkspaceFileRequest{
		WorkspaceInstanceId: instance, Path: "src/main/java/Main.java"})
	if status.Code(err) != codes.NotFound {
		t.Fatalf("cancelled workspace must not expose files: %v", err)
	}
}

func TestPrepareAndReadSurviveServiceRecreation(t *testing.T) {
	root := t.TempDir()
	client, close := connect(t, root)
	request := &rpc.PrepareWorkspaceRequest{WorkspaceInstanceId: instance, OperationId: operation, Problem: publication().Identity}
	view, err := client.PrepareWorkspace(authorized(t), request)
	if err != nil {
		close()
		t.Fatal(err)
	}
	if !view.Prepared || view.Revision != 0 || len(view.Paths) != 2 {
		t.Fatal("initial workspace was not prepared at revision zero")
	}
	close()
	client, close = connect(t, root)
	defer close()
	view, err = client.QueryWorkspace(authorized(t), &rpc.QueryWorkspaceRequest{WorkspaceInstanceId: instance})
	if err != nil || !view.GetPrepared() || view.GetOperationId() != operation {
		t.Fatalf("prepare receipt did not survive recreation: %v", err)
	}
	file, err := client.ReadWorkspaceFile(authorized(t), &rpc.ReadWorkspaceFileRequest{
		WorkspaceInstanceId: instance, Path: "src/main/java/Main.java", ExpectedRevision: 0})
	if err != nil || file.GetContent() != "class Main {}\n" {
		t.Fatalf("committed file did not survive recreation: %v", err)
	}
}

func fixtureBlobHash() string {
	return fmt.Sprintf("%x", sha256.Sum256([]byte(publication().InitialFiles[sourcePath])))
}
