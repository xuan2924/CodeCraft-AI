package workspace

import (
	"context"
	"errors"
	"fmt"
	"os"
	"syscall"
	"time"

	"codecraft.local/sandbox/internal/execution"
	rpc "codecraft.local/sandbox/internal/gen"
	"google.golang.org/grpc"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/metadata"
	"google.golang.org/grpc/status"
	"google.golang.org/protobuf/proto"
)

// StartTest orders copy publication and durable execution admission with writes
// and CloseGate. Once registered, page takeover does not cancel the worker.
func (s *Service) StartTest(ctx context.Context, request *rpc.StartTestRequest) (*rpc.TestTaskSnapshot, error) {
	release, err := s.enter(ctx)
	if err != nil {
		return nil, err
	}
	defer release()
	if !validTaskIdentity(request.ExerciseId, request.TaskId, request.ExecutionInstanceId, request.WorkspaceInstanceId) || !validTestPurpose(request) || request.Target != "base" || request.NotAfterUnixMillis <= 0 {
		return nil, status.Error(codes.InvalidArgument, "invalid test authority")
	}
	if s.executor == nil {
		return nil, status.Error(codes.Unavailable, "execution is not configured")
	}
	if request.HostEpoch != s.executor.HostEpoch() {
		return nil, status.Error(codes.FailedPrecondition, "stale execution host generation")
	}
	check, err := s.instance(request.WorkspaceInstanceId, false)
	if err != nil {
		return nil, err
	}
	syscall.Close(check)
	previous, err := s.executor.Query(ctx, request.ExecutionInstanceId)
	if err == nil {
		i := previous.Input
		if !sameFinalAuthority(i, request) {
			return nil, status.Error(codes.AlreadyExists, "task authority changed")
		}
		if i.ExerciseID != request.ExerciseId || i.TaskID != request.TaskId || i.WorkspaceID != request.WorkspaceInstanceId || i.Revision != request.ExpectedRevision || i.GateEpoch != request.GateEpoch || i.NotAfter.UnixMilli() != request.NotAfterUnixMillis || i.Target != request.Target {
			return nil, status.Error(codes.AlreadyExists, "task identity reused")
		}
		return taskSnapshot(previous), nil
	}
	if !errors.Is(err, os.ErrNotExist) {
		return nil, storageError(err)
	}
	if err := s.checkTaskGate(request); err != nil {
		return nil, err
	}
	// Look up immutable runtime settings before making a copy. Neither caller
	// commands nor resource settings cross this RPC boundary.
	fd, err := s.instance(request.WorkspaceInstanceId, false)
	if err != nil {
		return nil, err
	}
	current, _, readErr := latest(fd, request.WorkspaceInstanceId)
	syscall.Close(fd)
	if readErr != nil {
		return nil, storageError(readErr)
	}
	var policy *ExecutionPolicy
	for _, publication := range s.publications {
		if proto.Equal(publication.Identity, current.Snapshot.Problem) {
			policy = publication.Execution
			break
		}
	}
	if policy == nil {
		return nil, status.Error(codes.FailedPrecondition, "publication has no verified execution policy")
	}
	identity := current.Snapshot.Problem
	input := execution.Input{HostEpoch: request.HostEpoch, ExerciseID: request.ExerciseId, TaskID: request.TaskId, ExecutionID: request.ExecutionInstanceId, WorkspaceID: request.WorkspaceInstanceId,
		ProblemID: identity.ProblemId, ProblemVersion: identity.Version, Revision: request.ExpectedRevision, GateEpoch: request.GateEpoch,
		Image: identity.ExecutionImageDigest, PackageSHA256: identity.PackageSha256, Platform: identity.Platform,
		Target: request.Target, Subpath: request.WorkspaceInstanceId + "/copy-" + request.ExecutionInstanceId + "/tree", NotAfter: time.UnixMilli(request.NotAfterUnixMillis), Limits: policy.Limits, Suite: policy.Suite, Tests: policy.Tests}
	input.Purpose = request.Purpose
	if final := request.FinalVerification; final != nil {
		input.FinalizationID, input.AttemptID, input.FreezeID, input.FinalDeadline = final.FinalizationId, final.AttemptId, final.FreezeOperationId, final.DeadlineUnixMillis
	}
	prepare := func() error {
		if err := ctx.Err(); err != nil {
			return status.FromContextError(err).Err()
		}
		// A historical copy replay is not execution authority.
		if err := s.checkTaskGate(request); err != nil {
			return err
		}
		_, err := s.prepareTestCopyLocked(ctx, &rpc.PrepareTestCopyRequest{WorkspaceInstanceId: request.WorkspaceInstanceId, ExecutionInstanceId: request.ExecutionInstanceId, ExpectedRevision: request.ExpectedRevision, GateEpoch: request.GateEpoch, NotAfterUnixMillis: request.NotAfterUnixMillis, FinalVerification: request.FinalVerification})
		return err
	}
	result, err := s.executor.Start(s.worker, input, prepare, s.releaseTestCopy)
	if errors.Is(err, execution.ErrCancelled) {
		return nil, status.Error(codes.FailedPrecondition, "task was cancelled")
	}
	if errors.Is(err, execution.ErrBusy) {
		return nil, status.Error(codes.ResourceExhausted, "test slot busy")
	}
	if errors.Is(err, execution.ErrConflict) {
		return nil, status.Error(codes.AlreadyExists, "task identity reused")
	}
	if err != nil {
		if _, ok := status.FromError(err); ok {
			return nil, err
		}
		return nil, storageError(err)
	}
	return taskSnapshot(result), nil
}

