package workspace

import (
	"context"
	"crypto/rand"
	"encoding/json"
	"fmt"
	"os"
	"sort"
	"strconv"
	"strings"
	"syscall"

	rpc "codecraft.local/sandbox/internal/gen"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"
)

// CloseGate serializes with all file publications and durably fences late writes.
func (s *Service) CloseGate(ctx context.Context, request *rpc.CloseGateRequest) (*rpc.GateResponse, error) {
	release, err := s.enter(ctx)
	if err != nil {
		return nil, err
	}
	defer release()
	if !uuid.MatchString(request.TransitionId) {
		return nil, status.Error(codes.InvalidArgument, "invalid transition")
	}
	fd, err := s.instance(request.WorkspaceInstanceId, true)
	if err != nil {
		return nil, err
	}
	defer syscall.Close(fd)
	gates, err := gateRecords(fd, request.WorkspaceInstanceId)
	if err != nil {
		return nil, storageError(err)
	}
	for _, gate := range gates {
		if gate.TransitionId == request.TransitionId && gate.State == "CLOSED" {
			if gate.Epoch-1 != request.ExpectedEpoch {
				return nil, status.Error(codes.AlreadyExists, "transition payload changed")
			}
			if err := s.quiesceTerminals(ctx, request.WorkspaceInstanceId); err != nil {
				return nil, err
			}
			return gate, nil
		}
	}
	if len(gates) > 0 && gates[len(gates)-1].State == "FROZEN" {
		return nil, status.Error(codes.FailedPrecondition, "workspace frozen")
	}
	current := uint64(0)
	if len(gates) > 0 {
		current = gates[len(gates)-1].Epoch
	}
	if current != request.ExpectedEpoch || current == ^uint64(0) {
		return nil, status.Error(codes.FailedPrecondition, "gate epoch conflict")
	}
	gate := &rpc.GateResponse{WorkspaceInstanceId: request.WorkspaceInstanceId, TransitionId: request.TransitionId, Epoch: current + 1, State: "CLOSED"}
	if err = publishGate(ctx, fd, gate); err != nil {
		return nil, storageError(err)
	}
	if err := s.quiesceTerminals(ctx, request.WorkspaceInstanceId); err != nil {
		return nil, err
	}
	return gate, nil
}

// OpenGate activates only the exact closed transition. Replays return historical
// receipts without changing the current gate; a delayed Open cannot undo a Close.
func (s *Service) OpenGate(ctx context.Context, request *rpc.CloseGateRequest) (*rpc.GateResponse, error) {
	release, err := s.enter(ctx)
	if err != nil {
		return nil, err
	}
	defer release()
	if !uuid.MatchString(request.TransitionId) {
		return nil, status.Error(codes.InvalidArgument, "invalid transition")
	}
	fd, err := s.instance(request.WorkspaceInstanceId, false)
	if err != nil {
		return nil, err
	}
	defer syscall.Close(fd)
	r, _, err := latest(fd, request.WorkspaceInstanceId)
	if err != nil {
		return nil, storageError(err)
	}
	if r.Snapshot.Cancelled {
		return nil, status.Error(codes.FailedPrecondition, "workspace cancelled")
	}
	gates, err := gateRecords(fd, request.WorkspaceInstanceId)
	if err != nil {
		return nil, storageError(err)
	}
	if len(gates) > 0 && gates[len(gates)-1].State == "FROZEN" {
		return nil, status.Error(codes.FailedPrecondition, "workspace frozen")
	}
	for _, gate := range gates {
		if gate.TransitionId == request.TransitionId && gate.State == "OPEN" {
			if gate.Epoch-1 != request.ExpectedEpoch {
				return nil, status.Error(codes.AlreadyExists, "transition payload changed")
			}
			return gate, nil
		}
	}
	if len(gates) == 0 {
		return nil, status.Error(codes.FailedPrecondition, "closed transition required")
	}
	current := gates[len(gates)-1]
	if current.State != "CLOSED" || current.TransitionId != request.TransitionId || current.Epoch != request.ExpectedEpoch || current.Epoch == ^uint64(0) {
		return nil, status.Error(codes.FailedPrecondition, "gate epoch conflict")
	}
	gate := &rpc.GateResponse{WorkspaceInstanceId: request.WorkspaceInstanceId, TransitionId: request.TransitionId, Epoch: current.Epoch + 1, State: "OPEN"}
	if err = publishGate(ctx, fd, gate); err != nil {
		return nil, storageError(err)
	}
	return gate, nil
}

