package execution_test

import (
	"bufio"
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"codecraft.local/sandbox/internal/execution"
	"github.com/docker/docker/api/types/container"
	"github.com/docker/docker/client"
	"github.com/docker/docker/errdefs"
)

func identifier() string {
	var bytes [16]byte
	if _, err := rand.Read(bytes[:]); err != nil {
		panic(err)
	}
	text := hex.EncodeToString(bytes[:])
	return text[:8] + "-" + text[8:12] + "-4" + text[13:16] + "-8" + text[17:20] + "-" + text[20:]
}

func fixture(t *testing.T, source string) (*execution.Runner, execution.Input, *client.Client, string) {
	t.Helper()
	if os.Getenv("CODECRAFT_EXECUTION_TEST") != "1" {
		t.Skip("real Docker execution requires scripts/test-execution.mjs")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	t.Cleanup(cancel)
	docker, err := client.NewClientWithOpts(client.FromEnv, client.WithAPIVersionNegotiation())
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		if err := docker.Close(); err != nil {
			t.Error(err)
		}
	})
	owner := identifier()
	root := filepath.Join("/state", "registry-"+owner)
	runner, err := execution.New(ctx, docker, root, os.Getenv("CODECRAFT_EXECUTION_VOLUME"), owner)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		if err := runner.Close(); err != nil {
			t.Error(err)
		}
	})
	input := execution.Input{ExecutionID: identifier(), WorkspaceID: identifier(), Revision: 7,
		Image: os.Getenv("CODECRAFT_EXECUTION_IMAGE"), PackageSHA256: strings.Repeat("a", 64), Platform: "linux/amd64", Target: "base",
		NotAfter: time.Now().Add(time.Minute), Limits: execution.Limits{CPUs: 1, MemoryMiB: 768, PIDs: 128, WorkMiB: 128, TmpMiB: 64, ShmMiB: 16, TimeoutSeconds: 30, LogBytes: 1048576},
		Suite: "interview.CartTotalTest", Tests: []string{"emptyCartIsFree", "sumsPriceTimesQuantityInCents", "allowsZeroPriceAndZeroQuantity", "rejectsNegativePrice", "rejectsNegativeQuantity", "rejectsNullCartAndItem", "detectsMultiplicationOverflow", "detectsAdditionOverflow"}}
	input.Subpath = input.WorkspaceID + "/copy-" + input.ExecutionID + "/tree"
	path := filepath.Join("/state", input.Subpath, "src/main/java/interview/CartTotal.java")
	if err = os.MkdirAll(filepath.Dir(path), 0755); err != nil {
		t.Fatal(err)
	}
	if err = os.WriteFile(path, []byte(source), 0644); err != nil {
		t.Fatal(err)
	}
	return runner, input, docker, root
}

func TestRestrictedExecutionAuthorityFailureAndTimeout(t *testing.T) {
	for _, variant := range []string{"initial", "timeout"} {
		t.Run(variant, func(t *testing.T) {
			source, _ := os.ReadFile("/fixture/initial/src/main/java/interview/CartTotal.java")
			runner, input, docker, _ := fixture(t, string(source))
			want := "FAILED"
			if variant == "timeout" {
				input.Limits.TimeoutSeconds = 1
				want = "TIMED_OUT"
			}
			result, err := runner.Run(context.Background(), input)
			if err != nil || result.State != want || !result.Quiescent || result.Cleanup != "SUCCEEDED" {
				t.Fatalf("state=%s reason=%s cleanup=%s error=%v", result.State, result.Reason, result.Cleanup, err)
			}
			if _, err = docker.ContainerInspect(context.Background(), result.ContainerID); !errdefs.IsNotFound(err) {
				t.Fatalf("container remains: %v", err)
			}
			if variant == "initial" && len(result.Report) == 0 {
				t.Fatal("authority failure lost its evidence")
			}
		})
	}
}

func TestRestrictedExecutionCompilationFailure(t *testing.T) {
	for name, source := range map[string]string{
		"candidate-syntax":        "package interview; public class CartTotal { syntax error }",
		"acceptance-api-mismatch": "package interview; public class CartTotal {}",
	} {
		t.Run(name, func(t *testing.T) { compilationFailure(t, source) })
	}
}

