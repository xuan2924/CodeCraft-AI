package workspace

import (
	"context"
	"errors"
	"fmt"
	"os"
	"strings"
	"testing"
	"time"

	rpc "codecraft.local/sandbox/internal/gen"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"
)

func TestManifestCapacityIndependentOfSourceLimit(t *testing.T) {
	dir, err := os.Open(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	defer dir.Close()
	const id = "10000000-0000-0000-0000-000000000001"
	fd := int(dir.Fd())
	r := &record{Snapshot: &rpc.WorkspaceSnapshot{Prepared: true, WorkspaceInstanceId: id, OperationId: id, Problem: &rpc.ProblemIdentity{}, Paths: []string{"a"}}, Hashes: map[string]string{"a": sum(nil)}, Mutations: map[string]*rpc.MutationReceipt{}}
	for i := 0; i < 5000; i++ {
		key := fmt.Sprintf("20000000-0000-0000-0000-%012d", i)
		r.Mutations[key] = &rpc.MutationReceipt{WorkspaceInstanceId: id, OperationId: id, RequestId: key, Fingerprint: sum(nil)}
	}
	seq := uint64(len(r.Mutations))
	if err := publish(context.Background(), fd, seq, r, map[string]string{"a": ""}, nil, time.Now, time.Time{}); err != nil {
		t.Fatal(err)
	}
	got, commit, err := latest(fd, id)
	if err != nil || len(got.Mutations) != len(r.Mutations) {
		t.Fatalf("retained manifest unreadable: %v", err)
	}
	if _, err = readPath(fd, commit+"/manifest.json"); err == nil {
		t.Fatal("ordinary file reader accepted oversized source")
	}
	r.Snapshot.Paths = append(r.Snapshot.Paths, strings.Repeat("x", manifestLimit))
	err = publish(context.Background(), fd, seq+1, r, nil, nil, time.Now, time.Time{})
	if !errors.Is(err, errManifestCapacity) || status.Code(storageError(err)) != codes.ResourceExhausted {
		t.Fatalf("oversized manifest: %v", err)
	}
	_, latestCommit, err := latest(fd, id)
	if err != nil || latestCommit != commit {
		t.Fatalf("failed publication displaced receipts: %v", err)
	}
}
