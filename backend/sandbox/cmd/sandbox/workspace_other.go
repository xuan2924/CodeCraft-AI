//go:build !linux

package main

import (
	"fmt"

	"google.golang.org/grpc"
)

func registerWorkspace(_ *grpc.Server, _ string) error {
	return fmt.Errorf("workspace storage requires Linux")
}
