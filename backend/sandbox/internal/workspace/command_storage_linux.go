package workspace

import (
	"context"
	"crypto/rand"
	"encoding/json"
	"errors"
	"fmt"
	"io/fs"
	"os"
	"reflect"
	"sort"
	"strings"
	"syscall"
	"unicode/utf8"

	"codecraft.local/sandbox/internal/execution"
	rpc "codecraft.local/sandbox/internal/gen"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"
)

// Only tree/ is mounted writable. Base files, hashes and synchronization receipts
// remain outside the command container's filesystem capability.
type commandRecord struct {
	Input           execution.Input      `json:"input"`
	BaseHashes      map[string]string    `json:"baseHashes"`
	FinalHashes     map[string]string    `json:"finalHashes"`
	Synchronization string               `json:"synchronization"`
	Reason          string               `json:"reason,omitempty"`
	Mutation        *rpc.MutationReceipt `json:"mutation,omitempty"`
}

func (s *Service) prepareCommandLocked(ctx context.Context, input execution.Input) (result error) {
	fd, err := s.instance(input.WorkspaceID, false)
	if err != nil {
		return err
	}
	defer syscall.Close(fd)
	existing, commandFD, err := openCommand(fd, input.WorkspaceID, input.ExecutionID)
	if err == nil {
		syscall.Close(commandFD)
		if !sameCommandInput(existing.Input, input) {
			return status.Error(codes.AlreadyExists, "command identity changed")
		}
		return nil
	}
	if !errors.Is(err, syscall.ENOENT) {
		return storageError(err)
	}
	current, commit, err := latest(fd, input.WorkspaceID)
	if err != nil {
		return storageError(err)
	}
	if current.Snapshot.Revision != input.Revision {
		return status.Error(codes.Aborted, "revision conflict")
	}
	root, err := os.OpenRoot(fmt.Sprintf("/proc/self/fd/%d", fd))
	if err != nil {
		return err
	}
	defer root.Close()
	stage := "stage-command-" + rand.Text()
	if err = syscall.Mkdirat(fd, stage, 0700); err != nil {
		return err
	}
	defer func() { result = errors.Join(result, root.RemoveAll(stage)) }()
	staged, err := directory(fd, stage, false)
	if err != nil {
		return err
	}
	defer syscall.Close(staged)
	for _, name := range []string{"base", "tree"} {
		child, err := directory(staged, name, true)
		if err != nil {
			return err
		}
		syscall.Close(child)
	}
	for path, hash := range current.Hashes {
		if err = ctx.Err(); err != nil {
			return err
		}
		content, err := readPath(fd, commit+"/tree/"+path)
		if err != nil || sum(content) != hash {
			return status.Error(codes.DataLoss, "command base integrity failure")
		}
		for _, prefix := range []string{"base/", "tree/"} {
			if err = writePath(staged, prefix+path, content); err != nil {
				return err
			}
		}
	}
	if err = commandDirectory(staged, input.Command.Directory); err != nil {
		return status.Error(codes.InvalidArgument, "command directory does not exist")
	}
	record := &commandRecord{Input: input, BaseHashes: current.Hashes, Synchronization: "PENDING"}
	if err = saveCommand(staged, record); err != nil {
		return err
	}
	if err = ctx.Err(); err != nil {
		return err
	}
	if err = syscall.Renameat(fd, stage, fd, "command-"+input.ExecutionID); err != nil {
		return err
	}
	return syscall.Fsync(fd)
}

func sameCommandInput(left, right execution.Input) bool {
	left.NotAfter, right.NotAfter = left.NotAfter.UTC(), right.NotAfter.UTC()
	return reflect.DeepEqual(left, right)
}

func commandDirectory(parent int, path string) error {
	fd, err := directory(parent, "tree", false)
	if err != nil {
		return err
	}
	defer func() { syscall.Close(fd) }()
	if path == "." {
		return nil
	}
	for _, part := range strings.Split(path, "/") {
		next, err := directory(fd, part, false)
		if err != nil {
			return err
		}
		syscall.Close(fd)
		fd = next
	}
	return nil
}

