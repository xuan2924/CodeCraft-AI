package workspace

import (
	"context"
	"errors"
	"fmt"
	"syscall"

	"golang.org/x/sys/unix"
)

// Blobs belong to one workspace (and hence one quota/GC owner). The caller
// holds that workspace's lock. Never overwrite or link an existing blob.
func storeBlobs(ctx context.Context, parent, stage int, hashes, files map[string]string) error {
	if len(files) == 0 {
		return nil
	}
	blobs, err := directory(parent, "blobs", true)
	if err != nil {
		return err
	}
	defer syscall.Close(blobs)
	for path, text := range files {
		if err = ctx.Err(); err != nil {
			return err
		}
		sha := sum([]byte(text))
		if sha != hashes[path] {
			return fmt.Errorf("blob manifest mismatch")
		}
		old, err := readPath(blobs, sha)
		if err == nil {
			if sum(old) != sha {
				return fmt.Errorf("damaged existing blob")
			}
			continue
		}
		if !errors.Is(err, syscall.ENOENT) {
			return err
		}
		if err = writePath(stage, "new-blob", []byte(text)); err != nil {
			return err
		}
		if err = unix.Renameat2(stage, "new-blob", blobs, sha, unix.RENAME_NOREPLACE); err != nil {
			return err
		}
	}
	return syscall.Fsync(blobs)
}
