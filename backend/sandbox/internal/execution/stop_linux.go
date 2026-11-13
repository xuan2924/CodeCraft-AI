package execution

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"os"
	"path/filepath"
	"syscall"
)

// ErrCancelled rejects a delayed start whose identity has a durable cancellation.
var ErrCancelled = errors.New("execution cancelled before registration")

// TaskIdentity binds cancellation to the complete non-reusable task identity.
type TaskIdentity struct {
	ExerciseID  string `json:"exerciseId"`
	TaskID      string `json:"taskId"`
	ExecutionID string `json:"executionId"`
	WorkspaceID string `json:"workspaceId"`
}

func (i TaskIdentity) matches(input Input) bool {
	return i.ExerciseID == input.ExerciseID && i.TaskID == input.TaskID && i.ExecutionID == input.ExecutionID && i.WorkspaceID == input.WorkspaceID
}

// InputCancelled also fences a delayed standalone copy-preparation RPC. No task
// or result body is disclosed; workspace ownership must match the saved identity.
func (r *Runner) InputCancelled(ctx context.Context, workspace, executionID string) (bool, error) {
	if err := ctx.Err(); err != nil {
		return false, err
	}
	if !uuid.MatchString(workspace) || !uuid.MatchString(executionID) {
		return false, ErrConflict
	}
	identity, err := r.cancellation(executionID)
	if errors.Is(err, os.ErrNotExist) {
		return false, nil
	}
	if err != nil {
		return false, err
	}
	if identity.WorkspaceID != workspace {
		return false, ErrConflict
	}
	return true, nil
}

// Stop durably orders intent with natural completion. A missing registration is
// tombstoned under the same admission lock, so a delayed Start cannot create work.
// nil result means cancellation preceded registration, not an unknown outcome.
func (r *Runner) Stop(ctx context.Context, identity TaskIdentity) (*Result, error) {
	if !uuid.MatchString(identity.ExerciseID) || !uuid.MatchString(identity.TaskID) || !uuid.MatchString(identity.ExecutionID) || !uuid.MatchString(identity.WorkspaceID) {
		return nil, ErrConflict
	}
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.closed {
		return nil, ErrBusy
	}
	if previous, exists := r.tasks[identity.TaskID]; exists && previous != identity.ExecutionID {
		return nil, ErrConflict
	}
	r.stateMu.Lock()
	defer r.stateMu.Unlock()
	record, err := r.Query(ctx, identity.ExecutionID)
	if errors.Is(err, os.ErrNotExist) {
		previous, readErr := r.cancellation(identity.ExecutionID)
		if readErr == nil {
			if previous != identity {
				return nil, ErrConflict
			}
			return nil, nil
		}
		if !errors.Is(readErr, os.ErrNotExist) {
			return nil, readErr
		}
		data, err := json.Marshal(identity)
		if err != nil {
			return nil, err
		}
		if err = r.writeRegistry(identity.ExecutionID+".cancel", data); err != nil {
			return nil, err
		}
		r.tasks[identity.TaskID] = identity.ExecutionID
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	if !identity.matches(record.Input) {
		return nil, ErrConflict
	}
	if record.FinishedAt != nil {
		return &record, nil
	}
	if record.Intent == "" {
		record.Intent = "STOPPED"
	}
	if err = r.persistOrdered(record); err != nil {
		return nil, err
	}
	record, err = r.Query(ctx, identity.ExecutionID)
	return &record, err
}

// QueryStop reads a cancellation receipt without granting new stop authority.
// This remains usable after the original page gate or authorization expires.
func (r *Runner) QueryStop(ctx context.Context, identity TaskIdentity) (*Result, bool, error) {
	if !uuid.MatchString(identity.ExerciseID) || !uuid.MatchString(identity.TaskID) || !uuid.MatchString(identity.ExecutionID) || !uuid.MatchString(identity.WorkspaceID) {
		return nil, false, ErrConflict
	}
	record, err := r.Query(ctx, identity.ExecutionID)
	if err == nil {
		if !identity.matches(record.Input) {
			return nil, false, ErrConflict
		}
		return &record, false, nil
	}
	if !errors.Is(err, os.ErrNotExist) {
		return nil, false, err
	}
	cancellation, err := r.cancellation(identity.ExecutionID)
	if errors.Is(err, os.ErrNotExist) {
		return nil, false, nil
	}
	if err != nil {
		return nil, false, err
	}
	if cancellation != identity {
		return nil, false, ErrConflict
	}
	return nil, true, nil
}

func (r *Runner) cancellation(id string) (TaskIdentity, error) {
	file, err := os.OpenFile(filepath.Join(r.root, id+".cancel"), os.O_RDONLY|syscall.O_NOFOLLOW|syscall.O_NONBLOCK, 0)
	if err != nil {
		return TaskIdentity{}, err
	}
	defer file.Close()
	info, err := file.Stat()
	if err != nil {
		return TaskIdentity{}, err
	}
	stat, ok := info.Sys().(*syscall.Stat_t)
	if !info.Mode().IsRegular() || !ok || stat.Nlink != 1 || info.Size() > 1024 {
		return TaskIdentity{}, errors.New("unsafe cancellation record")
	}
	decoder := json.NewDecoder(io.LimitReader(file, 1025))
	decoder.DisallowUnknownFields()
	var identity TaskIdentity
	if err = decoder.Decode(&identity); err != nil {
		return identity, err
	}
	var extra any
	if err = decoder.Decode(&extra); !errors.Is(err, io.EOF) {
		return identity, errors.New("invalid cancellation record")
	}
	if identity.ExecutionID != id || !uuid.MatchString(identity.ExecutionID) || !uuid.MatchString(identity.ExerciseID) || !uuid.MatchString(identity.TaskID) || !uuid.MatchString(identity.WorkspaceID) {
		return identity, errors.New("invalid cancellation identity")
	}
	return identity, nil
}

func (r *Runner) intent(ctx context.Context, id string) (string, error) {
	record, err := r.Query(ctx, id)
	return record.Intent, err
}
