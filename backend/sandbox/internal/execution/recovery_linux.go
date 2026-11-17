package execution

import (
	"context"
	"crypto/rand"
	"errors"
	"log"
	"time"

	"github.com/docker/docker/api/types/container"
	"github.com/docker/docker/errdefs"
	"github.com/docker/docker/pkg/stdcopy"
)

// Recover resumes observation or retries cleanup, never creation or start. The
// owner is the service lifetime, not the requesting browser or RPC lifetime.
func (r *Runner) Recover(owner context.Context, id string, cleanup func(context.Context, Result) error) error {
	return r.RecoverMonitored(owner, id, nil, cleanup)
}

// RecoverMonitored restores mutable-copy budget checks while observing an existing command.
func (r *Runner) RecoverMonitored(owner context.Context, id string, monitor InputMonitor, cleanup func(context.Context, Result) error) error {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.closed {
		return ErrBusy
	}
	if r.running {
		return nil
	}
	record, err := r.Query(owner, id)
	if err != nil {
		return err
	}
	if record.Input.Purpose == "COMMAND" && monitor == nil {
		return errors.New("development commands require a workspace monitor")
	}
	if record.Quiescent && record.Cleanup == "SUCCEEDED" && (record.Input.TaskID == "" || record.CopyCleanup == "SUCCEEDED") {
		if record.StreamID == "" {
			return r.persist(record)
		}
		return nil
	}
	r.reserve(record.Input)
	go func() {
		result, err := r.recoverExecution(owner, record, monitor)
		if result.Quiescent && result.FinishedAt != nil && cleanup != nil && result.CopyCleanup != "SUCCEEDED" {
			ctx, cancel := context.WithTimeout(context.WithoutCancel(owner), 15*time.Second)
			durable, readErr := r.Query(ctx, id)
			if readErr == nil && durable.Quiescent && durable.FinishedAt != nil {
				cleanupErr := cleanup(ctx, durable)
				result.CopyCleanup = "SUCCEEDED"
				if cleanupErr != nil {
					result.CopyCleanup = "FAILED"
				}
				err = errors.Join(err, cleanupErr, r.persist(result))
			} else {
				err = errors.Join(err, readErr)
			}
			cancel()
		}
		if err != nil {
			log.Printf("execution recovery error: execution=%s error_type=%T", id, err)
		}
		r.finished(owner, result)
	}()
	return nil
}

func (r *Runner) recoverExecution(ctx context.Context, record Result, monitor InputMonitor) (Result, error) {
	if record.Quiescent && record.Reason == "network_setup_failed" {
		if err := r.removeNetwork(ctx, record.Input); err != nil {
			return record, err
		}
		record.Cleanup = "SUCCEEDED"
		return record, r.persist(record)
	}
	if record.Quiescent && record.Cleanup == "SUCCEEDED" && record.FinishedAt != nil {
		// Only copy finalization remains. In particular, a preparation-time
		// expiry has no container ID and must not become ambiguous Create.
		return record, nil
	}
	call, cancel := context.WithTimeout(ctx, 5*time.Second)
	id := record.ContainerID
	if id == "" {
		id = "cc-execution-" + record.Input.ExecutionID
	}
	inspected, err := r.docker.ContainerInspect(call, id)
	cancel()
	if errdefs.IsNotFound(err) {
		if record.ContainerID == "" {
			// An ambiguous Create may still arrive. Absence of its name is not a fence.
			record.State, record.Reason = "INTERRUPTED", "container_create_unconfirmed"
			now := time.Now().UTC()
			record.FinishedAt = &now
			record.Cleanup = "FAILED"
			persistErr := r.persist(record)
			return record, persistErr
		}
		// An absent immutable Docker ID fences any delayed Start for that ID.
		record.Quiescent = true
		record.Cleanup = "SUCCEEDED"
		if record.FinishedAt == nil {
			record.State, record.Reason = "INTERRUPTED", "execution_missing"
			now := time.Now().UTC()
			record.FinishedAt = &now
		}
		err = r.persist(record)
		if err == nil {
			record, err = r.Query(context.WithoutCancel(ctx), record.Input.ExecutionID)
		}
		return record, err
	}
	if err != nil {
		return record, err
	}
	if !r.owns(record, inspected) {
		return record, errors.New("recovery container ownership mismatch")
	}
	if record.ContainerID == "" {
		record.ContainerID = inspected.ID
		if err = r.persist(record); err != nil {
			return record, err
		}
	}
	if record.FinishedAt != nil {
		if !record.Quiescent {
			return r.fenceStart(ctx, record, &tailLog{limit: record.Input.Limits.LogBytes, chunks: record.Logs, dropped: record.DroppedBytes}, nil)
		}
		// A durable outcome is never regraded. Retry only exact-instance cleanup.
		if err = r.terminate(ctx, &record); err != nil {
			return record, err
		}
		return r.complete(ctx, record, record.State, record.Reason, nil)
	}
	if inspected.State == nil {
		return record, errors.New("recovery process state unavailable")
	}
	if !inspected.State.Running {
		if inspected.State.Status == "created" {
			// Start could still be in flight when the owner died. Exact-ID removal is
			// required, rather than interpreting Running=false as completed work.
			return r.fenceStart(ctx, record, &tailLog{limit: record.Input.Limits.LogBytes}, nil)
		}
		state, reason := "INTERRUPTED", "execution_exited_without_durable_evidence"
		if record.Intent != "" {
			state, reason = record.Intent, "recovered_intent"
		} else if record.ExitCode != nil {
			if record.Input.Purpose == "COMMAND" {
				state, reason = "EXITED", "command_exit"
			} else if record.Reason == "compilation_failed" && validCompilationReport(record.CompilationReport) {
				state, reason = "FAILED", "compilation_failed"
			} else if grade, e := classifyReport(record.Report, record.Input, *record.ExitCode); e == nil {
				state, reason = grade, "authority_report"
			}
		}
		return r.finish(ctx, record, state, reason, nil, nil)
	}
	if !time.Now().Before(record.StartedAt.Add(time.Duration(record.Input.Limits.TimeoutSeconds) * time.Second)) {
		record.Intent = "TIMED_OUT"
		if err = r.persist(record); err != nil {
			return record, err
		}
		return r.finish(ctx, record, "TIMED_OUT", "platform_deadline", nil, nil)
	}
	// Docker retains a bounded log tail. Replace, rather than append to, the old
	// snapshot; reconnect consumers will reset their snapshot/stream cursor.
	call, cancel = context.WithDeadline(ctx, record.StartedAt.Add(time.Duration(record.Input.Limits.TimeoutSeconds)*time.Second+15*time.Second))
	output, err := r.docker.ContainerLogs(call, record.ContainerID, container.LogsOptions{ShowStdout: true, ShowStderr: true, Follow: true, Tail: "all"})
	// The SDK response stream uses this context for its whole lifetime.
	if err != nil {
		cancel()
		return record, err
	}
	defer cancel()
	defer output.Close()
	logs := &tailLog{limit: record.Input.Limits.LogBytes, dropped: record.DroppedBytes}
	record.LogsIncomplete = true
	record.StreamID = rand.Text()
	drained := make(chan error, 1)
	go func() {
		_, err := stdcopy.StdCopy(channelWriter{logs, "stdout"}, channelWriter{logs, "stderr"}, output)
		drained <- err
	}()
	join := func() error {
		select {
		case err := <-drained:
			return err
		case <-time.After(5 * time.Second):
			output.Close()
			return <-drained
		}
	}
	return r.observeExecution(ctx, record, logs, join, monitor)
}
