package execution

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/docker/docker/client"
)

func TestPTYRawInputResizeSingleAttachAndBoundedOutput(t *testing.T) {
	resized := make(chan bool, 1)
	daemon := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		resized <- strings.HasSuffix(r.URL.Path, "/resize") && r.URL.Query().Get("w") == "100" && r.URL.Query().Get("h") == "40"
		w.WriteHeader(200)
	}))
	defer daemon.Close()
	docker, err := client.NewClientWithOpts(client.WithHost(daemon.URL), client.WithVersion("1.45"))
	if err != nil {
		t.Fatal(err)
	}
	defer docker.Close()
	a, b := net.Pipe()
	defer a.Close()
	defer b.Close()
	r := &Runner{docker: docker, root: t.TempDir()}
	in := commandInput()
	in.Command.Terminal = true
	in.Command.Text = TerminalCommand
	if err := r.persist(Result{Input: in, State: "RUNNING", StartedAt: time.Now(), Cleanup: "PENDING", CopyCleanup: "PENDING"}); err != nil {
		t.Fatal(err)
	}
	terminal := r.newTerminal(in, a)
	identity := TaskIdentity{ExerciseID: in.ExerciseID, TaskID: in.TaskID, ExecutionID: in.ExecutionID, WorkspaceID: in.WorkspaceID}
	if _, err = r.Terminal(identity); err != nil {
		t.Fatal(err)
	}
	if _, err = r.Terminal(identity); !errors.Is(err, ErrTerminalAttached) {
		t.Fatal("second PTY attached")
	}
	received := make(chan []byte, 1)
	go func() { data := make([]byte, 3); _, _ = io.ReadFull(b, data); received <- data }()
	if err = terminal.Input(context.Background(), []byte{3, 'x', 4}); err != nil {
		t.Fatal(err)
	}
	if string(<-received) != string([]byte{3, 'x', 4}) {
		t.Fatal("PTY controls were transformed")
	}
	if err = terminal.Resize(context.Background(), 100, 40); err != nil || !<-resized {
		t.Fatal("resize not delivered")
	}
	if terminal.Resize(context.Background(), 0, 40) == nil {
		t.Fatal("invalid resize accepted")
	}
	for n := 0; n < 32; n++ {
		if _, err = terminal.Write([]byte("bounded")); err != nil {
			t.Fatal(err)
		}
	}
	if _, err = terminal.Write([]byte("overflow")); err == nil {
		t.Fatal("unbounded PTY queue")
	}
	if !r.terminalExpired(in.ExecutionID) {
		t.Fatal("overflow did not fence process")
	}
	if err = terminal.Input(context.Background(), []byte("x")); !errors.Is(err, io.EOF) {
		t.Fatal("closed PTY accepted input")
	}
}

func TestTerminalQuiescenceWaitsForDurableProcessProof(t *testing.T) {
	in := commandInput()
	in.Command.Terminal = true
	in.Command.Text = TerminalCommand
	r := &Runner{root: t.TempDir(), tasks: map[string]string{}, now: time.Now, blocked: true}
	record := Result{Input: in, State: "RUNNING", StartedAt: time.Now(), Cleanup: "PENDING", CopyCleanup: "PENDING"}
	if err := r.persist(record); err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if !r.TerminalBusy(ctx, in.WorkspaceID, "") {
		t.Fatal("recovered terminal did not exclude writers")
	}
	finished := make(chan error, 1)
	go func() { finished <- r.QuiesceTerminals(ctx, in.WorkspaceID) }()
	for {
		got, err := r.Query(ctx, in.ExecutionID)
		if err != nil {
			t.Fatal(err)
		}
		if got.Intent == "STOPPED" {
			break
		}
		select {
		case <-ctx.Done():
			t.Fatal("stop was not persisted")
		case <-time.After(time.Millisecond):
		}
	}
	select {
	case err := <-finished:
		t.Fatalf("returned before process proof: %v", err)
	default:
	}
	now := time.Now()
	record.Quiescent = true
	record.State = "STOPPED"
	record.FinishedAt = &now
	record.Cleanup = "SUCCEEDED"
	if err := r.persist(record); err != nil {
		t.Fatal(err)
	}
	if err := <-finished; err != nil {
		t.Fatal(err)
	}
	if !r.TerminalBusy(ctx, in.WorkspaceID, "") || r.TerminalBusy(ctx, in.WorkspaceID, in.ExecutionID) {
		t.Fatal("only the quiescent terminal finalizer may publish before copy cleanup")
	}
}

