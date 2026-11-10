package execution

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"os"
	"strconv"
	"time"

	"github.com/docker/docker/api/types/container"
	"github.com/docker/docker/api/types/mount"
	"github.com/docker/docker/errdefs"
	"github.com/docker/docker/pkg/stdcopy"
	"github.com/docker/go-units"
	specs "github.com/opencontainers/image-spec/specs-go/v1"
)

const command = `cp -R /input/src /work/src || exit 90
mvn -o -B -ntp -f /opt/contract/pom.xml -Dmaven.repo.local=/opt/maven-repository -Dcandidate.sources=/work/src/main/java -Dacceptance.sources=/opt/acceptance -Dbuild.output=/work/target test
status=$?
printf '%s' "$status" > /work/.result-status.tmp || exit 91
mv /work/.result-status.tmp /work/result-status || exit 92
while :; do sleep 1; done`

// Run executes only the protected base target. ctx is the owning worker lifetime,
// not a browser/RPC context. Every Docker side effect follows durable registration.
// Replays return the original record; unresolved work never starts a second run.
func (r *Runner) Run(ctx context.Context, input Input) (Result, error) {
	record, fresh, err := r.register(ctx, input, nil)
	if err != nil || !fresh {
		return record, err
	}
	result, runErr := r.execute(ctx, record)
	r.finished(ctx, result)
	return result, runErr
}

// Start durably reserves capacity before returning. The caller supplies its worker
// lifetime (never the RPC lifetime) and a bounded cleanup callback for the input.
// Completion stays owned by this runner; Wait must precede Close.
func (r *Runner) Start(owner context.Context, input Input, prepare func() error, cleanup func(context.Context, Result) error) (Result, error) {
	record, fresh, err := r.register(owner, input, prepare)
	if err != nil || !fresh {
		return record, err
	}
	go func() {
		result, runErr := r.execute(owner, record)
		if result.Quiescent && cleanup != nil {
			ctx, cancel := context.WithTimeout(context.WithoutCancel(owner), 15*time.Second)
			// Operational errors can coexist with proven quiescence. Only durable
			// terminal evidence permits releasing the referenced input.
			durable, evidenceErr := r.Query(ctx, input.ExecutionID)
			if evidenceErr == nil && durable.Quiescent && durable.FinishedAt != nil && durable.State == result.State && durable.ContainerID == result.ContainerID {
				cleanupErr := cleanup(ctx, result)
				result.CopyCleanup = "SUCCEEDED"
				if cleanupErr != nil {
					result.CopyCleanup = "FAILED"
				}
				runErr = errors.Join(runErr, cleanupErr, r.persist(result))
			} else {
				runErr = errors.Join(runErr, evidenceErr, errors.New("input cleanup lacks durable quiescence evidence"))
			}
			cancel()
		}
		if runErr != nil {
			log.Printf("execution worker error: execution=%s reason=%s error_type=%T", input.ExecutionID, result.Reason, runErr)
		}
		r.finished(owner, result)
	}()
	return record, nil
}

