package execution_test

import (
	"context"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"codecraft.local/sandbox/internal/execution"
)

func TestRealTerminalPTYResizeControlCAndEOF(t *testing.T) {
	runner, input, _, _ := fixture(t, "original")
	if err := runner.ConfigureTerminalEnvironment(func(context.Context, execution.Input) ([]string, error) {
		return []string{"OPENAI_BASE_URL=http://egress:3128/internal/llm-proxy/openai/v1", "ANTHROPIC_BASE_URL=http://egress:3128/internal/llm-proxy/anthropic", "CODECRAFT_PROXY_TOKEN=fixture-ephemeral-grant", "OPENAI_API_KEY=fixture-ephemeral-grant", "ANTHROPIC_API_KEY=fixture-ephemeral-grant"}, nil
	}); err != nil {
		t.Fatal(err)
	}
	original := filepath.Dir(filepath.Join("/state", input.Subpath))
	input.Purpose, input.Target, input.Suite, input.Tests = "COMMAND", "bash", "", nil
	input.TaskID, input.ExerciseID, input.ProblemID, input.ProblemVersion = identifier(), identifier(), "cart-total", "1.1.0"
	input.Command = &execution.CommandSpec{Text: execution.TerminalCommand, Directory: ".", Terminal: true}
	input.Subpath = input.WorkspaceID + "/command-" + input.ExecutionID + "/tree"
	for _, dir := range []string{"home", "maven", "npm"} {
		if err := os.MkdirAll(filepath.Join("/state", input.WorkspaceID, "dev-cache", dir), 0755); err != nil {
			t.Fatal(err)
		}
	}
	owner, cancel := context.WithTimeout(context.Background(), 25*time.Second)
	t.Cleanup(func() { cancel(); runner.Wait() })
	_, err := runner.StartMonitored(owner, input, func() error { return os.Rename(original, filepath.Dir(filepath.Join("/state", input.Subpath))) }, func(context.Context, execution.Input) error { return nil }, func(context.Context, execution.Result) error { return nil })
	if err != nil {
		t.Fatal(err)
	}
	var terminal *execution.Terminal
	identity := execution.TaskIdentity{ExerciseID: input.ExerciseID, TaskID: input.TaskID, ExecutionID: input.ExecutionID, WorkspaceID: input.WorkspaceID}
	for terminal == nil {
		terminal, err = runner.Terminal(identity)
		if err != nil {
			select {
			case <-owner.Done():
				t.Fatal("PTY unavailable")
			case <-time.After(25 * time.Millisecond):
			}
		}
	}
	if err = terminal.Resize(owner, 100, 40); err != nil {
		t.Fatal(err)
	}
	send := func(text string) {
		t.Helper()
		if err := terminal.Input(owner, []byte(text)); err != nil {
			t.Fatal(err)
		}
	}
	wait := func(marker string) string {
		t.Helper()
		var output string
		for !strings.Contains(output, marker) {
			select {
			case bytes := <-terminal.Output():
				output += string(bytes)
			case <-owner.Done():
				t.Fatalf("PTY did not produce %q: %q", marker, output)
			}
		}
		return output
	}
	send("stty size; printf 'READY_%s\\n' yes\n")
	if output := wait("READY_yes"); !strings.Contains(output, "40 100") {
		t.Fatalf("PTY did not apply resize: %q", output)
	}
	send("[ \"$OPENAI_API_KEY\" = \"$CODECRAFT_PROXY_TOKEN\" ] && [ \"$ANTHROPIC_API_KEY\" = \"$CODECRAFT_PROXY_TOKEN\" ] && [ -n \"$CODECRAFT_PROXY_TOKEN\" ] && printf 'PROXY_%s\\n' yes\n")
	wait("PROXY_yes")
	send("sleep 20\n")
	time.Sleep(150 * time.Millisecond)
	send("\x03")
	send("printf 'ALIVE_%s\\n' yes\n")
	wait("ALIVE_yes")
	send("\x04")
	runner.Wait()
	record, err := runner.Query(context.Background(), input.ExecutionID)
	encoded, _ := json.Marshal(record.Input)
	if strings.Contains(string(encoded), "fixture-ephemeral-grant") {
		t.Fatal("ephemeral proxy grant entered durable Input")
	}
	if err != nil || !record.Quiescent || record.Cleanup != "SUCCEEDED" || record.CopyCleanup != "SUCCEEDED" {
		t.Fatalf("PTY did not converge: %+v %v", record, err)
	}
}