func (s *Service) checkTaskGate(request *rpc.StartTestRequest) error {
	fd, err := s.instance(request.WorkspaceInstanceId, false)
	if err != nil {
		return err
	}
	defer syscall.Close(fd)
	gates, err := gateRecords(fd, request.WorkspaceInstanceId)
	if err != nil {
		return storageError(err)
	}
	if !copyGateAuthorized(gates, request.GateEpoch, request.ExpectedRevision, request.NotAfterUnixMillis, request.FinalVerification) || !s.now().Before(time.UnixMilli(request.NotAfterUnixMillis)) {
		return status.Error(codes.FailedPrecondition, "task authority expired or gate closed")
	}
	current, _, err := latest(fd, request.WorkspaceInstanceId)
	if err != nil {
		return storageError(err)
	}
	if current.Snapshot.Cancelled || !current.Snapshot.Prepared || current.Snapshot.Revision != request.ExpectedRevision {
		return status.Error(codes.Aborted, "revision conflict")
	}
	return nil
}

// QueryTask validates the complete identity, never launches work, and does not
// infer safe retry from absence. Java must fence an unknown dispatch first.
func (s *Service) QueryTask(ctx context.Context, request *rpc.QueryTaskRequest) (*rpc.TestTaskSnapshot, error) {
	release, err := s.enter(ctx)
	if err != nil {
		return nil, err
	}
	defer release()
	if !validTaskIdentity(request.ExerciseId, request.TaskId, request.ExecutionInstanceId, request.WorkspaceInstanceId) {
		return nil, status.Error(codes.InvalidArgument, "invalid task identity")
	}
	if s.executor == nil {
		return nil, status.Error(codes.Unavailable, "execution is not configured")
	}
	result, err := s.executor.Query(ctx, request.ExecutionInstanceId)
	if errors.Is(err, os.ErrNotExist) {
		return nil, status.Error(codes.NotFound, "task not found")
	}
	if err != nil {
		return nil, storageError(err)
	}
	if result.Input.ExerciseID != request.ExerciseId || result.Input.TaskID != request.TaskId || result.Input.WorkspaceID != request.WorkspaceInstanceId {
		return nil, status.Error(codes.NotFound, "task not found")
	}
	return taskSnapshot(result), nil
}

