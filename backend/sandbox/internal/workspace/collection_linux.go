package workspace

import (
	"context"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"os"
	"sort"
	"strconv"
	"strings"
	"syscall"

	rpc "codecraft.local/sandbox/internal/gen"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"
)

// CollectWorkspace uses the same admission gate as publication and test-copy
// creation. The expected current revision fences Java's reference snapshot:
// future admissions can reference the current version, never an unpinned old one.
func (s *Service) CollectWorkspace(ctx context.Context, request *rpc.CollectWorkspaceRequest) (*rpc.WorkspaceCollectionReceipt, error) {
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
	current, latestName, err := latest(fd, request.WorkspaceInstanceId)
	if err != nil {
		return nil, storageError(err)
	}
	if current.Snapshot.Cancelled {
		return nil, status.Error(codes.FailedPrecondition, "workspace cancelled")
	}
	if current.Snapshot.Revision != request.ExpectedRevision {
		return nil, status.Error(codes.Aborted, "reference snapshot is stale")
	}
	if err = verifyPreparedTree(ctx, fd, current); err != nil {
		return nil, storageError(err)
	}
	retain := map[uint64]string{current.Snapshot.Revision: latestName}
	for _, revision := range request.RetainedRevisions {
		if revision > current.Snapshot.Revision {
			return nil, status.Error(codes.InvalidArgument, "future revision reference")
		}
		if _, exists := retain[revision]; !exists {
			retain[revision] = ""
		}
	}
	root, err := os.OpenRoot(fmt.Sprintf("/proc/self/fd/%d", fd))
	if err != nil {
		return nil, storageError(err)
	}
	defer root.Close()
	dirFile, err := root.Open(".")
	if err != nil {
		return nil, storageError(err)
	}
	entries, err := dirFile.ReadDir(-1)
	dirFile.Close()
	if err != nil {
		return nil, storageError(err)
	}
	live := map[string]bool{}
	commits := []string{}
	garbage := []string{}
	for _, entry := range entries {
		name := entry.Name()
		if exec, ok := strings.CutPrefix(name, "command-"); ok {
			if !uuid.MatchString(exec) {
				return nil, status.Error(codes.DataLoss, "invalid command identity")
			}
			marker, err := readPath(fd, name+"/released")
			if err == nil && string(marker) == exec {
				continue
			}
			if !errors.Is(err, syscall.ENOENT) {
				return nil, status.Error(codes.DataLoss, "invalid command release marker")
			}
			r, command, err := openCommand(fd, request.WorkspaceInstanceId, exec)
			if err != nil {
				return nil, storageError(err)
			}
			syscall.Close(command)
			for _, sha := range r.BaseHashes {
				if !digest.MatchString(sha) {
					return nil, status.Error(codes.DataLoss, "invalid command blob reference")
				}
				live[sha] = true
			}
			continue
		}
		if strings.HasPrefix(name, "gc-commit-") || strings.HasPrefix(name, "stage-") {
			garbage = append(garbage, name)
			continue
		}
		if !strings.HasPrefix(name, "commit-") {
			continue
		}
		if len(name) != 27 {
			return nil, status.Error(codes.DataLoss, "invalid commit identity")
		}
		if _, err = strconv.ParseUint(name[7:], 10, 64); err != nil {
			return nil, status.Error(codes.DataLoss, "invalid commit identity")
		}
		record, _, err := readCommit(fd, request.WorkspaceInstanceId, name)
		if err != nil {
			return nil, storageError(err)
		}
		if chosen, pinned := retain[record.Snapshot.Revision]; pinned && name > chosen {
			retain[record.Snapshot.Revision] = name
		}
		commits = append(commits, name)
	}
	pinned := map[string]bool{}
	for _, name := range retain {
		if name == "" {
			return nil, status.Error(codes.DataLoss, "referenced version missing")
		}
		pinned[name] = true
		r, _, err := readCommit(fd, request.WorkspaceInstanceId, name)
		if err != nil {
			return nil, storageError(err)
		}
		if err = verifyPreparedTree(ctx, fd, r); err != nil {
			return nil, storageError(err)
		}
		for _, h := range r.Hashes {
			live[h] = true
		}
	}
	// Validate every reference before deleting anything. A rename durably records
	// the GC decision before recursive removal, so a crash cannot leave a partially
	// removed commit that later looks like corrupt live authority.
	receipt := &rpc.WorkspaceCollectionReceipt{WorkspaceInstanceId: request.WorkspaceInstanceId, Revision: current.Snapshot.Revision}
	sort.Strings(commits)
	for _, name := range commits {
		if pinned[name] {
			continue
		}
		if err = ctx.Err(); err != nil {
			return nil, storageError(err)
		}
		tombstone := "gc-" + name
		if err = syscall.Renameat(fd, name, fd, tombstone); err != nil {
			return nil, storageError(err)
		}
		if err = syscall.Fsync(fd); err != nil {
			return nil, storageError(err)
		}
		garbage = append(garbage, tombstone)
		receipt.RemovedVersions++
	}
	for _, name := range garbage {
		if err = ctx.Err(); err != nil {
			return nil, storageError(err)
		}
		size, err := storedBytes(ctx, root.FS(), name)
		if err != nil {
			return nil, storageError(err)
		}
		if err = root.RemoveAll(name); err != nil {
			return nil, storageError(err)
		}
		receipt.ReclaimedBytes += size
	}
	blobs, err := directory(fd, "blobs", false)
	if err != nil {
		return nil, storageError(err)
	}
	bf := os.NewFile(uintptr(blobs), "workspace blobs")
	defer bf.Close()
	for {
		entries, err := bf.ReadDir(128)
		if errors.Is(err, io.EOF) {
			break
		}
		if err != nil {
			return nil, storageError(err)
		}
		for _, entry := range entries {
			if err = ctx.Err(); err != nil {
				return nil, storageError(err)
			}
			name := entry.Name()
			if !digest.MatchString(name) {
				return nil, status.Error(codes.DataLoss, "invalid blob name")
			}
			if live[name] {
				continue
			}
			data, err := readPath(blobs, name)
			if err != nil {
				return nil, storageError(err)
			}
			if err = syscall.Unlinkat(blobs, name); err != nil {
				return nil, storageError(err)
			}
			receipt.ReclaimedBytes += uint64(len(data))
		}
	}
	if err = bf.Sync(); err != nil {
		return nil, storageError(err)
	}
	if err = syscall.Fsync(fd); err != nil {
		return nil, storageError(err)
	}
	return receipt, nil
}

// Persisted content length, including every manifest and temporary file, rather
// than preallocated quota or host-wide filesystem blocks. Links are not followed.
func storedBytes(ctx context.Context, root fs.FS, path string) (uint64, error) {
	var total uint64
	err := fs.WalkDir(root, path, func(_ string, entry fs.DirEntry, walkErr error) error {
		if walkErr != nil {
			return walkErr
		}
		if err := ctx.Err(); err != nil {
			return err
		}
		info, err := entry.Info()
		if err != nil {
			return err
		}
		if info.Mode().IsRegular() || info.Mode()&os.ModeSymlink != 0 {
			if info.Size() < 0 || uint64(info.Size()) > ^uint64(0)-total {
				return errors.New("storage size overflow")
			}
			total += uint64(info.Size())
		} else if !info.IsDir() {
			return errors.New("unexpected storage object")
		}
		return nil
	})
	return total, err
}
