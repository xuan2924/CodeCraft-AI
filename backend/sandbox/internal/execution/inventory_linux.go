package execution

import (
	"context"
	"errors"
	"io"
	"os"
	"sort"
	"strings"
)

// InventoryEntry is deliberately small: log and report bodies are queried by
// their exact task identity, not retained while scanning historical executions.
type InventoryEntry struct {
	Identity    TaskIdentity
	State       string
	Quiescent   bool
	Cleanup     string
	CopyCleanup string
}

// HostEpoch is a durable opaque boot generation, never reused after restart.
func (r *Runner) HostEpoch() string { return r.epoch }

// List returns at most limit identities in lexical order. Directory scanning is
// batched and retains only limit+1 names; the cursor is bound to HostEpoch by RPC.
func (r *Runner) List(ctx context.Context, after string, limit int) ([]InventoryEntry, string, error) {
	if (after != "" && !uuid.MatchString(after)) || limit < 1 || limit > 100 {
		return nil, "", ErrConflict
	}
	directory, err := os.Open(r.root)
	if err != nil {
		return nil, "", err
	}
	defer directory.Close()
	ids := make([]string, 0, limit+1)
	for {
		if err = ctx.Err(); err != nil {
			return nil, "", err
		}
		entries, readErr := directory.ReadDir(128)
		for _, entry := range entries {
			if !strings.HasSuffix(entry.Name(), ".json") {
				continue
			}
			id := strings.TrimSuffix(entry.Name(), ".json")
			if !uuid.MatchString(id) || id <= after {
				continue
			}
			ids = append(ids, id)
			sort.Strings(ids)
			if len(ids) > limit+1 {
				ids = ids[:limit+1]
			}
		}
		if errors.Is(readErr, io.EOF) {
			break
		}
		if readErr != nil {
			return nil, "", readErr
		}
	}
	next := ""
	if len(ids) > limit {
		ids = ids[:limit]
		next = ids[len(ids)-1]
	}
	result := make([]InventoryEntry, 0, len(ids))
	for _, id := range ids {
		record, err := r.Query(ctx, id)
		if err != nil {
			return nil, "", err
		}
		result = append(result, InventoryEntry{TaskIdentity{record.Input.ExerciseID, record.Input.TaskID, record.Input.ExecutionID, record.Input.WorkspaceID}, record.State, record.Quiescent, record.Cleanup, record.CopyCleanup})
	}
	return result, next, nil
}
