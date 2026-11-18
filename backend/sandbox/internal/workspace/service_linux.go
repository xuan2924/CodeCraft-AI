package workspace

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"regexp"
	"slices"
	"sort"
	"strconv"
	"strings"
	"syscall"
	"time"
	"unicode/utf8"

	"codecraft.local/sandbox/internal/execution"
	rpc "codecraft.local/sandbox/internal/gen"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/metadata"
	"google.golang.org/grpc/status"
	"google.golang.org/protobuf/proto"
)

var uuid = regexp.MustCompile(`^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$`)
var digest = regexp.MustCompile(`^[0-9a-f]{64}$`)
var segment = regexp.MustCompile(`^[a-zA-Z0-9_.-]+$`)

// Receipts survive commit collection. Bound the aggregate independently of
// source files; never evict replay evidence to admit another mutation.
const manifestLimit = 16 << 20

var errManifestCapacity = fmt.Errorf("workspace manifest and retained mutation receipts exceed %d bytes; existing receipts remain replayable", manifestLimit)

const fileLimit = 1024 * 1024 // Existing v1 problem-package single-file contract.

// Service coordinates all operations on one private storage root. The process
// entrypoint must hold exclusive ownership of that root across service instances.
type Service struct {
	rpc.UnimplementedWorkspaceStorageServer
	root                string
	storageID           string
	key                 string
	publications        []Publication
	loadedPackageSHA256 []string // sorted unique base publication digests
	gate                chan struct{}
	watches             chan struct{}
	executor            *execution.Runner
	worker              context.Context
	now                 func() time.Time // Business authority clock; production uses the shared system clock.
	// Test-only synchronization, configured before serving; never exposed by RPC or configuration.
	beforeMutationPublish func(context.Context)
}

type record struct {
	Snapshot  *rpc.WorkspaceSnapshot          `json:"snapshot"`
	Hashes    map[string]string               `json:"hashes"`
	Mutations map[string]*rpc.MutationReceipt `json:"mutations,omitempty"`
}

// ExecutionRuntime binds an executor to its process-owned worker lifetime.
// A nil runtime constructs a storage-only service.
type ExecutionRuntime struct {
	Runner *execution.Runner
	Worker context.Context
}

// NewService configures published inputs and private local storage, never a user path.
func NewService(root string, publications []Publication, key string, runtime *ExecutionRuntime) (*Service, error) {
	if runtime != nil && (runtime.Runner == nil || runtime.Worker == nil) {
		return nil, errors.New("execution requires runner and worker lifetime")
	}
	if !digest.MatchString(key) || !filepath.IsAbs(root) {
		return nil, fmt.Errorf("absolute private root and 32-byte hex RPC key required")
	}
	if err := os.MkdirAll(root, 0700); err != nil {
		return nil, err
	}
	fd, err := directory(-1, root, false)
	if err != nil {
		return nil, err
	}
	storageID, err := storageIdentity(fd)
	syscall.Close(fd)
	if err != nil {
		return nil, err
	}
	s := &Service{root: root, storageID: storageID, key: key, gate: make(chan struct{}, 1), watches: make(chan struct{}, 16), now: time.Now}
	if runtime != nil {
		s.executor, s.worker = runtime.Runner, runtime.Worker
	}
	s.gate <- struct{}{}
	for _, publication := range publications {
		if publication.Identity == nil || len(publication.InitialFiles) == 0 {
			return nil, fmt.Errorf("publication requires identity and initial files")
		}
		copy := Publication{Identity: proto.Clone(publication.Identity).(*rpc.ProblemIdentity), InitialFiles: map[string]string{}}
		if publication.Execution != nil {
			policy := *publication.Execution
			policy.Tests = append([]string(nil), policy.Tests...)
			copy.Execution = &policy
		}
		if publication.Challenge != nil {
			child := publication.Challenge
			if child.Identity == nil || child.Execution == nil || child.Identity.ExecutionImageDigest == publication.Identity.ExecutionImageDigest {
				return nil, fmt.Errorf("challenge requires an independent verified image and execution policy")
			}
			policy := *child.Execution
			policy.Tests = append([]string(nil), policy.Tests...)
			copy.Challenge = &ChallengePublication{Identity: proto.Clone(child.Identity).(*rpc.ProblemIdentity), Execution: &policy}
		}
		for path, content := range publication.InitialFiles {
			if !validPath(path) || !(path == "pom.xml" || isGuidancePath(path) || strings.HasPrefix(path, "src/main/java/") || strings.HasPrefix(path, "src/test/java/")) ||
				len(content) > fileLimit || !utf8.ValidString(content) || strings.ContainsRune(content, 0) {
				return nil, fmt.Errorf("invalid initial file")
			}
			copy.InitialFiles[path] = content
		}
		if err := validateContentLimits(copy.InitialFiles); err != nil {
			return nil, err
		}
		s.publications = append(s.publications, copy)
		if sha := copy.Identity.PackageSha256; digest.MatchString(sha) {
			s.loadedPackageSHA256 = append(s.loadedPackageSHA256, sha)
		}
	}
	slices.Sort(s.loadedPackageSHA256)
	s.loadedPackageSHA256 = slices.Compact(s.loadedPackageSHA256)
	return s, nil
}

