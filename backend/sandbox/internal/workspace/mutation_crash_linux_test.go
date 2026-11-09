package workspace_test

import (
	"context"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"syscall"
	"testing"
	"time"

	rpc "codecraft.local/sandbox/internal/gen"
	"codecraft.local/sandbox/internal/workspace"
	"google.golang.org/grpc"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/credentials/insecure"
	"google.golang.org/grpc/status"
)

// This child uses the actual service and filesystem. The interceptor terminates
// the process at the RPC response boundary; production code has no process-exit hooks.
func TestMutationCrashWorker(t *testing.T) {
	root := os.Getenv("CODECRAFT_MUTATION_CRASH_ROOT")
	if root == "" {
		t.Skip("subprocess-only fault fixture")
	}
	service, err := workspace.NewService(root, []workspace.Publication{publication()}, fixtureKey)
	if err != nil {
		t.Fatal(err)
	}
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	server := grpc.NewServer(grpc.UnaryInterceptor(func(ctx context.Context, request any, info *grpc.UnaryServerInfo, handler grpc.UnaryHandler) (any, error) {
		if _, ok := request.(*rpc.ApplyBatchRequest); ok {
			if os.Getenv("CODECRAFT_MUTATION_CRASH_MODE") == "write-failure" {
				// Linux RLIMIT_FSIZE forces EFBIG during staging, without touching the
				// already-published tree. Only this disposable child has the limit.
				if err := syscall.Setrlimit(syscall.RLIMIT_FSIZE, &syscall.Rlimit{Cur: 8192, Max: 8192}); err != nil {
					t.Fatal(err)
				}
				_, err := handler(ctx, request)
				if err == nil {
					os.Exit(89)
				}
				os.Exit(87)
			}
			_, err := handler(ctx, request)
			if err != nil {
				os.Exit(89)
			}
			os.Exit(86) // Commit succeeded, but no response bytes reached the client.
		}
		return handler(ctx, request)
	}))
	rpc.RegisterWorkspaceStorageServer(server, service)
	if err = os.WriteFile(filepath.Join(root, "child-address"), []byte(listener.Addr().String()), 0600); err != nil {
		t.Fatal(err)
	}
	if err = server.Serve(listener); err != nil {
		t.Fatal(err)
	}
}

func TestMutationProcessFailureRecovery(t *testing.T) {
	for _, changeKind := range []string{"update", "rename"} {
		for _, mode := range []string{"lost-response", "write-failure"} {
			t.Run(changeKind+"/"+mode, func(t *testing.T) {
				root := t.TempDir()
				client, close := connect(t, root)
				epoch := writable(t, client)
				close()
				ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
				defer cancel()
				command := exec.CommandContext(ctx, os.Args[0], "-test.run=^TestMutationCrashWorker$", "-test.timeout=10s")
				command.Env = append(os.Environ(), "CODECRAFT_MUTATION_CRASH_ROOT="+root, "CODECRAFT_MUTATION_CRASH_MODE="+mode)
				if err := command.Start(); err != nil {
					t.Fatal(err)
				}
				defer func() {
					if command.ProcessState == nil {
						_ = command.Process.Kill()
						_ = command.Wait()
					}
				}()
				var address []byte
				for len(address) == 0 && ctx.Err() == nil {
					address, _ = os.ReadFile(filepath.Join(root, "child-address"))
					time.Sleep(5 * time.Millisecond)
				}
				if len(address) == 0 {
					t.Fatal("child did not start")
				}
				connection, err := grpc.NewClient(string(address), grpc.WithTransportCredentials(insecure.NewCredentials()))
				if err != nil {
					t.Fatal(err)
				}
				request := update(epoch)
				content := strings.Repeat("x", 16384)
				request.Changes[0].Content = content
				target := "src/main/java/Renamed.java"
				if changeKind == "rename" {
					request.Changes = []*rpc.FileChange{{Path: sourcePath, Kind: "DELETE"}, {Path: target, Kind: "CREATE", Content: content}}
				}
				_, err = rpc.NewWorkspaceStorageClient(connection).ApplyBatch(authorized(t), request)
				connection.Close()
				if status.Code(err) != codes.Unavailable {
					t.Fatalf("expected transport uncertainty: %v", err)
				}
				if err = command.Wait(); err == nil {
					t.Fatal("fault child unexpectedly succeeded")
				}
				expectedExit := 86
				if mode == "write-failure" {
					expectedExit = 87
				}
				if command.ProcessState.ExitCode() != expectedExit {
					t.Fatalf("fault not exercised: %v", command.ProcessState)
				}
				client, close = connect(t, root)
				defer close()
				receipt, err := client.QueryMutation(authorized(t), &rpc.QueryMutationRequest{WorkspaceInstanceId: instance, RequestId: mutation})
				revision := uint64(1)
				if mode == "write-failure" {
					revision = 0
					if status.Code(err) != codes.NotFound {
						t.Fatalf("failed staging published receipt: %v %v", receipt, err)
					}
				} else if err != nil || receipt.Revision != 1 {
					t.Fatalf("lost-response receipt missing: %v %v", receipt, err)
				}
				readPath := sourcePath
				if changeKind == "rename" {
					absentPath := target
					if revision == 1 {
						readPath = target
						absentPath = sourcePath
					}
					if _, err := client.ReadWorkspaceFile(authorized(t), &rpc.ReadWorkspaceFileRequest{WorkspaceInstanceId: instance, ExpectedRevision: revision, Path: absentPath}); status.Code(err) != codes.NotFound {
						t.Fatalf("rename recovered a partial tree: %v", err)
					}
				}
				file, err := client.ReadWorkspaceFile(authorized(t), &rpc.ReadWorkspaceFileRequest{WorkspaceInstanceId: instance, ExpectedRevision: revision, Path: readPath})
				if err != nil {
					t.Fatal(err)
				}
				expected := content
				if revision == 0 {
					expected = publication().InitialFiles[sourcePath]
				}
				if file.Content != expected {
					t.Fatal("recovered tree is not the atomic old/new version")
				}
			})
		}
	}
}
