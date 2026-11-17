package workspace_test

import (
	"context"
	"os"
	"path/filepath"
	"strings"
	"testing"

	rpc "codecraft.local/sandbox/internal/gen"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"
)

func TestGrepWorkspaceCommittedTreeAndLimits(t *testing.T) {
	published := publication()
	published.InitialFiles["AGENTS.md"] = "before\na.*b\nafter\na.*b\nlast"
	published.InitialFiles["docs/AGENTS.md"] = "a.*b"
	client, close := connectRuntime(t, t.TempDir(), published, nil)
	defer close()
	writable(t, client)
	request := &rpc.GrepWorkspaceRequest{WorkspaceInstanceId: instance, Pattern: "a.*b", PathPrefix: "AGENTS", ContextLines: 1, Limit: 200}
	result, err := client.GrepWorkspace(authorized(t), request)
	if err != nil || result.Truncated || len(result.Lines) != 5 || result.Lines[1] != "AGENTS.md:2: a.*b" || result.Revision != 0 {
		t.Fatalf("literal/context/dedup: %v %v", result, err)
	}
	request.Limit = 1
	result, err = client.GrepWorkspace(authorized(t), request)
	if err != nil || !result.Truncated || len(result.Lines) != 4 {
		t.Fatalf("match limit: %v %v", result, err)
	}
	request.Pattern, request.IsRegex, request.Limit = "^a.*b$", true, 200
	result, err = client.GrepWorkspace(authorized(t), request)
	if err != nil || len(result.Lines) != 5 {
		t.Fatalf("regex: %v %v", result, err)
	}
	request.ExpectedRevision = 1
	if _, err = client.GrepWorkspace(authorized(t), request); status.Code(err) != codes.Aborted {
		t.Fatalf("stale: %v", err)
	}
	request.ExpectedRevision = 0
	for _, pattern := range []string{"(a+)+$", "a|b", "a*", "^a**", "["} {
		request.Pattern = pattern
		if _, err = client.GrepWorkspace(authorized(t), request); status.Code(err) != codes.InvalidArgument {
			t.Fatalf("unsafe regex %s: %v", pattern, err)
		}
	}
	request.Pattern, request.IsRegex, request.PathPrefix = "a", false, "../"
	if _, err = client.GrepWorkspace(authorized(t), request); status.Code(err) != codes.InvalidArgument {
		t.Fatalf("prefix: %v", err)
	}
	request.PathPrefix = ""
	ctx, cancel := context.WithCancel(authorized(t))
	cancel()
	if _, err = client.GrepWorkspace(ctx, request); status.Code(err) != codes.Canceled {
		t.Fatalf("cancel: %v", err)
	}
	if _, err = client.GrepWorkspace(context.Background(), request); status.Code(err) != codes.Unauthenticated {
		t.Fatalf("authorization: %v", err)
	}
}

func TestGrepWorkspaceRejectsCorruptOrLinkedTrees(t *testing.T) {
	for _, linked := range []bool{false, true} {
		root := t.TempDir()
		client, close := connect(t, root)
		writable(t, client)
		path := filepath.Join(root, instance, "commit-00000000000000000000", "tree", "src", "main", "java", "Main.java")
		if err := os.Remove(path); err != nil {
			t.Fatal(err)
		}
		if linked {
			outside := filepath.Join(t.TempDir(), "outside")
			if err := os.WriteFile(outside, []byte("class Main {}\n"), 0600); err != nil {
				t.Fatal(err)
			}
			if err := os.Symlink(outside, path); err != nil {
				t.Fatal(err)
			}
		} else if err := os.WriteFile(path, []byte("corrupt"), 0600); err != nil {
			t.Fatal(err)
		}
		result, err := client.GrepWorkspace(authorized(t), &rpc.GrepWorkspaceRequest{WorkspaceInstanceId: instance, Pattern: "Main", Limit: 200})
		close()
		if err == nil || result != nil {
			t.Fatalf("unsafe tree returned: %v %v", result, err)
		}
	}
}

func TestGrepWorkspaceBoundsRowsAndUTF8Bytes(t *testing.T) {
	for _, content := range []string{strings.Repeat("hit\n", 300), "hit" + strings.Repeat("大", 30000)} {
		published := publication()
		published.InitialFiles["AGENTS.md"] = content
		client, close := connectRuntime(t, t.TempDir(), published, nil)
		writable(t, client)
		result, err := client.GrepWorkspace(authorized(t), &rpc.GrepWorkspaceRequest{WorkspaceInstanceId: instance, Pattern: "hit", PathPrefix: "AGENTS", Limit: 200})
		close()
		if err != nil || !result.Truncated || len(result.Lines) > 201 || len(strings.Join(result.Lines, "\n")) > 65536 {
			t.Fatalf("bounds: %v %v", result, err)
		}
	}
}
