package workspace

import (
	"errors"
	"syscall"

	rpc "codecraft.local/sandbox/internal/gen"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"
)

func commandPrunable(parent int, request *rpc.QueryTaskRequest) error {
	fd, err := directory(parent, "command-"+request.ExecutionInstanceId, false)
	if errors.Is(err, syscall.ENOENT) {
		return nil // Executor pruning still has to establish cancellation/quiescence.
	}
	if err != nil {
		return storageError(err)
	}
	defer syscall.Close(fd)
	marker, err := readPath(fd, "released")
	if err == nil {
		if string(marker) != request.ExecutionInstanceId {
			return status.Error(codes.DataLoss, "invalid command release marker")
		}
		return nil
	}
	if !errors.Is(err, syscall.ENOENT) {
		return storageError(err)
	}
	record, recordFD, err := openCommand(parent, request.WorkspaceInstanceId, request.ExecutionInstanceId)
	if err != nil {
		return storageError(err)
	}
	defer syscall.Close(recordFD)
	if record.Input.ExerciseID != request.ExerciseId || record.Input.TaskID != request.TaskId {
		return status.Error(codes.NotFound, "command record unavailable")
	}
	if record.Synchronization != "SYNCED" && record.Synchronization != "UNCHANGED" {
		return status.Error(codes.FailedPrecondition, "unresolved command changes remain protected")
	}
	return nil
}
