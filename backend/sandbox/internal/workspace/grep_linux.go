package workspace

import (
	"context"
	"fmt"
	"regexp"
	"sort"
	"strings"
	"syscall"
	"unicode/utf8"

	rpc "codecraft.local/sandbox/internal/gen"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"
)

// GrepWorkspace scans one committed tree, never test copies or unpublished files.
func (s *Service) GrepWorkspace(ctx context.Context, request *rpc.GrepWorkspaceRequest) (*rpc.GrepWorkspaceResponse, error) {
	release, err := s.enter(ctx)
	if err != nil {
		return nil, err
	}
	defer release()
	pattern, prefix := request.Pattern, request.PathPrefix
	if pattern == "" || utf8.RuneCountInString(pattern) > 512 || !utf8.ValidString(pattern) || request.ContextLines > 10 || request.Limit < 1 || request.Limit > 200 || strings.HasPrefix(prefix, "/") || strings.Contains(prefix, "..") || strings.Contains(prefix, "\\") {
		return nil, status.Error(codes.InvalidArgument, "invalid search")
	}
	var expression *regexp.Regexp
	if request.IsRegex {
		// Preserve the public restricted subset; Go's RE2 also prevents backtracking.
		quantifiers := strings.Count(pattern, "*") + strings.Count(pattern, "+") + strings.Count(pattern, "?")
		if strings.ContainsAny(pattern, "(){}|\\") || quantifiers > 1 || (quantifiers > 0 && !strings.HasPrefix(pattern, "^")) {
			return nil, status.Error(codes.InvalidArgument, "invalid regex")
		}
		expression, err = regexp.Compile(pattern)
		if err != nil {
			return nil, status.Error(codes.InvalidArgument, "invalid regex")
		}
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
	if err = verifyPreparedTree(ctx, fd, r, commit); err != nil {
		return nil, storageError(err)
	}
	result := &rpc.GrepWorkspaceResponse{WorkspaceInstanceId: request.WorkspaceInstanceId, Revision: r.Snapshot.Revision}
	paths := make([]string, 0, len(r.Hashes))
	for path := range r.Hashes {
		if strings.HasPrefix(path, prefix) {
			paths = append(paths, path)
		}
	}
	sort.Strings(paths)
	bytes, matches := 0, uint32(0)
scan:
	for _, path := range paths {
		if err = ctx.Err(); err != nil {
			return nil, storageError(err)
		}
		content, readErr := readPath(fd, commit+"/tree/"+path)
		if readErr != nil {
			return nil, storageError(readErr)
		}
		if sum(content) != r.Hashes[path] || !utf8.Valid(content) {
			return nil, status.Error(codes.DataLoss, "file integrity check failed")
		}
		lines := strings.Split(string(content), "\n")
		emittedThrough := -1
		for index, line := range lines {
			if err = ctx.Err(); err != nil {
				return nil, storageError(err)
			}
			if expression != nil && len(line) > 65536 {
				result.Truncated = true
				break scan
			}
			matched := strings.Contains(line, pattern)
			if expression != nil {
				matched = expression.MatchString(line)
			}
			if !matched {
				continue
			}
			matches++
			if matches > request.Limit {
				result.Truncated = true
				break scan
			}
			for row := max(emittedThrough+1, index-int(request.ContextLines)); row <= min(len(lines)-1, index+int(request.ContextLines)); row++ {
				text := fmt.Sprintf("%s:%d: %s", path, row+1, lines[row])
				if len(result.Lines) >= 200 || len(text)+1 > 64*1024-160-bytes {
					result.Truncated = true
					break scan
				}
				result.Lines = append(result.Lines, text)
				bytes += len(text) + 1
				emittedThrough = row
			}
		}
	}
	current, _, err := latest(fd, request.WorkspaceInstanceId)
	if err != nil {
		return nil, storageError(err)
	}
	if current.Snapshot.Revision != request.ExpectedRevision {
		return nil, status.Error(codes.Aborted, "revision conflict")
	}
	if result.Truncated {
		result.Lines = append(result.Lines, "[Results truncated; narrow pattern or path_prefix to continue]")
	}
	return result, nil
}