func compilationFailure(t *testing.T, source string) {
	t.Helper()
	runner, input, docker, root := fixture(t, source)
	input.Limits.LogBytes = 1024
	result, err := runner.Run(context.Background(), input)
	if err != nil || result.State != "FAILED" || result.Reason != "compilation_failed" || result.ExitCode == nil || *result.ExitCode != 1 || !result.Quiescent || result.Cleanup != "SUCCEEDED" {
		t.Fatalf("compilation outcome: state=%s reason=%s cleanup=%s error=%v", result.State, result.Reason, result.Cleanup, err)
	}
	if _, err = docker.ContainerInspect(context.Background(), result.ContainerID); !errdefs.IsNotFound(err) {
		t.Fatalf("container remains: %v", err)
	}
	persisted, err := runner.Query(context.Background(), input.ExecutionID)
	if err != nil || persisted.State != "FAILED" || persisted.Reason != "compilation_failed" || len(persisted.CompilationReport) == 0 || persisted.DroppedBytes == 0 {
		t.Fatalf("compilation result not durable independently of truncated logs: state=%s error=%v", persisted.State, err)
	}
	// Corrupted evidence must not recover a grade or authorize a replacement run.
	persisted.CompilationReport = []byte("compiler-failure-v1\nunknown\n")
	damaged, err := json.Marshal(persisted)
	if err != nil {
		t.Fatal(err)
	}
	if err = os.WriteFile(filepath.Join(root, input.ExecutionID+".json"), damaged, 0600); err != nil {
		t.Fatal(err)
	}
	if _, err = runner.Query(context.Background(), input.ExecutionID); err == nil {
		t.Fatal("damaged compilation evidence recovered as FAILED")
	}
	if _, err = runner.Run(context.Background(), input); err == nil {
		t.Fatal("damaged compilation evidence accepted for replay")
	}
}

func TestRestrictedExecutionIsolationAndLogFlood(t *testing.T) {
	source, _ := os.ReadFile("/fixture/reference/src/main/java/interview/CartTotal.java")
	probe := `set -eu
[ "$(id -u)" = 10001 ]
grep -q '^CapEff:[[:space:]]*0000000000000000$' /proc/self/status
grep -q '^NoNewPrivs:[[:space:]]*1$' /proc/self/status
grep -q '^Seccomp:[[:space:]]*2$' /proc/self/status
[ "$(ls /sys/class/net)" = lo ]
if timeout 2 bash -c 'echo x > /dev/tcp/1.1.1.1/443' 2>/dev/null; then exit 10; fi
for location in /root-write-probe /opt/contract/pom.xml /opt/acceptance/write-probe /opt/maven-repository/write-probe /input/write-probe; do
 if touch "$location" 2>/dev/null; then exit 11; fi
done
[ ! -e /var/run/docker.sock ]
[ ! -e /docker.sock ]
[ ! -e /input/receipt.json ]
[ ! -e /opt/reference ]
[ "$(cat /sys/fs/cgroup/memory.max)" = 805306368 ]
[ "$(cat /sys/fs/cgroup/memory.swap.max)" = 0 ]
[ "$(cat /sys/fs/cgroup/pids.max)" = 128 ]
[ "$(cat /sys/fs/cgroup/cpu.max)" = '100000 100000' ]
if dd if=/dev/zero of=/work/full bs=1M count=129 2>/dev/null; then exit 12; fi
rm /work/full
if dd if=/dev/zero of=/tmp/full bs=1M count=65 2>/dev/null; then exit 13; fi
rm /tmp/full
printf 'ISOLATION_OK\n'`
	// JSON quoting is also a Java string literal for this ASCII fixture.
	encoded, _ := json.Marshal(probe)
	initializer := `static { try {
 if(new ProcessBuilder("/bin/sh","-c",` + string(encoded) + `).inheritIO().start().waitFor()!=0) throw new IllegalStateException("isolation failed");
 System.out.print("x".repeat(1200000)); System.err.println("FLOOD_TAIL");
 } catch(Exception e) { throw new ExceptionInInitializerError(e); } }`
	modified := strings.Replace(string(source), "public final class CartTotal {", "public final class CartTotal {"+initializer, 1)
	runner, input, docker, _ := fixture(t, modified)
	// User tests and POM cannot replace image authority, even if they look successful.
	tree := filepath.Join("/state", input.Subpath)
	if err := os.MkdirAll(filepath.Join(tree, "src/test/java/interview"), 0755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(tree, "src/test/java/interview/CartTotalTest.java"), []byte("THIS MUST NOT COMPILE"), 0644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(tree, "pom.xml"), []byte("THIS MUST NOT LOAD"), 0644); err != nil {
		t.Fatal(err)
	}
	result, err := runner.Run(context.Background(), input)
	if err != nil || result.State != "PASSED" || !result.Quiescent || result.Cleanup != "SUCCEEDED" {
		t.Fatalf("state=%s reason=%s cleanup=%s error=%v", result.State, result.Reason, result.Cleanup, err)
	}
	retained := 0
	stderrSeen := false
	for _, chunk := range result.Logs {
		retained += len(chunk.Data)
		if chunk.Channel == "stderr" && strings.Contains(string(chunk.Data), "FLOOD_TAIL") {
			stderrSeen = true
		}
	}
	if retained > input.Limits.LogBytes || result.DroppedBytes == 0 || !stderrSeen {
		t.Fatalf("unbounded or missing tail: retained=%d dropped=%d stderr=%v", retained, result.DroppedBytes, stderrSeen)
	}
	if _, err = docker.ContainerInspect(context.Background(), result.ContainerID); !errdefs.IsNotFound(err) {
		t.Fatalf("container remains: %v", err)
	}
}

