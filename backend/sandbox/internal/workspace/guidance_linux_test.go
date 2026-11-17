package workspace_test

import (
	"strings"
	"testing"

	rpc "codecraft.local/sandbox/internal/gen"
	"codecraft.local/sandbox/internal/workspace"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"
)

func TestPublishedGuidanceIsReadableButNeverMutable(t *testing.T) {
	published := publication()
	paths := []string{"AGENTS.md", "docs/AGENTS.md", "src/main/java/AGENTS.md", "src/test/java/nested/AGENTS.md"}
	for _, path := range paths {
		published.InitialFiles[path] = "controlled guidance: " + path
	}
	client, close := connectRuntime(t, t.TempDir(), published, nil)
	defer close()
	epoch := writable(t, client)
	for _, path := range paths {
		for _, kind := range []string{"CREATE", "UPDATE", "DELETE"} {
			t.Run(path+"/"+kind, func(t *testing.T) {
				request := update(epoch)
				change := &rpc.FileChange{Path: path, Kind: kind}
				if kind != "DELETE" {
					change.Content = "untrusted replacement"
				}
				request.Changes = []*rpc.FileChange{change}
				if _, err := client.ApplyBatch(authorized(t), request); status.Code(err) != codes.PermissionDenied {
					t.Fatalf("guidance mutation: %v", err)
				}
				file, err := client.ReadWorkspaceFile(authorized(t), &rpc.ReadWorkspaceFileRequest{WorkspaceInstanceId: instance, Path: path})
				if err != nil || file.Content != published.InitialFiles[path] || file.Revision != 0 {
					t.Fatalf("guidance changed or unreadable: %v %v", file, err)
				}
			})
		}
	}
	request := update(epoch)
	request.Changes = []*rpc.FileChange{{Path: "src/main/java/new/AGENTS.md", Kind: "CREATE", Content: "injected guidance"}}
	if _, err := client.ApplyBatch(authorized(t), request); status.Code(err) != codes.PermissionDenied {
		t.Fatalf("new guidance injection: %v", err)
	}
	// The protection does not disable ordinary source editing.
	if _, err := client.ApplyBatch(authorized(t), update(epoch)); err != nil {
		t.Fatal(err)
	}
}

func TestInitialGuidanceRetainsPathAndContentValidation(t *testing.T) {
	for _, path := range []string{"../AGENTS.md", "/AGENTS.md", "docs/../AGENTS.md", "docs//AGENTS.md", "docs\\AGENTS.md", "docs/agents.md", "docs/OTHER.md"} {
		t.Run(path, func(t *testing.T) {
			published := publication()
			published.InitialFiles[path] = "guidance"
			if _, err := workspace.NewService(t.TempDir(), []workspace.Publication{published}, fixtureKey, nil); err == nil {
				t.Fatal("invalid initial path accepted")
			}
		})
	}
	for _, content := range []string{"invalid\x00text", string([]byte{0xff}), strings.Repeat("x", 1024*1024+1)} {
		published := publication()
		published.InitialFiles["AGENTS.md"] = content
		if _, err := workspace.NewService(t.TempDir(), []workspace.Publication{published}, fixtureKey, nil); err == nil {
			t.Fatal("invalid guidance content accepted")
		}
	}
}