func (s *Service) enter(ctx context.Context) (func(), error) {
	keys := metadata.ValueFromIncomingContext(ctx, "x-codecraft-internal-key")
	if len(keys) != 1 || subtle.ConstantTimeCompare([]byte(keys[0]), []byte(s.key)) != 1 {
		return nil, status.Error(codes.Unauthenticated, "internal authentication required")
	}
	fd, err := directory(-1, s.root, false)
	if err != nil {
		return nil, storageError(err)
	}
	identity, readErr := readPath(fd, ".storage-id")
	syscall.Close(fd)
	if readErr != nil || string(identity) != s.storageID {
		return nil, status.Error(codes.FailedPrecondition, "storage identity unavailable")
	}
	storage := metadata.ValueFromIncomingContext(ctx, "x-codecraft-storage-id")
	if len(storage) > 0 && (len(storage) != 1 || storage[0] != s.storageID) {
		return nil, status.Error(codes.FailedPrecondition, "storage identity changed")
	}
	select {
	case <-ctx.Done():
		return nil, status.FromContextError(ctx.Err()).Err()
	case <-s.gate:
		return func() { s.gate <- struct{}{} }, nil
	}
}

func (s *Service) instance(id string, create bool) (int, error) {
	fd, err := s.rawInstance(id, create)
	if err != nil {
		return -1, err
	}
	_, err = readPath(fd, "deleted")
	if err == nil {
		syscall.Close(fd)
		return -1, status.Error(codes.FailedPrecondition, "workspace deleted")
	}
	if !errors.Is(err, syscall.ENOENT) {
		syscall.Close(fd)
		return -1, storageError(err)
	}
	return fd, nil
}

func (s *Service) rawInstance(id string, create bool) (int, error) {
	if !uuid.MatchString(id) {
		return -1, status.Error(codes.InvalidArgument, "invalid workspace instance")
	}
	root, err := directory(-1, s.root, false)
	if err != nil {
		return -1, storageError(err)
	}
	defer syscall.Close(root)
	fd, err := directory(root, id, create)
	if err == nil && create {
		if syncErr := syscall.Fsync(root); syncErr != nil {
			syscall.Close(fd)
			return -1, storageError(syncErr)
		}
	}
	if errors.Is(err, syscall.ENOENT) {
		return -1, status.Error(codes.NotFound, "workspace not found")
	}
	return fd, storageError(err)
}

