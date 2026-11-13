package workspace

import (
	"context"
	"crypto/rand"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"strings"
	"syscall"
	"time"

	rpc "codecraft.local/sandbox/internal/gen"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"
	"google.golang.org/protobuf/proto"
)

var errNoTestCopy = errors.New("test copy absent")

type testCopyRecord struct {
	Receipt *rpc.TestCopyReceipt `json:"receipt"`
	Hashes  map[string]string    `json:"hashes"`
}

// PrepareTestCopy publishes an independent, integrity-checked MANUAL input under
// the same coordinator as ApplyBatch and CloseGate. It does not reserve an
// execution slot or authorize container creation; the executor must do both.
func (s *Service) PrepareTestCopy(ctx context.Context, request *rpc.PrepareTestCopyRequest) (*rpc.TestCopyReceipt, error) {
	release, err := s.enter(ctx)
	if err != nil {
		return nil, err
	}
	defer release()
	return s.prepareTestCopyLocked(ctx, request)
}

func (s *Service) prepareTestCopyLocked(ctx context.Context, request *rpc.PrepareTestCopyRequest) (*rpc.TestCopyReceipt, error) {
	if !uuid.MatchString(request.ExecutionInstanceId) {
		return nil, status.Error(codes.InvalidArgument, "invalid execution instance")
	}
	fd, err := s.instance(request.WorkspaceInstanceId, false)
	if err != nil {
		return nil, err
	}
	defer syscall.Close(fd)
	if s.executor != nil {
		cancelled, err := s.executor.InputCancelled(ctx, request.WorkspaceInstanceId, request.ExecutionInstanceId)
		if err != nil {
			return nil, storageError(err)
		}
		if cancelled {
			return nil, status.Error(codes.FailedPrecondition, "input preparation cancelled")
		}
	}
	existing, err := readTestCopy(ctx, fd, request.WorkspaceInstanceId, request.ExecutionInstanceId)
	if err == nil {
		if existing.Revision != request.ExpectedRevision {
			return nil, status.Error(codes.AlreadyExists, "execution input changed")
		}
		return existing, nil
	}
	if !errors.Is(err, errNoTestCopy) {
		return nil, storageError(err)
	}
	deadline := time.UnixMilli(request.NotAfterUnixMillis)
	if request.NotAfterUnixMillis <= 0 || !s.now().Before(deadline) {
		return nil, status.Error(codes.FailedPrecondition, "copy authorization expired")
	}
	ctx, cancel := context.WithDeadline(ctx, deadline)
	defer cancel()
	gates, err := gateRecords(fd, request.WorkspaceInstanceId)
	if err != nil {
		return nil, storageError(err)
	}
	if !copyGateAuthorized(gates, request.GateEpoch, request.ExpectedRevision, request.NotAfterUnixMillis, request.FinalVerification) {
		return nil, status.Error(codes.FailedPrecondition, "copy gate closed or stale")
	}
	current, commit, err := latest(fd, request.WorkspaceInstanceId)
	if err != nil {
		return nil, storageError(err)
	}
	if current.Snapshot.Cancelled || !current.Snapshot.Prepared {
		return nil, status.Error(codes.FailedPrecondition, "workspace unavailable")
	}
	if current.Snapshot.Revision != request.ExpectedRevision {
		return nil, status.Error(codes.Aborted, "revision conflict")
	}
	files := make(map[string]string, len(current.Hashes))
	for _, path := range current.Snapshot.Paths {
		if err = ctx.Err(); err != nil {
			return nil, storageError(err)
		}
		content, readErr := readPath(fd, commit+"/tree/"+path)
		if readErr != nil {
			return nil, storageError(readErr)
		}
		if sum(content) != current.Hashes[path] {
			return nil, status.Error(codes.DataLoss, "input integrity check failed")
		}
		files[path] = string(content)
	}
	receipt := &rpc.TestCopyReceipt{WorkspaceInstanceId: request.WorkspaceInstanceId, ExecutionInstanceId: request.ExecutionInstanceId,
		Revision: current.Snapshot.Revision, Problem: proto.Clone(current.Snapshot.Problem).(*rpc.ProblemIdentity),
		RelativePath: request.WorkspaceInstanceId + "/copy-" + request.ExecutionInstanceId + "/tree"}
	if err = publishTestCopy(ctx, fd, &testCopyRecord{Receipt: receipt, Hashes: current.Hashes}, files); err != nil {
		return nil, storageError(err)
	}
	return receipt, nil
}

