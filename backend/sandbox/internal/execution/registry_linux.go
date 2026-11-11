package execution

import (
	"context"
	"crypto/rand"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"sync"
	"syscall"
	"time"

	"github.com/docker/docker/api/types/container"
	"github.com/docker/docker/api/types/filters"
	"github.com/docker/docker/api/types/versions"
	"github.com/docker/docker/client"
)

var uuid = regexp.MustCompile(`^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$`)
var sha256 = regexp.MustCompile(`^[0-9a-f]{64}$`)
var testName = regexp.MustCompile(`^[A-Za-z_][A-Za-z_0-9.]*$`)

// ErrBusy means an execution or an unresolved durable registration holds capacity.
var ErrBusy = errors.New("execution capacity unavailable; reconciliation required")

// ErrConflict prevents changing the immutable input associated with an execution.
var ErrConflict = errors.New("execution input conflict")

// Runner exclusively owns one private registry and one Docker namespace. Pending
// registrations block new work after restart; recovery must not blindly rerun them.
type Runner struct {
	docker      *client.Client
	root        string
	volume      string
	inputPrefix string
	owner       string
	epoch       string
	lock        *os.File
	mu          sync.Mutex
	stateMu     sync.Mutex // Orders durable intent and result publication.
	blocked     bool
	running     bool
	closed      bool
	done        chan struct{}
	tasks       map[string]string
	now         func() time.Time // Authorization only; process timeout timers remain real.
}

// New acquires process ownership and checks inventory before admitting work.
// Missing/corrupt registrations and unknown containers fail closed. It never
// resumes a container or deletes execution evidence on construction.
func New(ctx context.Context, docker *client.Client, root, volume, owner string) (*Runner, error) {
	if docker == nil || !filepath.IsAbs(root) || !uuid.MatchString(owner) || volume == "" || strings.ContainsAny(volume, "/\\\x00") {
		return nil, errors.New("invalid execution deployment configuration")
	}
	if err := os.MkdirAll(root, 0700); err != nil {
		return nil, err
	}
	lock, err := os.OpenFile(filepath.Join(root, ".lock"), os.O_CREATE|os.O_RDWR, 0600)
	if err != nil {
		return nil, err
	}
	if err = syscall.Flock(int(lock.Fd()), syscall.LOCK_EX|syscall.LOCK_NB); err != nil {
		lock.Close()
		return nil, fmt.Errorf("execution registry already owned: %w", err)
	}
	r := &Runner{docker: docker, root: root, volume: volume, owner: owner, lock: lock, tasks: map[string]string{}, epoch: rand.Text(), now: time.Now}
	// Each process durably fences all previous dispatch generations before it
	// inspects/repairs evidence or permits RPC admission.
	if err = r.writeRegistry(".epoch", []byte(r.epoch)); err != nil {
		lock.Close()
		return nil, err
	}
	if err = r.recoverOrphans(ctx); err != nil {
		lock.Close()
		return nil, err
	}
	if err = r.repairResults(ctx); err != nil {
		lock.Close()
		return nil, err
	}
	if err = r.inventory(ctx); err != nil {
		lock.Close()
		return nil, err
	}
	return r, nil
}

// NewWorkspaceRunner uses the production data volume layout, whose workspaces
// are beneath /workspaces while the execution registry is a sibling directory.
func NewWorkspaceRunner(ctx context.Context, docker *client.Client, root, volume, owner string) (*Runner, error) {
	runner, err := New(ctx, docker, root, volume, owner)
	if err != nil {
		return nil, err
	}
	runner.inputPrefix = "workspaces/"
	return runner, nil
}

// Close releases registry ownership only when no Run is still executing. The
// service owner must cancel its workers and join them before closing the runner.
func (r *Runner) Close() error {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.running {
		return ErrBusy
	}
	if r.closed {
		return nil
	}
	r.closed = true
	return r.lock.Close()
}

// Wait joins the one owned asynchronous execution before releasing registry storage.
func (r *Runner) Wait() {
	r.mu.Lock()
	done := r.done
	r.mu.Unlock()
	if done != nil {
		<-done
	}
}

