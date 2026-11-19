package workspace

import (
	"context"
	"errors"
	"fmt"
	"io/fs"
	"os"
	"syscall"

	"codecraft.local/sandbox/internal/execution"
)

const maxCommandWorkingBytes = 128 * 1024 * 1024
const maxCommandWorkingEntries = 20000

// Called only after confirmed quiescence; never remove candidate source files.
func (s *Service) clearCommandCaches(ctx context.Context, input execution.Input) error {
	if err := ctx.Err(); err != nil {
		return err
	}
	parent, err := s.instance(input.WorkspaceID, false)
	if err != nil {
		return err
	}
	defer syscall.Close(parent)
	command, err := directory(parent, "command-"+input.ExecutionID, false)
	if err != nil {
		return err
	}
	defer syscall.Close(command)
	tree, err := directory(command, "tree", false)
	if err != nil {
		return err
	}
	defer syscall.Close(tree)
	root, err := os.OpenRoot(fmt.Sprintf("/proc/self/fd/%d", tree))
	if err != nil {
		return err
	}
	defer root.Close()
	return errors.Join(root.RemoveAll("target"), root.RemoveAll("node_modules"))
}

// Include removable build directories in the retained-copy budget. Final
// publication still enforces the ordinary 200-file/10-MiB workspace contract.
// This is periodic admission protection, not a filesystem quota or a guarantee
// against malicious disk exhaustion between observations.
func (s *Service) commandBudget(ctx context.Context, input execution.Input) error {
	parent, err := s.instance(input.WorkspaceID, false)
	if err != nil {
		return err
	}
	defer syscall.Close(parent)
	gates, err := gateRecords(parent, input.WorkspaceID)
	if err != nil {
		return err
	}
	if len(gates) == 0 || gates[len(gates)-1].State != "OPEN" || gates[len(gates)-1].Epoch != input.GateEpoch || !s.now().Before(input.NotAfter) {
		return execution.ErrCommandAuthorityEnded
	}
	command, err := directory(parent, "command-"+input.ExecutionID, false)
	if err != nil {
		return err
	}
	defer syscall.Close(command)
	tree, err := directory(command, "tree", false)
	if err != nil {
		return err
	}
	defer syscall.Close(tree)
	root, err := os.OpenRoot(fmt.Sprintf("/proc/self/fd/%d", tree))
	if err != nil {
		return err
	}
	defer root.Close()
	var bytes int64
	budget := int64(maxCommandWorkingBytes)
	if input.Limits.WorkMiB > 0 {
		budget = min(budget, input.Limits.WorkMiB*1024*1024)
	}
	entries := 0
	scan := func(root *os.Root) error {
		return fs.WalkDir(root.FS(), ".", func(path string, entry fs.DirEntry, cause error) error {
			if cause != nil {
				if path != "." && os.IsNotExist(cause) {
					return nil // A live rename/remove is not a zero-byte storage snapshot.
				}
				return cause
			}
			if err := ctx.Err(); err != nil {
				return err
			}
			entries++
			if entries > maxCommandWorkingEntries {
				return execution.ErrCommandOutputLimit
			}
			if entry.IsDir() {
				return nil
			}
			info, err := entry.Info()
			if os.IsNotExist(err) {
				return nil
			}
			if err != nil {
				return err
			}
			if info.Size() < 0 || info.Size() > budget {
				return execution.ErrCommandOutputLimit
			}
			bytes += info.Size()
			if bytes > budget {
				return execution.ErrCommandOutputLimit
			}
			return nil
		})
	}
	if err := scan(root); err != nil {
		return err
	}
	cacheFD, err := directory(parent, "dev-cache", false)
	if err != nil {
		return err
	}
	defer syscall.Close(cacheFD)
	cache, err := os.OpenRoot(fmt.Sprintf("/proc/self/fd/%d", cacheFD))
	if err != nil {
		return err
	}
	defer cache.Close()
	return scan(cache)
}
