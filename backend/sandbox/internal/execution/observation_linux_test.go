package execution

import (
	"context"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/docker/docker/client"
)

// The collector remains outstanding across manually driven observation ticks.
// Only new output may advance the durable record; cancelling must join it.
func TestQuietObservationsDoNotPollDockerOrRewriteResults(t *testing.T) {
	for _, terminal := range []bool{false, true} {
		name := "command"
		if terminal {
			name = "pty"
		}
		t.Run(name, func(t *testing.T) {
			ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
			defer cancel()
			containerID := strings.Repeat("c", 64)
			var calls atomic.Int32
			started := make(chan struct{})
			unblock := make(chan struct{})
			daemon := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
				if req.URL.Path != "/v1.45/containers/"+containerID+"/exec" {
					t.Errorf("unexpected daemon request: %s", req.URL.Path)
				}
				if calls.Add(1) == 1 {
					close(started)
				}
				select {
				case <-unblock:
				case <-req.Context().Done():
				}
			}))
			defer daemon.Close()
			defer close(unblock)
			docker, err := client.NewClientWithOpts(client.WithHost(daemon.URL), client.WithVersion("1.45"))
			if err != nil {
				t.Fatal(err)
			}
			defer docker.Close()
			r := &Runner{root: t.TempDir(), docker: docker}
			in := commandInput()
			if terminal {
				in.Command.Terminal = true
				in.Command.Text = TerminalCommand
				r.newTerminal(in, nil)
			}
			record := Result{Input: in, State: "RUNNING", StartedAt: time.Now().UTC(), ContainerID: containerID, Cleanup: "PENDING", CopyCleanup: "PENDING"}
			if err := r.persist(record); err != nil {
				t.Fatal(err)
			}
			logs := &tailLog{limit: 3}
			ticks := make(chan time.Time)
			result := make(chan string, 1)
			go func() { state, _ := r.observe(ctx, &record, logs, nil, waitStatusLock, ticks); result <- state }()
			select {
			case <-started:
			case state := <-result:
				t.Fatalf("observation exited before collecting: %s", state)
			case <-ctx.Done():
				t.Fatal(ctx.Err())
			}
			tick := func() {
				t.Helper()
				select {
				case ticks <- time.Time{}:
				case <-ctx.Done():
					t.Fatal(ctx.Err())
				case s := <-result:
					t.Fatalf("early exit %s", s)
				}
			}
			version := func(want uint64) {
				t.Helper()
				got, err := r.Query(ctx, in.ExecutionID)
				if err != nil || got.Version != want {
					t.Fatalf("version %d, want %d (err %v)", got.Version, want, err)
				}
			}
			tick()
			tick()
			version(1)
			_, _ = (channelWriter{logs, "stdout"}).Write([]byte("flood"))
			tick()
			tick()
			version(2)
			cancel()
			if state := <-result; state != "INTERRUPTED" {
				t.Fatalf("cancelled: %s", state)
			}
			if got := calls.Load(); got != 1 {
				t.Fatalf("Docker calls: %d", got)
			}
		})
	}
}

func TestTerminalIdleTimeoutBoundaries(t *testing.T) {
	now := time.Unix(10, 0)
	terminal := &Terminal{lastActivity: now, attached: true}
	runner := &Runner{}
	if defaultTerminalIdleTimeout != 30*time.Minute {
		t.Fatal("unexpected default")
	}
	for _, timeout := range []time.Duration{defaultTerminalIdleTimeout, 2 * time.Minute} {
		if err := runner.ConfigureTerminalIdleTimeout(timeout); err != nil {
			t.Fatal(err)
		}
		if terminal.idleExpired(now.Add(timeout-time.Nanosecond), runner.terminalIdleTimeout) || !terminal.idleExpired(now.Add(timeout), runner.terminalIdleTimeout) {
			t.Fatal("attached deadline changed")
		}
	}
	terminal.attached = false
	if terminal.idleExpired(now.Add(30*time.Second-time.Nanosecond), runner.terminalIdleTimeout) || !terminal.idleExpired(now.Add(30*time.Second), runner.terminalIdleTimeout) {
		t.Fatal("attach grace changed")
	}
	if runner.ConfigureTerminalIdleTimeout(0) == nil {
		t.Fatal("zero timeout accepted")
	}
}