func openCommand(parent int, workspace, executionID string) (*commandRecord, int, error) {
	fd, err := directory(parent, "command-"+executionID, false)
	if err != nil {
		return nil, -1, err
	}
	data, err := readPath(fd, "receipt.json")
	var record commandRecord
	if err == nil {
		err = json.Unmarshal(data, &record)
	}
	if err == nil && (record.Input.WorkspaceID != workspace || record.Input.ExecutionID != executionID || record.Input.Purpose != "COMMAND" || record.Input.Command == nil || record.BaseHashes == nil) {
		err = errors.New("invalid command receipt")
	}
	if err != nil {
		syscall.Close(fd)
		return nil, -1, err
	}
	return &record, fd, nil
}

func saveCommand(parent int, record *commandRecord) error {
	data, err := json.Marshal(record)
	if err != nil || len(data) > fileLimit {
		return errors.New("command receipt exceeds metadata bound")
	}
	name := "stage-receipt-" + rand.Text()
	if err = writePath(parent, name, data); err != nil {
		return err
	}
	if err = syscall.Renameat(parent, name, parent, "receipt.json"); err != nil {
		return err
	}
	return syscall.Fsync(parent)
}

// Capture only after Docker has proven process quiescence. Source files survive
// every execution exit kind; temporary build caches are deliberately excluded.
func commandFiles(ctx context.Context, parent int) (map[string]string, error) {
	fd, err := directory(parent, "tree", false)
	if err != nil {
		return nil, err
	}
	defer syscall.Close(fd)
	root, err := os.OpenRoot(fmt.Sprintf("/proc/self/fd/%d", fd))
	if err != nil {
		return nil, err
	}
	defer root.Close()
	files := map[string]string{}
	entries := 0
	err = fs.WalkDir(root.FS(), ".", func(path string, entry fs.DirEntry, cause error) error {
		if cause != nil {
			return cause
		}
		if err := ctx.Err(); err != nil {
			return err
		}
		entries++
		if entries > maxWorkspaceFiles*16 {
			return status.Error(codes.ResourceExhausted, "command file tree exceeds entry bound")
		}
		if path == "." {
			return nil
		}
		if !validPath(path) {
			return status.Error(codes.InvalidArgument, "invalid command output path")
		}
		if path == "target" || path == "node_modules" {
			if entry.IsDir() {
				return fs.SkipDir
			}
			return nil
		}
		if entry.IsDir() {
			return nil
		}
		if !entry.Type().IsRegular() {
			return status.Error(codes.InvalidArgument, "command output must be regular text files")
		}
		info, err := entry.Info()
		if err != nil {
			return err
		}
		stat, ok := info.Sys().(*syscall.Stat_t)
		if !ok || stat.Nlink != 1 {
			return status.Error(codes.InvalidArgument, "linked command output is forbidden")
		}
		if info.Size() > fileLimit {
			return status.Error(codes.ResourceExhausted, "command output file exceeds limit")
		}
		content, err := readPath(fd, path)
		if err != nil {
			return err
		}
		if !utf8.Valid(content) || strings.ContainsRune(string(content), 0) {
			return status.Error(codes.InvalidArgument, "command output must be text")
		}
		files[path] = string(content)
		return validateContentLimits(files)
	})
	return files, err
}

