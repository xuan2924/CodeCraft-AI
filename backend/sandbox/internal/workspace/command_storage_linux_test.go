package workspace

import (
	"context"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"syscall"
	"testing"
	"time"

	"codecraft.local/sandbox/internal/execution"
	rpc "codecraft.local/sandbox/internal/gen"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/metadata"
	"google.golang.org/grpc/status"
)

const commandSource = "src/main/java/Main.java"

type commandFixture struct {
	service *Service
	ctx     context.Context
	input   execution.Input
	tree    string
}

func newCommandFixture(t *testing.T) commandFixture { return newCommandFixtureMode(t, false) }

func newCommandFixtureMode(t *testing.T, terminal bool) commandFixture {
	t.Helper()
	workspace := "10000000-0000-0000-0000-000000000001"
	executionID := "10000000-0000-0000-0000-000000000002"
	operation := "10000000-0000-0000-0000-000000000003"
	transition := "10000000-0000-0000-0000-000000000004"
	key := strings.Repeat("a", 64)
	identity := &rpc.ProblemIdentity{ProblemId: "cart", Version: "1.0.0", PackageSha256: strings.Repeat("b", 64), ExecutionImageDigest: "sha256:" + strings.Repeat("c", 64), Platform: "linux/amd64"}
	root := t.TempDir()
	service, err := NewService(root, []Publication{{Identity: identity, InitialFiles: map[string]string{commandSource: "class Main {}\n", "pom.xml": "protected"}}}, key, nil)
	if err != nil {
		t.Fatal(err)
	}
	ctx := metadata.NewIncomingContext(context.Background(), metadata.Pairs("x-codecraft-internal-key", key))
	snapshot, err := service.PrepareWorkspace(ctx, &rpc.PrepareWorkspaceRequest{WorkspaceInstanceId: workspace, OperationId: operation, Problem: identity, NotAfterUnixMillis: time.Now().Add(time.Minute).UnixMilli()})
	if err != nil {
		t.Fatal(err)
	}
	closed, err := service.CloseGate(ctx, &rpc.CloseGateRequest{WorkspaceInstanceId: workspace, TransitionId: transition})
	if err != nil {
		t.Fatal(err)
	}
	opened, err := service.OpenGate(ctx, &rpc.CloseGateRequest{WorkspaceInstanceId: workspace, TransitionId: transition, ExpectedEpoch: closed.Epoch})
	if err != nil {
		t.Fatal(err)
	}
	input := execution.Input{Purpose: "COMMAND", ExerciseID: workspace, TaskID: executionID, ExecutionID: executionID, WorkspaceID: workspace,
		Revision: snapshot.Revision, GateEpoch: opened.Epoch, NotAfter: time.Now().Add(time.Minute),
		Command: &execution.CommandSpec{Text: "simulated command", Directory: ".", Terminal: terminal}}
	if terminal {
		input.Command.Text = execution.TerminalCommand
	}
	release, err := service.enter(ctx, input.WorkspaceID)
	if err != nil {
		t.Fatal(err)
	}
	err = service.prepareCommandLocked(ctx, input)
	release()
	if err != nil {
		t.Fatal(err)
	}
	return commandFixture{service, ctx, input, filepath.Join(root, workspace, "command-"+executionID, "tree")}
}

func (f commandFixture) state(t *testing.T) *commandRecord {
	t.Helper()
	parent, err := f.service.instance(f.input.WorkspaceID, false)
	if err != nil {
		t.Fatal(err)
	}
	defer syscall.Close(parent)
	record, fd, err := openCommand(parent, f.input.WorkspaceID, f.input.ExecutionID)
	if err != nil {
		t.Fatal(err)
	}
	syscall.Close(fd)
	return record
}

func (f commandFixture) finished(state string, exit int) execution.Result {
	now := time.Now()
	return execution.Result{Input: f.input, State: state, Quiescent: true, ExitCode: &exit, FinishedAt: &now}
}