type transportFunc func(*http.Request) (*http.Response, error)

func (f transportFunc) RoundTrip(request *http.Request) (*http.Response, error) { return f(request) }

func TestRestrictedExecutionFencesDelayedStartAfterLostResponse(t *testing.T) {
	source, _ := os.ReadFile("/fixture/reference/src/main/java/interview/CartTotal.java")
	runner, input, docker, root := fixture(t, string(source))
	if err := runner.Close(); err != nil {
		t.Fatal(err)
	}
	httpClient := docker.HTTPClient()
	realTransport := httpClient.Transport
	delayed := make(chan *http.Request, 1)
	httpClient.Transport = transportFunc(func(request *http.Request) (*http.Response, error) {
		if request.Method == http.MethodPost && strings.Contains(request.URL.Path, "/containers/") && strings.HasSuffix(request.URL.Path, "/start") {
			// Delivery is delayed independently of the caller's transport failure.
			delayed <- request.Clone(context.WithoutCancel(request.Context()))
			return nil, errors.New("injected lost Start response")
		}
		return realTransport.RoundTrip(request)
	})
	faultClient, err := client.NewClientWithOpts(client.FromEnv, client.WithHTTPClient(httpClient), client.WithAPIVersionNegotiation())
	if err != nil {
		t.Fatal(err)
	}
	defer faultClient.Close()
	faultRunner, err := execution.New(context.Background(), faultClient, root, os.Getenv("CODECRAFT_EXECUTION_VOLUME"), strings.TrimPrefix(filepath.Base(root), "registry-"))
	if err != nil {
		t.Fatal(err)
	}
	defer faultRunner.Close()
	result, runErr := faultRunner.Run(context.Background(), input)
	defer func() {
		err := docker.ContainerRemove(context.Background(), result.ContainerID, container.RemoveOptions{Force: true})
		if err != nil && !errdefs.IsNotFound(err) {
			t.Error(err)
		}
	}()
	var late *http.Request
	select {
	case late = <-delayed:
	default:
		t.Fatal("Start was not fault-injected")
	}
	lateCtx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	response, err := realTransport.RoundTrip(late.WithContext(lateCtx))
	if err != nil {
		t.Fatal(err)
	}
	response.Body.Close()
	if result.Quiescent && response.StatusCode != http.StatusNotFound {
		t.Errorf("published quiescence but delayed Start remained executable: HTTP %d", response.StatusCode)
	}
	if runErr == nil || result.State != "INTERRUPTED" || !result.Quiescent || result.Cleanup != "SUCCEEDED" {
		t.Errorf("ambiguous Start not fenced: state=%s cleanup=%s quiescent=%v error=%v", result.State, result.Cleanup, result.Quiescent, runErr)
	}
	saved, err := faultRunner.Query(context.Background(), input.ExecutionID)
	if err != nil || saved.State != "INTERRUPTED" || !saved.Quiescent {
		t.Fatalf("fencing result not durable: %v", err)
	}
}

