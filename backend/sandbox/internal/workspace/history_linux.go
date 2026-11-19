package workspace

import (
	"context"
	"os"
	"sort"
	"strings"
	"syscall"
	"unicode/utf8"

	rpc "codecraft.local/sandbox/internal/gen"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"
	"google.golang.org/protobuf/proto"
)

// PreviewWorkspaceHistory reads retained immutable versions without publishing
// or opening a gate. Undo is whole-file conservative: later edits to a touched
// file conflict; unrelated files are preserved. ApplyBatch remains the only
// publication path, with its own current-revision and authorization checks.
func (s *Service) PreviewWorkspaceHistory(ctx context.Context, request *rpc.WorkspaceHistoryRequest) (*rpc.WorkspaceHistoryPreview, error) {
	if request == nil || request.ResultRevision <= request.BaseRevision || request.ResultRevision != request.BaseRevision+1 || request.ResultRevision > request.ExpectedRevision {
		return nil, status.Error(codes.InvalidArgument, "invalid history identity")
	}
	if request.Mode != "DIFF" && request.Mode != "UNDO" && request.Mode != "ROLLBACK" {
		return nil, status.Error(codes.InvalidArgument, "invalid history mode")
	}
	if request.Mode == "ROLLBACK" && request.TargetRevision != request.BaseRevision && request.TargetRevision != request.ResultRevision || request.Mode != "ROLLBACK" && request.TargetRevision != 0 {
		return nil, status.Error(codes.InvalidArgument, "invalid history target")
	}
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
	current, _, err := latest(fd, request.WorkspaceInstanceId)
	if err != nil {
		return nil, storageError(err)
	}
	if !current.Snapshot.Prepared || current.Snapshot.Cancelled {
		return nil, status.Error(codes.FailedPrecondition, "workspace unavailable")
	}
	if current.Snapshot.Revision != request.ExpectedRevision {
		return nil, status.Error(codes.Aborted, "history preview baseline changed")
	}
	versions, err := historyVersions(ctx, fd, request.WorkspaceInstanceId, request.BaseRevision, request.ResultRevision, request.ExpectedRevision)
	if err != nil {
		return nil, err
	}
	before, after, now := versions[request.BaseRevision], versions[request.ResultRevision], versions[request.ExpectedRevision]
	preview := &rpc.WorkspaceHistoryPreview{WorkspaceInstanceId: request.WorkspaceInstanceId,
		BaseRevision: request.BaseRevision, ResultRevision: request.ResultRevision, CurrentRevision: request.ExpectedRevision, Mode: request.Mode}
	switch request.Mode {
	case "DIFF":
		preview.Changes = historyDifference(before, after)
	case "ROLLBACK":
		preview.TargetRevision = request.TargetRevision
		preview.Changes = historyDifference(now, versions[request.TargetRevision])
	case "UNDO":
		desired := make(map[string]string, len(now))
		for path, content := range now {
			desired[path] = content
		}
		for _, difference := range historyDifference(before, after) {
			path := difference.Change.Path
			oldValue, oldExists := before[path]
			resultValue, resultExists := after[path]
			currentValue, currentExists := now[path]
			if currentExists != resultExists || currentValue != resultValue {
				preview.Conflicts = append(preview.Conflicts, path)
			}
			if oldExists {
				desired[path] = oldValue
			} else {
				delete(desired, path)
			}
		}
		preview.Changes = historyDifference(now, desired)
	}
	encoded, err := (proto.MarshalOptions{Deterministic: true}).Marshal(preview)
	if err != nil {
		return nil, storageError(err)
	}
	preview.Fingerprint = sum(encoded)
	return preview, nil
}

// Read the newest commit for each requested revision (no-op receipts can share
// a revision). The service admission lock prevents collection during this read.
func historyVersions(ctx context.Context, parent int, workspace string, revisions ...uint64) (map[uint64]map[string]string, error) {
	wanted := map[uint64]bool{}
	for _, revision := range revisions {
		wanted[revision] = true
	}
	fd, err := directory(parent, ".", false)
	if err != nil {
		return nil, storageError(err)
	}
	file := os.NewFile(uintptr(fd), "history commits")
	entries, err := file.ReadDir(-1)
	file.Close()
	if err != nil {
		return nil, storageError(err)
	}
	sort.Slice(entries, func(i, j int) bool { return entries[i].Name() > entries[j].Name() })
	result := map[uint64]map[string]string{}
	for _, entry := range entries {
		if err = ctx.Err(); err != nil {
			return nil, storageError(err)
		}
		name := entry.Name()
		if !strings.HasPrefix(name, "commit-") {
			continue
		}
		// latest already validated commit identities before this locked scan.
		record, _, readErr := readCommit(parent, workspace, name)
		if readErr != nil {
			return nil, storageError(readErr)
		}
		revision := record.Snapshot.Revision
		if !wanted[revision] || result[revision] != nil {
			continue
		}
		files := map[string]string{}
		for _, path := range record.Snapshot.Paths {
			if err = ctx.Err(); err != nil {
				return nil, storageError(err)
			}
			content, readErr := readPath(parent, "blobs/"+record.Hashes[path])
			if readErr != nil {
				return nil, storageError(readErr)
			}
			if !utf8.Valid(content) || sum(content) != record.Hashes[path] {
				return nil, status.Error(codes.DataLoss, "history integrity check failed")
			}
			files[path] = string(content)
		}
		result[revision] = files
		if len(result) == len(wanted) {
			return result, nil
		}
	}
	return nil, status.Error(codes.NotFound, "history version is unavailable")
}

func historyDifference(before, after map[string]string) []*rpc.WorkspaceFileDifference {
	paths := map[string]bool{}
	for path := range before {
		paths[path] = true
	}
	for path := range after {
		paths[path] = true
	}
	ordered := make([]string, 0, len(paths))
	for path := range paths {
		ordered = append(ordered, path)
	}
	sort.Strings(ordered)
	var changes []*rpc.WorkspaceFileDifference
	for _, path := range ordered {
		oldValue, oldExists := before[path]
		newValue, newExists := after[path]
		if oldExists == newExists && oldValue == newValue {
			continue
		}
		kind := "UPDATE"
		if !newExists {
			kind = "DELETE"
		} else if !oldExists {
			kind = "CREATE"
		}
		changes = append(changes, &rpc.WorkspaceFileDifference{Change: &rpc.FileChange{Path: path, Kind: kind, Content: newValue}, PreviousContent: oldValue})
	}
	return changes
}