// QueryTestCopy observes durable input without creating a copy or execution.
// A missing receipt is not permission to replay an uncertain execution start.
func (s *Service) QueryTestCopy(ctx context.Context, request *rpc.QueryTestCopyRequest) (*rpc.TestCopyReceipt, error) {
	release, err := s.enter(ctx)
	if err != nil {
		return nil, err
	}
	defer release()
	if !uuid.MatchString(request.ExecutionInstanceId) {
		return nil, status.Error(codes.InvalidArgument, "invalid execution instance")
	}
	fd, err := s.instance(request.WorkspaceInstanceId, false)
	if err != nil {
		return nil, err
	}
	defer syscall.Close(fd)
	receipt, err := readTestCopy(ctx, fd, request.WorkspaceInstanceId, request.ExecutionInstanceId)
	if errors.Is(err, errNoTestCopy) {
		return nil, status.Error(codes.NotFound, "test copy not found")
	}
	if err != nil {
		return nil, storageError(err)
	}
	return receipt, nil
}

func readTestCopy(ctx context.Context, parent int, instanceID, executionID string) (*rpc.TestCopyReceipt, error) {
	name := "copy-" + executionID
	fd, err := directory(parent, name, false)
	if errors.Is(err, syscall.ENOENT) {
		return nil, errNoTestCopy
	}
	if err != nil {
		return nil, err
	}
	defer syscall.Close(fd)
	if _, err := readPath(fd, "released"); err == nil || !errors.Is(err, syscall.ENOENT) {
		return nil, fmt.Errorf("test input already released or release unconfirmed")
	}
	// Once the committed directory exists, missing metadata/content is corruption,
	// not absence. Never recreate a damaged input under an existing execution ID.
	data, err := readPath(fd, "receipt.json")
	if err != nil {
		return nil, fmt.Errorf("copy receipt unavailable: %w", err)
	}
	var record testCopyRecord
	if err = json.Unmarshal(data, &record); err != nil {
		return nil, err
	}
	r := record.Receipt
	if r == nil || r.WorkspaceInstanceId != instanceID || r.ExecutionInstanceId != executionID || r.Problem == nil ||
		r.RelativePath != instanceID+"/"+name+"/tree" || len(record.Hashes) == 0 {
		return nil, fmt.Errorf("invalid copy receipt")
	}
	for path, hash := range record.Hashes {
		if err = ctx.Err(); err != nil {
			return nil, err
		}
		if !validPath(path) || !digest.MatchString(hash) {
			return nil, fmt.Errorf("invalid copy manifest")
		}
		content, err := readPath(fd, "tree/"+path)
		if err != nil {
			return nil, fmt.Errorf("copy content unavailable: %w", err)
		}
		if sum(content) != hash {
			return nil, fmt.Errorf("copy integrity failure")
		}
	}
	return r, nil
}

// Any copy-shaped entry, even a damaged one, protects execution evidence.
func hasTestCopies(parent int) (bool, error) {
	fd, err := directory(parent, ".", false)
	if err != nil {
		return false, err
	}
	file := os.NewFile(uintptr(fd), "workspace copy inventory")
	defer file.Close()
	for {
		entries, err := file.ReadDir(64)
		if err != nil && !errors.Is(err, io.EOF) {
			return false, err
		}
		for _, entry := range entries {
			if strings.HasPrefix(entry.Name(), "copy-") {
				return true, nil
			}
		}
		if errors.Is(err, io.EOF) {
			return false, nil
		}
	}
}

func publishTestCopy(ctx context.Context, parent int, record *testCopyRecord, files map[string]string) (result error) {
	owned, err := os.OpenRoot(fmt.Sprintf("/proc/self/fd/%d", parent))
	if err != nil {
		return err
	}
	defer owned.Close()
	stage := "stage-copy-" + rand.Text()
	if err = syscall.Mkdirat(parent, stage, 0700); err != nil {
		return err
	}
	defer func() { result = errors.Join(result, owned.RemoveAll(stage)) }()
	fd, err := directory(parent, stage, false)
	if err != nil {
		return err
	}
	defer syscall.Close(fd)
	for path, content := range files {
		if err = ctx.Err(); err != nil {
			return err
		}
		if err = writePath(fd, "tree/"+path, []byte(content)); err != nil {
			return err
		}
	}
	data, err := json.Marshal(record)
	if err != nil {
		return err
	}
	if len(data) > fileLimit {
		return fmt.Errorf("copy receipt too large")
	}
	if err = writePath(fd, "receipt.json", data); err != nil {
		return err
	}
	if err = syscall.Fsync(fd); err != nil {
		return err
	}
	if err = ctx.Err(); err != nil {
		return err
	}
	if deadline, ok := ctx.Deadline(); ok && !time.Now().Before(deadline) {
		return context.DeadlineExceeded
	}
	if err = syscall.Renameat(parent, stage, parent, "copy-"+record.Receipt.ExecutionInstanceId); err != nil {
		return err
	}
	return syscall.Fsync(parent)
}
