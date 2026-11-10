package execution_test

import (
	"context"
	"net"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	rpc "codecraft.local/sandbox/internal/gen"
	"codecraft.local/sandbox/internal/workspace"
	"github.com/docker/docker/errdefs"
	"google.golang.org/grpc"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/credentials/insecure"
	"google.golang.org/grpc/metadata"
	"google.golang.org/grpc/status"
)

// This seam runs the real gRPC service and Docker, never a simulated executor.
func TestGRPCStopOrdersWithCompletionAndFencesLateStart(t *testing.T) {
	for _, beforeStart := range []bool{false, true} {
		t.Run(map[bool]string{false: "running", true: "before-registration"}[beforeStart], func(t *testing.T) {
			reference, _ := os.ReadFile("/fixture/reference/src/main/java/interview/CartTotal.java")
			source := strings.Replace(string(reference), "public final class CartTotal {", `public final class CartTotal { static { try { Thread.sleep(60000); } catch (InterruptedException e) { throw new RuntimeException(e); } }`, 1)
			runner, input, docker, _ := fixture(t, source)
			if err := os.RemoveAll(filepath.Join("/state", input.WorkspaceID)); err != nil {
				t.Fatal(err)
			}
			key := strings.Repeat("b", 64)
			identity := &rpc.ProblemIdentity{ProblemId: "fixture", Version: "1.0.1", PackageSha256: input.PackageSHA256, ExecutionImageDigest: input.Image, Platform: input.Platform}
			owner, stop := context.WithCancel(context.Background())
			defer func() { stop(); runner.Wait() }()
			service, err := workspace.NewService("/state", []workspace.Publication{{Identity: identity, InitialFiles: map[string]string{"pom.xml": "<project/>", "src/main/java/interview/CartTotal.java": source}, Execution: &workspace.ExecutionPolicy{Limits: input.Limits, Suite: input.Suite, Tests: input.Tests}}}, key, &workspace.ExecutionRuntime{Runner: runner, Worker: owner})
			if err != nil {
				t.Fatal(err)
			}
			listener, err := net.Listen("tcp", "127.0.0.1:0")
			if err != nil {
				t.Fatal(err)
			}
			server := grpc.NewServer()
			rpc.RegisterWorkspaceStorageServer(server, service)
			serving := make(chan error, 1)
			go func() { serving <- server.Serve(listener) }()
			defer func() { server.Stop(); <-serving }()
			connection, err := grpc.NewClient(listener.Addr().String(), grpc.WithTransportCredentials(insecure.NewCredentials()))
			if err != nil {
				t.Fatal(err)
			}
			defer connection.Close()
			client := rpc.NewWorkspaceStorageClient(connection)
			ctx, cancel := context.WithTimeout(context.Background(), time.Minute)
			defer cancel()
			ctx = metadata.AppendToOutgoingContext(ctx, "x-codecraft-internal-key", key)
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
			start := &rpc.StartTestRequest{ExerciseId: identifier(), TaskId: identifier(), ExecutionInstanceId: input.ExecutionID, WorkspaceInstanceId: input.WorkspaceID, Target: "base", Purpose: "MANUAL", GateEpoch: opened.Epoch, NotAfterUnixMillis: time.Now().Add(time.Minute).UnixMilli()}
			query := &rpc.QueryTaskRequest{ExerciseId: start.ExerciseId, TaskId: start.TaskId, ExecutionInstanceId: start.ExecutionInstanceId, WorkspaceInstanceId: start.WorkspaceInstanceId}
			stopRequest := &rpc.StopTaskRequest{Task: query, GateEpoch: opened.Epoch, NotAfterUnixMillis: start.NotAfterUnixMillis}
			if !beforeStart {
				if _, err = client.StartTest(ctx, start); err != nil {
					t.Fatal(err)
				}
				for {
					snapshot, err := client.QueryTask(ctx, query)
					if err != nil {
						t.Fatal(err)
					}
					if snapshot.State == "RUNNING" {
						break
					}
					select {
					case <-ctx.Done():
						t.Fatal(ctx.Err())
					case <-time.After(20 * time.Millisecond):
					}
				}
			}
			if !beforeStart {
				snapshot, err := client.QueryTask(ctx, query)
				if err != nil {
					t.Fatal(err)
				}
				watchContext, closeWatch := context.WithCancel(ctx)
				stream, err := client.WatchTask(watchContext, &rpc.WatchTaskRequest{Task: query, StreamId: snapshot.StreamId})
				if err != nil {
					closeWatch()
					t.Fatal(err)
				}
				for {
					event, err := stream.Recv()
					if err != nil {
						closeWatch()
						t.Fatalf("live logs unavailable: %v", err)
					}
					if event.Type == "log" {
						if len(event.Data) == 0 || len(event.Data) > 16384 || (event.Channel != "stdout" && event.Channel != "stderr") {
							t.Fatalf("invalid log event: %v", event)
						}
						break
					}
				}
				closeWatch()
				stillRunning, err := client.QueryTask(ctx, query)
				if err != nil || stillRunning.State != "RUNNING" {
					t.Fatalf("watch cancellation stopped execution: %v %v", stillRunning, err)
				}
			}
			stopped, err := client.StopTask(ctx, stopRequest)
			if err != nil {
				t.Fatalf("stop: %v", err)
			}
			if beforeStart {
				if !stopped.CancelledBeforeRegistration {
					t.Fatalf("missing durable cancellation: %v", stopped)
				}
				if _, err = client.CloseGate(ctx, &rpc.CloseGateRequest{WorkspaceInstanceId: input.WorkspaceID, TransitionId: identifier(), ExpectedEpoch: opened.Epoch}); err != nil {
					t.Fatal(err)
				}
				receipt, err := client.QueryStop(ctx, query)
				if err != nil || !receipt.CancelledBeforeRegistration {
					t.Fatalf("cancellation receipt lost after fencing: %v %v", receipt, err)
				}
				if _, err = client.StartTest(ctx, start); status.Code(err) != codes.FailedPrecondition {
					t.Fatalf("late Start escaped tombstone: %v", err)
				}
				if _, err = os.Stat(filepath.Join("/state", input.Subpath)); !os.IsNotExist(err) {
					t.Fatalf("cancelled task prepared an input: %v", err)
				}
				return
			}
			if stopped.Snapshot == nil || stopped.Snapshot.State != "STOPPING" {
				t.Fatalf("not stopping: %v", stopped)
			}
			runner.Wait()
			result, err := client.QueryTask(ctx, query)
			if err != nil || result.State != "STOPPED" || !result.Quiescent || result.Cleanup != "SUCCEEDED" || result.CopyCleanup != "SUCCEEDED" {
				t.Fatalf("stop did not terminate and reclaim: %v %v", result, err)
			}
			durable, err := runner.Query(ctx, input.ExecutionID)
			if err != nil {
				t.Fatal(err)
			}
			if _, err = docker.ContainerInspect(ctx, durable.ContainerID); !errdefs.IsNotFound(err) {
				t.Fatalf("stopped container remains: %v", err)
			}
			again, err := client.StopTask(ctx, stopRequest)
			if err != nil || again.Snapshot.State != "STOPPED" {
				t.Fatalf("stop replay changed terminal: %v %v", again, err)
			}
		})
	}
}