func commandChanges(parent int, record *commandRecord) (*rpc.CommandChanges, error) {
	if record.FinalHashes == nil {
		return nil, status.Error(codes.FailedPrecondition, "command changes not confirmed")
	}
	result := &rpc.CommandChanges{WorkspaceInstanceId: record.Input.WorkspaceID, ExecutionInstanceId: record.Input.ExecutionID, BaseRevision: record.Input.Revision}
	paths := map[string]bool{}
	for path := range record.BaseHashes {
		paths[path] = true
	}
	for path := range record.FinalHashes {
		paths[path] = true
	}
	ordered := make([]string, 0, len(paths))
	for path := range paths {
		ordered = append(ordered, path)
	}
	sort.Strings(ordered)
	for _, path := range ordered {
		oldHash, existed := record.BaseHashes[path]
		newHash, exists := record.FinalHashes[path]
		if existed && exists && oldHash == newHash {
			continue
		}
		change := &rpc.WorkspaceFileDifference{Change: &rpc.FileChange{Path: path, Kind: "UPDATE"}}
		if existed {
			content, err := readPath(parent, "base/"+path)
			if err != nil || sum(content) != oldHash {
				return nil, status.Error(codes.DataLoss, "command base integrity failure")
			}
			change.PreviousContent = string(content)
		} else {
			change.Change.Kind = "CREATE"
		}
		if exists {
			content, err := readPath(parent, "tree/"+path)
			if err != nil || sum(content) != newHash {
				return nil, status.Error(codes.DataLoss, "command output integrity failure")
			}
			change.Change.Content = string(content)
		} else {
			change.Change.Kind = "DELETE"
		}
		result.Changes = append(result.Changes, change)
	}
	return result, nil
}

func (s *Service) synchronizeCommandLocked(ctx context.Context, result execution.Result) (*commandRecord, error) {
	if !result.Quiescent || result.FinishedAt == nil || result.Input.Purpose != "COMMAND" {
		return nil, status.Error(codes.FailedPrecondition, "command has not become quiescent")
	}
	parent, err := s.instance(result.Input.WorkspaceID, false)
	if err != nil {
		return nil, err
	}
	defer syscall.Close(parent)
	record, fd, err := openCommand(parent, result.Input.WorkspaceID, result.Input.ExecutionID)
	if err != nil {
		return nil, storageError(err)
	}
	defer syscall.Close(fd)
	if !sameCommandInput(record.Input, result.Input) {
		return nil, status.Error(codes.DataLoss, "command receipt identity changed")
	}
	if record.Synchronization != "PENDING" {
		return record, nil
	}
	if record.FinalHashes == nil {
		files, err := commandFiles(ctx, fd)
		if err != nil {
			if ctx.Err() != nil || (status.Code(err) != codes.InvalidArgument && status.Code(err) != codes.ResourceExhausted) {
				return nil, err
			}
			record.Synchronization, record.Reason = "REJECTED", "INVALID_COMMAND_FILES"
			return record, saveCommand(fd, record)
		}
		record.FinalHashes = map[string]string{}
		for path, content := range files {
			record.FinalHashes[path] = sum([]byte(content))
		}
		if err = saveCommand(fd, record); err != nil {
			return nil, err
		}
	}
	changes, err := commandChanges(fd, record)
	if err != nil {
		return nil, err
	}
	if len(changes.Changes) == 0 {
		record.Synchronization = "UNCHANGED"
		return record, saveCommand(fd, record)
	}
	batch := &rpc.ApplyBatchRequest{WorkspaceInstanceId: result.Input.WorkspaceID, RequestId: result.Input.ExecutionID, OperationId: result.Input.TaskID,
		ExpectedRevision: result.Input.Revision, GateEpoch: result.Input.GateEpoch, NotAfterUnixMillis: result.Input.NotAfter.UnixMilli()}
	for _, change := range changes.Changes {
		batch.Changes = append(batch.Changes, change.Change)
	}
	receipt, err := s.applyBatchLocked(ctx, batch)
	if err == nil {
		record.Synchronization, record.Mutation = "SYNCED", receipt
	} else {
		switch status.Code(err) {
		case codes.Aborted:
			record.Synchronization, record.Reason = "CONFLICT", "REVISION_CONFLICT"
		case codes.FailedPrecondition:
			record.Synchronization, record.Reason = "REJECTED", "WRITE_AUTHORITY_ENDED"
		case codes.InvalidArgument, codes.PermissionDenied, codes.AlreadyExists, codes.ResourceExhausted, codes.NotFound:
			record.Synchronization, record.Reason = "REJECTED", "COMMAND_CHANGES_REJECTED"
		default:
			return nil, err // Unknown publication remains pending; never reinterpret as no effect.
		}
	}
	return record, saveCommand(fd, record)
}
