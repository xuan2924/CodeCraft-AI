package workspace_test

import (
	"crypto/sha256"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"syscall"
	"testing"

	rpc "codecraft.local/sandbox/internal/gen"
	"codecraft.local/sandbox/internal/workspace"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"
	"google.golang.org/protobuf/proto"
)

func TestArchiveFreezeAndHydrateAcrossNodes(t *testing.T) {
	objects := map[string][]byte{}
	var mu sync.Mutex
	rejectManifest := true
	corruptBlob := false
	requests := 0
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		mu.Lock()
		defer mu.Unlock()
		requests++
		key := r.URL.Path
		auth := r.Header.Get("Authorization")
		body, err := io.ReadAll(r.Body)
		sha := fmt.Sprintf("%x", sha256.Sum256(body))
		if err != nil || !strings.HasPrefix(auth, "AWS4-HMAC-SHA256 Credential=fixture/") || !strings.Contains(auth, "/us-east-1/s3/aws4_request") || !strings.Contains(auth, "Signature=") || r.Header.Get("X-Amz-Date") == "" || sha != r.Header.Get("X-Amz-Content-Sha256") {
			t.Error("unsigned or invalid archive request")
			w.WriteHeader(403)
			return
		}
		if r.Method == "PUT" {
			if !strings.HasSuffix(key, "/"+sha) || r.Header.Get("If-None-Match") != "*" {
				t.Error("non-CAS PUT")
				w.WriteHeader(400)
				return
			}
			if strings.Contains(key, "/manifests/") && rejectManifest {
				w.WriteHeader(503)
				return
			}
			if _, ok := objects[key]; ok {
				w.WriteHeader(412)
				return
			}
			objects[key] = body
			w.WriteHeader(200)
			return
		}
		if r.Method != "GET" {
			w.WriteHeader(405)
			return
		}
		data, ok := objects[key]
		if !ok {
			w.WriteHeader(404)
			return
		}
		if corruptBlob && strings.Contains(key, "/blobs/") {
			data = []byte("bad")
		}
		w.Write(data)
	}))
	defer server.Close()
	configure := func(s *workspace.Service) {
		if err := s.ConfigureFrozenArchive(workspace.S3Config{Endpoint: server.URL, Bucket: "archive", Region: "us-east-1", AccessKey: "fixture", SecretKey: "fixture-secret"}); err != nil {
			t.Fatal(err)
		}
	}
	a, stop := connect(t, t.TempDir(), configure)
	defer stop()
	ctx := authorized(t)
	request := update(writable(t, a))
	write, err := a.ApplyBatch(ctx, request)
	if err != nil {
		t.Fatal(err)
	}
	closeReq := &rpc.CloseGateRequest{WorkspaceInstanceId: instance, TransitionId: nextTransition, ExpectedEpoch: request.GateEpoch}
	closed, err := a.CloseGate(ctx, closeReq)
	if err != nil {
		t.Fatal(err)
	}
	mu.Lock()
	if requests != 0 {
		t.Error("ordinary writes accessed S3")
	}
	mu.Unlock()
	closeReq.ExpectedEpoch = closed.Epoch
	if _, err = a.Freeze(ctx, closeReq); status.Code(err) != codes.Unavailable {
		t.Fatalf("failed upload acknowledged: %v", err)
	}
	if _, err = a.QueryFreeze(ctx, &rpc.QueryWorkspaceRequest{WorkspaceInstanceId: instance}); status.Code(err) != codes.NotFound {
		t.Fatalf("failed freeze published: %v", err)
	}
	mu.Lock()
	rejectManifest = false
	mu.Unlock()
	frozen, err := a.Freeze(ctx, closeReq)
	if err != nil || len(frozen.ArchiveSha256) != 64 {
		t.Fatalf("freeze: %v %v", frozen, err)
	}
	root := t.TempDir()
	orphan := filepath.Join(root, "hydrate-"+instance)
	if err = os.Mkdir(orphan, 0700); err != nil {
		t.Fatal(err)
	}
	var syncs atomic.Int32
	b, stopB := connect(t, root, configure, func(s *workspace.Service) {
		workspace.HydrateRootSyncForTest(s, func(fd int) error {
			if syncs.Add(1) <= 2 {
				return syscall.EIO
			}
			return syscall.Fsync(fd)
		})
	})
	if _, err = os.Stat(orphan); !os.IsNotExist(err) {
		t.Fatalf("interrupted restore leaked: %v", err)
	}
	defer stopB()
	dest, err := b.NodeStatus(ctx, &rpc.NodeStatusRequest{})
	if err != nil {
		t.Fatal(err)
	}
	q := &rpc.HydrateFrozenRequest{WorkspaceInstanceId: instance, FreezeOperationId: nextTransition, ArchiveSha256: frozen.ArchiveSha256}
	if _, err = b.HydrateFrozen(ctx, q); status.Code(err) != codes.FailedPrecondition {
		t.Fatalf("missing destination: %v", err)
	}
	q.DestinationStorageId = dest.StorageId
	mu.Lock()
	corruptBlob = true
	mu.Unlock()
	if _, err = b.HydrateFrozen(ctx, q); err == nil {
		t.Fatal("accepted corrupt remote data")
	}
	if _, err = os.Stat(filepath.Join(root, instance)); !os.IsNotExist(err) {
		t.Fatalf("partially visible restore: %v", err)
	}
	mu.Lock()
	corruptBlob = false
	mu.Unlock()
	for range 2 { // First publish and its replay must both reject an unconfirmed parent directory.
		if _, err := b.HydrateFrozen(ctx, q); err == nil {
			t.Fatal("acknowledged before root fsync")
		}
	}
	for range 2 {
		got, err := b.HydrateFrozen(ctx, q)
		if err != nil || !proto.Equal(got, frozen) {
			t.Fatalf("hydrate: %v %v", got, err)
		}
	}
	file, err := b.ReadWorkspaceFile(ctx, &rpc.ReadWorkspaceFileRequest{WorkspaceInstanceId: instance, ExpectedRevision: write.Revision, Path: sourcePath})
	if err != nil || file.Content != request.Changes[0].Content || file.ContentSha256 != write.ContentSha256[sourcePath] {
		t.Fatalf("restore file: %v %v", file, err)
	}
	snapshot, err := b.QueryWorkspace(ctx, &rpc.QueryWorkspaceRequest{WorkspaceInstanceId: instance})
	if err != nil || snapshot.GetContentSha256()[sourcePath] != file.ContentSha256 {
		t.Fatalf("snapshot: %v %v", snapshot, err)
	}
	if _, err = b.OpenGate(ctx, closeReq); status.Code(err) != codes.FailedPrecondition {
		t.Fatalf("hydrated workspace reopened: %v", err)
	}
	if _, err = b.DeleteWorkspace(ctx, &rpc.QueryWorkspaceRequest{WorkspaceInstanceId: instance}); err != nil {
		t.Fatal(err)
	}
	if _, err = b.HydrateFrozen(ctx, q); status.Code(err) != codes.FailedPrecondition {
		t.Fatalf("restore bypassed deletion fence: %v", err)
	}
}