func (r *Runner) register(ctx context.Context, input Input, prepare func() error) (Result, bool, error) {
	if err := validateInput(input); err != nil {
		return Result{}, false, err
	}
	input.Tests = append([]string(nil), input.Tests...)
	r.mu.Lock()
	if cancellation, err := r.cancellation(input.ExecutionID); err == nil {
		r.mu.Unlock()
		if !cancellation.matches(input) {
			return Result{}, false, ErrConflict
		}
		return Result{}, false, ErrCancelled
	} else if !errors.Is(err, os.ErrNotExist) {
		r.mu.Unlock()
		return Result{}, false, err
	}
	if previous, exists := r.tasks[input.TaskID]; input.TaskID != "" && exists && previous != input.ExecutionID {
		r.mu.Unlock()
		return Result{}, false, ErrConflict
	}
	previous, err := r.Query(ctx, input.ExecutionID)
	if err == nil {
		r.mu.Unlock()
		a, _ := json.Marshal(previous.Input)
		b, _ := json.Marshal(input)
		if !bytes.Equal(a, b) {
			return Result{}, false, ErrConflict
		}
		return previous, false, nil
	}
	if !errors.Is(err, os.ErrNotExist) {
		r.mu.Unlock()
		return Result{}, false, err
	}
	if r.running || r.blocked || r.closed {
		r.mu.Unlock()
		return Result{}, false, ErrBusy
	}
	if !time.Now().Before(input.NotAfter) {
		r.mu.Unlock()
		return Result{}, false, errors.New("execution authorization expired")
	}
	if prepare != nil {
		if err := prepare(); err != nil {
			r.mu.Unlock()
			return Result{}, false, err
		}
	}
	record := Result{Input: input, State: "STARTING", StartedAt: time.Now().UTC(), Cleanup: "PENDING"}
	if ctx.Err() != nil || !time.Now().Before(input.NotAfter) {
		// Publication succeeded: retain a durable owner even if authority expired
		// at this boundary. It owns cleanup only, never container creation.
		record.State, record.Reason = "INTERRUPTED", "authority_expired_after_input"
		record.Quiescent, record.Cleanup = true, "SUCCEEDED"
		now := time.Now().UTC()
		record.FinishedAt = &now
	}
	if input.TaskID != "" {
		record.CopyCleanup = "PENDING"
	}
	if err = r.persist(record); err != nil {
		r.blocked = true
		r.mu.Unlock()
		return Result{}, false, err
	}
	if input.TaskID != "" {
		r.tasks[input.TaskID] = input.ExecutionID
	}
	r.running = true
	r.done = make(chan struct{})
	r.mu.Unlock()
	return record, true, nil
}

func (r *Runner) finished(ctx context.Context, result Result) {
	durable, err := r.Query(context.WithoutCancel(ctx), result.Input.ExecutionID)
	r.mu.Lock()
	defer r.mu.Unlock()
	r.running = false
	if err != nil || !result.Quiescent || !durable.Quiescent || durable.Cleanup != "SUCCEEDED" || durable.CopyCleanup == "FAILED" || (durable.Input.TaskID != "" && durable.CopyCleanup != "SUCCEEDED") {
		r.blocked = true
	} else {
		r.blocked = false
		if err := r.inventory(context.WithoutCancel(ctx)); err != nil {
			r.blocked = true
		}
	}
	close(r.done)
}

