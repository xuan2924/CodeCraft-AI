package workspace

import (
	"context"
	"errors"
	"fmt"
	"os"
	"syscall"

	rpc "codecraft.local/sandbox/internal/gen"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"
)

// DeleteWorkspace first persists an irreversible admission fence, then removes
// payloads only when execution cleanup is confirmed. A failure never reopens it.
func (s *Service) DeleteWorkspace(ctx context.Context, request *rpc.QueryWorkspaceRequest) (*rpc.WorkspaceDeletionReceipt, error) {
	release, err := s.enter(ctx)
	if err != nil {
		return nil, err
	}
	defer release()
	fd, err := s.rawInstance(request.WorkspaceInstanceId, true)
	if err != nil {
		return nil, err
	}
	defer syscall.Close(fd)
	data, err := readPath(fd, "deleted")
	if errors.Is(err, syscall.ENOENT) {
		// Atomically publish the tombstone using the same durable stage protocol.
		if err = writePath(fd, "stage-delete", []byte(request.WorkspaceInstanceId)); errors.Is(err, syscall.EEXIST) {
			if err = syscall.Unlinkat(fd, "stage-delete"); err == nil {
				err = writePath(fd, "stage-delete", []byte(request.WorkspaceInstanceId))
			}
		}
		if err == nil {
			err = syscall.Renameat(fd, "stage-delete", fd, "deleted")
		}
		if err != nil {
			return nil, storageError(err)
		}
	} else if err != nil {
		return nil, storageError(err)
	} else if string(data) != request.WorkspaceInstanceId {
		return nil, status.Error(codes.DataLoss, "invalid deletion fence")
	}
	// Also sync on replay: a failed directory flush is not a durable receipt.
	if err = syscall.Fsync(fd); err != nil {
		return nil, storageError(err)
	}
	if err := s.quiesceTerminals(ctx, request.WorkspaceInstanceId); err != nil {
		return nil, err
	}
	if s.executor != nil {
		after := ""
		for {
			entries, next, listErr := s.executor.List(ctx, after, 100)
			if listErr != nil {
				return nil, storageError(listErr)
			}
			for _, entry := range entries {
				if entry.Identity.WorkspaceID == request.WorkspaceInstanceId {
					if _, err = s.executor.Stop(ctx, entry.Identity); err != nil {
						return nil, storageError(err)
					}
				}
			}
			if next == "" {
				break
			}
			after = next
		}
		if err = s.executor.DeleteWorkspace(ctx, request.WorkspaceInstanceId); err != nil {
			return nil, storageError(err)
		}
	}
	root, err := os.OpenRoot(fmt.Sprintf("/proc/self/fd/%d", fd))
	if err != nil {
		return nil, storageError(err)
	}
	defer root.Close()
	directory, err := os.Open(fmt.Sprintf("/proc/self/fd/%d", fd))
	if err != nil {
		return nil, storageError(err)
	}
	entries, readErr := directory.ReadDir(-1)
	closeErr := directory.Close()
	if readErr != nil {
		return nil, storageError(readErr)
	}
	if closeErr != nil {
		return nil, storageError(closeErr)
	}
	for _, entry := range entries {
		if err = ctx.Err(); err != nil {
			return nil, storageError(err)
		}
		if entry.Name() != "deleted" {
			if err = root.RemoveAll(entry.Name()); err != nil {
				return nil, storageError(err)
			}
		}
	}
	if err = syscall.Fsync(fd); err != nil {
		return nil, storageError(err)
	}
	return &rpc.WorkspaceDeletionReceipt{WorkspaceInstanceId: request.WorkspaceInstanceId, Complete: true}, nil
}