func TestCommandChangesPublishAfterEveryConfirmedExitKind(t *testing.T) {
	for _, outcome := range []struct {
		state string
		exit  int
	}{{"EXITED", 0}, {"EXITED", 7}, {"STOPPED", 137}, {"TIMED_OUT", 137}, {"INTERRUPTED", 137}} {
		t.Run(fmt.Sprintf("%s-%d", outcome.state, outcome.exit), func(t *testing.T) {
			fixture := newCommandFixture(t)
			if err := os.WriteFile(filepath.Join(fixture.tree, commandSource), []byte("class Main { int changed; }\n"), 0600); err != nil {
				t.Fatal(err)
			}
			result := fixture.finished(outcome.state, outcome.exit)
			if err := fixture.service.completeCommand(fixture.ctx, result); err != nil {
				t.Fatal(err)
			}
			record := fixture.state(t)
			if record.Synchronization != "SYNCED" || record.Mutation == nil || record.Mutation.Revision != fixture.input.Revision+1 {
				t.Fatalf("modifications were lost after %s: %+v", outcome.state, record)
			}
			if err := fixture.service.completeCommand(fixture.ctx, result); err != nil {
				t.Fatal(err)
			}
			snapshot, err := fixture.service.QueryWorkspace(fixture.ctx, &rpc.QueryWorkspaceRequest{WorkspaceInstanceId: fixture.input.WorkspaceID})
			if err != nil || snapshot.Revision != fixture.input.Revision+1 {
				t.Fatalf("reconciliation duplicated a revision: %v %+v", err, snapshot)
			}
		})
	}
}

func TestCommandUnchangedAndCachesDoNotAdvanceRevision(t *testing.T) {
	fixture := newCommandFixture(t)
	if err := os.MkdirAll(filepath.Join(fixture.tree, "target"), 0700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(fixture.tree, "target", "artifact.class"), []byte{0, 1, 2}, 0600); err != nil {
		t.Fatal(err)
	}
	if err := fixture.service.completeCommand(fixture.ctx, fixture.finished("EXITED", 0)); err != nil {
		t.Fatal(err)
	}
	if record := fixture.state(t); record.Synchronization != "UNCHANGED" || record.Mutation != nil {
		t.Fatalf("cache became source history: %+v", record)
	}
	if _, err := os.Stat(filepath.Join(fixture.tree, "target")); !os.IsNotExist(err) {
		t.Fatalf("build cache was not cleaned: %v", err)
	}
	if _, err := os.Stat(filepath.Join(fixture.tree, commandSource)); err != nil {
		t.Fatal(err)
	}
	snapshot, err := fixture.service.QueryWorkspace(fixture.ctx, &rpc.QueryWorkspaceRequest{WorkspaceInstanceId: fixture.input.WorkspaceID})
	if err != nil || snapshot.Revision != fixture.input.Revision {
		t.Fatalf("unchanged command advanced revision: %v %+v", err, snapshot)
	}
}

func TestCommandPruningProtectsPendingAndConflictingChanges(t *testing.T) {
	for _, outcome := range []string{"PENDING", "UNCHANGED", "SYNCED", "CONFLICT", "REJECTED"} {
		t.Run(outcome, func(t *testing.T) {
			fixture := newCommandFixture(t)
			if outcome != "PENDING" && outcome != "UNCHANGED" {
				if err := os.WriteFile(filepath.Join(fixture.tree, commandSource), []byte("class Changed {}"), 0600); err != nil {
					t.Fatal(err)
				}
			}
			if outcome == "CONFLICT" {
				_, err := fixture.service.ApplyBatch(fixture.ctx, &rpc.ApplyBatchRequest{WorkspaceInstanceId: fixture.input.WorkspaceID,
					RequestId: "10000000-0000-0000-0000-000000000005", OperationId: "10000000-0000-0000-0000-000000000005", ExpectedRevision: fixture.input.Revision, GateEpoch: fixture.input.GateEpoch, NotAfterUnixMillis: fixture.input.NotAfter.UnixMilli(),
					Changes: []*rpc.FileChange{{Kind: "UPDATE", Path: commandSource, Content: "class Newer {}"}}})
				if err != nil {
					t.Fatal(err)
				}
			}
			if outcome == "REJECTED" {
				if err := os.WriteFile(filepath.Join(fixture.tree, "pom.xml"), []byte("protected edit"), 0600); err != nil {
					t.Fatal(err)
				}
			}
			if outcome != "PENDING" {
				if err := fixture.service.completeCommand(fixture.ctx, fixture.finished("EXITED", 0)); err != nil {
					t.Fatal(err)
				}
			}
			parent, err := fixture.service.instance(fixture.input.WorkspaceID, false)
			if err != nil {
				t.Fatal(err)
			}
			defer syscall.Close(parent)
			err = commandPrunable(parent, &rpc.QueryTaskRequest{ExerciseId: fixture.input.ExerciseID, TaskId: fixture.input.TaskID,
				WorkspaceInstanceId: fixture.input.WorkspaceID, ExecutionInstanceId: fixture.input.ExecutionID})
			allowed := outcome == "SYNCED" || outcome == "UNCHANGED"
			if (err == nil) != allowed || (!allowed && status.Code(err) != codes.FailedPrecondition) {
				t.Fatalf("pruning outcome %s: %v", outcome, err)
			}
		})
	}
}

