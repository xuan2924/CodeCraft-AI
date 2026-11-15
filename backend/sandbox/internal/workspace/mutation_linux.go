package workspace

import (
	"context"
	"encoding/json"
	"errors"
	"sort"
	"strconv"
	"strings"
	"syscall"
	"time"
	"unicode/utf8"

	rpc "codecraft.local/sandbox/internal/gen"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"
)

// Current-content limits do not replace the cross-storage budget and evidence GC.
const maxWorkspaceFiles = 200
const maxWorkspaceBytes = 10 * 1024 * 1024

// ApplyBatch publishes the entire tree and its receipt together. The service's
// exclusive storage ownership and coordinator also cover CloseGate/OpenGate.
func (s *Service) ApplyBatch(ctx context.Context, request *rpc.ApplyBatchRequest) (*rpc.MutationReceipt, error) {
	release, err := s.enter(ctx)
	if err != nil {
		return nil, err
	}
	defer release()
	return s.applyBatchLocked(ctx, request)
}

func (s *Service) applyBatchLocked(ctx context.Context, request *rpc.ApplyBatchRequest) (*rpc.MutationReceipt, error) {
	fingerprint, err := mutationFingerprint(request)
	if err != nil {
		return nil, err
	}
	fd, err := s.instance(request.WorkspaceInstanceId, false)
	if err != nil {
		return nil, err
	}
	defer syscall.Close(fd)
	r, commit, err := latest(fd, request.WorkspaceInstanceId)
	if err != nil {
		return nil, storageError(err)
	}
	if r.Snapshot.Cancelled {
		return nil, status.Error(codes.FailedPrecondition, "workspace cancelled")
	}
	if receipt := r.Mutations[request.RequestId]; receipt != nil {
		if receipt.Fingerprint != fingerprint {
			return nil, status.Error(codes.AlreadyExists, "request payload changed")
		}
		if err = verifyPreparedTree(ctx, fd, r, commit); err != nil {
			return nil, storageError(err)
		}
		return receipt, nil
	}
	gates, err := gateRecords(fd, request.WorkspaceInstanceId)
	if err != nil {
		return nil, storageError(err)
	}
	if len(gates) == 0 || gates[len(gates)-1].State != "OPEN" || gates[len(gates)-1].Epoch != request.GateEpoch {
		return nil, status.Error(codes.FailedPrecondition, "write gate is not active")
	}
	deadline := time.UnixMilli(request.NotAfterUnixMillis)
	if request.NotAfterUnixMillis <= 0 || !s.now().Before(deadline) {
		return nil, status.Error(codes.FailedPrecondition, "write authorization expired")
	}
	ctx, cancel := context.WithDeadline(ctx, deadline)
	defer cancel()
	if r.Snapshot.Revision != request.ExpectedRevision {
		return nil, status.Error(codes.Aborted, "revision conflict")
	}
	files := make(map[string]string, len(r.Hashes))
	for _, path := range r.Snapshot.Paths {
		if err = ctx.Err(); err != nil {
			return nil, storageError(err)
		}
		content, readErr := readPath(fd, commit+"/tree/"+path)
		if readErr != nil {
			return nil, storageError(readErr)
		}
		if sum(content) != r.Hashes[path] || !utf8.Valid(content) {
			return nil, status.Error(codes.DataLoss, "committed tree integrity failure")
		}
		files[path] = string(content)
	}
	changed := false
	for _, change := range request.Changes {
		previous, exists := files[change.Path]
		switch change.Kind {
		case "CREATE":
			if exists {
				return nil, status.Error(codes.AlreadyExists, "file already exists")
			}
			files[change.Path] = change.Content
			changed = true
		case "UPDATE":
			if !exists {
				return nil, status.Error(codes.NotFound, "file not found")
			}
			if previous != change.Content {
				files[change.Path] = change.Content
				changed = true
			}
		case "DELETE":
			if !exists {
				return nil, status.Error(codes.NotFound, "file not found")
			}
			delete(files, change.Path)
			changed = true
		}
	}
	if err = validateContentLimits(files); err != nil {
		return nil, err
	}
	// A file may not also be a parent directory, including across batch entries.
	for path := range files {
		for parent := path; strings.Contains(parent, "/"); {
			parent = parent[:strings.LastIndex(parent, "/")]
			if _, exists := files[parent]; exists {
				return nil, status.Error(codes.InvalidArgument, "file and directory conflict")
			}
		}
	}
	sequence, err := strconv.ParseUint(commit[7:], 10, 64)
	if err != nil || sequence == ^uint64(0) || (changed && r.Snapshot.Revision == ^uint64(0)) {
		return nil, status.Error(codes.ResourceExhausted, "workspace counter exhausted")
	}
	if changed {
		r.Snapshot.Revision++
	}
	receipt := &rpc.MutationReceipt{WorkspaceInstanceId: request.WorkspaceInstanceId, RequestId: request.RequestId,
		OperationId: request.OperationId, Fingerprint: fingerprint, Revision: r.Snapshot.Revision, Changed: changed}
	if r.Mutations == nil {
		r.Mutations = map[string]*rpc.MutationReceipt{}
	}
	r.Mutations[request.RequestId] = receipt
	r.Hashes = map[string]string{}
	r.Snapshot.Paths = nil
	for path, content := range files {
		r.Hashes[path] = sum([]byte(content))
		r.Snapshot.Paths = append(r.Snapshot.Paths, path)
	}
	sort.Strings(r.Snapshot.Paths)
	encoded, err := json.Marshal(r)
	if err != nil {
		return nil, storageError(err)
	}
	// Never evict a receipt to admit a new write. Retention/GC requires Java's references.
	if len(encoded) > fileLimit {
		return nil, status.Error(codes.ResourceExhausted, "workspace receipt capacity exhausted")
	}
	if err = publish(ctx, fd, sequence+1, r, files, s.beforeMutationPublish, s.now, deadline); err != nil {
		return nil, storageError(err)
	}
	return receipt, nil
}