func (r *Runner) inventory(ctx context.Context) error {
	entries, err := os.ReadDir(r.root)
	if err != nil {
		return err
	}
	// Inventory needs identities only; do not retain all historical log/report bodies.
	records := map[string]string{}
	for _, entry := range entries {
		if entry.Name() == ".lock" || entry.Name() == ".epoch" || strings.HasPrefix(entry.Name(), ".stage-") || strings.HasPrefix(entry.Name(), ".corrupt-") || strings.HasSuffix(entry.Name(), ".meta") {
			continue
		}
		if strings.HasSuffix(entry.Name(), ".cancel") {
			identity, err := r.cancellation(strings.TrimSuffix(entry.Name(), ".cancel"))
			if err != nil {
				return err
			}
			if previous, exists := r.tasks[identity.TaskID]; exists && previous != identity.ExecutionID {
				return ErrConflict
			}
			r.tasks[identity.TaskID] = identity.ExecutionID
			continue
		}
		if !strings.HasSuffix(entry.Name(), ".json") {
			return errors.New("unknown registry entry")
		}
		id := strings.TrimSuffix(entry.Name(), ".json")
		record, err := r.Query(ctx, id)
		if err != nil {
			return err
		}
		records[id] = record.ContainerID
		if task := record.Input.TaskID; task != "" {
			if previous, exists := r.tasks[task]; exists && previous != id {
				return errors.New("task has conflicting execution identities")
			}
			r.tasks[task] = id
		}
		if !record.Quiescent || record.Cleanup != "SUCCEEDED" || (record.Input.TaskID != "" && record.CopyCleanup != "SUCCEEDED") {
			r.blocked = true
		}
	}
	call, cancel := context.WithTimeout(ctx, 5*time.Second)
	defer cancel()
	info, err := r.docker.Info(call)
	if err != nil {
		return fmt.Errorf("execution security configuration unavailable: %w", err)
	}
	if versions.LessThan(r.docker.ClientVersion(), "1.45") {
		return errors.New("execution requires volume subpath support (Docker API 1.45+)")
	}
	seccomp := false
	for _, option := range info.SecurityOptions {
		if strings.Contains(option, "name=seccomp") && strings.Contains(option, "profile=builtin") {
			seccomp = true
		}
	}
	if !seccomp {
		return errors.New("execution requires Docker builtin seccomp")
	}
	containers, err := r.docker.ContainerList(call, container.ListOptions{All: true, Filters: filters.NewArgs(filters.Arg("label", "codecraft.execution.volume="+r.volume))})
	if err != nil {
		return fmt.Errorf("execution inventory unavailable: %w", err)
	}
	for _, found := range containers {
		containerID, ok := records[found.Labels["codecraft.execution.id"]]
		if !ok || found.Labels["codecraft.execution.owner"] != r.owner || containerID != found.ID {
			return errors.New("unregistered execution requires reconciliation")
		}
		// A retained container, even if marked quiescent in its record, requires
		// reconciliation before admitting another execution after restart.
		r.blocked = true
	}
	return nil
}