// StopTask shares the workspace gate coordinator with takeover and revocation.
// Its RPC lifetime authorizes the intent only; the execution owner reclaims work.
func (s *Service) StopTask(ctx context.Context, request *rpc.StopTaskRequest) (*rpc.StopTaskResponse, error) {
	release, err := s.enter(ctx)
	if err != nil {
		return nil, err
	}
	defer release()
	task := request.GetTask()
	if task == nil || !validTaskIdentity(task.ExerciseId, task.TaskId, task.ExecutionInstanceId, task.WorkspaceInstanceId) || request.NotAfterUnixMillis <= 0 {
		return nil, status.Error(codes.InvalidArgument, "invalid stop authority")
	}
	if s.executor == nil {
		return nil, status.Error(codes.Unavailable, "execution is not configured")
	}
	fd, err := s.instance(task.WorkspaceInstanceId, false)
	if err != nil {
		return nil, err
	}
	defer syscall.Close(fd)
	gates, err := gateRecords(fd, task.WorkspaceInstanceId)
	if err != nil {
		return nil, storageError(err)
	}
	if len(gates) == 0 || gates[len(gates)-1].State != "OPEN" || gates[len(gates)-1].Epoch != request.GateEpoch || !time.Now().Before(time.UnixMilli(request.NotAfterUnixMillis)) {
		return nil, status.Error(codes.FailedPrecondition, "stop authority expired or gate closed")
	}
	result, err := s.executor.Stop(ctx, execution.TaskIdentity{ExerciseID: task.ExerciseId, TaskID: task.TaskId, ExecutionID: task.ExecutionInstanceId, WorkspaceID: task.WorkspaceInstanceId})
	if errors.Is(err, execution.ErrConflict) {
		return nil, status.Error(codes.NotFound, "task not found")
	}
	if err != nil {
		return nil, storageError(err)
	}
	if result == nil {
		return &rpc.StopTaskResponse{CancelledBeforeRegistration: true, HostEpoch: s.executor.HostEpoch()}, nil
	}
	return &rpc.StopTaskResponse{Snapshot: taskSnapshot(*result), HostEpoch: s.executor.HostEpoch()}, nil
}

// ListExecutions pages identities without buffering report/log bodies.
func (s *Service) ListExecutions(ctx context.Context, request *rpc.ExecutionInventoryRequest) (*rpc.ExecutionInventory, error) {
	release, err := s.enter(ctx)
	if err != nil {
		return nil, err
	}
	release() // Engine observation must not hold the workspace write coordinator.
	if s.executor == nil {
		return nil, status.Error(codes.Unavailable, "execution is not configured")
	}
	if request.HostEpoch != "" && request.HostEpoch != s.executor.HostEpoch() {
		return nil, status.Error(codes.FailedPrecondition, "inventory generation changed")
	}
	if request.Limit < 1 || request.Limit > 100 || (request.AfterExecutionId != "" && !uuid.MatchString(request.AfterExecutionId)) {
		return nil, status.Error(codes.InvalidArgument, "invalid inventory page")
	}
	if request.AfterExecutionId == "" {
		if err = s.executor.RefreshEvidence(ctx); err != nil {
			return nil, storageError(err)
		}
	}
	entries, next, err := s.executor.List(ctx, request.AfterExecutionId, int(request.Limit))
	if err != nil {
		return nil, storageError(err)
	}
	response := &rpc.ExecutionInventory{HostEpoch: s.executor.HostEpoch(), NextExecutionId: next}
	for _, entry := range entries {
		response.Entries = append(response.Entries, &rpc.ExecutionInventoryEntry{Task: &rpc.QueryTaskRequest{ExerciseId: entry.Identity.ExerciseID, TaskId: entry.Identity.TaskID, ExecutionInstanceId: entry.Identity.ExecutionID, WorkspaceInstanceId: entry.Identity.WorkspaceID}, State: entry.State, Quiescent: entry.Quiescent, Cleanup: entry.Cleanup, CopyCleanup: entry.CopyCleanup})
	}
	return response, nil
}