func TestCommandMonitorStopsExpiredOrReplacedAuthorityWithoutWallClockWaiting(t *testing.T) {
	for _, expiry := range []bool{true, false} {
		t.Run(fmt.Sprint(expiry), func(t *testing.T) {
			fixture := newCommandFixture(t)
			if expiry {
				fixture.service.now = func() time.Time { return fixture.input.NotAfter }
			} else {
				_, err := fixture.service.CloseGate(fixture.ctx, &rpc.CloseGateRequest{WorkspaceInstanceId: fixture.input.WorkspaceID,
					TransitionId: "10000000-0000-0000-0000-000000000009", ExpectedEpoch: fixture.input.GateEpoch})
				if err != nil {
					t.Fatal(err)
				}
			}
			if err := fixture.service.commandBudget(fixture.ctx, fixture.input); !errors.Is(err, execution.ErrCommandAuthorityEnded) {
				t.Fatalf("expired command authority remained live: %v", err)
			}
		})
	}
}

func TestCommandBudgetIncludesRemovableBuildCaches(t *testing.T) {
	fixture := newCommandFixture(t)
	if err := os.MkdirAll(filepath.Join(fixture.tree, "target"), 0700); err != nil {
		t.Fatal(err)
	}
	file, err := os.Create(filepath.Join(fixture.tree, "target", "large-cache"))
	if err != nil {
		t.Fatal(err)
	}
	err = errors.Join(file.Truncate(maxCommandWorkingBytes+1), file.Close())
	if err != nil {
		t.Fatal(err)
	}
	if err = fixture.service.commandBudget(fixture.ctx, fixture.input); !errors.Is(err, execution.ErrCommandOutputLimit) {
		t.Fatalf("build cache escaped the copy budget: %v", err)
	}
	if err = fixture.service.completeCommand(fixture.ctx, fixture.finished("STOPPED", 137)); err != nil {
		t.Fatal(err)
	}
	if _, err = os.Stat(filepath.Join(fixture.tree, "target")); !os.IsNotExist(err) {
		t.Fatalf("cache cleanup failed: %v", err)
	}
}

func TestCommandBudgetRejectsOversizeWorkingCopyWithoutWaitingForWallClock(t *testing.T) {
	fixture := newCommandFixture(t)
	if err := fixture.service.commandBudget(fixture.ctx, fixture.input); err != nil {
		t.Fatal(err)
	}
	file, err := os.Create(filepath.Join(fixture.tree, "oversize.bin"))
	if err != nil {
		t.Fatal(err)
	}
	resizeErr := file.Truncate(maxCommandWorkingBytes + 1)
	closeErr := file.Close()
	if resizeErr != nil || closeErr != nil {
		t.Fatal(errors.Join(resizeErr, closeErr))
	}
	if err = fixture.service.commandBudget(fixture.ctx, fixture.input); !errors.Is(err, execution.ErrCommandOutputLimit) {
		t.Fatalf("working-copy limit not enforced: %v", err)
	}
	if err = fixture.service.completeCommand(fixture.ctx, fixture.finished("STOPPED", 137)); err != nil {
		t.Fatal(err)
	}
	if record := fixture.state(t); record.Synchronization != "REJECTED" || record.Mutation != nil {
		t.Fatalf("invalid output claimed synchronized: %+v", record)
	}
}

