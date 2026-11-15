package workspace

import (
	"context"
	"errors"
	"os"
	"syscall"
	"time"

	"codecraft.local/sandbox/internal/execution"
	rpc "codecraft.local/sandbox/internal/gen"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/metadata"
	"google.golang.org/grpc/status"
	"google.golang.org/protobuf/proto"
)

// StartCommand shares the executor's durable admission, cancellation tombstones
// and global slot. Its independent mutable copy is never authority-test input.
func (s *Service) StartCommand(ctx context.Context, request *rpc.StartCommandRequest) (*rpc.CommandSnapshot, error) {
	release, err := s.enter(ctx)
	if err != nil {
		return nil, err
	}
	defer release()
	if !validTaskIdentity(request.ExerciseId, request.TaskId, request.ExecutionInstanceId, request.WorkspaceInstanceId) || request.NotAfterUnixMillis <= 0 {
		return nil, status.Error(codes.InvalidArgument, "invalid command authority")
	}
	if s.executor == nil {
		return nil, status.Error(codes.Unavailable, "execution is not configured")
	}
	if request.HostEpoch != s.executor.HostEpoch() {
		return nil, status.Error(codes.FailedPrecondition, "stale execution host generation")
	}
	fd, err := s.instance(request.WorkspaceInstanceId, false)
	if err != nil {
		return nil, err
	}
	defer syscall.Close(fd)
	previous, err := s.executor.Query(ctx, request.ExecutionInstanceId)
	if err == nil {
		i := previous.Input
		if i.Purpose != "COMMAND" || i.Command == nil || i.Command.Text != request.Command || i.Command.Directory != request.Directory ||
			i.ExerciseID != request.ExerciseId || i.TaskID != request.TaskId || i.WorkspaceID != request.WorkspaceInstanceId ||
			i.Revision != request.ExpectedRevision || i.GateEpoch != request.GateEpoch || i.NotAfter.UnixMilli() != request.NotAfterUnixMillis {
			return nil, status.Error(codes.AlreadyExists, "command identity changed")
		}
		return commandSnapshot(fd, previous)
	}
	if !errors.Is(err, os.ErrNotExist) {
		return nil, storageError(err)
	}
	gate := &rpc.StartTestRequest{WorkspaceInstanceId: request.WorkspaceInstanceId, ExpectedRevision: request.ExpectedRevision,
		GateEpoch: request.GateEpoch, NotAfterUnixMillis: request.NotAfterUnixMillis}
	if err = s.checkTaskGate(gate); err != nil {
		return nil, err
	}
	current, _, err := latest(fd, request.WorkspaceInstanceId)
	if err != nil {
		return nil, storageError(err)
	}
	var policy *ExecutionPolicy
	for _, publication := range s.publications {
		if proto.Equal(publication.Identity, current.Snapshot.Problem) {
			policy = publication.Execution
			break
		}
	}
	if policy == nil {
		return nil, status.Error(codes.FailedPrecondition, "publication has no execution policy")
	}
	identity := current.Snapshot.Problem
	input := execution.Input{Purpose: "COMMAND", HostEpoch: request.HostEpoch, ExerciseID: request.ExerciseId, TaskID: request.TaskId,
		ExecutionID: request.ExecutionInstanceId, WorkspaceID: request.WorkspaceInstanceId, ProblemID: identity.ProblemId,
		ProblemVersion: identity.Version, Revision: request.ExpectedRevision, GateEpoch: request.GateEpoch,
		Image: identity.ExecutionImageDigest, PackageSHA256: identity.PackageSha256, Platform: identity.Platform,
		Target: "bash", Subpath: request.WorkspaceInstanceId + "/command-" + request.ExecutionInstanceId + "/tree",
		NotAfter: time.UnixMilli(request.NotAfterUnixMillis).UTC(), Limits: policy.Limits,
		Command: &execution.CommandSpec{Text: request.Command, Directory: request.Directory}}
	if err = execution.ValidateCommand(input); err != nil {
		return nil, status.Error(codes.InvalidArgument, "invalid development command")
	}
	prepare := func() error {
		if err := ctx.Err(); err != nil {
			return status.FromContextError(err).Err()
		}
		if err := s.checkTaskGate(gate); err != nil {
			return err
		}
		return s.prepareCommandLocked(ctx, input)
	}
	result, err := s.executor.StartMonitored(s.worker, input, prepare, s.commandBudget, s.releaseTestCopy)
	switch {
	case errors.Is(err, execution.ErrCancelled):
		return nil, status.Error(codes.FailedPrecondition, "command was cancelled")
	case errors.Is(err, execution.ErrBusy):
		return nil, status.Error(codes.ResourceExhausted, "execution slot busy")
	case errors.Is(err, execution.ErrConflict):
		return nil, status.Error(codes.AlreadyExists, "command identity changed")
	case err != nil:
		if _, ok := status.FromError(err); ok {
			return nil, err
		}
		return nil, storageError(err)
	}
	return commandSnapshot(fd, result)
}

