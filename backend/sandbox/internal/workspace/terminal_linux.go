package workspace

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"syscall"
	"time"

	"codecraft.local/sandbox/internal/execution"
	rpc "codecraft.local/sandbox/internal/gen"
	"google.golang.org/grpc"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"
)

func (s *Service) quiesceTerminals(ctx context.Context, workspace string) error {
	if s.executor == nil {
		return nil
	}
	cleanup, cancel := context.WithTimeout(context.WithoutCancel(ctx), 15*time.Second)
	defer cancel()
	if err := s.executor.QuiesceTerminals(cleanup, workspace); err != nil {
		return status.Error(codes.Unavailable, "terminal process quiescence unconfirmed")
	}
	return nil
}

func (s *Service) terminalAuthority(ctx context.Context, open *rpc.TerminalOpen) (execution.TaskIdentity, error) {
	release, err := s.enter(ctx)
	if err != nil {
		return execution.TaskIdentity{}, err
	}
	defer release()
	id := open.GetIdentity()
	if id == nil || !validTaskIdentity(id.ExerciseId, id.TaskId, id.ExecutionInstanceId, id.WorkspaceInstanceId) || open.Cols < 1 || open.Cols > 500 || open.Rows < 1 || open.Rows > 300 || open.Term != "xterm-256color" {
		return execution.TaskIdentity{}, status.Error(codes.InvalidArgument, "invalid terminal open")
	}
	identity := execution.TaskIdentity{ExerciseID: id.ExerciseId, TaskID: id.TaskId, ExecutionID: id.ExecutionInstanceId, WorkspaceID: id.WorkspaceInstanceId}
	if s.executor == nil {
		return identity, status.Error(codes.Unavailable, "execution unavailable")
	}
	record, err := s.queryCommandLocked(ctx, id)
	if err != nil {
		return identity, err
	}
	if record.Input.Command == nil || !record.Input.Command.Terminal || record.Input.GateEpoch != open.GateEpoch || record.Quiescent {
		return identity, status.Error(codes.FailedPrecondition, "terminal is not active")
	}
	fd, err := s.instance(identity.WorkspaceID, false)
	if err != nil {
		return identity, err
	}
	defer syscall.Close(fd)
	gates, err := gateRecords(fd, identity.WorkspaceID)
	if err != nil {
		return identity, storageError(err)
	}
	if len(gates) == 0 || gates[len(gates)-1].State != "OPEN" || gates[len(gates)-1].Epoch != open.GateEpoch || !s.now().Before(record.Input.NotAfter) {
		return identity, status.Error(codes.PermissionDenied, "terminal authority ended")
	}
	return identity, nil
}

// SyncTerminal records explicit commit intent while the original gate is open,
// then orders process termination. The normal command finalizer owns publication.
func (s *Service) SyncTerminal(ctx context.Context, request *rpc.QueryTaskRequest) (*rpc.CommandSnapshot, error) {
	release, err := s.enter(ctx)
	if err != nil {
		return nil, err
	}
	defer release()
	record, err := s.queryCommandLocked(ctx, request)
	if err != nil {
		return nil, err
	}
	if record.Input.Command == nil || !record.Input.Command.Terminal {
		return nil, status.Error(codes.InvalidArgument, "not a terminal")
	}
	parent, err := s.instance(request.WorkspaceInstanceId, false)
	if err != nil {
		return nil, err
	}
	defer syscall.Close(parent)
	receipt, fd, err := openCommand(parent, request.WorkspaceInstanceId, request.ExecutionInstanceId)
	if err != nil {
		return nil, storageError(err)
	}
	defer syscall.Close(fd)
	if !sameCommandInput(receipt.Input, record.Input) {
		return nil, status.Error(codes.DataLoss, "terminal receipt identity changed")
	}
	if receipt.Synchronization != "PENDING" {
		return commandSnapshot(parent, record)
	}
	if err = s.checkTaskGate(&rpc.StartTestRequest{WorkspaceInstanceId: record.Input.WorkspaceID, ExpectedRevision: record.Input.Revision, GateEpoch: record.Input.GateEpoch, NotAfterUnixMillis: record.Input.NotAfter.UnixMilli()}); err != nil {
		return nil, err
	}
	receipt.TerminalSyncRequested = true
	if err = saveCommand(fd, receipt); err != nil {
		return nil, storageError(err)
	}
	_, err = s.executor.Stop(ctx, execution.TaskIdentity{ExerciseID: request.ExerciseId, TaskID: request.TaskId, ExecutionID: request.ExecutionInstanceId, WorkspaceID: request.WorkspaceInstanceId})
	if err != nil {
		return nil, storageError(err)
	}
	return commandSnapshot(parent, record)
}

