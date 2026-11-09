package main

import (
	"fmt"
	"os"
	"syscall"
)

// acquireStorage owns the private Linux volume for this process. A second Go
// process must not serve the same volume with an independent in-memory coordinator.
func acquireStorage() (*os.File, error) {
	root, err := syscall.Open("/var/lib/codecraft", syscall.O_RDONLY|syscall.O_DIRECTORY|syscall.O_NOFOLLOW|syscall.O_CLOEXEC, 0)
	if err != nil {
		return nil, fmt.Errorf("open private storage: %w", err)
	}
	defer syscall.Close(root)
	fd, err := syscall.Openat(root, ".owner.lock", syscall.O_CREAT|syscall.O_RDWR|syscall.O_NOFOLLOW|syscall.O_CLOEXEC|syscall.O_NONBLOCK, 0600)
	if err != nil {
		return nil, fmt.Errorf("open storage ownership lock: %w", err)
	}
	file := os.NewFile(uintptr(fd), "storage ownership lock")
	var info syscall.Stat_t
	if err = syscall.Fstat(fd, &info); err == nil && (info.Mode&syscall.S_IFMT != syscall.S_IFREG || info.Nlink != 1) {
		err = fmt.Errorf("storage ownership lock must be a regular single-link file")
	}
	if err == nil {
		err = syscall.Flock(fd, syscall.LOCK_EX|syscall.LOCK_NB)
	}
	if err != nil {
		file.Close()
		return nil, fmt.Errorf("acquire exclusive storage ownership: %w", err)
	}
	return file, nil
}
