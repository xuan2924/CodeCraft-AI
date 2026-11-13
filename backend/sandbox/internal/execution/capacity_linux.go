package execution

import (
	"context"
	"errors"
	"io"
	"math"
	"os"
	"strings"
	"syscall"
)

// StorageBytes attributes every persisted primary, recovery receipt, cancellation
// and owned failure stage. Shared registry locks/boot metadata are infrastructure,
// not a second per-exercise quota. Active growth remains covered by Java's credit.
func (r *Runner) StorageBytes(ctx context.Context, identities []TaskIdentity) (uint64, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	owned := map[string]TaskIdentity{}
	for _, identity := range identities {
		if err := ctx.Err(); err != nil {
			return 0, err
		}
		if !uuid.MatchString(identity.ExerciseID) || !uuid.MatchString(identity.TaskID) || !uuid.MatchString(identity.ExecutionID) || !uuid.MatchString(identity.WorkspaceID) {
			return 0, ErrConflict
		}
		if previous, exists := owned[identity.ExecutionID]; exists && previous != identity {
			return 0, ErrConflict
		}
		owned[identity.ExecutionID] = identity
		record, err := r.Query(ctx, identity.ExecutionID)
		if err == nil && !identity.matches(record.Input) {
			return 0, ErrConflict
		}
		if err != nil && !errors.Is(err, os.ErrNotExist) {
			return 0, err
		}
		recovery, err := r.readRecoveryReceipt(identity.ExecutionID)
		if err == nil && !identity.matches(recovery.Input) {
			return 0, ErrConflict
		}
		if err != nil && !errors.Is(err, os.ErrNotExist) {
			return 0, err
		}
		cancellation, err := r.cancellation(identity.ExecutionID)
		if err == nil && cancellation != identity {
			return 0, ErrConflict
		}
		if err != nil && !errors.Is(err, os.ErrNotExist) {
			return 0, err
		}
	}
	directory, err := os.Open(r.root)
	if err != nil {
		return 0, err
	}
	defer directory.Close()
	var total uint64
	for {
		if err = ctx.Err(); err != nil {
			return 0, err
		}
		entries, readErr := directory.ReadDir(128)
		for _, entry := range entries {
			id := storedExecutionID(entry.Name())
			if _, exists := owned[id]; !exists {
				continue
			}
			info, err := entry.Info()
			if errors.Is(err, os.ErrNotExist) {
				continue
			} // Concurrent removal can only free bytes.
			if err != nil {
				return 0, err
			}
			stat, ok := info.Sys().(*syscall.Stat_t)
			if !ok || !info.Mode().IsRegular() || stat.Nlink != 1 || info.Size() < 0 {
				return 0, errors.New("unsafe owned execution storage")
			}
			if uint64(info.Size()) > math.MaxInt64-total {
				return 0, errors.New("execution storage byte counter exhausted")
			}
			total += uint64(info.Size())
		}
		if errors.Is(readErr, io.EOF) {
			break
		}
		if readErr != nil {
			return 0, readErr
		}
	}
	return total, nil
}

func storedExecutionID(name string) string {
	for _, suffix := range []string{".json", ".meta", ".cancel"} {
		if strings.HasSuffix(name, suffix) {
			return strings.TrimSuffix(name, suffix)
		}
	}
	for _, prefix := range []string{".stage-", ".corrupt-meta-", ".corrupt-"} {
		if strings.HasPrefix(name, prefix) && len(name) >= len(prefix)+36 {
			id := name[len(prefix) : len(prefix)+36]
			if uuid.MatchString(id) {
				return id
			}
		}
	}
	return ""
}