// AttachTerminal attaches to one admitted command, never creates a second owner.
// All I/O is bounded and EOF/cancellation orders a durable stop. The ordinary
// command finalizer captures files only after all container processes are dead.
func (s *Service) AttachTerminal(stream grpc.BidiStreamingServer[rpc.TerminalClientFrame, rpc.TerminalServerFrame]) (resultErr error) {
	release, err := s.enter(stream.Context())
	if err != nil {
		return err
	}
	release()
	first, err := stream.Recv()
	if err != nil {
		return err
	}
	open := first.GetOpen()
	if open == nil {
		return status.Error(codes.InvalidArgument, "open must be first")
	}
	identity, err := s.terminalAuthority(stream.Context(), open)
	if err != nil {
		return err
	}
	var terminal *execution.Terminal
	ready := time.NewTimer(10 * time.Second)
	defer ready.Stop()
	for {
		terminal, err = s.executor.Terminal(identity)
		if err == nil {
			break
		}
		if !errors.Is(err, execution.ErrBusy) {
			return status.Error(codes.FailedPrecondition, "terminal ownership conflict")
		}
		select {
		case <-stream.Context().Done():
			return stream.Context().Err()
		case <-ready.C:
			return status.Error(codes.Unavailable, "terminal not ready or already attached")
		case <-time.After(25 * time.Millisecond):
		}
	}
	defer func() {
		ctx, cancel := context.WithTimeout(context.WithoutCancel(stream.Context()), 15*time.Second)
		defer cancel()
		if err := s.executor.QuiesceTerminal(ctx, identity); err != nil {
			slog.Warn("terminal cleanup requires reconciliation", "execution_id", identity.ExecutionID)
			if resultErr == nil {
				resultErr = status.Error(codes.Unavailable, "terminal cleanup unconfirmed")
			}
		}
	}()
	if err = terminal.Resize(stream.Context(), open.Cols, open.Rows); err != nil {
		return status.Error(codes.Unavailable, "PTY resize failed")
	}
	if err = stream.Send(&rpc.TerminalServerFrame{Frame: &rpc.TerminalServerFrame_Opened{Opened: true}}); err != nil {
		return err
	}
	incoming := make(chan *rpc.TerminalClientFrame, 1)
	readErrors := make(chan error, 1)
	go func() {
		for {
			frame, err := stream.Recv()
			if err != nil {
				readErrors <- err
				return
			}
			select {
			case incoming <- frame:
			case <-stream.Context().Done():
				return
			}
		}
	}()
	ticker := time.NewTicker(250 * time.Millisecond)
	defer ticker.Stop()
	stopping := false
	for {
		select {
		case <-stream.Context().Done():
			return stream.Context().Err()
		case err := <-readErrors:
			if errors.Is(err, io.EOF) {
				return nil
			}
			return err
		case frame := <-incoming:
			if stopping {
				continue
			}
			if _, err = s.terminalAuthority(stream.Context(), open); err != nil {
				return err
			}
			switch value := frame.Frame.(type) {
			case *rpc.TerminalClientFrame_Stdin:
				err = terminal.Input(stream.Context(), value.Stdin)
			case *rpc.TerminalClientFrame_Resize:
				err = terminal.Resize(stream.Context(), value.Resize.GetCols(), value.Resize.GetRows())
			case *rpc.TerminalClientFrame_Heartbeat: // Transport liveness does not extend the idle/process/authority deadlines.
			case *rpc.TerminalClientFrame_Sync:
				_, err = s.SyncTerminal(stream.Context(), open.Identity)
				stopping = true
			default:
				return status.Error(codes.InvalidArgument, "unexpected terminal frame")
			}
			if err != nil {
				return status.Error(codes.FailedPrecondition, "terminal input rejected")
			}
		case data := <-terminal.Output():
			if err = stream.Send(&rpc.TerminalServerFrame{Frame: &rpc.TerminalServerFrame_Output{Output: data}}); err != nil {
				return err
			}
		case <-ticker.C:
			record, err := s.executor.Query(stream.Context(), identity.ExecutionID)
			if err != nil {
				return storageError(err)
			}
			if record.Quiescent && record.CopyCleanup == "SUCCEEDED" {
				snapshot, err := s.QueryCommand(stream.Context(), open.Identity)
				if err != nil {
					return err
				}
				for {
					select {
					case data := <-terminal.Output():
						if err := stream.Send(&rpc.TerminalServerFrame{Frame: &rpc.TerminalServerFrame_Output{Output: data}}); err != nil {
							return err
						}
					default:
						return stream.Send(&rpc.TerminalServerFrame{Frame: &rpc.TerminalServerFrame_Exit{Exit: snapshot}})
					}
				}
			}
			if !stopping {
				if _, err = s.terminalAuthority(stream.Context(), open); err != nil {
					if _, stopErr := s.executor.Stop(stream.Context(), identity); stopErr != nil {
						return storageError(stopErr)
					}
					stopping = true
				}
			}
		}
	}
}