func TestCommandCannotPublishWhileRunningOrOverwriteNewerSave(t *testing.T) {
	fixture := newCommandFixture(t)
	if err := os.WriteFile(filepath.Join(fixture.tree, commandSource), []byte("command candidate"), 0600); err != nil {
		t.Fatal(err)
	}
	active := fixture.finished("RUNNING", 0)
	active.Quiescent, active.FinishedAt = false, nil
	if fixture.service.completeCommand(fixture.ctx, active) == nil {
		t.Fatal("active command files were published")
	}
	id := "10000000-0000-0000-0000-000000000005"
	saved, err := fixture.service.ApplyBatch(fixture.ctx, &rpc.ApplyBatchRequest{WorkspaceInstanceId: fixture.input.WorkspaceID,
		RequestId: id, OperationId: id, ExpectedRevision: fixture.input.Revision, GateEpoch: fixture.input.GateEpoch,
		NotAfterUnixMillis: fixture.input.NotAfter.UnixMilli(), Changes: []*rpc.FileChange{{Path: commandSource, Kind: "UPDATE", Content: "newer manual save"}}})
	if err != nil {
		t.Fatal(err)
	}
	if err = fixture.service.completeCommand(fixture.ctx, fixture.finished("EXITED", 0)); err != nil {
		t.Fatal(err)
	}
	if record := fixture.state(t); record.Synchronization != "CONFLICT" || record.Mutation != nil {
		t.Fatalf("conflict hidden: %+v", record)
	}
	// Retiring the baseline commit must not retire blobs pinned by the command draft.
	if _, err = fixture.service.CollectWorkspace(fixture.ctx, &rpc.CollectWorkspaceRequest{WorkspaceInstanceId: fixture.input.WorkspaceID, ExpectedRevision: saved.Revision}); err != nil {
		t.Fatal(err)
	}
	file, err := fixture.service.ReadWorkspaceFile(fixture.ctx, &rpc.ReadWorkspaceFileRequest{WorkspaceInstanceId: fixture.input.WorkspaceID, Path: commandSource, ExpectedRevision: saved.Revision})
	if err != nil || file.Content != "newer manual save" {
		t.Fatalf("command overwrote a later save: %v %+v", err, file)
	}
	parent, err := fixture.service.instance(fixture.input.WorkspaceID, false)
	if err != nil {
		t.Fatal(err)
	}
	defer syscall.Close(parent)
	record, fd, err := openCommand(parent, fixture.input.WorkspaceID, fixture.input.ExecutionID)
	if err != nil {
		t.Fatal(err)
	}
	defer syscall.Close(fd)
	changes, err := commandChanges(parent, fd, record)
	if err != nil || len(changes.Changes) != 1 || changes.Changes[0].PreviousContent != "class Main {}\n" || changes.Changes[0].Change.Content != "command candidate" {
		t.Fatalf("conflicting candidate evidence not retained: %v %+v", err, changes)
	}
}

func TestCommandCannotPublishAfterGateClosesOrHideProtectedChanges(t *testing.T) {
	for _, closeGate := range []bool{false, true} {
		t.Run(fmt.Sprint(closeGate), func(t *testing.T) {
			fixture := newCommandFixture(t)
			path := "pom.xml"
			if closeGate {
				path = commandSource
				_, err := fixture.service.CloseGate(fixture.ctx, &rpc.CloseGateRequest{WorkspaceInstanceId: fixture.input.WorkspaceID,
					TransitionId: "10000000-0000-0000-0000-000000000006", ExpectedEpoch: fixture.input.GateEpoch})
				if err != nil {
					t.Fatal(err)
				}
			}
			if err := os.WriteFile(filepath.Join(fixture.tree, path), []byte("unauthorized candidate"), 0600); err != nil {
				t.Fatal(err)
			}
			if err := fixture.service.completeCommand(fixture.ctx, fixture.finished("STOPPED", 137)); err != nil {
				t.Fatal(err)
			}
			if record := fixture.state(t); record.Synchronization != "REJECTED" || record.Mutation != nil {
				t.Fatalf("unauthorized change silently accepted: %+v", record)
			}
		})
	}
}

func TestRegularCommandRetainsMavenCacheInsideCombinedBudget(t *testing.T) {
	f := newCommandFixture(t)
	cached := filepath.Join(f.service.root, f.input.WorkspaceID, "dev-cache", "maven", "artifact")
	if err := os.WriteFile(cached, []byte("cached"), 0600); err != nil {
		t.Fatal(err)
	}
	next := f.input
	next.ExecutionID = "30000000-0000-0000-0000-000000000005"
	next.TaskID = next.ExecutionID
	if err := f.service.prepareCommandLocked(f.ctx, next); err != nil {
		t.Fatal(err)
	}
	if data, err := os.ReadFile(cached); err != nil || string(data) != "cached" {
		t.Fatalf("lost shared cache: %q %v", data, err)
	}
	next.Limits.WorkMiB = 1
	if err := os.Truncate(cached, 1024*1024); err != nil {
		t.Fatal(err)
	}
	if err := f.service.commandBudget(f.ctx, next); !errors.Is(err, execution.ErrCommandOutputLimit) {
		t.Fatalf("tree plus Maven cache exceeded budget: %v", err)
	}
}
