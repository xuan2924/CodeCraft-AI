package execution_test

import (
	"context"
	"os"
	"path/filepath"
	"testing"
	"time"

	"codecraft.local/sandbox/internal/execution"
)

func TestRestrictedExecutionRetainsCleanupOwnershipAfterPreparedInputExpires(t *testing.T) {
	for _, cause := range []string{"expiry", "owner-cancelled"} {
		t.Run(cause, func(t *testing.T) {
			source, _ := os.ReadFile("/fixture/reference/src/main/java/interview/CartTotal.java")
			runner, input, _, _ := fixture(t, string(source))
			input.TaskID, input.ExerciseID, input.ProblemID, input.ProblemVersion = identifier(), identifier(), "cart-total", "1.0.1"
			owner, cancel := context.WithCancel(context.Background())
			defer cancel()
			if cause == "expiry" {
				input.NotAfter = time.Now().Add(100 * time.Millisecond)
			}
			prepared := func() error {
				// The fixture is an actual independent input tree. Fault precisely after its
				// publication, before admission completes; no Docker side effect may start.
				if cause == "expiry" {
					<-time.After(time.Until(input.NotAfter) + time.Millisecond)
				} else {
					cancel()
				}
				return nil
			}
			cleanup := func(_ context.Context, result execution.Result) error {
				return os.RemoveAll(filepath.Dir(filepath.Join("/state", result.Input.Subpath)))
			}
			accepted, err := runner.Start(owner, input, prepared, cleanup)
			if err != nil {
				t.Fatalf("published input lost its durable cleanup owner: %v", err)
			}
			wait, stop := context.WithTimeout(context.Background(), 10*time.Second)
			defer stop()
			runner.Wait()
			result, err := runner.Query(wait, accepted.Input.ExecutionID)
			if err != nil || result.State != "INTERRUPTED" || !result.Quiescent || result.ContainerID != "" || result.CopyCleanup != "SUCCEEDED" {
				t.Fatalf("incomplete interrupted-input cleanup: state=%s cleanup=%s error=%v", result.State, result.CopyCleanup, err)
			}
			if _, err := os.Stat(filepath.Join("/state", input.Subpath)); !os.IsNotExist(err) {
				t.Fatalf("input retained: %v", err)
			}
			// A durably resolved, cleaned attempt must not poison executor capacity.
			next := input
			next.ExecutionID, next.TaskID, next.Subpath = identifier(), identifier(), ""
			next.Subpath = next.WorkspaceID + "/copy-" + next.ExecutionID + "/tree"
			next.NotAfter = time.Now().Add(time.Minute)
			nextOwner, cancelNext := context.WithCancel(context.Background())
			if _, err = runner.Start(nextOwner, next, func() error { cancelNext(); return nil }, cleanup); err != nil {
				t.Fatal(err)
			}
			runner.Wait()
		})
	}
}