func commandSnapshot(parent int, result execution.Result) (*rpc.CommandSnapshot, error) {
	record, fd, err := openCommand(parent, result.Input.WorkspaceID, result.Input.ExecutionID)
	if err != nil {
		if errors.Is(err, os.ErrNotExist) || status.Code(err) == codes.NotFound {
			return nil, status.Error(codes.DataLoss, "registered command copy unavailable")
		}
		return nil, storageError(err)
	}
	defer syscall.Close(fd)
	if !sameCommandInput(record.Input, result.Input) {
		return nil, status.Error(codes.DataLoss, "command receipt identity changed")
	}
	return &rpc.CommandSnapshot{Execution: taskSnapshot(result), Synchronization: record.Synchronization,
		SynchronizationReason: record.Reason, Mutation: record.Mutation}, nil
}

func (s *Service) queryCommandLocked(ctx context.Context, request *rpc.QueryTaskRequest) (execution.Result, error) {
	if !validTaskIdentity(request.ExerciseId, request.TaskId, request.ExecutionInstanceId, request.WorkspaceInstanceId) {
		return execution.Result{}, status.Error(codes.InvalidArgument, "invalid command identity")
	}
	if s.executor == nil {
		return execution.Result{}, status.Error(codes.Unavailable, "execution is not configured")
	}
	result, err := s.executor.Query(ctx, request.ExecutionInstanceId)
	if err != nil {
		if errors.Is(err, os.ErrNotExist) {
			return result, status.Error(codes.NotFound, "command record unavailable")
		}
		return result, storageError(err)
	}
	if result.Input.Purpose != "COMMAND" || result.Input.ExerciseID != request.ExerciseId || result.Input.TaskID != request.TaskId || result.Input.WorkspaceID != request.WorkspaceInstanceId {
		return execution.Result{}, status.Error(codes.NotFound, "command record unavailable")
	}
	return result, nil
}

// QueryCommand reads an exact owned execution and its synchronization receipt;
// it never starts, stops, or publishes files. A missing registered copy is data loss.
func (s *Service) QueryCommand(ctx context.Context, request *rpc.QueryTaskRequest) (*rpc.CommandSnapshot, error) {
	release, err := s.enter(ctx)
	if err != nil {
		return nil, err
	}
	defer release()
	result, err := s.queryCommandLocked(ctx, request)
	if err != nil {
		return nil, err
	}
	fd, err := s.instance(request.WorkspaceInstanceId, false)
	if err != nil {
		return nil, err
	}
	defer syscall.Close(fd)
	return commandSnapshot(fd, result)
}

// SynchronizeCommand reconciles only a proven-quiescent command using its
// original gate, deadline, and baseline. Repeats reuse the durable mutation receipt.
func (s *Service) SynchronizeCommand(ctx context.Context, request *rpc.QueryTaskRequest) (*rpc.CommandSnapshot, error) {
	release, err := s.enter(ctx)
	if err != nil {
		return nil, err
	}
	defer release()
	result, err := s.queryCommandLocked(ctx, request)
	if err != nil {
		return nil, err
	}
	record, err := s.synchronizeCommandLocked(ctx, result)
	if err != nil {
		return nil, err
	}
	return &rpc.CommandSnapshot{Execution: taskSnapshot(result), Synchronization: record.Synchronization,
		SynchronizationReason: record.Reason, Mutation: record.Mutation}, nil
}

// ReadCommandChanges reads the retained baseline/final text diff without writing.
// Active processes and candidates without a valid captured file tree are unavailable.
func (s *Service) ReadCommandChanges(ctx context.Context, request *rpc.QueryTaskRequest) (*rpc.CommandChanges, error) {
	release, err := s.enter(ctx)
	if err != nil {
		return nil, err
	}
	defer release()
	result, err := s.queryCommandLocked(ctx, request)
	if err != nil {
		return nil, err
	}
	if !result.Quiescent || result.FinishedAt == nil {
		return nil, status.Error(codes.FailedPrecondition, "command still running")
	}
	parent, err := s.instance(request.WorkspaceInstanceId, false)
	if err != nil {
		return nil, err
	}
	defer syscall.Close(parent)
	record, fd, err := openCommand(parent, request.WorkspaceInstanceId, request.ExecutionInstanceId)
	if err != nil {
		return nil, storageError(err)
	}
	defer syscall.Close(fd)
	return commandChanges(fd, record)
}

func (s *Service) completeCommand(ctx context.Context, result execution.Result) error {
	ctx = metadata.NewIncomingContext(ctx, metadata.Pairs("x-codecraft-internal-key", s.key))
	release, err := s.enter(ctx)
	if err != nil {
		return err
	}
	defer release()
	_, err = s.synchronizeCommandLocked(ctx, result)
	if err != nil {
		return err
	}
	return s.clearCommandCaches(ctx, result.Input)
}