// QueryStop recovers the original receipt even when its write authority expired.
func (s *Service) QueryStop(ctx context.Context, request *rpc.QueryTaskRequest) (*rpc.StopTaskResponse, error) {
	release, err := s.enter(ctx)
	if err != nil {
		return nil, err
	}
	defer release()
	if !validTaskIdentity(request.ExerciseId, request.TaskId, request.ExecutionInstanceId, request.WorkspaceInstanceId) {
		return nil, status.Error(codes.InvalidArgument, "invalid task identity")
	}
	if s.executor == nil {
		return nil, status.Error(codes.Unavailable, "execution is not configured")
	}
	result, cancelled, err := s.executor.QueryStop(ctx, execution.TaskIdentity{ExerciseID: request.ExerciseId, TaskID: request.TaskId, ExecutionID: request.ExecutionInstanceId, WorkspaceID: request.WorkspaceInstanceId})
	if errors.Is(err, execution.ErrConflict) {
		return nil, status.Error(codes.NotFound, "task not found")
	}
	if err != nil {
		return nil, storageError(err)
	}
	response := &rpc.StopTaskResponse{CancelledBeforeRegistration: cancelled, HostEpoch: s.executor.HostEpoch()}
	if result != nil {
		response.Snapshot = taskSnapshot(*result)
	}
	return response, nil
}

// ReconcileTask only resumes observation and exact-instance cleanup. It cannot
// replay a start or infer a successful test from logs or an exit code alone.
func (s *Service) ReconcileTask(ctx context.Context, request *rpc.QueryTaskRequest) (*rpc.TestTaskSnapshot, error) {
	snapshot, err := s.QueryTask(ctx, request)
	if err != nil {
		return nil, err
	}
	if err = s.executor.AlignRecoveredVersion(ctx, request.ExecutionInstanceId, request.ObservedStateVersion); err != nil {
		return nil, storageError(err)
	}
	if err = s.executor.Recover(s.worker, request.ExecutionInstanceId, s.releaseTestCopy); err != nil {
		return nil, storageError(err)
	}
	return snapshot, nil
}

// WatchTask consumes persisted observations; disconnecting cannot cancel execution.
// No per-subscriber unbounded queue is maintained. gRPC flow control may block
// Send, but never the execution owner or the storage coordinator.
func (s *Service) WatchTask(request *rpc.WatchTaskRequest, stream grpc.ServerStreamingServer[rpc.TaskEvent]) error {
	ctx := stream.Context()
	if request.GetTask() == nil {
		return status.Error(codes.InvalidArgument, "task required")
	}
	if _, err := s.QueryTask(ctx, request.Task); err != nil {
		return err
	}
	select {
	case s.watches <- struct{}{}:
		defer func() { <-s.watches }()
	default:
		return status.Error(codes.ResourceExhausted, "watch capacity reached")
	}
	after := request.AfterSequence
	ticker := time.NewTicker(200 * time.Millisecond)
	defer ticker.Stop()
	for {
		result, err := s.executor.Query(ctx, request.Task.ExecutionInstanceId)
		if err != nil {
			return storageError(err)
		}
		first := result.Sequence + 1
		if len(result.Events) > 0 {
			first = result.Events[0].Sequence
		}
		if request.StreamId == "" || request.StreamId != result.StreamID || after > result.Sequence || after+1 < first {
			return stream.Send(&rpc.TaskEvent{SchemaVersion: 1, TaskId: request.Task.TaskId, ExecutionInstanceId: request.Task.ExecutionInstanceId, StreamId: result.StreamID, Sequence: result.Sequence, Type: "reset_required"})
		}
		for _, event := range result.Events {
			if event.Sequence <= after {
				continue
			}
			if err = stream.Send(&rpc.TaskEvent{SchemaVersion: 1, TaskId: request.Task.TaskId, ExecutionInstanceId: request.Task.ExecutionInstanceId, StreamId: result.StreamID, Sequence: event.Sequence, Type: event.Type, OccurredAtUnixMillis: event.OccurredAt.UnixMilli(), Channel: event.Channel, Data: event.Data, State: event.State, DroppedBytes: event.DroppedBytes}); err != nil {
				return err
			}
			after = event.Sequence
		}
		if result.FinishedAt != nil {
			return nil
		}
		select {
		case <-ctx.Done():
			return status.FromContextError(ctx.Err()).Err()
		case <-ticker.C:
		}
	}
}

