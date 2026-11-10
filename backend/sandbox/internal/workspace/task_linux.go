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
	if !validTaskIdentity(request.ExerciseId, request.TaskId, request.ExecutionInstanceId, request.WorkspaceInstanceId) || request.Purpose != "MANUAL" || request.Target != "base" || request.NotAfterUnixMillis <= 0 {
		return nil, status.Error(codes.InvalidArgument, "invalid manual task")
	}
	if s.executor == nil {
		return nil, status.Error(codes.Unavailable, "execution is not configured")
	}
	previous, err := s.executor.Query(ctx, request.ExecutionInstanceId)
	if err == nil {
		i := previous.Input
		if i.ExerciseID != request.ExerciseId || i.TaskID != request.TaskId || i.WorkspaceID != request.WorkspaceInstanceId || i.Revision != request.ExpectedRevision || i.GateEpoch != request.GateEpoch || i.NotAfter.UnixMilli() != request.NotAfterUnixMillis || i.Target != request.Target {
			return nil, status.Error(codes.AlreadyExists, "task identity reused")
		}
		return taskSnapshot(previous), nil
	}
	if !errors.Is(err, os.ErrNotExist) {
		return nil, storageError(err)
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
	input := execution.Input{ExerciseID: request.ExerciseId, TaskID: request.TaskId, ExecutionID: request.ExecutionInstanceId, WorkspaceID: request.WorkspaceInstanceId,
		ProblemID: identity.ProblemId, ProblemVersion: identity.Version, Revision: request.ExpectedRevision, GateEpoch: request.GateEpoch,
		Image: identity.ExecutionImageDigest, PackageSHA256: identity.PackageSha256, Platform: identity.Platform,
		Target: request.Target, Subpath: request.WorkspaceInstanceId + "/copy-" + request.ExecutionInstanceId + "/tree", NotAfter: time.UnixMilli(request.NotAfterUnixMillis), Limits: policy.Limits, Suite: policy.Suite, Tests: policy.Tests}
	prepare := func() error {
		if err := ctx.Err(); err != nil {
			return status.FromContextError(err).Err()
		}
		// A historical copy replay is not execution authority.
		if err := s.checkTaskGate(request); err != nil {
			return err
		}
		_, err := s.prepareTestCopyLocked(ctx, &rpc.PrepareTestCopyRequest{WorkspaceInstanceId: request.WorkspaceInstanceId, ExecutionInstanceId: request.ExecutionInstanceId, ExpectedRevision: request.ExpectedRevision, GateEpoch: request.GateEpoch, NotAfterUnixMillis: request.NotAfterUnixMillis})
		return err
	}
	result, err := s.executor.Start(s.worker, input, prepare, s.releaseTestCopy)
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
	if len(gates) == 0 || gates[len(gates)-1].State != "OPEN" || gates[len(gates)-1].Epoch != request.GateEpoch || !time.Now().Before(time.UnixMilli(request.NotAfterUnixMillis)) {
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

func validTaskIdentity(exercise, task, executionID, workspace string) bool {
	return uuid.MatchString(exercise) && uuid.MatchString(task) && uuid.MatchString(executionID) && uuid.MatchString(workspace)
}

func taskSnapshot(result execution.Result) *rpc.TestTaskSnapshot {
	i := result.Input
	snapshot := &rpc.TestTaskSnapshot{ExerciseId: i.ExerciseID, TaskId: i.TaskID, ExecutionInstanceId: i.ExecutionID, WorkspaceInstanceId: i.WorkspaceID,
		Revision: i.Revision, Target: i.Target, Problem: &rpc.ProblemIdentity{ProblemId: i.ProblemID, Version: i.ProblemVersion, PackageSha256: i.PackageSHA256, ExecutionImageDigest: i.Image, Platform: i.Platform},
		State: result.State, Reason: result.Reason, StartedAtUnixMillis: result.StartedAt.UnixMilli(), Quiescent: result.Quiescent, Cleanup: result.Cleanup, CopyCleanup: result.CopyCleanup,
		DroppedBytes: result.DroppedBytes, LogsIncomplete: result.LogsIncomplete}
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
	fd, err := s.instance(result.Input.WorkspaceID, false)
	if err != nil {
		return err
	}
	defer syscall.Close(fd)
	name := "copy-" + result.Input.ExecutionID
	if _, err = readTestCopy(ctx, fd, result.Input.WorkspaceID, result.Input.ExecutionID); err != nil {
		return err
	}
	// Persist a tombstone before deleting bytes: delayed input preparation may
	// never recreate this execution's copy after the terminal record is retained.
	if err = writePath(fd, name+"/released", []byte(result.Input.ExecutionID)); err != nil {
		return err
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
