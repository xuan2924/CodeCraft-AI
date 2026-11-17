package workspace

import (
	"os"
	"path/filepath"
	"syscall"
	"testing"

	rpc "codecraft.local/sandbox/internal/gen"
)

func TestTerminalDraftExplicitSyncProtectedFilesAndGateFence(t *testing.T) {
	for _, mode := range []string{"draft", "sync", "protected", "closed"} {
		t.Run(mode, func(t *testing.T) {
			f := newCommandFixtureMode(t, true)
			path := commandSource
			if mode == "protected" {
				path = "pom.xml"
			}
			if err := os.WriteFile(filepath.Join(f.tree, path), []byte("class TerminalEdit {}"), 0600); err != nil {
				t.Fatal(err)
			}
			if mode != "draft" {
				parent, err := f.service.instance(f.input.WorkspaceID, false)
				if err != nil {
					t.Fatal(err)
				}
				record, fd, err := openCommand(parent, f.input.WorkspaceID, f.input.ExecutionID)
				if err != nil {
					t.Fatal(err)
				}
				record.TerminalSyncRequested = true
				if err = saveCommand(fd, record); err != nil {
					t.Fatal(err)
				}
				syscall.Close(fd)
				syscall.Close(parent)
			}
			if mode == "closed" {
				if _, err := f.service.CloseGate(f.ctx, &rpc.CloseGateRequest{WorkspaceInstanceId: f.input.WorkspaceID, TransitionId: "10000000-0000-0000-0000-000000000008", ExpectedEpoch: f.input.GateEpoch}); err != nil {
					t.Fatal(err)
				}
			}
			if err := f.service.completeCommand(f.ctx, f.finished("STOPPED", 137)); err != nil {
				t.Fatal(err)
			}
			receipt := f.state(t)
			expected := map[string]string{"draft": "CONFLICT", "sync": "SYNCED", "protected": "REJECTED", "closed": "REJECTED"}[mode]
			if receipt.Synchronization != expected {
				t.Fatalf("%s: %+v", mode, receipt)
			}
			snapshot, err := f.service.QueryWorkspace(f.ctx, &rpc.QueryWorkspaceRequest{WorkspaceInstanceId: f.input.WorkspaceID})
			if err != nil {
				t.Fatal(err)
			}
			revision := f.input.Revision
			if mode == "sync" {
				revision++
			}
			if snapshot.Revision != revision {
				t.Fatal("unrequested or fenced terminal changes published")
			}
			if _, err := os.Stat(filepath.Join(f.service.root, f.input.WorkspaceID, "dev-cache", "maven")); err != nil {
				t.Fatal("persistent cache missing")
			}
		})
	}
}

func TestTerminalCacheIsChargedToWorkingBudget(t *testing.T) {
	f := newCommandFixtureMode(t, true)
	file, err := os.Create(filepath.Join(f.service.root, f.input.WorkspaceID, "dev-cache", "maven", "too-large"))
	if err != nil {
		t.Fatal(err)
	}
	if err = file.Truncate(maxCommandWorkingBytes + 1); err != nil {
		t.Fatal(err)
	}
	if err = file.Close(); err != nil {
		t.Fatal(err)
	}
	if err = f.service.commandBudget(f.ctx, f.input); err == nil {
		t.Fatal("persistent cache bypassed storage limit")
	}
}
