package execution

import (
	"context"
	"strings"
	"testing"
	"time"
)

func commandInput() Input {
	workspace := "10000000-0000-0000-0000-000000000001"
	execution := "10000000-0000-0000-0000-000000000002"
	return Input{Purpose: "COMMAND", ExerciseID: workspace, TaskID: execution, ExecutionID: execution, WorkspaceID: workspace,
		ProblemID: "cart", ProblemVersion: "1.0.0", Revision: 1, GateEpoch: 1, Image: "sha256:" + strings.Repeat("a", 64),
		PackageSHA256: strings.Repeat("b", 64), Platform: "linux/amd64", Target: "bash",
		Subpath: workspace + "/command-" + execution + "/tree", NotAfter: time.Now().Add(time.Minute),
		Command: &CommandSpec{Text: "printf 'ok\\n'; exit 7", Directory: "."},
		Limits:  Limits{CPUs: 1, MemoryMiB: 256, PIDs: 64, WorkMiB: 64, TmpMiB: 32, ShmMiB: 16, TimeoutSeconds: 120, LogBytes: 1048576}}
}

func TestCommandContainerUsesNetworkAndOnlyItsMutableCopy(t *testing.T) {
	input := commandInput()
	if err := validateInput(input); err != nil {
		t.Fatal(err)
	}
	runner := &Runner{volume: "managed-workspaces", inputPrefix: "instances/", owner: "fixture"}
	config, host := runner.containerConfig(input)
	if config.NetworkDisabled || host.NetworkMode != "bridge" || !host.ReadonlyRootfs || config.User != "10001:10001" {
		t.Fatalf("unexpected command isolation: config=%+v host=%+v", config, host)
	}
	if len(host.Mounts) != 1 || host.Mounts[0].Target != "/project" || host.Mounts[0].ReadOnly || host.Mounts[0].VolumeOptions.Subpath != "instances/"+input.Subpath {
		t.Fatalf("command must mount only its independent copy: %+v", host.Mounts)
	}
	if !strings.Contains(strings.Join(config.Env, "\n"), "CODECRAFT_COMMAND="+input.Command.Text) || config.Entrypoint[0] != "/bin/bash" {
		t.Fatal("command must be passed unchanged to container Bash")
	}
	if !strings.Contains(host.Tmpfs["/cache"], "size=64m") || host.Tmpfs["/project/target"] != "" || host.Tmpfs["/project/node_modules"] != "" {
		t.Fatal("dependency cache must be bounded; build directories must remain removable")
	}
}

func TestCommandCannotMasqueradeAsAuthorityOrEscapeItsCopy(t *testing.T) {
	for _, mutate := range []func(*Input){
		func(input *Input) { input.Purpose = "MANUAL" },
		func(input *Input) { input.Target = "base" },
		func(input *Input) { input.Subpath = input.WorkspaceID + "/commit-1/tree" },
		func(input *Input) { input.Suite = "AuthorityTest"; input.Tests = []string{"passes"} },
		func(input *Input) { input.Command.Directory = "../other" },
		func(input *Input) { input.Command.Directory = "/tmp" },
		func(input *Input) { input.Command.Directory = "src/../../other" },
		func(input *Input) { input.Command.Text = strings.Repeat("x", 4097) },
		func(input *Input) { input.Command.Text = "echo\x00bad" },
		func(input *Input) { input.Command = nil },
	} {
		input := commandInput()
		mutate(&input)
		if validateInput(input) == nil {
			t.Errorf("accepted invalid command input: %+v", input)
		}
	}
}

func TestCommandCopyRecoveryDoesNotReopenConfirmedContainerCleanup(t *testing.T) {
	finished := time.Now().UTC()
	runner := &Runner{} // No Docker client: the durable quiescence proof is sufficient.
	record := Result{Input: commandInput(), State: "INTERRUPTED", Reason: "authority_expired", FinishedAt: &finished,
		Quiescent: true, Cleanup: "SUCCEEDED", CopyCleanup: "PENDING"}
	result, err := runner.recoverExecution(context.Background(), record, nil)
	if err != nil || !result.Quiescent || result.Cleanup != "SUCCEEDED" || result.CopyCleanup != "PENDING" {
		t.Fatalf("copy finalization lost its cleanup proof: %v %+v", err, result)
	}
}

func TestCommandStopReasonFollowsFirstDurableIntent(t *testing.T) {
	for _, first := range []string{"command_authority_ended", "user_stop"} {
		t.Run(first, func(t *testing.T) {
			now := time.Date(2026, 1, 1, 0, 0, 0, 0, time.UTC)
			runner := &Runner{root: t.TempDir(), now: func() time.Time { return now }, tasks: map[string]string{}}
			input := commandInput()
			input.NotAfter = now.Add(-time.Second)
			record := Result{Input: input, State: "RUNNING", Intent: "STOPPED", Reason: first, StartedAt: now.Add(-time.Minute), Cleanup: "PENDING", CopyCleanup: "PENDING"}
			if err := runner.persist(record); err != nil {
				t.Fatal(err)
			}
			record.FinishedAt, record.Quiescent, record.Cleanup = &now, true, "SUCCEEDED"
			record.Reason = "user_stop"
			if first == "user_stop" {
				record.Reason = "command_authority_ended"
			}
			if err := runner.persist(record); err != nil {
				t.Fatal(err)
			}
			result, err := runner.Query(context.Background(), input.ExecutionID)
			if err != nil || result.State != "STOPPED" || result.Reason != first {
				t.Fatalf("first intent reason changed: %v %+v", err, result)
			}
		})
	}
}

func TestCommandNonzeroExitIsDurableWithoutAnAuthorityGrade(t *testing.T) {
	runner := &Runner{root: t.TempDir(), now: time.Now, tasks: map[string]string{}}
	input := commandInput()
	now, exit := time.Now().UTC(), 7
	result := Result{Input: input, State: "EXITED", Reason: "command_exit", StartedAt: now, FinishedAt: &now,
		Quiescent: true, Cleanup: "SUCCEEDED", CopyCleanup: "SUCCEEDED", ExitCode: &exit}
	if err := runner.persist(result); err != nil {
		t.Fatal(err)
	}
	retained, err := runner.Query(context.Background(), input.ExecutionID)
	if err != nil || retained.State != "EXITED" || retained.ExitCode == nil || *retained.ExitCode != 7 || retained.Evidence() != nil {
		t.Fatalf("command was misclassified: %v %+v", err, retained)
	}
	if _, err = runner.Start(context.Background(), input, nil, nil); err == nil {
		t.Fatal("unmonitored command was admitted")
	}
}

func TestAuthorityContainerStaysOfflineAndReadOnly(t *testing.T) {
	input := commandInput()
	input.Purpose, input.Target, input.Command = "MANUAL", "base", nil
	input.Subpath = input.WorkspaceID + "/copy-" + input.ExecutionID + "/tree"
	input.Suite, input.Tests = "AuthorityTest", []string{"passes"}
	if err := validateInput(input); err != nil {
		t.Fatal(err)
	}
	config, host := (&Runner{volume: "managed-workspaces"}).containerConfig(input)
	if !config.NetworkDisabled || host.NetworkMode != "none" || !host.Mounts[0].ReadOnly || host.Mounts[0].Target != "/input" {
		t.Fatal("development commands must not relax authority execution")
	}
}