func TestRestrictedExecutionCleanupFailureRemainsObservable(t *testing.T) {
	source, _ := os.ReadFile("/fixture/reference/src/main/java/interview/CartTotal.java")
	runner, input, docker, root := fixture(t, string(source))
	if err := runner.Close(); err != nil {
		t.Fatal(err)
	}
	httpClient := docker.HTTPClient()
	realTransport := httpClient.Transport
	httpClient.Transport = transportFunc(func(request *http.Request) (*http.Response, error) {
		// Explicit fault injection at the Engine boundary; all creation, execution,
		// evidence collection, kill and inspect calls still reach the real daemon.
		if request.Method == http.MethodDelete && strings.Contains(request.URL.Path, "/containers/") {
			return &http.Response{StatusCode: 500, Header: make(http.Header), Body: io.NopCloser(strings.NewReader(`{"message":"injected removal failure"}`)), Request: request}, nil
		}
		return realTransport.RoundTrip(request)
	})
	faultClient, err := client.NewClientWithOpts(client.FromEnv, client.WithHTTPClient(httpClient), client.WithAPIVersionNegotiation())
	if err != nil {
		t.Fatal(err)
	}
	defer faultClient.Close()
	faultRunner, err := execution.New(context.Background(), faultClient, root, os.Getenv("CODECRAFT_EXECUTION_VOLUME"), strings.TrimPrefix(filepath.Base(root), "registry-"))
	if err != nil {
		t.Fatal(err)
	}
	defer faultRunner.Close()
	result, err := faultRunner.Run(context.Background(), input)
	if err == nil || result.State != "PASSED" || !result.Quiescent || result.Cleanup != "FAILED" {
		t.Fatalf("cleanup failure hidden: state=%s cleanup=%s error=%v", result.State, result.Cleanup, err)
	}
	defer func() {
		if err := docker.ContainerRemove(context.Background(), result.ContainerID, container.RemoveOptions{}); err != nil {
			t.Error(err)
		}
	}()
	inspected, err := docker.ContainerInspect(context.Background(), result.ContainerID)
	if err != nil || inspected.State == nil || inspected.State.Running {
		t.Fatalf("cleanup failure did not confirm termination: %v", err)
	}
	saved, err := faultRunner.Query(context.Background(), input.ExecutionID)
	if err != nil || saved.Cleanup != "FAILED" || len(saved.Report) == 0 {
		t.Fatalf("cleanup failure/evidence not persisted: %v", err)
	}
}

