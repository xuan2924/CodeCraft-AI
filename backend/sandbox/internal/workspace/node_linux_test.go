package workspace

import (
	"context"
	"os"
	"path/filepath"
	"strings"
	"testing"

	rpc "codecraft.local/sandbox/internal/gen"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/metadata"
	"google.golang.org/grpc/status"
)

func TestNodeStorageIdentityPersistsAndFencesWrongVolume(t *testing.T) {
	root := t.TempDir()
	key := strings.Repeat("a", 64)
	first, err := NewService(root, nil, key, nil)
	if err != nil {
		t.Fatal(err)
	}
	second, err := NewService(root, nil, key, nil)
	if err != nil || first.storageID != second.storageID || len(first.storageID) != 64 {
		t.Fatalf("identity changed: %v", err)
	}
	ctx := metadata.NewIncomingContext(context.Background(), metadata.Pairs("x-codecraft-internal-key", key))
	result, err := second.NodeStatus(ctx, &rpc.NodeStatusRequest{})
	if err != nil || result.StorageId != first.storageID || result.Ready {
		t.Fatalf("invalid readiness: %v %+v", err, result)
	}
	wrong := metadata.NewIncomingContext(context.Background(), metadata.Pairs("x-codecraft-internal-key", key, "x-codecraft-storage-id", strings.Repeat("b", 64)))
	if _, err = second.NodeStatus(wrong, &rpc.NodeStatusRequest{}); status.Code(err) != codes.FailedPrecondition {
		t.Fatalf("wrong volume accepted: %v", err)
	}
	if _, err = second.NodeStatus(context.Background(), &rpc.NodeStatusRequest{}); status.Code(err) != codes.Unauthenticated {
		t.Fatalf("anonymous status accepted: %v", err)
	}
	if err = os.WriteFile(filepath.Join(root, ".storage-id"), []byte("corrupt"), 0600); err != nil {
		t.Fatal(err)
	}
	if _, err = second.NodeStatus(ctx, &rpc.NodeStatusRequest{}); status.Code(err) != codes.FailedPrecondition {
		t.Fatalf("live node ignored replaced volume identity: %v", err)
	}
	if _, err = NewService(root, nil, key, nil); err == nil {
		t.Fatal("corrupt identity silently replaced")
	}
}