func (r *Runner) execute(ctx context.Context, record Result) (Result, error) {
	input := record.Input
	if record.State == "INTERRUPTED" && record.Quiescent {
		return record, nil
	}
	if ctx.Err() != nil || !time.Now().Before(input.NotAfter) {
		record.State, record.Reason = "INTERRUPTED", "authority_expired_before_create"
		record.Quiescent, record.Cleanup = true, "SUCCEEDED"
		now := time.Now().UTC()
		record.FinishedAt = &now
		return record, r.persist(record)
	}
	intent, intentErr := r.intent(ctx, input.ExecutionID)
	if intentErr != nil {
		return record, intentErr
	}
	if intent != "" {
		record.State, record.Intent, record.Reason = intent, intent, "user_stop"
		record.Quiescent, record.Cleanup = true, "SUCCEEDED"
		now := time.Now().UTC()
		record.FinishedAt = &now
		return record, r.persist(record)
	}
	config, host := r.containerConfig(input)
	call, cancel := context.WithTimeout(ctx, 5*time.Second)
	created, err := r.docker.ContainerCreate(call, config, host, nil, &specs.Platform{OS: "linux", Architecture: "amd64"}, "cc-execution-"+input.ExecutionID)
	cancel()
	if err != nil {
		// A lost create response may still produce a container. Keep the registration
		// and capacity unresolved; querying an absent name is not proof of cancellation.
		record.State = "INTERRUPTED"
		record.Reason = "container_create_unconfirmed"
		record.Cleanup = "FAILED"
		now := time.Now().UTC()
		record.FinishedAt = &now
		return record, errors.Join(err, r.persist(record))
	}
	record.ContainerID = created.ID
	if err = r.persist(record); err != nil {
		return r.finish(ctx, record, "INTERRUPTED", "registration_update_failed", nil, err)
	}
	if !time.Now().Before(input.NotAfter) {
		return r.finish(ctx, record, "INTERRUPTED", "authorization_expired_before_start", nil, nil)
	}
	call, cancel = context.WithTimeout(ctx, 5*time.Second)
	attached, err := r.docker.ContainerAttach(call, created.ID, container.AttachOptions{Stream: true, Stdout: true, Stderr: true})
	cancel()
	if err != nil {
		return r.finish(ctx, record, "ENVIRONMENT_ERROR", "attach_failed", nil, err)
	}
	defer attached.Close()
	logs := &tailLog{limit: input.Limits.LogBytes}
	drained := make(chan error, 1)
	go func() {
		_, err := stdcopy.StdCopy(channelWriter{logs, "stdout"}, channelWriter{logs, "stderr"}, attached.Reader)
		drained <- err
	}()
	// The owner always joins this goroutine, even if the daemon stops responding.
	join := func() error {
		select {
		case err := <-drained:
			return err
		case <-time.After(5 * time.Second):
			attached.Close()
			return <-drained
		}
	}
	if !time.Now().Before(input.NotAfter) {
		attached.Close()
		_ = join()
		return r.finish(ctx, record, "INTERRUPTED", "authorization_expired_before_start", logs, nil)
	}
	intent, intentErr = r.intent(ctx, input.ExecutionID)
	if intentErr != nil || intent != "" {
		attached.Close()
		_ = join()
		return r.finish(ctx, record, "STOPPED", "user_stop", logs, intentErr)
	}
	call, cancel = context.WithTimeout(ctx, 5*time.Second)
	err = r.docker.ContainerStart(call, created.ID, container.StartOptions{})
	cancel()
	if err != nil {
		attached.Close()
		_ = join()
		return r.fenceStart(ctx, record, logs, err)
	}
	record.State = "RUNNING"
	if err = r.persist(record); err != nil {
		attached.Close()
		_ = join()
		return r.finish(ctx, record, "INTERRUPTED", "running_state_persistence_failed", logs, err)
	}
	return r.observeExecution(ctx, record, logs, join)
}

func (r *Runner) observeExecution(ctx context.Context, record Result, logs *tailLog, join func() error) (Result, error) {
	// Restart never grants a fresh runtime budget.
	runtime, cancelRuntime := context.WithDeadline(ctx, record.StartedAt.Add(time.Duration(record.Input.Limits.TimeoutSeconds)*time.Second))
	state, reason := r.observe(runtime, &record, logs)
	if runtime.Err() != nil {
		state, reason = "TIMED_OUT", "platform_deadline"
	}
	if ctx.Err() != nil {
		state, reason = "INTERRUPTED", "worker_cancelled"
	}
	cancelRuntime()
	// Evidence is durable BEFORE killing the holding shell/unmounting tmpfs.
	if state == "TIMED_OUT" {
		record.Intent = "TIMED_OUT"
	}
	record.Reason = reason // Durable timeout/interruption intent precedes termination.
	record.Logs, record.DroppedBytes = logs.snapshot()
	evidenceErr := r.persist(record)
	stopErr := r.terminate(ctx, &record)
	drainErr := join()
	record.LogsIncomplete = record.LogsIncomplete || drainErr != nil
	record.Logs, record.DroppedBytes = logs.snapshot()
	// Publish the terminal state only once, after quiescence and log draining.
	// A broken output stream is visible but never overrides structured evidence.
	return r.complete(ctx, record, state, reason, errors.Join(evidenceErr, stopErr))
}

