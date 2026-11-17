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
	"time"

	"github.com/docker/docker/api/types/container"
	"github.com/docker/docker/api/types/filters"
)

// recoverOrphans only reconstructs an INTERRUPTED registration from an exact
// Engine-side immutable owner receipt. It does not stop/delete anything: Java
// must corroborate the task before requesting normal exact-instance recovery.
func (r *Runner) recoverOrphans(ctx context.Context) error {
	call, cancel := context.WithTimeout(ctx, 5*time.Second)
	defer cancel()
	found, err := r.docker.ContainerList(call, container.ListOptions{All: true, Filters: filters.NewArgs(filters.Arg("label", "codecraft.execution.volume="+r.volume))})
	if err != nil {
		return err
	}
	for _, item := range found {
		id := item.Labels["codecraft.execution.id"]
		if !uuid.MatchString(id) || item.Labels["codecraft.execution.owner"] != r.owner {
			return errors.New("unowned execution remains isolated")
		}
		record, queryErr := r.Query(ctx, id)
		previous, metaErr := r.readRecoveryReceipt(id)
		if queryErr == nil && record.ContainerID != "" {
			if record.ContainerID != item.ID {
				return errors.New("registered container identity mismatch")
			}
			if errors.Is(metaErr, os.ErrNotExist) {
				if err = r.writeRecoveryReceipt(record); err != nil {
					return err
				}
			}
			continue
		}
		if metaErr == nil && previous.ContainerID != "" {
			if previous.ContainerID != item.ID {
				return errors.New("recovery container identity mismatch")
			}
			continue
		}
		var pathError *os.PathError
		if !errors.Is(metaErr, os.ErrNotExist) && errors.As(metaErr, &pathError) {
			return metaErr
		}
		encoded := item.Labels["codecraft.execution.receipt"]
		if len(encoded) == 0 || len(encoded) > 262144 {
			return errors.New("orphan execution has no recoverable owner receipt")
		}
		var receipt recoveryReceipt
		decoder := json.NewDecoder(strings.NewReader(encoded))
		decoder.DisallowUnknownFields()
		if err = decoder.Decode(&receipt); err != nil {
			return err
		}
		var trailing any
		if err = decoder.Decode(&trailing); !errors.Is(err, io.EOF) {
			return errors.New("orphan receipt trailing content")
		}
		input := receipt.Input
		if validateInput(input) != nil || input.ExecutionID != id || input.Image != item.ImageID || receipt.StartedAt.IsZero() ||
			input.WorkspaceID != item.Labels["codecraft.workspace.id"] || input.TaskID != item.Labels["codecraft.task.id"] ||
			input.ExerciseID != item.Labels["codecraft.exercise.id"] || input.HostEpoch != item.Labels["codecraft.host.epoch"] {
			return errors.New("orphan receipt ownership mismatch")
		}
		// A Create response can be lost before either registry file records
		// its ID. Corroborate the immutable Engine receipt against every valid
		// local owner before advancing recovery evidence.
		receipt.Version = 1
		receipt.CounterUncertain = true
		if queryErr == nil {
			if !reflect.DeepEqual(record.Input, input) || !record.StartedAt.Equal(receipt.StartedAt) {
				return errors.New("orphan primary ownership mismatch")
			}
			receipt.Version = record.Version
			receipt.CounterUncertain = record.CounterUncertain
		}
		if metaErr == nil {
			if !reflect.DeepEqual(previous.Input, input) || !previous.StartedAt.Equal(receipt.StartedAt) {
				return errors.New("orphan recovery ownership mismatch")
			}
			if previous.Version > receipt.Version {
				receipt.Version = previous.Version
			}
			receipt.CounterUncertain = previous.CounterUncertain
		}
		if receipt.Version >= ^uint64(0)-1 {
			return errors.New("execution version exhausted")
		}
		receipt.Version++
		path := filepath.Join(r.root, id+".meta")
		if info, statErr := os.Lstat(path); statErr == nil {
			if !info.Mode().IsRegular() {
				return errors.New("unsafe orphan recovery receipt")
			}
			if err = os.Rename(path, filepath.Join(r.root, ".corrupt-meta-"+id+"-"+time.Now().UTC().Format("20060102150405.000000000"))); err != nil {
				return err
			}
		} else if !errors.Is(statErr, os.ErrNotExist) {
			return statErr
		}
		receipt.ContainerID = item.ID
		data, err := json.Marshal(receipt)
		if err != nil {
			return err
		}
		if err = r.writeRegistry(id+".meta", data); err != nil {
			return err
		}
	}
	return nil
}

// RefreshEvidence also discovers a late Create from an old, dead owner. Any
// unresolved registration keeps admission closed until recovery confirms exit.
func (r *Runner) RefreshEvidence(ctx context.Context) error {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.closed {
		return ErrBusy
	}
	if r.running {
		return nil
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
	r.blocked = false
	if r.coordinator != "" {
		if err := r.ConfigureEgress(ctx, r.coordinator); err != nil {
			r.blocked = true
			return err
		}
	}
	if err := r.inventory(ctx); err != nil {
		r.blocked = true
		return err
	}
	return nil
}

// AlignRecoveredVersion uses Java's committed observation as a lower bound only
// when both Go counters were lost. It never changes the interrupted outcome.
func (r *Runner) AlignRecoveredVersion(ctx context.Context, id string, minimum uint64) error {
	if minimum >= uint64(1<<63-1) {
		return ErrConflict
	}
	r.stateMu.Lock()
	defer r.stateMu.Unlock()
	record, err := r.Query(ctx, id)
	if err != nil {
		return err
	}
	if !record.CounterUncertain {
		return nil
	}
	if record.State != "INTERRUPTED" || record.Reason != "registry_evidence_lost" {
		return ErrConflict
	}
	if record.Version < minimum {
		record.Version = minimum
	}
	record.Version++
	record.CounterUncertain = false
	if err = r.writeRecoveryReceipt(record); err != nil {
		return err
	}
	data, err := json.Marshal(record)
	if err != nil {
		return err
	}
	return r.writeRegistry(id+".json", data)
}