// PrepareWorkspace publishes revision zero or replays an integrity-checked committed receipt.
func (s *Service) PrepareWorkspace(ctx context.Context, request *rpc.PrepareWorkspaceRequest) (*rpc.WorkspaceSnapshot, error) {
	release, err := s.enter(ctx)
	if err != nil {
		return nil, err
	}
	defer release()
	if request.NotAfterUnixMillis <= 0 {
		return nil, status.Error(codes.InvalidArgument, "missing preparation authorization deadline")
	}
	deadline := time.UnixMilli(request.NotAfterUnixMillis)
	if !s.now().Before(deadline) {
		return nil, status.Error(codes.FailedPrecondition, "preparation authorization expired")
	}
	ctx, cancel := context.WithDeadline(ctx, deadline)
	defer cancel()
	if !uuid.MatchString(request.OperationId) {
		return nil, status.Error(codes.InvalidArgument, "invalid operation")
	}
	var publication *Publication
	for i := range s.publications {
		if proto.Equal(s.publications[i].Identity, request.Problem) {
			publication = &s.publications[i]
			break
		}
	}
	if publication == nil {
		return nil, status.Error(codes.FailedPrecondition, "problem version not published")
	}
	fd, err := s.instance(request.WorkspaceInstanceId, true)
	if err != nil {
		return nil, err
	}
	defer syscall.Close(fd)
	previous, commit, err := latest(fd, request.WorkspaceInstanceId)
	if err != nil && !errors.Is(err, errNoCommit) {
		return nil, storageError(err)
	}
	if err = cleanupPreparation(ctx, fd, "", false); err != nil {
		return nil, storageError(err)
	}
	if previous != nil {
		if previous.Snapshot.Cancelled {
			return nil, status.Error(codes.FailedPrecondition, "preparation cancelled")
		}
		if previous.Snapshot.OperationId != request.OperationId || !proto.Equal(previous.Snapshot.Problem, request.Problem) {
			return nil, status.Error(codes.AlreadyExists, "workspace instance cannot be reused")
		}
		if err = verifyPreparedTree(ctx, fd, previous, commit); err != nil {
			return nil, storageError(err)
		}
		return previous.Snapshot, nil
	}
	gates, err := gateRecords(fd, request.WorkspaceInstanceId)
	if err != nil {
		return nil, storageError(err)
	}
	if len(gates) != 0 {
		return nil, status.Error(codes.FailedPrecondition, "preparation fenced")
	}
	view := &rpc.WorkspaceSnapshot{WorkspaceInstanceId: request.WorkspaceInstanceId, OperationId: request.OperationId,
		Problem: publication.Identity, Prepared: true}
	r := &record{Snapshot: view, Hashes: map[string]string{}}
	for path, content := range publication.InitialFiles {
		view.Paths = append(view.Paths, path)
		r.Hashes[path] = sum([]byte(content))
	}
	sort.Strings(view.Paths)
	if err = publish(ctx, fd, 0, r, publication.InitialFiles, nil, s.now, deadline); err != nil {
		return nil, storageError(err)
	}
	return view, nil
}

// CancelPreparation persists a replay-safe tombstone and confirms physical cleanup before success.
func (s *Service) CancelPreparation(ctx context.Context, request *rpc.CancelPreparationRequest) (*rpc.WorkspaceSnapshot, error) {
	release, err := s.enter(ctx)
	if err != nil {
		return nil, err
	}
	defer release()
	if !uuid.MatchString(request.OperationId) {
		return nil, status.Error(codes.InvalidArgument, "invalid operation")
	}
	fd, err := s.instance(request.WorkspaceInstanceId, true)
	if err != nil {
		return nil, err
	}
	defer syscall.Close(fd)
	previous, commit, err := latest(fd, request.WorkspaceInstanceId)
	if err != nil && !errors.Is(err, errNoCommit) {
		return nil, storageError(err)
	}
	// Input copies may be referenced by an execution. Preparation cancellation
	// cannot reconcile those references or truthfully confirm their cleanup.
	copies, err := hasTestCopies(fd)
	if err != nil {
		return nil, storageError(err)
	}
	if copies {
		return nil, status.Error(codes.FailedPrecondition, "workspace has test copies requiring execution reconciliation")
	}
	view := &rpc.WorkspaceSnapshot{WorkspaceInstanceId: request.WorkspaceInstanceId, OperationId: request.OperationId, Cancelled: true}
	var sequence uint64
	if previous != nil {
		if previous.Snapshot.WorkspaceInstanceId != request.WorkspaceInstanceId {
			return nil, status.Error(codes.DataLoss, "workspace identity mismatch")
		}
		if previous.Snapshot.Cancelled {
			if err = cleanupPreparation(ctx, fd, commit, true); err != nil {
				return nil, storageError(err)
			}
			return previous.Snapshot, nil
		}
		// Preparation cancellation must never discard write receipts or an open
		// workspace. READY abandonment first closes its control gate. Ended-workspace
		// deletion with mutations needs the separate reconciliation flow.
		gates, gateErr := gateRecords(fd, request.WorkspaceInstanceId)
		if gateErr != nil {
			return nil, storageError(gateErr)
		}
		if len(previous.Mutations) != 0 {
			return nil, status.Error(codes.FailedPrecondition, "workspace has mutations")
		}
		if len(gates) > 0 && gates[len(gates)-1].State != "CLOSED" {
			return nil, status.Error(codes.FailedPrecondition, "workspace gate must be closed")
		}
		sequence, err = strconv.ParseUint(commit[7:], 10, 64)
		if err != nil || sequence == ^uint64(0) {
			return nil, status.Error(codes.DataLoss, "invalid commit sequence")
		}
		sequence++
		view.Problem = previous.Snapshot.Problem
	}
	if err = publish(ctx, fd, sequence, &record{Snapshot: view, Hashes: map[string]string{}}, nil, nil, s.now, time.Time{}); err != nil {
		return nil, storageError(err)
	}
	if err = cleanupPreparation(ctx, fd, fmt.Sprintf("commit-%020d", sequence), true); err != nil {
		return nil, storageError(err)
	}
	return view, nil
}