func (r *Runner) containerConfig(input Input) (*container.Config, *container.HostConfig) {
	limits := input.Limits
	pids := limits.PIDs
	config := &container.Config{Image: input.Image, User: "10001:10001", WorkingDir: "/work", Entrypoint: []string{"/bin/sh"}, Cmd: []string{"-c", command},
		Env:             []string{"HOME=/tmp", "MAVEN_OPTS=-Xmx256m -XX:ActiveProcessorCount=1 -Djava.io.tmpdir=/tmp"},
		Labels:          map[string]string{"codecraft.execution.owner": r.owner, "codecraft.execution.volume": r.volume, "codecraft.execution.id": input.ExecutionID, "codecraft.workspace.id": input.WorkspaceID},
		NetworkDisabled: true, AttachStdout: true, AttachStderr: true}
	host := &container.HostConfig{NetworkMode: "none", ReadonlyRootfs: true, CapDrop: []string{"ALL"}, SecurityOpt: []string{"no-new-privileges:true"},
		RestartPolicy: container.RestartPolicy{Name: container.RestartPolicyDisabled},
		Resources:     container.Resources{CPUPeriod: 100000, CPUQuota: limits.CPUs * 100000, Memory: limits.MemoryMiB * 1024 * 1024, MemorySwap: limits.MemoryMiB * 1024 * 1024, PidsLimit: &pids, Ulimits: []*units.Ulimit{{Name: "core", Soft: 0, Hard: 0}}},
		ShmSize:       limits.ShmMiB * 1024 * 1024,
		Tmpfs:         map[string]string{"/work": fmt.Sprintf("rw,nosuid,nodev,noexec,size=%dm,uid=10001,gid=10001", limits.WorkMiB), "/tmp": fmt.Sprintf("rw,nosuid,nodev,noexec,size=%dm,uid=10001,gid=10001", limits.TmpMiB)},
		Mounts:        []mount.Mount{{Type: mount.TypeVolume, Source: r.volume, Target: "/input", ReadOnly: true, VolumeOptions: &mount.VolumeOptions{NoCopy: true, Subpath: r.inputPrefix + input.Subpath}}},
		LogConfig:     container.LogConfig{Type: "local", Config: map[string]string{"max-size": strconv.Itoa(limits.LogBytes), "max-file": "1", "compress": "false"}}}
	return config, host
}

func (r *Runner) observe(ctx context.Context, record *Result, logs *tailLog) (string, string) {
	ticker := time.NewTicker(500 * time.Millisecond)
	defer ticker.Stop()
	for {
		if err := ctx.Err(); err != nil {
			if errors.Is(err, context.DeadlineExceeded) {
				return "TIMED_OUT", "platform_deadline"
			}
			return "INTERRUPTED", "worker_cancelled"
		}
		intent, err := r.intent(ctx, record.Input.ExecutionID)
		if err != nil {
			return "INTERRUPTED", "intent_unavailable"
		}
		if intent != "" {
			return intent, "user_stop"
		}
		record.Logs, record.DroppedBytes = logs.snapshot()
		if err := r.persist(*record); err != nil {
			return "INTERRUPTED", "log_checkpoint_failed"
		}
		data, err := r.readFile(ctx, record.ContainerID, "/work/result-status", 16)
		if err == nil {
			exit, parseErr := strconv.Atoi(string(data))
			if parseErr != nil || exit < 0 || exit > 255 {
				return "ENVIRONMENT_ERROR", "invalid_controlled_exit"
			}
			record.ExitCode = &exit
			if exit == 1 {
				compilation, readErr := r.readFile(ctx, record.ContainerID, "/work/compilation-result", compilationReportLimit)
				if readErr == nil && validCompilationReport(compilation) {
					record.CompilationReport = compilation
					return "FAILED", "compilation_failed"
				}
				if readErr == nil || !errdefs.IsNotFound(readErr) {
					return "ENVIRONMENT_ERROR", "compilation_evidence_invalid"
				}
			}
			report, err := r.readFile(ctx, record.ContainerID, "/work/target/surefire-reports/TEST-"+record.Input.Suite+".xml", reportLimit)
			if err != nil {
				return "ENVIRONMENT_ERROR", "authority_report_unavailable"
			}
			record.Report = report
			state, err := classifyReport(report, record.Input, exit)
			if err != nil {
				return "ENVIRONMENT_ERROR", "authority_report_invalid"
			}
			return state, "authority_report"
		}
		if !errdefs.IsNotFound(err) {
			if ctx.Err() != nil {
				continue
			}
			return "ENVIRONMENT_ERROR", "evidence_collection_failed"
		}
		call, cancel := context.WithTimeout(ctx, 5*time.Second)
		inspected, inspectErr := r.docker.ContainerInspect(call, record.ContainerID)
		cancel()
		if inspectErr != nil {
			if ctx.Err() != nil {
				continue
			}
			return "ENVIRONMENT_ERROR", "execution_observation_failed"
		}
		if inspected.State == nil || !inspected.State.Running {
			return "ENVIRONMENT_ERROR", "execution_exited_without_evidence"
		}
		select {
		case <-ctx.Done():
		case <-ticker.C:
		}
	}
}

