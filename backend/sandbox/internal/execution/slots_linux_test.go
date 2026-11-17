package execution

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/docker/docker/client"
)

func TestParallelSlotsReachDockerAndPreserveWorkspaceExclusion(t *testing.T) {
	entered := make(chan struct{}, 4)
	release := make(chan struct{})
	daemon := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case strings.HasSuffix(r.URL.Path, "/containers/create"):
			entered <- struct{}{}
			<-release
			http.Error(w, "injected ambiguous create", 500)
		default:
			http.Error(w, "unexpected Docker operation", 500)
		}
	}))
	defer daemon.Close()
	docker, err := client.NewClientWithOpts(client.WithHost(daemon.URL), client.WithVersion("1.45"))
	if err != nil {
		t.Fatal(err)
	}
	defer docker.Close()
	runner := &Runner{docker: docker, root: t.TempDir(), now: time.Now, tasks: map[string]string{}, active: map[string]Input{}, concurrency: 4}
	defer runner.Wait()
	defer close(release)
	input := commandInput()
	start := func(in Input) error {
		_, err := runner.StartMonitored(context.Background(), in, nil, func(context.Context, Input) error { return nil }, nil)
		return err
	}
	for n := 0; n < 4; n++ {
		in := input
		in.ExecutionID = fmt.Sprintf("20000000-0000-0000-0000-%012d", n)
		in.TaskID = in.ExecutionID
		in.WorkspaceID = fmt.Sprintf("30000000-0000-0000-0000-%012d", n)
		in.ExerciseID = in.WorkspaceID
		in.Subpath = in.WorkspaceID + "/command-" + in.ExecutionID + "/tree"
		if err := start(in); err != nil {
			t.Fatal(err)
		}
		select {
		case <-entered:
		case <-time.After(5 * time.Second):
			t.Fatal("distinct workspace blocked behind running container")
		}
		duplicate := in
		duplicate.ExecutionID = fmt.Sprintf("40000000-0000-0000-0000-%012d", n)
		duplicate.TaskID = duplicate.ExecutionID
		duplicate.Subpath = duplicate.WorkspaceID + "/command-" + duplicate.ExecutionID + "/tree"
		if err := start(duplicate); !errors.Is(err, ErrBusy) {
			t.Fatalf("same workspace admitted: %v", err)
		}
	}
	if err := start(input); !errors.Is(err, ErrBusy) {
		t.Fatalf("fifth workspace admitted: %v", err)
	}
}

func TestSlotConfigurationAndExerciseAliasExclusion(t *testing.T) {
	r := &Runner{active: map[string]Input{}, concurrency: 4}
	for _, n := range []int{0, 65} {
		if r.Configure(n, "", "") == nil {
			t.Fatal("invalid slot count")
		}
	}
	if r.Configure(4, "bridge", "http://proxy:3128") == nil {
		t.Fatal("open bridge accepted")
	}
	if err := r.Configure(2, "isolated", "http://proxy:3128"); err != nil {
		t.Fatal(err)
	}
	in := commandInput()
	r.reserve(in)
	other := in
	other.WorkspaceID = "different"
	if !r.capacityBusy(other) {
		t.Fatal("same exercise aliases admitted")
	}
}
