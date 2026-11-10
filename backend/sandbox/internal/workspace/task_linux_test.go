package workspace_test

import (
	"context"
	"testing"

	rpc "codecraft.local/sandbox/internal/gen"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"
)

func TestTaskRPCRequiresInternalIdentity(t *testing.T) {
	client, close := connect(t, t.TempDir())
	defer close()
	if _, err := client.StartTest(context.Background(), &rpc.StartTestRequest{}); status.Code(err) != codes.Unauthenticated {
		t.Fatalf("unauthorized start: %v", err)
	}
	if _, err := client.QueryTask(context.Background(), &rpc.QueryTaskRequest{}); status.Code(err) != codes.Unauthenticated {
		t.Fatalf("unauthorized query: %v", err)
	}
}