// Docker's archive API cannot read tmpfs. A fixed exec collector reads one
// bounded regular file while the holding shell keeps the mount alive. Arguments
// are positional, never interpolated into shell source; no caller supplies commands.
func (r *Runner) readFile(ctx context.Context, id, name string, limit int) ([]byte, error) {
	call, cancel := context.WithTimeout(ctx, 5*time.Second)
	defer cancel()
	created, err := r.docker.ContainerExecCreate(call, id, container.ExecOptions{User: "10001:10001", AttachStdout: true, AttachStderr: true,
		Cmd: []string{"/bin/sh", "-c", `[ -e "$1" ] || exit 44
[ -f "$1" ] && [ ! -L "$1" ] || exit 45
exec head -c "$2" "$1"`, "collect", name, strconv.Itoa(limit + 1)}})
	if err != nil {
		return nil, err
	}
	attached, err := r.docker.ContainerExecAttach(call, created.ID, container.ExecAttachOptions{})
	if err != nil {
		return nil, err
	}
	defer attached.Close()
	stop := context.AfterFunc(call, attached.Close)
	defer stop()
	output := &boundedEvidence{limit: limit + 1}
	_, err = stdcopy.StdCopy(output, io.Discard, attached.Reader)
	if err != nil {
		return nil, err
	}
	for {
		inspected, err := r.docker.ContainerExecInspect(call, created.ID)
		if err != nil {
			return nil, err
		}
		if !inspected.Running {
			if inspected.ExitCode == 44 {
				return nil, errdefs.NotFound(errors.New("evidence not ready"))
			}
			if inspected.ExitCode != 0 || output.Len() > limit {
				return nil, errors.New("evidence unavailable or excessive")
			}
			return output.Bytes(), nil
		}
		select {
		case <-call.Done():
			return nil, call.Err()
		case <-time.After(10 * time.Millisecond):
		}
	}
}

type boundedEvidence struct {
	bytes.Buffer
	limit int
}

func (b *boundedEvidence) Write(data []byte) (int, error) {
	if len(data) > b.limit-b.Len() {
		return 0, errors.New("evidence exceeds collection bound")
	}
	return b.Buffer.Write(data)
}

