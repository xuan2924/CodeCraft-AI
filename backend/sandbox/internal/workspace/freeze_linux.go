package workspace

import (
	"context"
	"errors"
	"syscall"

	"codecraft.local/sandbox/internal/execution"
	rpc "codecraft.local/sandbox/internal/gen"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"
)

// Freeze fixes the verified tree revision after a durable close. Its gate receipt
// is published under the same coordinator as mutations; it never reopens a gate.
func (s *Service) Freeze(ctx context.Context, request *rpc.CloseGateRequest) (*rpc.GateResponse, error) {
	release, err := s.enter(ctx, request.GetWorkspaceInstanceId())
	if err != nil {
		return nil, err
	}
	defer release()
	if !uuid.MatchString(request.TransitionId) {
		return nil, status.Error(codes.InvalidArgument, "invalid freeze operation")
	}
	fd, err := s.instance(request.WorkspaceInstanceId, false)
	if err != nil {
		return nil, err
	}
	defer syscall.Close(fd)
	gates, err := gateRecords(fd, request.WorkspaceInstanceId)
	if err != nil {
		return nil, storageError(err)
	}
	if len(gates) == 0 {
		return nil, status.Error(codes.FailedPrecondition, "closed gate required")
	}
	current := gates[len(gates)-1]
	if current.State == "CLOSED" || current.State == "FROZEN" {
		if err := s.quiesceTerminals(ctx, request.WorkspaceInstanceId); err != nil {
			return nil, err
		}
	}
	if current.State == "FROZEN" {
		if current.TransitionId != request.TransitionId || current.Epoch-1 != request.ExpectedEpoch {
			return nil, status.Error(codes.AlreadyExists, "freeze identity changed")
		}
		if err := verifyFrozen(ctx, fd, current); err != nil {
			return nil, storageError(err)
		}
		return current, nil
	}
	if current.State != "CLOSED" || current.Epoch != request.ExpectedEpoch || current.Epoch == ^uint64(0) {
		return nil, status.Error(codes.FailedPrecondition, "closed gate epoch required")
	}
	record, _, err := latest(fd, request.WorkspaceInstanceId)
	if err != nil {
		return nil, storageError(err)
	}
	if record.Snapshot.Cancelled || !record.Snapshot.Prepared {
		return nil, status.Error(codes.FailedPrecondition, "workspace unavailable")
	}
	if err := verifyPreparedTree(ctx, fd, record); err != nil {
		return nil, storageError(err)
	}
	receipt := &rpc.GateResponse{WorkspaceInstanceId: request.WorkspaceInstanceId, TransitionId: request.TransitionId, Epoch: current.Epoch + 1, State: "FROZEN", FrozenRevision: record.Snapshot.Revision}
	if s.archive != nil {
		sha, err := s.archive.save(ctx, fd, record, append(gates, receipt))
		if err != nil {
			return nil, storageError(err)
		}
		receipt.ArchiveSha256 = sha
	}
	if err := publishGate(ctx, fd, receipt); err != nil {
		return nil, storageError(err)
	}
	return receipt, nil
}

// QueryFreeze confirms both the immutable receipt and its still-readable tree.
func (s *Service) QueryFreeze(ctx context.Context, request *rpc.QueryWorkspaceRequest) (*rpc.GateResponse, error) {
	release, err := s.enter(ctx, request.GetWorkspaceInstanceId())
	if err != nil {
		return nil, err
	}
	defer release()
	fd, err := s.instance(request.WorkspaceInstanceId, false)
	if err != nil {
		return nil, err
	}
	defer syscall.Close(fd)
	gates, err := gateRecords(fd, request.WorkspaceInstanceId)
	if err != nil {
		return nil, storageError(err)
	}
	if len(gates) == 0 || gates[len(gates)-1].State != "FROZEN" {
		return nil, status.Error(codes.NotFound, "freeze absent")
	}
	receipt := gates[len(gates)-1]
	if err := verifyFrozen(ctx, fd, receipt); err != nil {
		return nil, storageError(err)
	}
	return receipt, nil
}