// The cancellation commit is the durable cleanup intent. Replaying cancellation
// retries physical cleanup while retaining the latest tombstone and all gate receipts.
func cleanupPreparation(ctx context.Context, parent int, keep string, removeCommits bool) error {
	owned, err := os.OpenRoot(fmt.Sprintf("/proc/self/fd/%d", parent))
	if err != nil {
		return err
	}
	defer owned.Close()
	directory, err := owned.Open(".")
	if err != nil {
		return err
	}
	defer directory.Close()
	for {
		entries, readErr := directory.ReadDir(64)
		if readErr != nil && !errors.Is(readErr, io.EOF) {
			return readErr
		}
		for _, entry := range entries {
			if err = ctx.Err(); err != nil {
				return err
			}
			name := entry.Name()
			if strings.HasPrefix(name, "stage-") || (removeCommits && strings.HasPrefix(name, "commit-") && name != keep) {
				if err = owned.RemoveAll(name); err != nil {
					return err
				}
			}
		}
		if errors.Is(readErr, io.EOF) {
			break
		}
	}
	return syscall.Fsync(parent)
}

// QueryWorkspace confirms only committed, intact trees or durable cancellation tombstones.
func (s *Service) QueryWorkspace(ctx context.Context, request *rpc.QueryWorkspaceRequest) (*rpc.WorkspaceSnapshot, error) {
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
	r, commit, err := latest(fd, request.WorkspaceInstanceId)
	if errors.Is(err, errNoCommit) {
		return nil, status.Error(codes.NotFound, "no committed workspace")
	}
	if err != nil {
		return nil, storageError(err)
	}
	if r.Snapshot.WorkspaceInstanceId != request.WorkspaceInstanceId {
		return nil, status.Error(codes.DataLoss, "workspace identity mismatch")
	}
	if err = verifyPreparedTree(ctx, fd, r, commit); err != nil {
		return nil, storageError(err)
	}
	return r.Snapshot, nil
}