// Query reads persisted state without starting work. os.ErrNotExist is distinct
// from corrupt/unreadable evidence; callers may not infer safe replay from absence.
func (r *Runner) Query(ctx context.Context, id string) (Result, error) {
	if err := ctx.Err(); err != nil {
		return Result{}, err
	}
	if !uuid.MatchString(id) {
		return Result{}, errors.New("invalid execution identity")
	}
	file, err := os.OpenFile(filepath.Join(r.root, id+".json"), os.O_RDONLY|syscall.O_NOFOLLOW|syscall.O_NONBLOCK, 0)
	if err != nil {
		return Result{}, err
	}
	defer file.Close()
	info, err := file.Stat()
	if err != nil {
		return Result{}, err
	}
	stat, ok := info.Sys().(*syscall.Stat_t)
	if !info.Mode().IsRegular() || !ok || stat.Nlink != 1 || info.Size() > 16*1024*1024 {
		return Result{}, errors.New("unsafe execution record")
	}
	decoder := json.NewDecoder(io.LimitReader(file, 16*1024*1024))
	decoder.DisallowUnknownFields()
	var result Result
	if err = decoder.Decode(&result); err != nil {
		return Result{}, fmt.Errorf("execution record corrupt: %w", err)
	}
	var trailing any
	if err = decoder.Decode(&trailing); !errors.Is(err, io.EOF) {
		return Result{}, errors.New("execution record trailing content")
	}
	if result.Input.ExecutionID != id || validateInput(result.Input) != nil || result.StartedAt.IsZero() {
		return Result{}, errors.New("invalid execution record")
	}
	switch result.State {
	case "STARTING", "RUNNING", "STOPPING":
		if result.Quiescent || result.FinishedAt != nil {
			return Result{}, errors.New("invalid active execution record")
		}
	case "PASSED", "FAILED", "TIMED_OUT", "STOPPED", "ENVIRONMENT_ERROR", "INTERRUPTED":
		if result.FinishedAt == nil {
			return Result{}, errors.New("missing terminal execution time")
		}
	default:
		return Result{}, errors.New("unknown execution state")
	}
	if result.Intent != "" && result.Intent != "STOPPED" && result.Intent != "TIMED_OUT" {
		return Result{}, errors.New("invalid execution intent")
	}
	if result.State == "STOPPING" && result.Intent == "" {
		return Result{}, errors.New("stopping without durable intent")
	}
	if result.CopyCleanup != "" && result.CopyCleanup != "PENDING" && result.CopyCleanup != "SUCCEEDED" && result.CopyCleanup != "FAILED" {
		return Result{}, errors.New("invalid input cleanup state")
	}
	if result.CopyCleanup == "SUCCEEDED" && (!result.Quiescent || result.FinishedAt == nil) {
		return Result{}, errors.New("input cleaned before confirmed completion")
	}
	if result.Input.TaskID != "" && result.CopyCleanup == "" {
		return Result{}, errors.New("task input cleanup status missing")
	}
	if result.Cleanup != "PENDING" && result.Cleanup != "SUCCEEDED" && result.Cleanup != "FAILED" {
		return Result{}, errors.New("invalid cleanup state")
	}
	if result.Cleanup == "SUCCEEDED" && !result.Quiescent {
		return Result{}, errors.New("cleanup without quiescence")
	}
	if result.ContainerID != "" && !sha256.MatchString(result.ContainerID) {
		return Result{}, errors.New("invalid recorded container identity")
	}
	if len(result.Events) > eventCountLimit {
		return Result{}, errors.New("event window exceeds limit")
	}
	eventBytes := 0
	for index, event := range result.Events {
		if event.Sequence == 0 || event.Sequence > result.Sequence || (index > 0 && event.Sequence != result.Events[index-1].Sequence+1) || len(event.Data) > eventChunkLimit {
			return Result{}, errors.New("invalid event sequence or size")
		}
		eventBytes += len(event.Data) + 256
	}
	if eventBytes > eventBytesLimit {
		return Result{}, errors.New("event window exceeds byte limit")
	}
	if len(result.Report) > reportLimit || len(result.CompilationReport) > compilationReportLimit || len(result.Logs) > 4096 {
		return Result{}, errors.New("recorded evidence exceeds bounds")
	}
	retained := 0
	for _, chunk := range result.Logs {
		if (chunk.Channel != "stdout" && chunk.Channel != "stderr") || len(chunk.Data) > 16*1024 {
			return Result{}, errors.New("invalid recorded log fragment")
		}
		retained += len(chunk.Data)
	}
	if retained > result.Input.Limits.LogBytes {
		return Result{}, errors.New("recorded logs exceed limit")
	}
	if result.State == "PASSED" || result.State == "FAILED" {
		if !result.Quiescent || result.ExitCode == nil {
			return Result{}, errors.New("grade without confirmed process outcome")
		}
		if result.Reason == "compilation_failed" {
			if result.State != "FAILED" || *result.ExitCode != 1 || len(result.Report) != 0 || !validCompilationReport(result.CompilationReport) {
				return Result{}, errors.New("recorded compilation evidence invalid")
			}
			return result, nil
		}
		state, err := classifyReport(result.Report, result.Input, *result.ExitCode)
		if err != nil || state != result.State {
			return Result{}, errors.New("recorded authority evidence invalid")
		}
	}
	return result, nil
}

func (r *Runner) persist(result Result) error {
	r.stateMu.Lock()
	defer r.stateMu.Unlock()
	return r.persistOrdered(result)
}