// QueryGate returns durable fencing authority, not a process-local cache.
func (s *Service) QueryGate(ctx context.Context, request *rpc.QueryWorkspaceRequest) (*rpc.GateResponse, error) {
	release, err := s.enter(ctx)
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
	if len(gates) > 0 {
		return gates[len(gates)-1], nil
	}
	return &rpc.GateResponse{WorkspaceInstanceId: request.WorkspaceInstanceId, State: "CLOSED"}, nil
}

func gateRecords(parent int, expectedID string) ([]*rpc.GateResponse, error) {
	fd, err := syscall.Openat(parent, ".", syscall.O_RDONLY|syscall.O_DIRECTORY|syscall.O_CLOEXEC, 0)
	if err != nil {
		return nil, err
	}
	file := os.NewFile(uintptr(fd), "gate directory")
	entries, err := file.ReadDir(-1)
	closeErr := file.Close()
	if err != nil {
		return nil, err
	}
	if closeErr != nil {
		return nil, closeErr
	}
	gates := []*rpc.GateResponse{}
	for _, entry := range entries {
		name := entry.Name()
		if !strings.HasPrefix(name, "gate-") {
			continue
		}
		if len(name) != 30 || !strings.HasSuffix(name, ".json") {
			return nil, fmt.Errorf("invalid gate name")
		}
		epoch, err := strconv.ParseUint(name[5:25], 10, 64)
		if err != nil || epoch == 0 {
			return nil, fmt.Errorf("invalid gate epoch")
		}
		data, err := readPath(parent, name)
		if err != nil {
			return nil, err
		}
		var gate rpc.GateResponse
		if err = json.Unmarshal(data, &gate); err != nil {
			return nil, err
		}
		if gate.Epoch != epoch || gate.WorkspaceInstanceId != expectedID || !uuid.MatchString(gate.TransitionId) || (gate.State != "CLOSED" && gate.State != "OPEN" && gate.State != "FROZEN") {
			return nil, fmt.Errorf("invalid gate receipt")
		}
		gates = append(gates, &gate)
	}
	sort.Slice(gates, func(i, j int) bool { return gates[i].Epoch < gates[j].Epoch })
	// Missing history is corruption, not permission to reset the epoch.
	for index, gate := range gates {
		if gate.Epoch != uint64(index)+1 {
			return nil, fmt.Errorf("missing gate receipt")
		}
		if gate.State == "FROZEN" && (index == 0 || gates[index-1].State != "CLOSED" || index != len(gates)-1) {
			return nil, fmt.Errorf("invalid freeze ordering")
		}
		if gate.State != "FROZEN" && gate.FrozenRevision != 0 {
			return nil, fmt.Errorf("unexpected frozen revision")
		}
		if gate.State == "OPEN" && (index == 0 || gates[index-1].State != "CLOSED" || gates[index-1].TransitionId != gate.TransitionId) {
			return nil, fmt.Errorf("open gate without matching close")
		}
	}
	return gates, nil
}

func publishGate(ctx context.Context, parent int, gate *rpc.GateResponse) (result error) {
	owned, err := os.OpenRoot(fmt.Sprintf("/proc/self/fd/%d", parent))
	if err != nil {
		return err
	}
	defer owned.Close()
	stage := "stage-gate-" + rand.Text()
	defer func() {
		if err := owned.RemoveAll(stage); result == nil {
			result = err
		}
	}()
	data, err := json.Marshal(gate)
	if err != nil {
		return err
	}
	if err = writePath(parent, stage, data); err != nil {
		return err
	}
	if err = ctx.Err(); err != nil {
		return err
	}
	if err = syscall.Renameat(parent, stage, parent, fmt.Sprintf("gate-%020d.json", gate.Epoch)); err != nil {
		return err
	}
	return syscall.Fsync(parent)
}
