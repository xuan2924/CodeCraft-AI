//go:build !linux

package main

import (
	"fmt"

	"google.golang.org/grpc"
)

func registerWorkspace(_ *grpc.Server, _, _ string) (func() error, error) {
	return func() error { return nil }, fmt.Errorf("workspace storage requires Linux")
}