// Caller owns stateMu. An active worker cannot overwrite a previously durable
// stop/timeout intent. Terminal publication is the natural-completion commit point.
func (r *Runner) persistOrdered(result Result) error {
	previous, err := r.Query(context.TODO(), result.Input.ExecutionID)
	if err != nil && !errors.Is(err, os.ErrNotExist) {
		return err
	}
	if err == nil {
		result.CounterUncertain = previous.CounterUncertain
		if previous.Intent != "" {
			result.Intent = previous.Intent
		}
	}
	if result.Intent != "" {
		if result.FinishedAt == nil {
			result.State = "STOPPING"
		} else if result.Quiescent {
			result.State = result.Intent
			result.Reason = map[string]string{"STOPPED": "user_stop", "TIMED_OUT": "platform_deadline"}[result.Intent]
			result.Report, result.CompilationReport, result.ExitCode = nil, nil, nil
		}
	}
	if err == nil && previous.FinishedAt != nil {
		// Terminal meaning is immutable; only quiescence/cleanup can advance.
		result.State, result.Reason, result.FinishedAt = previous.State, previous.Reason, previous.FinishedAt
		result.Report, result.CompilationReport, result.ExitCode = previous.Report, previous.CompilationReport, previous.ExitCode
		if previous.Quiescent {
			result.Quiescent = true
		}
	}
	if previous.Version == ^uint64(0) {
		return errors.New("execution version exhausted")
	}
	result.Version = previous.Version + 1
	appendEvents(&result, previous)
	data, err := json.Marshal(result)
	if err != nil {
		return err
	}
	if err = r.writeRecoveryReceipt(result); err != nil {
		return err
	}
	return r.writeRegistry(result.Input.ExecutionID+".json", data)
}

func (r *Runner) writeRegistry(destination string, data []byte) (err error) {
	file, err := os.CreateTemp(r.root, ".stage-")
	if err != nil {
		return err
	}
	name := file.Name()
	defer func() {
		if name != "" {
			err = errors.Join(err, os.Remove(name))
		}
	}()
	if _, err = file.Write(data); err != nil {
		file.Close()
		return err
	}
	syncErr := file.Sync()
	closeErr := file.Close()
	if err = errors.Join(syncErr, closeErr); err != nil {
		return err
	}
	if err = os.Rename(name, filepath.Join(r.root, destination)); err != nil {
		return err
	}
	// A successful rename makes the temporary pathname absent; cleanup must not
	// turn that normal condition into a false failed persistence result.
	name = ""
	directory, err := os.Open(r.root)
	if err != nil {
		return err
	}
	err = directory.Sync()
	return errors.Join(err, directory.Close())
}

func validateInput(input Input) error {
	if input.TaskID != "" && (!uuid.MatchString(input.TaskID) || !uuid.MatchString(input.ExerciseID) || input.ProblemID == "" || input.ProblemVersion == "") {
		return errors.New("invalid task ownership")
	}
	limits := input.Limits
	if !uuid.MatchString(input.ExecutionID) || !uuid.MatchString(input.WorkspaceID) || !strings.HasPrefix(input.Image, "sha256:") || !sha256.MatchString(strings.TrimPrefix(input.Image, "sha256:")) || !sha256.MatchString(input.PackageSHA256) || input.Platform != "linux/amd64" || input.Target != "base" || input.NotAfter.IsZero() {
		return errors.New("invalid fixed execution input")
	}
	if input.Subpath != input.WorkspaceID+"/copy-"+input.ExecutionID+"/tree" {
		return errors.New("input must identify exactly one stable copy")
	}
	if limits.CPUs < 1 || limits.CPUs > 16 || limits.MemoryMiB < 64 || limits.MemoryMiB > 8192 || limits.PIDs < 16 || limits.PIDs > 1024 || limits.WorkMiB < 1 || limits.WorkMiB > 1024 || limits.TmpMiB < 1 || limits.TmpMiB > 1024 || limits.ShmMiB < 1 || limits.ShmMiB > 128 || limits.TimeoutSeconds < 1 || limits.TimeoutSeconds > 600 || limits.LogBytes < 1 || limits.LogBytes > 1048576 {
		return errors.New("invalid execution resource limits")
	}
	if len(input.Suite) > 255 || !testName.MatchString(input.Suite) || len(input.Tests) == 0 || len(input.Tests) > 200 {
		return errors.New("invalid authority test contract")
	}
	seen := map[string]bool{}
	for _, name := range input.Tests {
		if len(name) > 255 || !testName.MatchString(name) || seen[name] {
			return errors.New("invalid or duplicate authority test")
		}
		seen[name] = true
	}
	return nil
}