// ReadWorkspaceFile reads a safe committed file only at the requested revision and verifies its hash.
func (s *Service) ReadWorkspaceFile(ctx context.Context, request *rpc.ReadWorkspaceFileRequest) (*rpc.ReadWorkspaceFileResponse, error) {
	release, err := s.enter(ctx)
	if err != nil {
		return nil, err
	}
	defer release()
	if !validPath(request.Path) {
		return nil, status.Error(codes.InvalidArgument, "invalid path")
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
	if r.Snapshot.Cancelled || !r.Snapshot.Prepared {
		return nil, status.Error(codes.NotFound, "workspace unavailable")
	}
	if r.Snapshot.WorkspaceInstanceId != request.WorkspaceInstanceId {
		return nil, status.Error(codes.DataLoss, "workspace identity mismatch")
	}
	if r.Snapshot.Revision != request.ExpectedRevision {
		return nil, status.Error(codes.Aborted, "revision conflict")
	}
	expected, exists := r.Hashes[request.Path]
	if !exists {
		return nil, status.Error(codes.NotFound, "file not found")
	}
	content, err := readPath(fd, commit+"/tree/"+request.Path)
	if err != nil {
		return nil, storageError(err)
	}
	if sum(content) != expected || !utf8.Valid(content) {
		return nil, status.Error(codes.DataLoss, "file integrity check failed")
	}
	return &rpc.ReadWorkspaceFileResponse{WorkspaceInstanceId: request.WorkspaceInstanceId,
		Revision: r.Snapshot.Revision, Path: request.Path, Content: string(content)}, nil
}

// Guidance is supplied by controlled publication, never by workspace mutation.
func isGuidancePath(path string) bool {
	return path == "AGENTS.md" || strings.HasSuffix(path, "/AGENTS.md")
}

func validPath(path string) bool {
	if path == "" || len(path) > 4096 {
		return false
	}
	for _, part := range strings.Split(path, "/") {
		if part == "." || part == ".." || !segment.MatchString(part) {
			return false
		}
	}
	return true
}
func sum(data []byte) string { hash := sha256.Sum256(data); return hex.EncodeToString(hash[:]) }
func storageError(err error) error {
	if err == nil {
		return nil
	}
	if errors.Is(err, context.Canceled) || errors.Is(err, context.DeadlineExceeded) {
		return status.FromContextError(err).Err()
	}
	if errors.Is(err, errManifestCapacity) {
		return status.Error(codes.ResourceExhausted, errManifestCapacity.Error())
	}
	return status.Error(codes.Unavailable, "workspace storage cannot confirm result")
}
func directory(parent int, name string, create bool) (int, error) {
	if create {
		if err := syscall.Mkdirat(parent, name, 0700); err != nil && !errors.Is(err, syscall.EEXIST) {
			return -1, err
		}
	}
	return syscall.Openat(parent, name, syscall.O_RDONLY|syscall.O_DIRECTORY|syscall.O_NOFOLLOW|syscall.O_CLOEXEC, 0)
}
func readPath(parent int, path string) ([]byte, error) {
	return readLimitedPath(parent, path, fileLimit)
}

func readLimitedPath(parent int, path string, limit int64) ([]byte, error) {
	parts := strings.Split(path, "/")
	fd, err := syscall.Dup(parent)
	if err != nil {
		return nil, err
	}
	for _, part := range parts[:len(parts)-1] {
		next, openErr := directory(fd, part, false)
		syscall.Close(fd)
		if openErr != nil {
			return nil, openErr
		}
		fd = next
	}
	fileFD, err := syscall.Openat(fd, parts[len(parts)-1], syscall.O_RDONLY|syscall.O_NONBLOCK|syscall.O_NOFOLLOW|syscall.O_CLOEXEC, 0)
	syscall.Close(fd)
	if err != nil {
		return nil, err
	}
	file := os.NewFile(uintptr(fileFD), "workspace file")
	defer file.Close()
	var info syscall.Stat_t
	if err := syscall.Fstat(fileFD, &info); err != nil {
		return nil, err
	}
	if info.Mode&syscall.S_IFMT != syscall.S_IFREG || info.Nlink != 1 || info.Size > limit {
		return nil, fmt.Errorf("unsafe file")
	}
	data, err := io.ReadAll(io.LimitReader(file, limit+1))
	if int64(len(data)) > limit {
		return nil, fmt.Errorf("file too large")
	}
	return data, err
}
func verifyPreparedTree(ctx context.Context, parent int, r *record, commit string) error {
	for _, path := range r.Snapshot.Paths {
		if err := ctx.Err(); err != nil {
			return err
		}
		content, err := readPath(parent, commit+"/tree/"+path)
		if err != nil {
			return fmt.Errorf("committed file unavailable: %w", err)
		}
		if sum(content) != r.Hashes[path] || !utf8.Valid(content) {
			return fmt.Errorf("committed file integrity failure")
		}
	}
	return nil
}

var errNoCommit = errors.New("no committed workspace")

func latest(parent int, expectedID string) (*record, string, error) {
	fd, err := syscall.Openat(parent, ".", syscall.O_RDONLY|syscall.O_DIRECTORY|syscall.O_CLOEXEC, 0)
	if err != nil {
		return nil, "", err
	}
	file := os.NewFile(uintptr(fd), "workspace directory")
	entries, err := file.ReadDir(-1)
	file.Close()
	if err != nil {
		return nil, "", err
	}
	name := ""
	for _, entry := range entries {
		candidate := entry.Name()
		if !strings.HasPrefix(candidate, "commit-") {
			continue
		}
		if len(candidate) != 27 {
			return nil, "", fmt.Errorf("invalid commit name")
		}
		if _, err := strconv.ParseUint(candidate[7:], 10, 64); err != nil {
			return nil, "", err
		}
		if candidate > name {
			name = candidate
		}
	}
	if name == "" {
		return nil, "", errNoCommit
	}
	return readCommit(parent, expectedID, name)
}

func readCommit(parent int, expectedID, name string) (*record, string, error) {
	data, err := readLimitedPath(parent, name+"/manifest.json", manifestLimit)
	if err != nil {
		return nil, "", err
	}
	var r record
	if err = json.Unmarshal(data, &r); err != nil {
		return nil, "", err
	}
	if r.Snapshot == nil || r.Hashes == nil || r.Snapshot.WorkspaceInstanceId != expectedID ||
		!uuid.MatchString(r.Snapshot.OperationId) || r.Snapshot.Prepared == r.Snapshot.Cancelled {
		return nil, "", fmt.Errorf("invalid commit record")
	}
	if len(r.Snapshot.Paths) != len(r.Hashes) ||
		(r.Snapshot.Prepared && (r.Snapshot.Problem == nil || len(r.Hashes) == 0)) ||
		(r.Snapshot.Cancelled && len(r.Hashes) != 0) {
		return nil, "", fmt.Errorf("incomplete commit record")
	}
	for index, path := range r.Snapshot.Paths {
		if !validPath(path) || !digest.MatchString(r.Hashes[path]) || (index > 0 && path <= r.Snapshot.Paths[index-1]) {
			return nil, "", fmt.Errorf("invalid file manifest")
		}
	}
	sequence, err := strconv.ParseUint(name[7:], 10, 64)
	if err != nil || (r.Snapshot.Prepared && uint64(len(r.Mutations)) != sequence) {
		return nil, "", fmt.Errorf("incomplete mutation history")
	}
	for requestID, receipt := range r.Mutations {
		if receipt == nil || !uuid.MatchString(requestID) || receipt.RequestId != requestID ||
			receipt.WorkspaceInstanceId != expectedID || !uuid.MatchString(receipt.OperationId) ||
			!digest.MatchString(receipt.Fingerprint) || receipt.Revision > r.Snapshot.Revision ||
			(receipt.Changed && receipt.Revision == 0) {
			return nil, "", fmt.Errorf("invalid mutation receipt")
		}
	}
	return &r, name, nil
}
func publish(ctx context.Context, parent int, sequence uint64, r *record, files map[string]string, beforePublish func(context.Context), now func() time.Time, authorityUntil time.Time) (result error) {
	// Pin cleanup to the already-open directory, not a mutable absolute path.
	owned, err := os.OpenRoot(fmt.Sprintf("/proc/self/fd/%d", parent))
	if err != nil {
		return err
	}
	defer owned.Close()
	// Never reuse an interrupted stage, even for the same operation.
	stage := "stage-" + rand.Text()
	if err := syscall.Mkdirat(parent, stage, 0700); err != nil {
		return err
	}
	defer func() { result = errors.Join(result, owned.RemoveAll(stage)) }()
	stageFD, err := directory(parent, stage, false)
	if err != nil {
		return err
	}
	defer syscall.Close(stageFD)
	for path, content := range files {
		if err := writePath(stageFD, "tree/"+path, []byte(content)); err != nil {
			return err
		}
	}
	data, err := json.Marshal(r)
	if err != nil {
		return err
	}
	if len(data) > manifestLimit {
		return errManifestCapacity
	}
	if err = writePath(stageFD, "manifest.json", data); err != nil {
		return err
	}
	if err = syscall.Fsync(stageFD); err != nil {
		return err
	}
	if beforePublish != nil {
		beforePublish(ctx)
	}
	if err = ctx.Err(); err != nil {
		return err
	}
	// Business authority uses its own clock; never advance a transport deadline
	// using simulated business time. Cleanup publication has no business authority.
	if !authorityUntil.IsZero() && !now().Before(authorityUntil) {
		return context.DeadlineExceeded
	}
	if deadline, ok := ctx.Deadline(); ok && !time.Now().Before(deadline) {
		return context.DeadlineExceeded
	}
	if err = syscall.Renameat(parent, stage, parent, fmt.Sprintf("commit-%020d", sequence)); err != nil {
		return err
	}
	return syscall.Fsync(parent)
}
func writePath(parent int, path string, data []byte) error {
	parts := strings.Split(path, "/")
	fd, err := syscall.Dup(parent)
	if err != nil {
		return err
	}
	defer func() { syscall.Close(fd) }()
	for _, part := range parts[:len(parts)-1] {
		next, err := directory(fd, part, true)
		if err != nil {
			return err
		}
		if err = syscall.Fsync(fd); err != nil {
			syscall.Close(next)
			return err
		}
		syscall.Close(fd)
		fd = next
	}
	fileFD, err := syscall.Openat(fd, parts[len(parts)-1], syscall.O_CREAT|syscall.O_EXCL|syscall.O_WRONLY|syscall.O_NOFOLLOW|syscall.O_CLOEXEC, 0600)
	if err != nil {
		return err
	}
	file := os.NewFile(uintptr(fileFD), "staged workspace file")
	_, writeErr := file.Write(data)
	if writeErr == nil {
		writeErr = file.Sync()
	}
	closeErr := file.Close()
	if writeErr != nil {
		return writeErr
	}
	if closeErr != nil {
		return closeErr
	}
	return syscall.Fsync(fd)
}
