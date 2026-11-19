package workspace

import (
	"context"
	"crypto/rand"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"maps"
	"math"
	"os"
	"regexp"
	"slices"
	"strings"
	"syscall"

	"codecraft.local/sandbox/internal/execution"
	rpc "codecraft.local/sandbox/internal/gen"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"
)

const maxMeterBytes = 64 * 1024 * 1024

var meterEpoch = regexp.MustCompile(`^[A-Z2-7]{26}$`)

type storageMeter struct {
	ExerciseID string                            `json:"exerciseId"`
	Epoch      string                            `json:"epoch"`
	Sequence   uint64                            `json:"sequence"`
	Instances  map[string]bool                   `json:"instances"`
	Executions map[string]execution.TaskIdentity `json:"executions"`
}

// A durable scope union is essential: an older Java request may arrive after a
// newly added instance/execution has already been measured and its credit settled.
func (s *Service) MeasureExerciseStorage(ctx context.Context, request *rpc.MeasureExerciseStorageRequest) (*rpc.StorageMeasurement, error) {
	release, err := s.enter(ctx, "meter:"+request.GetExerciseId())
	if err != nil {
		return nil, err
	}
	defer release()
	if !uuid.MatchString(request.ExerciseId) || len(request.WorkspaceInstanceIds) > 1000 || len(request.Executions) > 1000 {
		return nil, status.Error(codes.InvalidArgument, "invalid storage measurement scope")
	}
	rootFD, err := directory(-1, s.root, false)
	if err != nil {
		return nil, storageError(err)
	}
	defer syscall.Close(rootFD)
	name := "usage-" + request.ExerciseId + ".json"
	meter, err := readStorageMeter(rootFD, name, request.ExerciseId)
	if errors.Is(err, syscall.ENOENT) {
		meter = storageMeter{ExerciseID: request.ExerciseId, Epoch: rand.Text(), Instances: map[string]bool{}, Executions: map[string]execution.TaskIdentity{}}
	} else if err != nil {
		return nil, storageError(err)
	}
	if meter.Sequence >= math.MaxInt64 {
		return nil, status.Error(codes.ResourceExhausted, "storage measurement counter exhausted")
	}
	for _, id := range request.WorkspaceInstanceIds {
		if !uuid.MatchString(id) {
			return nil, status.Error(codes.InvalidArgument, "invalid storage instance")
		}
		meter.Instances[id] = true
	}
	for _, task := range request.Executions {
		if task == nil || task.ExerciseId != request.ExerciseId || !validTaskIdentity(task.ExerciseId, task.TaskId, task.ExecutionInstanceId, task.WorkspaceInstanceId) {
			return nil, status.Error(codes.InvalidArgument, "invalid storage execution")
		}
		identity := execution.TaskIdentity{ExerciseID: task.ExerciseId, TaskID: task.TaskId, ExecutionID: task.ExecutionInstanceId, WorkspaceID: task.WorkspaceInstanceId}
		if previous, exists := meter.Executions[identity.ExecutionID]; exists && previous != identity {
			return nil, status.Error(codes.AlreadyExists, "storage execution identity changed")
		}
		meter.Executions[identity.ExecutionID] = identity
		meter.Instances[identity.WorkspaceID] = true
	}
	meter.Sequence++
	encoded, err := json.Marshal(meter)
	if err != nil {
		return nil, storageError(err)
	}
	if len(encoded) > maxMeterBytes {
		return nil, status.Error(codes.ResourceExhausted, "storage meter metadata exhausted")
	}
	owned, err := os.OpenRoot(fmt.Sprintf("/proc/self/fd/%d", rootFD))
	if err != nil {
		return nil, storageError(err)
	}
	defer owned.Close()
	if err = cleanupMeterStages(ctx, owned, "usage-stage-"+request.ExerciseId+"-"); err != nil {
		return nil, storageError(err)
	}
	// Stable acquisition order across overlapping meter scopes. A measurement sees
	// either side of a publication/GC, never a half-built directory.
	for _, id := range slices.Sorted(maps.Keys(meter.Instances)) {
		unlock, err := s.locks.acquire(ctx, id)
		if err != nil {
			return nil, err
		}
		defer unlock()
	}
	var total uint64
	for id := range meter.Instances {
		if err = ctx.Err(); err != nil {
			return nil, storageError(err)
		}
		fd, openErr := s.rawInstance(id, false)
		if status.Code(openErr) == codes.NotFound {
			continue
		}
		if openErr != nil {
			return nil, openErr
		}
		root, openErr := os.OpenRoot(fmt.Sprintf("/proc/self/fd/%d", fd))
		if openErr != nil {
			syscall.Close(fd)
			return nil, storageError(openErr)
		}
		size, countErr := storedBytes(ctx, root.FS(), ".")
		root.Close()
		syscall.Close(fd)
		if countErr != nil {
			return nil, storageError(countErr)
		}
		if size > math.MaxInt64-total {
			return nil, status.Error(codes.ResourceExhausted, "storage byte counter exhausted")
		}
		total += size
	}
	if len(meter.Executions) > 0 {
		if s.executor == nil {
			return nil, status.Error(codes.Unavailable, "execution storage unavailable")
		}
		identities := make([]execution.TaskIdentity, 0, len(meter.Executions))
		for _, identity := range meter.Executions {
			identities = append(identities, identity)
		}
		size, countErr := s.executor.StorageBytes(ctx, identities)
		if countErr != nil {
			return nil, storageError(countErr)
		}
		if size > math.MaxInt64-total {
			return nil, status.Error(codes.ResourceExhausted, "storage byte counter exhausted")
		}
		total += size
	}
	if uint64(len(encoded)) > math.MaxInt64-total {
		return nil, status.Error(codes.ResourceExhausted, "storage byte counter exhausted")
	}
	total += uint64(len(encoded))
	stage := "usage-stage-" + request.ExerciseId + "-" + rand.Text()
	defer owned.Remove(stage)
	if err = writePath(rootFD, stage, encoded); err != nil {
		return nil, storageError(err)
	}
	if err = ctx.Err(); err != nil {
		return nil, storageError(err)
	}
	if err = syscall.Renameat(rootFD, stage, rootFD, name); err != nil {
		return nil, storageError(err)
	}
	if err = syscall.Fsync(rootFD); err != nil {
		return nil, storageError(err)
	}
	return &rpc.StorageMeasurement{ExerciseId: request.ExerciseId, MeterEpoch: meter.Epoch, Sequence: meter.Sequence, Bytes: total}, nil
}