func TestRestrictedExecutionWorkerCancellationAndCapacity(t *testing.T) {
	source, _ := os.ReadFile("/fixture/reference/src/main/java/interview/CartTotal.java")
	runner, input, docker, _ := fixture(t, string(source))
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	type outcome struct {
		result execution.Result
		err    error
	}
	done := make(chan outcome, 1)
	go func() { result, err := runner.Run(ctx, input); done <- outcome{result, err} }()
	deadline := time.NewTimer(15 * time.Second)
	defer deadline.Stop()
	ticker := time.NewTicker(20 * time.Millisecond)
	defer ticker.Stop()
	for {
		observed, err := runner.Query(context.Background(), input.ExecutionID)
		if err == nil && observed.State == "RUNNING" {
			break
		}
		select {
		case <-deadline.C:
			cancel()
			<-done
			t.Fatal("execution did not start")
		case result := <-done:
			t.Fatalf("execution ended before cancellation: %s %v", result.result.State, result.err)
		case <-ticker.C:
		}
	}
	other := input
	other.ExecutionID = identifier()
	other.Subpath = other.WorkspaceID + "/copy-" + other.ExecutionID + "/tree"
	if _, err := runner.Run(context.Background(), other); !errors.Is(err, execution.ErrBusy) {
		t.Fatalf("concurrent execution was not rejected: %v", err)
	}
	cancel()
	result := <-done
	if result.err != nil || result.result.State != "INTERRUPTED" || !result.result.Quiescent || result.result.Cleanup != "SUCCEEDED" {
		t.Fatalf("cancellation did not terminate execution: %s %s %v", result.result.State, result.result.Cleanup, result.err)
	}
	if _, err := docker.ContainerInspect(context.Background(), result.result.ContainerID); !errdefs.IsNotFound(err) {
		t.Fatalf("cancelled process container remains: %v", err)
	}
}

func TestRestrictedExecutionProcessDeathRetainsSlot(t *testing.T) {
	source, _ := os.ReadFile("/fixture/reference/src/main/java/interview/CartTotal.java")
	runner, input, docker, root := fixture(t, string(source))
	if err := runner.Close(); err != nil {
		t.Fatal(err)
	}
	encoded, err := json.Marshal(input)
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	child := exec.CommandContext(ctx, "/execution.test", "-test.run=^TestExecutionCrashWorker$")
	child.Env = append(os.Environ(), "CODECRAFT_CRASH_INPUT="+string(encoded), "CODECRAFT_CRASH_ROOT="+root)
	output, err := child.StdoutPipe()
	if err != nil {
		t.Fatal(err)
	}
	if err = child.Start(); err != nil {
		t.Fatal(err)
	}
	scanner := bufio.NewScanner(output)
	ready := false
	for scanner.Scan() {
		if scanner.Text() == "EXECUTION_REGISTERED_AND_RUNNING" {
			ready = true
			break
		}
	}
	killErr := child.Process.Kill()
	_ = child.Wait()
	if !ready || killErr != nil {
		t.Fatalf("crash fixture never reached running registration: ready=%v kill=%v scan=%v", ready, killErr, scanner.Err())
	}
	restarted, err := execution.New(ctx, docker, root, os.Getenv("CODECRAFT_EXECUTION_VOLUME"), strings.TrimPrefix(filepath.Base(root), "registry-"))
	if err != nil {
		t.Fatal(err)
	}
	defer restarted.Close()
	observed, err := restarted.Query(ctx, input.ExecutionID)
	if err != nil || observed.Quiescent || observed.ContainerID == "" {
		t.Fatalf("lost execution evidence: %s %v", observed.State, err)
	}
	// Test teardown only: production recovery must reconcile, not blindly remove.
	defer func() {
		if err := docker.ContainerRemove(context.Background(), observed.ContainerID, container.RemoveOptions{Force: true}); err != nil {
			t.Error(err)
		}
	}()
	other := input
	other.ExecutionID = identifier()
	other.Subpath = other.WorkspaceID + "/copy-" + other.ExecutionID + "/tree"
	if _, err = restarted.Run(ctx, other); !errors.Is(err, execution.ErrBusy) {
		t.Fatalf("restart reopened unresolved capacity: %v", err)
	}
	replay, err := restarted.Run(ctx, input)
	if err != nil || replay.ContainerID != observed.ContainerID {
		t.Fatalf("restart re-executed original input: %v", err)
	}
}