// StopSystemTask persists lifecycle cancellation behind a closed/frozen gate.
// It needs no page credential or expired answering deadline; the caller is the
// authenticated Java lifecycle owner. Unknown execution still remains isolated.
func (s *Service) StopSystemTask(ctx context.Context, request *rpc.QueryTaskRequest) (*rpc.StopTaskResponse, error) {
	release, err := s.enter(ctx, request.GetWorkspaceInstanceId())
	if err != nil {
		return nil, err
	}
	defer release()
	if !validTaskIdentity(request.ExerciseId, request.TaskId, request.ExecutionInstanceId, request.WorkspaceInstanceId) {
		return nil, status.Error(codes.InvalidArgument, "invalid lifecycle task")
	}
	if s.executor == nil {
		return nil, status.Error(codes.Unavailable, "execution is not configured")
	}
	fd, err := s.instance(request.WorkspaceInstanceId, false)
	if err != nil {
		return nil, err
	}
	defer syscall.Close(fd)
	gates, err := gateRecords(fd, request.WorkspaceInstanceId)
	if err != nil {
		return nil, storageError(err)
	}
	// Trusted exact-task cancellation only removes work. Ordinary owner/Copilot
	// stops must also work while OPEN, without revoking unrelated editor authority.
	// The execution registry still verifies the complete immutable task identity.
	if len(gates) == 0 {
		return nil, status.Error(codes.FailedPrecondition, "lifecycle cancellation requires initialized gate")
	}
	result, err := s.executor.Stop(ctx, execution.TaskIdentity{ExerciseID: request.ExerciseId, TaskID: request.TaskId, ExecutionID: request.ExecutionInstanceId, WorkspaceID: request.WorkspaceInstanceId})
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

func validTestPurpose(request *rpc.StartTestRequest) bool {
	if request.Purpose == "MANUAL" {
		return request.FinalVerification == nil
	}
	return request.Purpose == "FINAL_VERIFICATION" && validFinalAuthority(request.FinalVerification, request.NotAfterUnixMillis)
}

func validFinalAuthority(final *rpc.FinalVerificationAuthority, notAfter int64) bool {
	return final != nil && uuid.MatchString(final.FinalizationId) && uuid.MatchString(final.AttemptId) &&
		uuid.MatchString(final.FreezeOperationId) && final.DeadlineUnixMillis > 0 && notAfter > 0 && notAfter <= final.DeadlineUnixMillis
}

func sameFinalAuthority(input execution.Input, request *rpc.StartTestRequest) bool {
	purpose := input.Purpose
	// Registrations written before system final verification existed are manual.
	if purpose == "" {
		purpose = "MANUAL"
	}
	if purpose != request.Purpose {
		return false
	}
	final := request.FinalVerification
	if final == nil {
		return input.FinalizationID == "" && input.AttemptID == "" && input.FreezeID == "" && input.FinalDeadline == 0
	}
	return input.FinalizationID == final.FinalizationId && input.AttemptID == final.AttemptId && input.FreezeID == final.FreezeOperationId && input.FinalDeadline == final.DeadlineUnixMillis
}

// copyGateAuthorized keeps system read-only admission separate from answering.
func copyGateAuthorized(gates []*rpc.GateResponse, epoch, revision uint64, notAfter int64, final *rpc.FinalVerificationAuthority) bool {
	if len(gates) == 0 {
		return false
	}
	gate := gates[len(gates)-1]
	if gate.Epoch != epoch {
		return false
	}
	if final == nil {
		return gate.State == "OPEN"
	}
	return validFinalAuthority(final, notAfter) &&
		gate.State == "FROZEN" && gate.TransitionId == final.FreezeOperationId && gate.FrozenRevision == revision
}

func verifyFrozen(ctx context.Context, fd int, receipt *rpc.GateResponse) error {
	record, _, err := latest(fd, receipt.WorkspaceInstanceId)
	if err != nil {
		return err
	}
	if !record.Snapshot.Prepared || record.Snapshot.Cancelled || record.Snapshot.Revision != receipt.FrozenRevision {
		return status.Error(codes.DataLoss, "frozen tree identity changed")
	}
	return verifyPreparedTree(ctx, fd, record)
}