func readStorageMeter(parent int, name, exercise string) (storageMeter, error) {
	var meter storageMeter
	fd, err := syscall.Openat(parent, name, syscall.O_RDONLY|syscall.O_NOFOLLOW|syscall.O_NONBLOCK|syscall.O_CLOEXEC, 0)
	if err != nil {
		return meter, err
	}
	file := os.NewFile(uintptr(fd), "exercise storage meter")
	defer file.Close()
	info, err := file.Stat()
	if err != nil {
		return meter, err
	}
	stat, ok := info.Sys().(*syscall.Stat_t)
	if !ok || !info.Mode().IsRegular() || stat.Nlink != 1 || info.Size() > maxMeterBytes {
		return meter, errors.New("unsafe storage meter")
	}
	decoder := json.NewDecoder(io.LimitReader(file, maxMeterBytes+1))
	decoder.DisallowUnknownFields()
	if err = decoder.Decode(&meter); err != nil {
		return meter, err
	}
	var trailing any
	if err = decoder.Decode(&trailing); !errors.Is(err, io.EOF) {
		return meter, errors.New("storage meter trailing content")
	}
	if meter.ExerciseID != exercise || !meterEpoch.MatchString(meter.Epoch) || meter.Sequence == 0 || meter.Instances == nil || meter.Executions == nil {
		return meter, errors.New("invalid storage meter")
	}
	for id, present := range meter.Instances {
		if !uuid.MatchString(id) || !present {
			return meter, errors.New("invalid stored instance")
		}
	}
	for id, identity := range meter.Executions {
		if id != identity.ExecutionID || identity.ExerciseID != exercise || !validTaskIdentity(identity.ExerciseID, identity.TaskID, id, identity.WorkspaceID) || !meter.Instances[identity.WorkspaceID] {
			return meter, errors.New("invalid stored execution")
		}
	}
	return meter, nil
}

func cleanupMeterStages(ctx context.Context, root *os.Root, prefix string) error {
	directory, err := root.Open(".")
	if err != nil {
		return err
	}
	defer directory.Close()
	for {
		if err = ctx.Err(); err != nil {
			return err
		}
		entries, readErr := directory.ReadDir(128)
		for _, entry := range entries {
			if strings.HasPrefix(entry.Name(), prefix) {
				if err = root.Remove(entry.Name()); err != nil {
					return err
				}
			}
		}
		if errors.Is(readErr, io.EOF) {
			return nil
		}
		if readErr != nil {
			return readErr
		}
	}
}