func TestExecutionCrashWorker(t *testing.T) {
	encoded := os.Getenv("CODECRAFT_CRASH_INPUT")
	if encoded == "" {
		t.Skip("subprocess crash fixture only")
	}
	var input execution.Input
	if err := json.Unmarshal([]byte(encoded), &input); err != nil {
		t.Fatal(err)
	}
	docker, err := client.NewClientWithOpts(client.FromEnv, client.WithAPIVersionNegotiation())
	if err != nil {
		t.Fatal(err)
	}
	defer docker.Close()
	root := os.Getenv("CODECRAFT_CRASH_ROOT")
	runner, err := execution.New(context.Background(), docker, root, os.Getenv("CODECRAFT_EXECUTION_VOLUME"), strings.TrimPrefix(filepath.Base(root), "registry-"))
	if err != nil {
		t.Fatal(err)
	}
	defer runner.Close()
	done := make(chan error, 1)
	go func() { _, err := runner.Run(context.Background(), input); done <- err }()
	ticker := time.NewTicker(10 * time.Millisecond)
	defer ticker.Stop()
	for {
		record, err := runner.Query(context.Background(), input.ExecutionID)
		if err == nil && record.State == "RUNNING" {
			fmt.Println("EXECUTION_REGISTERED_AND_RUNNING")
			<-done
			return
		}
		select {
		case err := <-done:
			t.Fatalf("run ended early: %v", err)
		case <-ticker.C:
		}
	}
}

func TestRestrictedExecutionMissingReportIsNotSuccess(t *testing.T) {
	source, _ := os.ReadFile("/fixture/reference/src/main/java/interview/CartTotal.java")
	modified := strings.Replace(string(source), "public final class CartTotal {", "public final class CartTotal { static { if (System.nanoTime()!=0) System.exit(0); }", 1)
	runner, input, _, _ := fixture(t, modified)
	result, err := runner.Run(context.Background(), input)
	if err != nil || result.State != "ENVIRONMENT_ERROR" || !result.Quiescent || result.Cleanup != "SUCCEEDED" {
		t.Fatalf("state=%s reason=%s cleanup=%s error=%v", result.State, result.Reason, result.Cleanup, err)
	}
}

func TestRestrictedExecutionPreservesReportAndDurableResult(t *testing.T) {
	source, err := os.ReadFile("/fixture/reference/src/main/java/interview/CartTotal.java")
	if err != nil && os.Getenv("CODECRAFT_EXECUTION_TEST") == "1" {
		t.Fatal(err)
	}
	runner, input, docker, root := fixture(t, string(source))
	result, err := runner.Run(context.Background(), input)
	if err != nil {
		t.Fatal(err)
	}
	if result.State != "PASSED" || !result.Quiescent || result.Cleanup != "SUCCEEDED" || result.ExitCode == nil || *result.ExitCode != 0 || len(result.Report) == 0 {
		t.Fatalf("unexpected result: state=%s reason=%s quiescent=%v cleanup=%s", result.State, result.Reason, result.Quiescent, result.Cleanup)
	}
	if err := runner.Close(); err != nil {
		t.Fatal(err)
	}
	runner, err = execution.New(context.Background(), docker, root, os.Getenv("CODECRAFT_EXECUTION_VOLUME"), strings.TrimPrefix(filepath.Base(root), "registry-"))
	if err != nil {
		t.Fatal(err)
	}
	defer runner.Close()
	recovered, err := runner.Query(context.Background(), input.ExecutionID)
	if err != nil || recovered.State != "PASSED" || string(recovered.Report) != string(result.Report) {
		t.Fatalf("result not durable: %v %v", recovered.State, err)
	}
	replay, err := runner.Run(context.Background(), input)
	if err != nil || replay.ContainerID != result.ContainerID || !replay.StartedAt.Equal(result.StartedAt) {
		t.Fatalf("replay launched another execution: %v", err)
	}
	// Storage-fault fixture: a well-formed record with damaged authority evidence
	// must not recover a successful grade or become permission to run again.
	recovered.Report = []byte("<testsuite/>")
	damaged, err := json.Marshal(recovered)
	if err != nil {
		t.Fatal(err)
	}
	if err = os.WriteFile(filepath.Join(root, input.ExecutionID+".json"), damaged, 0600); err != nil {
		t.Fatal(err)
	}
	if _, err = runner.Query(context.Background(), input.ExecutionID); err == nil {
		t.Fatal("damaged authority report recovered as PASSED")
	}
	if _, err = runner.Run(context.Background(), input); err == nil {
		t.Fatal("damaged authority report accepted for replay")
	}
}