// An ambiguous Start can still arrive after an inspect of state=created. Removing
// the exact, previously registered container fences that late request. A snapshot
// of Running=false alone must never release execution capacity in this branch.
func (r *Runner) fenceStart(ctx context.Context, record Result, logs *tailLog, cause error) (Result, error) {
	record.State = "INTERRUPTED"
	record.Reason = "container_start_unconfirmed"
	record.Quiescent = false
	record.Cleanup = "PENDING"
	record.LogsIncomplete = true
	record.Logs, record.DroppedBytes = logs.snapshot()
	now := time.Now().UTC()
	record.FinishedAt = &now
	intentErr := r.persist(record)
	cleanup, cancel := context.WithTimeout(context.WithoutCancel(ctx), 15*time.Second)
	defer cancel()
	inspected, err := r.docker.ContainerInspect(cleanup, record.ContainerID)
	if err == nil {
		if !r.owns(record, inspected) {
			err = errors.New("container ownership mismatch")
		} else {
			err = r.docker.ContainerRemove(cleanup, record.ContainerID, container.RemoveOptions{Force: true})
		}
	}
	if err == nil || errdefs.IsNotFound(err) {
		record.Quiescent = true
		record.Cleanup = "SUCCEEDED"
		err = nil
	} else {
		record.Cleanup = "FAILED"
	}
	// Even when the intent write fails, fence the known container: the original
	// durable registration already owns its ID, and no reliable test result exists.
	// Persistence failure keeps the runner blocked instead of claiming recovery.
	return record, errors.Join(cause, intentErr, err, r.persist(record))
}

func (r *Runner) owns(record Result, inspected container.InspectResponse) bool {
	return inspected.Config != nil && inspected.Config.Labels["codecraft.execution.owner"] == r.owner && inspected.Config.Labels["codecraft.execution.id"] == record.Input.ExecutionID && inspected.Image == record.Input.Image
}

func (r *Runner) finish(ctx context.Context, record Result, state, reason string, logs *tailLog, cause error) (Result, error) {
	stopErr := r.terminate(ctx, &record)
	if logs != nil {
		record.Logs, record.DroppedBytes = logs.snapshot()
	}
	return r.complete(ctx, record, state, reason, errors.Join(cause, stopErr))
}

// Cleanup deliberately has a bounded lifetime independent of cancelled worker
// authority: cancellation must terminate the real execution, not abandon it.
func (r *Runner) terminate(ctx context.Context, record *Result) error {
	cleanup, cancel := context.WithTimeout(context.WithoutCancel(ctx), 15*time.Second)
	defer cancel()
	inspected, err := r.docker.ContainerInspect(cleanup, record.ContainerID)
	if err != nil {
		return err
	}
	if !r.owns(*record, inspected) {
		return errors.New("container ownership mismatch")
	}
	if inspected.State != nil && inspected.State.Running {
		if err = r.docker.ContainerKill(cleanup, record.ContainerID, "SIGKILL"); err != nil {
			return err
		}
		inspected, err = r.docker.ContainerInspect(cleanup, record.ContainerID)
		if err != nil {
			return err
		}
	}
	if inspected.State == nil || inspected.State.Running {
		return errors.New("execution quiescence unconfirmed")
	}
	record.Quiescent = true
	record.OOMKilled = inspected.State.OOMKilled
	code := inspected.State.ExitCode
	record.ContainerExitCode = &code
	return nil
}

func (r *Runner) complete(ctx context.Context, record Result, state, reason string, cause error) (Result, error) {
	record.State = state
	record.Reason = reason
	record.Cleanup = "FAILED"
	if record.OOMKilled {
		record.State = "ENVIRONMENT_ERROR"
		record.Reason = "resource_limit_oom"
	}
	if !record.Quiescent {
		record.State = "INTERRUPTED"
		record.Reason = "execution_quiescence_unconfirmed"
	}
	now := time.Now().UTC()
	record.FinishedAt = &now
	persistErr := r.persist(record)
	if persistErr == nil {
		// Publication may have been ordered after a competing stop intent.
		record, persistErr = r.Query(context.WithoutCancel(ctx), record.Input.ExecutionID)
	}
	// Never remove the only evidence when durable result storage has failed.
	if persistErr == nil && cause == nil && record.Quiescent {
		cleanup, cancel := context.WithTimeout(context.WithoutCancel(ctx), 5*time.Second)
		cause = r.docker.ContainerRemove(cleanup, record.ContainerID, container.RemoveOptions{})
		cancel()
		if cause == nil {
			record.Cleanup = "SUCCEEDED"
			persistErr = r.persist(record)
		}
	}
	return record, errors.Join(cause, persistErr)
}
