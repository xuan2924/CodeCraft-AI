package workspace

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"errors"
	"fmt"
	"slices"
	"syscall"

	rpc "codecraft.local/sandbox/internal/gen"
)

// Root ownership is held by the process before NewService. A corrupt identity fails closed.
func storageIdentity(fd int) (string, error) {
	value, err := readPath(fd, ".storage-id")
	if errors.Is(err, syscall.ENOENT) {
		data := make([]byte, 32)
		if _, err = rand.Read(data); err != nil {
			return "", err
		}
		value = []byte(hex.EncodeToString(data))
		if err = writePath(fd, ".storage-id", value); err != nil {
			return "", err
		}
	} else if err != nil {
		return "", err
	}
	if !digest.Match(value) {
		return "", fmt.Errorf("invalid storage identity")
	}
	return string(value), nil
}

// NodeStatus is authenticated and reports a durable volume identity plus process readiness.
func (s *Service) NodeStatus(ctx context.Context, request *rpc.NodeStatusRequest) (*rpc.NodeStatusResponse, error) {
	release, err := s.enter(ctx, "")
	if err != nil {
		return nil, err
	}
	defer release()
	result := &rpc.NodeStatusResponse{StorageId: s.storageID, LoadedPackageSha256: slices.Clone(s.loadedPackageSHA256)}
	if s.executor != nil {
		active, maximum, ready := s.executor.Capacity()
		result.HostEpoch = s.executor.HostEpoch()
		result.ActiveSlots = uint32(active)
		result.MaxSlots = uint32(maximum)
		result.Ready = ready
	}
	return result, nil
}
