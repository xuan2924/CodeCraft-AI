package execution_test

import (
	"context"
	"net"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"codecraft.local/sandbox/internal/execution"
	rpc "codecraft.local/sandbox/internal/gen"
	"codecraft.local/sandbox/internal/workspace"
	"github.com/docker/docker/errdefs"
	"google.golang.org/grpc"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/credentials/insecure"
	"google.golang.org/grpc/metadata"
	"google.golang.org/grpc/status"
)

func TestRealTerminalGRPCSyncCloseFreezeAndDelete(t *testing.T) {
	for _, mode := range []string{"sync", "close", "freeze", "delete"} {
		t.Run(mode, func(t *testing.T) {
			runner, input, docker, _ := fixture(t, "original")
			if err := os.RemoveAll(filepath.Join("/state", input.WorkspaceID)); err != nil {
				t.Fatal(err)
			}
			worker, cancel := context.WithTimeout(context.Background(), 30*time.Second)
			key := strings.Repeat("a", 64)
			identity := &rpc.ProblemIdentity{ProblemId: "fixture", Version: "1.0.0", PackageSha256: input.PackageSHA256, ExecutionImageDigest: input.Image, Platform: input.Platform}
			service, err := workspace.NewService("/state", []workspace.Publication{{Identity: identity, InitialFiles: map[string]string{"pom.xml": "protected", "src/main/java/Main.java": "class Main {}"}, Execution: &workspace.ExecutionPolicy{Limits: input.Limits, Suite: input.Suite, Tests: input.Tests}}}, key, &workspace.ExecutionRuntime{Runner: runner, Worker: worker})
			if err != nil {
				cancel()
				t.Fatal(err)
			}
			listener, err := net.Listen("tcp", "127.0.0.1:0")
			if err != nil {
				cancel()
				t.Fatal(err)
			}
			server := grpc.NewServer()
			rpc.RegisterWorkspaceStorageServer(server, service)
			go func() { _ = server.Serve(listener) }()
			connection, err := grpc.NewClient(listener.Addr().String(), grpc.WithTransportCredentials(insecure.NewCredentials()))
			if err != nil {
				cancel()
				server.Stop()
				t.Fatal(err)
			}
			t.Cleanup(func() { cancel(); server.Stop(); runner.Wait(); _ = connection.Close() })
			client := rpc.NewWorkspaceStorageClient(connection)
			ctx := metadata.AppendToOutgoingContext(worker, "x-codecraft-internal-key", key)
			if _, err = client.PrepareWorkspace(ctx, &rpc.PrepareWorkspaceRequest{WorkspaceInstanceId: input.WorkspaceID, OperationId: identifier(), Problem: identity, NotAfterUnixMillis: time.Now().Add(time.Minute).UnixMilli()}); err != nil {
				t.Fatal(err)
			}
			transition := identifier()
			closed, err := client.CloseGate(ctx, &rpc.CloseGateRequest{WorkspaceInstanceId: input.WorkspaceID, TransitionId: transition})
			if err != nil {
				t.Fatal(err)
			}
			opened, err := client.OpenGate(ctx, &rpc.CloseGateRequest{WorkspaceInstanceId: input.WorkspaceID, TransitionId: transition, ExpectedEpoch: closed.Epoch})
			if err != nil {
				t.Fatal(err)
			}
			task, exercise := identifier(), identifier()
			request := &rpc.QueryTaskRequest{WorkspaceInstanceId: input.WorkspaceID, ExerciseId: exercise, TaskId: task, ExecutionInstanceId: input.ExecutionID}
			if _, err = client.StartCommand(ctx, &rpc.StartCommandRequest{WorkspaceInstanceId: input.WorkspaceID, ExerciseId: exercise, TaskId: task, ExecutionInstanceId: input.ExecutionID, GateEpoch: opened.Epoch, HostEpoch: runner.HostEpoch(), NotAfterUnixMillis: time.Now().Add(time.Minute).UnixMilli(), Command: execution.TerminalCommand, Directory: ".", Terminal: true}); err != nil {
				t.Fatal(err)
			}
			stream, err := client.AttachTerminal(ctx)
			if err != nil {
				t.Fatal(err)
			}
			if err = stream.Send(&rpc.TerminalClientFrame{Frame: &rpc.TerminalClientFrame_Open{Open: &rpc.TerminalOpen{Identity: request, GateEpoch: opened.Epoch, Cols: 100, Rows: 40, Term: "xterm-256color"}}}); err != nil {
				t.Fatal(err)
			}
			first, err := stream.Recv()
			if err != nil || !first.GetOpened() {
				t.Fatalf("PTY did not open: %v %v", first, err)
			}
			command := "test -z \"$(ls -A /opt/acceptance)\" || exit 99; printf cache > /cache/maven/retained; printf 'class Changed {}' > src/main/java/Main.java; printf 'EDIT_%s\\n' ready; sleep 20 &\n"
			if err = stream.Send(&rpc.TerminalClientFrame{Frame: &rpc.TerminalClientFrame_Stdin{Stdin: []byte(command)}}); err != nil {
				t.Fatal(err)
			}
			output := ""
			for !strings.Contains(output, "EDIT_ready") {
				frame, err := stream.Recv()
				if err != nil {
					t.Fatal(err)
				}
				output += string(frame.GetOutput())
			}
			mutation := identifier()
			_, err = client.ApplyBatch(ctx, &rpc.ApplyBatchRequest{WorkspaceInstanceId: input.WorkspaceID, RequestId: mutation, OperationId: mutation, GateEpoch: opened.Epoch, NotAfterUnixMillis: time.Now().Add(time.Minute).UnixMilli(), Changes: []*rpc.FileChange{{Path: "src/main/java/Main.java", Kind: "UPDATE", Content: "class Race {}"}}})
			if status.Code(err) != codes.ResourceExhausted {
				t.Fatalf("editor raced terminal: %v", err)
			}
			if mode == "sync" {
				if _, err = client.SyncTerminal(ctx, request); err != nil {
					t.Fatal(err)
				}
			} else if mode == "delete" {
				_, err = client.DeleteWorkspace(ctx, &rpc.QueryWorkspaceRequest{WorkspaceInstanceId: input.WorkspaceID})
				if err != nil && status.Code(err) != codes.Unavailable {
					t.Fatal(err)
				}
			} else {
				fence, err := client.CloseGate(ctx, &rpc.CloseGateRequest{WorkspaceInstanceId: input.WorkspaceID, TransitionId: identifier(), ExpectedEpoch: opened.Epoch})
				if err != nil {
					t.Fatal(err)
				}
				record, err := runner.Query(ctx, input.ExecutionID)
				if err != nil || !record.Quiescent {
					t.Fatal("CloseGate returned before process quiescence")
				}
				if mode == "freeze" {
					if _, err = client.Freeze(ctx, &rpc.CloseGateRequest{WorkspaceInstanceId: input.WorkspaceID, TransitionId: identifier(), ExpectedEpoch: fence.Epoch}); err != nil {
						t.Fatal(err)
					}
				}
			}
			runner.Wait()
			if _, err = docker.ContainerInspect(ctx, "cc-execution-"+input.ExecutionID); !errdefs.IsNotFound(err) {
				t.Fatalf("terminal process/container survived: %v", err)
			}
			if mode == "delete" {
				receipt, err := client.DeleteWorkspace(ctx, &rpc.QueryWorkspaceRequest{WorkspaceInstanceId: input.WorkspaceID})
				if err != nil || !receipt.Complete {
					t.Fatalf("terminal deletion did not converge: %v", err)
				}
			} else {
				snapshot, err := client.QueryWorkspace(ctx, &rpc.QueryWorkspaceRequest{WorkspaceInstanceId: input.WorkspaceID})
				if err != nil {
					t.Fatal(err)
				}
				want := uint64(0)
				if mode == "sync" {
					want = 1
				}
				if snapshot.Revision != want {
					t.Fatalf("unexpected terminal revision: %d", snapshot.Revision)
				}
				command, err := client.QueryCommand(ctx, request)
				if err != nil {
					t.Fatal(err)
				}
				if mode == "sync" && command.Synchronization != "SYNCED" {
					t.Fatal("explicit sync did not publish")
				}
				cache, err := os.ReadFile(filepath.Join("/state", input.WorkspaceID, "dev-cache/maven/retained"))
				if err != nil || string(cache) != "cache" {
					t.Fatal("dependency cache was not retained after container cleanup")
				}
				if mode != "sync" && command.Synchronization != "CONFLICT" {
					t.Fatal("fenced terminal draft was not retained")
				}
			}
		})
	}
}