func TestLateTerminalDisconnectCannotStopReplacementSession(t *testing.T) {
	r := &Runner{root: t.TempDir(), tasks: map[string]string{}}
	old := commandInput()
	old.Command.Terminal = true
	old.Command.Text = TerminalCommand
	now := time.Now()
	if err := r.persist(Result{Input: old, State: "STOPPED", StartedAt: now, FinishedAt: &now, Quiescent: true, Cleanup: "SUCCEEDED", CopyCleanup: "SUCCEEDED"}); err != nil {
		t.Fatal(err)
	}
	next := old
	next.ExecutionID = "10000000-0000-0000-0000-000000000009"
	next.TaskID = next.ExecutionID
	next.Subpath = next.WorkspaceID + "/command-" + next.ExecutionID + "/tree"
	if err := r.persist(Result{Input: next, State: "RUNNING", StartedAt: now, Cleanup: "PENDING", CopyCleanup: "PENDING"}); err != nil {
		t.Fatal(err)
	}
	if err := r.QuiesceTerminal(context.Background(), TaskIdentity{ExerciseID: old.ExerciseID, TaskID: old.TaskID, ExecutionID: old.ExecutionID, WorkspaceID: old.WorkspaceID}); err != nil {
		t.Fatal(err)
	}
	record, err := r.Query(context.Background(), next.ExecutionID)
	if err != nil || record.Intent != "" {
		t.Fatal("late disconnect stopped a replacement terminal")
	}
}

func TestTerminalEnvironmentGrantRejectsRawModelKeysAndOverrides(t *testing.T) {
	r := &Runner{}
	in := commandInput()
	in.Command.Terminal = true
	in.Command.Text = TerminalCommand
	for _, value := range []string{"OPENAI_API_KEY=forbidden", "PATH=/untrusted", "CODECRAFT_PROXY_TOKEN=bad\nline"} {
		if err := r.ConfigureTerminalEnvironment(func(context.Context, Input) ([]string, error) { return []string{value}, nil }); err != nil {
			t.Fatal(err)
		}
		if _, err := r.terminalGrant(context.Background(), in); err == nil {
			t.Fatal("unsafe environment grant accepted")
		}
	}
	if err := r.ConfigureTerminalEnvironment(func(context.Context, Input) ([]string, error) {
		return []string{"OPENAI_BASE_URL=http://egress/model", "CODECRAFT_PROXY_TOKEN=ephemeral"}, nil
	}); err != nil {
		t.Fatal(err)
	}
	values, err := r.terminalGrant(context.Background(), in)
	if err != nil || len(values) != 2 {
		t.Fatal("trusted temporary grant rejected")
	}
	encoded, _ := json.Marshal(in)
	if strings.Contains(string(encoded), "ephemeral") {
		t.Fatal("temporary grant entered durable input")
	}
}

func TestTerminalIsolationAndPersistenceMounts(t *testing.T) {
	r := &Runner{volume: "data", inputPrefix: "workspaces/"}
	in := commandInput()
	in.Command.Terminal = true
	in.Command.Text = TerminalCommand
	config, host := r.containerConfig(in)
	if !config.Tty || !config.OpenStdin || !config.AttachStdin {
		t.Fatal("real Docker PTY not requested")
	}
	if len(host.Mounts) != 2 || host.Mounts[1].Target != "/cache" || host.Mounts[1].VolumeOptions.Subpath != "workspaces/"+in.WorkspaceID+"/dev-cache" {
		t.Fatal("cache not scoped to exercise workspace")
	}
	if host.Tmpfs["/opt/acceptance"] == "" || host.Tmpfs["/cache"] != "" {
		t.Fatal("authority tests exposed or cache ephemeral")
	}
	data, err := json.Marshal(in)
	if err != nil || strings.Contains(string(data), "API_KEY") {
		t.Fatal("unexpected environment in durable input")
	}
}