func validTaskIdentity(exercise, task, executionID, workspace string) bool {
	return uuid.MatchString(exercise) && uuid.MatchString(task) && uuid.MatchString(executionID) && uuid.MatchString(workspace)
}

func taskSnapshot(result execution.Result) *rpc.TestTaskSnapshot {
	i := result.Input
	snapshot := &rpc.TestTaskSnapshot{ExerciseId: i.ExerciseID, TaskId: i.TaskID, ExecutionInstanceId: i.ExecutionID, WorkspaceInstanceId: i.WorkspaceID,
		Revision: i.Revision, Target: i.Target, Problem: &rpc.ProblemIdentity{ProblemId: i.ProblemID, Version: i.ProblemVersion, PackageSha256: i.PackageSHA256, ExecutionImageDigest: i.Image, Platform: i.Platform},
		State: result.State, Reason: result.Reason, StartedAtUnixMillis: result.StartedAt.UnixMilli(), Quiescent: result.Quiescent, Cleanup: result.Cleanup, CopyCleanup: result.CopyCleanup,
		DroppedBytes: result.DroppedBytes, LogsIncomplete: result.LogsIncomplete, StreamId: result.StreamID, Sequence: result.Sequence, StateVersion: result.Version}
	if len(result.Events) > 0 {
		snapshot.FirstRetainedSequence = result.Events[0].Sequence
	}
	if result.FinishedAt != nil {
		value := result.FinishedAt.UnixMilli()
		snapshot.FinishedAtUnixMillis = &value
	}
	if result.ExitCode != nil {
		value := int32(*result.ExitCode)
		snapshot.ExitCode = &value
	}
	if evidence := result.Evidence(); evidence != nil {
		snapshot.Evidence = &rpc.TestEvidence{Kind: evidence.Kind, Sha256: evidence.SHA256, Bytes: evidence.Bytes}
	}
	for _, chunk := range result.Logs {
		snapshot.Logs = append(snapshot.Logs, &rpc.TestLogChunk{Channel: chunk.Channel, Data: chunk.Data})
	}
	return snapshot
}

func (s *Service) releaseTestCopy(ctx context.Context, result execution.Result) error {
	if !result.Quiescent || result.FinishedAt == nil {
		return fmt.Errorf("cannot release active input")
	}
	ctx = metadata.NewIncomingContext(ctx, metadata.Pairs("x-codecraft-internal-key", s.key))
	release, err := s.enter(ctx)
	if err != nil {
		return err
	}
	defer release()
	fd, err := s.rawInstance(result.Input.WorkspaceID, false)
	if err != nil {
		return err
	}
	defer syscall.Close(fd)
	name := "copy-" + result.Input.ExecutionID
	marker, markerErr := readPath(fd, name+"/released")
	if errors.Is(markerErr, syscall.ENOENT) {
		if _, err = readTestCopy(ctx, fd, result.Input.WorkspaceID, result.Input.ExecutionID); err != nil {
			return err
		}
		// Tombstone precedes byte deletion. A retry after either step must remain
		// idempotent without granting delayed preparation new authority.
		if err = writePath(fd, name+"/released", []byte(result.Input.ExecutionID)); err != nil {
			return err
		}
	} else if markerErr != nil {
		return markerErr
	} else if string(marker) != result.Input.ExecutionID {
		return fmt.Errorf("invalid input release tombstone")
	}
	owned, err := os.OpenRoot(fmt.Sprintf("/proc/self/fd/%d", fd))
	if err != nil {
		return err
	}
	defer owned.Close()
	if err = owned.RemoveAll(name + "/tree"); err != nil {
		return err
	}
	copyFD, err := directory(fd, name, false)
	if err != nil {
		return err
	}
	defer syscall.Close(copyFD)
	return syscall.Fsync(copyFD)
}