// QueryMutation distinguishes an absent committed receipt from unavailable or
// corrupt authority. Absence alone does not prove a delayed Apply cannot commit.
func (s *Service) QueryMutation(ctx context.Context, request *rpc.QueryMutationRequest) (*rpc.MutationReceipt, error) {
	release, err := s.enter(ctx)
	if err != nil {
		return nil, err
	}
	defer release()
	if !uuid.MatchString(request.RequestId) {
		return nil, status.Error(codes.InvalidArgument, "invalid request")
	}
	fd, err := s.instance(request.WorkspaceInstanceId, false)
	if err != nil {
		return nil, err
	}
	defer syscall.Close(fd)
	r, commit, err := latest(fd, request.WorkspaceInstanceId)
	if errors.Is(err, errNoCommit) {
		return nil, status.Error(codes.NotFound, "no committed mutation")
	}
	if err != nil {
		return nil, storageError(err)
	}
	if r.Snapshot.Cancelled {
		return nil, status.Error(codes.FailedPrecondition, "workspace cancelled")
	}
	if err = verifyPreparedTree(ctx, fd, r, commit); err != nil {
		return nil, storageError(err)
	}
	receipt := r.Mutations[request.RequestId]
	if receipt == nil {
		return nil, status.Error(codes.NotFound, "no committed mutation")
	}
	return receipt, nil
}

func mutationFingerprint(request *rpc.ApplyBatchRequest) (string, error) {
	if !uuid.MatchString(request.RequestId) || !uuid.MatchString(request.OperationId) || len(request.Changes) == 0 {
		return "", status.Error(codes.InvalidArgument, "invalid mutation identity or empty batch")
	}
	if len(request.Changes) > 2*maxWorkspaceFiles {
		return "", status.Error(codes.ResourceExhausted, "batch exceeds file limit")
	}
	seen := map[string]bool{}
	changes := append([]*rpc.FileChange(nil), request.Changes...)
	for _, change := range changes {
		if change == nil || !validPath(change.Path) || seen[change.Path] {
			return "", status.Error(codes.InvalidArgument, "invalid or duplicate path")
		}
		seen[change.Path] = true
		if !(strings.HasPrefix(change.Path, "src/main/java/") || strings.HasPrefix(change.Path, "src/test/java/")) {
			return "", status.Error(codes.PermissionDenied, "path is not editable")
		}
		if change.Kind != "CREATE" && change.Kind != "UPDATE" && change.Kind != "DELETE" {
			return "", status.Error(codes.InvalidArgument, "invalid change kind")
		}
		if (change.Kind == "DELETE" && change.Content != "") || !utf8.ValidString(change.Content) || strings.ContainsRune(change.Content, 0) {
			return "", status.Error(codes.InvalidArgument, "text content required")
		}
		if len(change.Content) > fileLimit {
			return "", status.Error(codes.ResourceExhausted, "file exceeds content limit")
		}
	}
	sort.Slice(changes, func(i, j int) bool { return changes[i].Path < changes[j].Path })
	// Authorization may expire after a successful commit; it is deliberately not
	// part of the business fingerprint. Operation identity and baseline are bound.
	payload := struct {
		Operation string
		Revision  uint64
		Changes   []*rpc.FileChange
	}{request.OperationId, request.ExpectedRevision, changes}
	data, err := json.Marshal(payload)
	if err != nil {
		return "", storageError(err)
	}
	return sum(data), nil
}

func validateContentLimits(files map[string]string) error {
	if len(files) > maxWorkspaceFiles {
		return status.Error(codes.ResourceExhausted, "workspace file count exceeded")
	}
	total := 0
	for _, content := range files {
		if len(content) > fileLimit {
			return status.Error(codes.ResourceExhausted, "file exceeds content limit")
		}
		total += len(content)
		if total > maxWorkspaceBytes {
			return status.Error(codes.ResourceExhausted, "workspace content limit exceeded")
		}
	}
	return nil
}
