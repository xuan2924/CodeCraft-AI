package workspace

import (
	"context"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"testing"

	rpc "codecraft.local/sandbox/internal/gen"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/metadata"
	"google.golang.org/grpc/status"
)

func TestNodeStatusAdvertisesTrustedPreparePackages(t *testing.T) {
	a, b := strings.Repeat("a", 64), strings.Repeat("b", 64)
	var publications []Publication
	for _, sha := range []string{b, a, b} {
		publications = append(publications, Publication{Identity: &rpc.ProblemIdentity{PackageSha256: sha}, InitialFiles: map[string]string{"pom.xml": "fixture"}, Challenge: &ChallengePublication{Identity: &rpc.ProblemIdentity{PackageSha256: strings.Repeat("c", 64), ExecutionImageDigest: "separate"}, Execution: &ExecutionPolicy{}}})
	}
	s, err := NewService(t.TempDir(), publications, a, nil)
	if err != nil {
		t.Fatal(err)
	}
	publications[0].Identity.PackageSha256 = strings.Repeat("d", 64)
	ctx := metadata.NewIncomingContext(context.Background(), metadata.Pairs("x-codecraft-internal-key", a))
	got, err := s.NodeStatus(ctx, &rpc.NodeStatusRequest{})
	if err != nil || !slices.Equal(got.GetLoadedPackageSha256(), []string{a, b}) {
		t.Fatalf("loaded prepare packages: %v %v", got, err)
	}
}

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
	if err != nil || result.StorageId != first.storageID || len(result.LoadedPackageSha256) != 0 || result.Ready {
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
