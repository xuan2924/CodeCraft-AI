package workspace_test

import (
	"os"
	"path/filepath"
	"testing"

	rpc "codecraft.local/sandbox/internal/gen"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"
)

func TestTestCopyDamageNeverBecomesMissingOrRecreated(t *testing.T) {
	for _, damage := range []string{"missing-receipt", "missing-content", "changed-content", "symlink", "hardlink"} {
		t.Run(damage, func(t *testing.T) {
			root := t.TempDir()
			client, close := connect(t, root)
			request := copyRequest(writable(t, client))
			receipt, err := client.PrepareTestCopy(authorized(t), request)
			close()
			if err != nil {
				t.Fatal(err)
			}
			path := filepath.Join(root, receipt.RelativePath, sourcePath)
			if damage == "missing-receipt" {
				path = filepath.Join(root, instance, "copy-"+execution, "receipt.json")
			}
			if err = os.Remove(path); err != nil {
				t.Fatal(err)
			}
			switch damage {
			case "changed-content":
				err = os.WriteFile(path, []byte("changed"), 0600)
			case "symlink", "hardlink":
				outside := filepath.Join(t.TempDir(), "source.java")
				if err = os.WriteFile(outside, []byte("class Main {}\n"), 0600); err != nil {
					t.Fatal(err)
				}
				if damage == "symlink" {
					err = os.Symlink(outside, path)
				} else {
					err = os.Link(outside, path)
				}
			}
			if err != nil {
				t.Fatal(err)
			}
			client, close = connect(t, root)
			defer close()
			if _, err = client.QueryTestCopy(authorized(t), &rpc.QueryTestCopyRequest{WorkspaceInstanceId: instance, ExecutionInstanceId: execution}); status.Code(err) != codes.Unavailable {
				t.Fatalf("damage mistaken for absence: %v", err)
			}
			if _, err = client.PrepareTestCopy(authorized(t), request); status.Code(err) != codes.Unavailable {
				t.Fatalf("damaged input recreated: %v", err)
			}
		})
	}
}

func TestTestCopyAndSaveHaveSingleRevisionOrdering(t *testing.T) {
	root := t.TempDir()
	client, disconnect := connect(t, root)
	defer disconnect()
	epoch := writable(t, client)
	type outcome struct {
		receipt *rpc.TestCopyReceipt
		err     error
	}
	copied := make(chan outcome, 1)
	saved := make(chan error, 1)
	ctx := authorized(t)
	start := make(chan struct{})
	go func() {
		<-start
		receipt, err := client.PrepareTestCopy(ctx, copyRequest(epoch))
		copied <- outcome{receipt, err}
	}()
	go func() { <-start; _, err := client.ApplyBatch(ctx, update(epoch)); saved <- err }()
	close(start)
	result := <-copied
	if err := <-saved; err != nil {
		t.Fatal(err)
	}
	if result.err != nil {
		if status.Code(result.err) != codes.Aborted {
			t.Fatal(result.err)
		}
		if _, err := client.QueryTestCopy(ctx, &rpc.QueryTestCopyRequest{WorkspaceInstanceId: instance, ExecutionInstanceId: execution}); status.Code(err) != codes.NotFound {
			t.Fatalf("conflicted copy published: %v", err)
		}
		return
	}
	content, err := os.ReadFile(filepath.Join(root, result.receipt.RelativePath, sourcePath))
	if err != nil || string(content) != "class Main {}\n" || result.receipt.Revision != 0 {
		t.Fatalf("mixed revision copy: %v %q %v", result.receipt, content, err)
	}
}
