package execution

import (
	"context"
	"encoding/json"
	"os"
	"strings"
)

// Called only during construction while the process ownership lock is held and
// before workers exist. Stages were never published; primary/recovery records
// have already been validated and are not removed here.
func (r *Runner) cleanupStages(ctx context.Context) error {
	root, err := os.OpenRoot(r.root)
	if err != nil {
		return err
	}
	defer root.Close()
	entries, err := os.ReadDir(r.root)
	if err != nil {
		return err
	}
	for _, entry := range entries {
		if err = ctx.Err(); err != nil {
			return err
		}
		if strings.HasPrefix(entry.Name(), ".stage-") {
			if err = root.Remove(entry.Name()); err != nil {
				return err
			}
		}
	}
	return nil
}

// PruneExecution discards one completed ordinary record, never a final attempt
// or unresolved execution. Its complete identity is retained as an anti-replay fence.
func (r *Runner) PruneExecution(ctx context.Context, identity TaskIdentity) error {
	if !uuid.MatchString(identity.ExerciseID) || !uuid.MatchString(identity.TaskID) || !uuid.MatchString(identity.ExecutionID) || !uuid.MatchString(identity.WorkspaceID) {
		return ErrConflict
	}
	return r.deleteEvidence(ctx, identity.WorkspaceID, &identity)
}

// DeleteWorkspace removes evidence only after all registered executions are
// physically quiescent and cleaned. Cancellation identities precede removal so
// an interrupted purge cannot admit an old execution again.
func (r *Runner) DeleteWorkspace(ctx context.Context, workspace string) error {
	return r.deleteEvidence(ctx, workspace, nil)
}

func (r *Runner) deleteEvidence(ctx context.Context, workspace string, exact *TaskIdentity) error {
	if !uuid.MatchString(workspace) {
		return ErrConflict
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.closed {
		return ErrBusy
	}
	for id, active := range r.active {
		if (exact == nil && active.WorkspaceID == workspace) || (exact != nil && id == exact.ExecutionID) {
			return ErrBusy
		}
	}
	r.stateMu.Lock()
	defer r.stateMu.Unlock()
	if err := r.recoverOrphans(ctx); err != nil {
		r.blocked = true
		return err
	}
	if err := r.repairResults(ctx); err != nil {
		r.blocked = true
		return err
	}
	identities := map[string]TaskIdentity{}
	after := ""
	for {
		entries, next, err := r.List(ctx, after, 100)
		if err != nil {
			return err
		}
		for _, entry := range entries {
			if exact != nil && entry.Identity.ExecutionID == exact.ExecutionID && entry.Identity != *exact {
				return ErrConflict
			}
			if entry.Identity.WorkspaceID != workspace || (exact != nil && entry.Identity.ExecutionID != exact.ExecutionID) {
				continue
			}
			if exact != nil {
				if entry.Identity != *exact {
					return ErrConflict
				}
				record, err := r.Query(ctx, exact.ExecutionID)
				if err != nil {
					return err
				}
				if record.Input.Purpose == "FINAL_VERIFICATION" || record.Input.FinalizationID != "" {
					return ErrConflict
				}
			}
			if !entry.Quiescent || entry.Cleanup != "SUCCEEDED" || entry.CopyCleanup != "SUCCEEDED" {
				return ErrBusy
			}
			identities[entry.Identity.ExecutionID] = entry.Identity
		}
		if next == "" {
			break
		}
		after = next
	}
	entries, err := os.ReadDir(r.root)
	if err != nil {
		return err
	}
	// A previous attempt may have removed the primary and crashed before
	// finishing secondary cleanup. Its cancellation still proves ownership.
	for _, entry := range entries {
		if !strings.HasSuffix(entry.Name(), ".cancel") {
			continue
		}
		identity, err := r.cancellation(strings.TrimSuffix(entry.Name(), ".cancel"))
		if err != nil {
			return err
		}
		if exact != nil && identity.ExecutionID == exact.ExecutionID && identity != *exact {
			return ErrConflict
		}
		if identity.WorkspaceID == workspace && (exact == nil || identity.ExecutionID == exact.ExecutionID) {
			if exact != nil && identity != *exact {
				return ErrConflict
			}
			identities[identity.ExecutionID] = identity
		}
	}
	if exact != nil {
		identities[exact.ExecutionID] = *exact
	}
	root, err := os.OpenRoot(r.root)
	if err != nil {
		return err
	}
	defer root.Close()
	for id, identity := range identities {
		if err = ctx.Err(); err != nil {
			return err
		}
		data, err := json.Marshal(identity)
		if err != nil {
			return err
		}
		if err = r.writeRegistry(id+".cancel", data); err != nil {
			return err
		}
		for _, entry := range entries {
			if storedExecutionID(entry.Name()) == id && (strings.HasPrefix(entry.Name(), ".corrupt-") || strings.HasPrefix(entry.Name(), ".stage-")) {
				if err = root.Remove(entry.Name()); err != nil && !os.IsNotExist(err) {
					return err
				}
			}
		}
		// Remove recovery metadata before its primary; never regenerate evidence
		// as an INTERRUPTED outcome after a crash in the middle of deletion.
		for _, suffix := range []string{".meta", ".json"} {
			if err = root.Remove(id + suffix); err != nil && !os.IsNotExist(err) {
				return err
			}
		}
	}
	directory, err := os.Open(r.root)
	if err != nil {
		return err
	}
	syncErr := directory.Sync()
	closeErr := directory.Close()
	if syncErr != nil {
		return syncErr
	}
	return closeErr
}
