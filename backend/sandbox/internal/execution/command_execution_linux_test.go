package execution_test

import (
	"context"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"codecraft.local/sandbox/internal/execution"
)

// One local, deterministic command exercises the actual Docker adapter. It does
// not depend on DNS, downloads, Maven outcomes, or a model provider.
func TestDevelopmentCommandRunsInDockerAndRetainsNonzeroChanges(t *testing.T) {
	runner, input, docker, _ := fixture(t, "original source")
	original := filepath.Dir(filepath.Join("/state", input.Subpath))
	input.Purpose, input.Target, input.Suite, input.Tests = "COMMAND", "bash", "", nil
	input.TaskID, input.ExerciseID, input.ProblemID, input.ProblemVersion = identifier(), identifier(), "cart-total", "1.0.1"
	input.Subpath = input.WorkspaceID + "/command-" + input.ExecutionID + "/tree"
	input.Command = &execution.CommandSpec{Directory: ".", Text: "printf 'command changed' > src/main/java/interview/CartTotal.java; mkdir -p target; touch target/cache; rm -rf target || exit 8; printf COMMAND_STDOUT; printf COMMAND_STDERR >&2; exit 7"}
	prepare := func() error {
		return os.Rename(original, filepath.Dir(filepath.Join("/state", input.Subpath)))
	}
	monitor := func(ctx context.Context, current execution.Input) error {
		container, err := docker.ContainerInspect(ctx, "cc-execution-"+current.ExecutionID)
		if err != nil {
			return err
		}
		if container.HostConfig.NetworkMode != "none" || !container.HostConfig.ReadonlyRootfs || container.Config.User != "10001:10001" {
			return errors.New("development container configuration mismatch")
		}
		return nil
	}
	cleanup := func(_ context.Context, result execution.Result) error {
		if !result.Quiescent {
			return errors.New("copy callback ran before process quiescence")
		}
		content, err := os.ReadFile(filepath.Join("/state", result.Input.Subpath, "src/main/java/interview/CartTotal.java"))
		if err != nil {
			return err
		}
		if string(content) != "command changed" {
			return fmt.Errorf("source changes were not retained")
		}
		return nil
	}
	if _, err := runner.StartMonitored(context.Background(), input, prepare, monitor, cleanup); err != nil {
		t.Fatal(err)
	}
	runner.Wait()
	result, err := runner.Query(context.Background(), input.ExecutionID)
	if err != nil || result.State != "EXITED" || result.ExitCode == nil || *result.ExitCode != 7 || result.Evidence() != nil || !result.Quiescent || result.CopyCleanup != "SUCCEEDED" || result.Cleanup != "SUCCEEDED" {
		t.Fatalf("development outcome: state=%s reason=%s copy=%s error=%v", result.State, result.Reason, result.CopyCleanup, err)
	}
	channels := map[string]string{}
	for _, chunk := range result.Logs {
		channels[chunk.Channel] += string(chunk.Data)
	}
	if !strings.Contains(channels["stdout"], "COMMAND_STDOUT") || !strings.Contains(channels["stderr"], "COMMAND_STDERR") {
		t.Fatalf("missing ordinary command output: %+v", channels)
	}
	if _, err = os.Stat(filepath.Join("/state", input.Subpath, "target")); !os.IsNotExist(err) {
		t.Fatalf("build directory was not removable: %v", err)
	}
}
