package execution

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"syscall"
	"time"
)

// The small recovery receipt is flushed BEFORE each result replacement. It has
// no log/report body: losing the result cannot be repaired into a passing grade.
// It does preserve the immutable owner, exact container, deadline and version so
// cleanup can recover without inventing a new execution or resetting counters.
type recoveryReceipt struct {
	Input            Input     `json:"input"`
	StartedAt        time.Time `json:"startedAt"`
	ContainerID      string    `json:"containerId"`
	Version          uint64    `json:"version"`
	CounterUncertain bool      `json:"counterUncertain,omitempty"`
}

func (r *Runner) writeRecoveryReceipt(record Result) error {
	version := record.Version
	if version == 0 {
		version = 1
	} // Pre-stream records gain a recovery receipt on upgrade.
	data, err := json.Marshal(recoveryReceipt{record.Input, record.StartedAt, record.ContainerID, version, record.CounterUncertain})
	if err != nil {
		return err
	}
	return r.writeRegistry(record.Input.ExecutionID+".meta", data)
}

func (r *Runner) readRecoveryReceipt(id string) (recoveryReceipt, error) {
	var receipt recoveryReceipt
	file, err := os.OpenFile(filepath.Join(r.root, id+".meta"), os.O_RDONLY|syscall.O_NOFOLLOW|syscall.O_NONBLOCK, 0)
	if err != nil {
		return receipt, err
	}
	defer file.Close()
	info, err := file.Stat()
	if err != nil {
		return receipt, err
	}
	stat, ok := info.Sys().(*syscall.Stat_t)
	if !ok || !info.Mode().IsRegular() || stat.Nlink != 1 || info.Size() > 262144 {
		return receipt, errors.New("unsafe recovery receipt")
	}
	decoder := json.NewDecoder(io.LimitReader(file, 262144))
	decoder.DisallowUnknownFields()
	if err = decoder.Decode(&receipt); err != nil {
		return receipt, err
	}
	var trailing any
	if err = decoder.Decode(&trailing); !errors.Is(err, io.EOF) {
		return receipt, errors.New("recovery receipt trailing content")
	}
	if receipt.Input.ExecutionID != id || validateInput(receipt.Input) != nil || receipt.StartedAt.IsZero() || receipt.Version == 0 || receipt.Version == ^uint64(0) {
		return receipt, errors.New("invalid recovery receipt")
	}
	return receipt, nil
}

// repairResults runs under exclusive process ownership, before admission. A
// damaged primary is quarantined (never silently discarded); the resulting
// INTERRUPTED task still occupies capacity until exact-instance cleanup succeeds.
func (r *Runner) repairResults(ctx context.Context) error {
	entries, err := os.ReadDir(r.root)
	if err != nil {
		return err
	}
	for _, entry := range entries {
		if !strings.HasSuffix(entry.Name(), ".meta") {
			continue
		}
		id := strings.TrimSuffix(entry.Name(), ".meta")
		if !uuid.MatchString(id) {
			return errors.New("invalid recovery receipt name")
		}
		if _, live := r.active[id]; live {
			continue
		}
		receipt, err := r.readRecoveryReceipt(id)
		if err != nil {
			return err
		}
		record, queryErr := r.Query(ctx, id)
		if queryErr == nil {
			if !reflect.DeepEqual(record.Input, receipt.Input) || !record.StartedAt.Equal(receipt.StartedAt) {
				return errors.New("recovery receipt ownership mismatch")
			}
			if record.Version > receipt.Version {
				return errors.New("recovery receipt regressed")
			}
			if record.ContainerID == receipt.ContainerID {
				continue
			}
			// Create succeeded and its recovery receipt was flushed, but the
			// primary replacement did not commit. Preserve the exact container
			// for reconciliation rather than failing startup permanently.
			if record.ContainerID != "" || receipt.ContainerID == "" || receipt.Version <= record.Version {
				return errors.New("recovery receipt container mismatch")
			}
		}
		var pathError *os.PathError
		if !errors.Is(queryErr, os.ErrNotExist) && errors.As(queryErr, &pathError) {
			return queryErr
		}
		if ctx.Err() != nil {
			return ctx.Err()
		}
		// Never replace inaccessible evidence: only a missing or readable corrupt
		// regular primary can be quarantined. The safe reader already rejects links.
		primary := filepath.Join(r.root, id+".json")
		if info, statErr := os.Lstat(primary); statErr == nil {
			if !info.Mode().IsRegular() {
				return errors.New("unsafe damaged execution record")
			}
			if err = os.Rename(primary, filepath.Join(r.root, ".corrupt-"+id+"-"+time.Now().UTC().Format("20060102150405.000000000"))); err != nil {
				return err
			}
		} else if !errors.Is(statErr, os.ErrNotExist) {
			return statErr
		}
		now := time.Now().UTC()
		record = Result{Input: receipt.Input, StartedAt: receipt.StartedAt, ContainerID: receipt.ContainerID,
			Version: receipt.Version + 1, CounterUncertain: receipt.CounterUncertain, State: "INTERRUPTED", Reason: "registry_evidence_lost", FinishedAt: &now, Cleanup: "PENDING", LogsIncomplete: true}
		if receipt.Input.TaskID != "" {
			record.CopyCleanup = "PENDING"
		}
		appendEvents(&record, Result{})
		r.indexTerminal(record)
		if err = r.writeRecoveryReceipt(record); err != nil {
			return err
		}
		data, err := json.Marshal(record)
		if err != nil {
			return err
		}
		if err = r.writeRegistry(id+".json", data); err != nil {
			return err
		}
	}
	return nil
}
