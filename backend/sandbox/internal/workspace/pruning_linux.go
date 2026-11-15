package workspace

import (
	"context"
	"errors"
	"fmt"
	"os"
	"syscall"

	"codecraft.local/sandbox/internal/execution"
	rpc "codecraft.local/sandbox/internal/gen"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"
)

// PruneTestEvidence is used only for Java's durably selected ordinary-history
// eviction. Go independently verifies quiescence, complete identity and purpose.
func (s *Service) PruneTestEvidence(ctx context.Context, request *rpc.QueryTaskRequest) (*rpc.TestEvidencePruningReceipt, error) {
	return s.pruneExecutionEvidence(ctx, request, false)
}

// PruneCommandEvidence preserves unresolved candidate changes; only synchronized
// or unchanged commands can enter ordinary history eviction.
func (s *Service) PruneCommandEvidence(ctx context.Context, request *rpc.QueryTaskRequest) (*rpc.TestEvidencePruningReceipt, error) {
	return s.pruneExecutionEvidence(ctx, request, true)
}

func (s *Service) pruneExecutionEvidence(ctx context.Context, request *rpc.QueryTaskRequest, command bool) (*rpc.TestEvidencePruningReceipt, error) {
	release, err := s.enter(ctx)
	if err != nil {
		return nil, err
	}
	defer release()
	if !validTaskIdentity(request.ExerciseId, request.TaskId, request.ExecutionInstanceId, request.WorkspaceInstanceId) {
		return nil, status.Error(codes.InvalidArgument, "invalid pruning identity")
	}
	if s.executor == nil {
		return nil, status.Error(codes.Unavailable, "execution is not configured")
	}
	fd, err := s.instance(request.WorkspaceInstanceId, false)
	if err != nil {
		return nil, err
	}
	defer syscall.Close(fd)
	if retained, readErr := s.executor.Query(ctx, request.ExecutionInstanceId); readErr == nil {
		if (retained.Input.Purpose == "COMMAND") != command {
			return nil, status.Error(codes.FailedPrecondition, "execution purpose does not match pruning intent")
		}
	} else if !errors.Is(readErr, os.ErrNotExist) {
		return nil, storageError(readErr)
	}
	name := "copy-" + request.ExecutionInstanceId
	if command {
		name = "command-" + request.ExecutionInstanceId
		if err = commandPrunable(fd, request); err != nil {
			return nil, err
		}
	}
	identity := execution.TaskIdentity{ExerciseID: request.ExerciseId, TaskID: request.TaskId, ExecutionID: request.ExecutionInstanceId, WorkspaceID: request.WorkspaceInstanceId}
	if err = s.executor.PruneExecution(ctx, identity); err != nil {
		return nil, storageError(err)
	}
	copyFD, err := directory(fd, name, true)
	if err != nil {
		return nil, storageError(err)
	}
	defer syscall.Close(copyFD)
	// Retain an identity-only released marker even when registration never happened.
	// Removing the directory would let a delayed direct PrepareTestCopy resurrect it.
	marker, err := readPath(copyFD, "released")
	if errors.Is(err, syscall.ENOENT) {
		err = writePath(copyFD, "released", []byte(request.ExecutionInstanceId))
	} else if err == nil && string(marker) != request.ExecutionInstanceId {
		err = fmt.Errorf("invalid input release tombstone")
	}
	if err != nil {
		return nil, storageError(err)
	}
	root, err := os.OpenRoot(fmt.Sprintf("/proc/self/fd/%d", copyFD))
	if err != nil {
		return nil, storageError(err)
	}
	defer root.Close()
	directory, err := root.Open(".")
	if err != nil {
		return nil, storageError(err)
	}
	entries, err := directory.ReadDir(-1)
	directory.Close()
	if err != nil {
		return nil, storageError(err)
	}
	for _, entry := range entries {
		if err = ctx.Err(); err != nil {
			return nil, storageError(err)
		}
		if entry.Name() != "released" {
			if err = root.RemoveAll(entry.Name()); err != nil {
				return nil, storageError(err)
			}
		}
	}
	if err = syscall.Fsync(copyFD); err != nil {
		return nil, storageError(err)
	}
	if err = syscall.Fsync(fd); err != nil {
		return nil, storageError(err)
	}
	return &rpc.TestEvidencePruningReceipt{WorkspaceInstanceId: request.WorkspaceInstanceId, TaskId: request.TaskId, ExecutionInstanceId: request.ExecutionInstanceId, Complete: true}, nil
}
