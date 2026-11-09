//go:build !linux

package main

import (
	"fmt"
	"os"
)

func acquireStorage() (*os.File, error) {
	return nil, fmt.Errorf("private workspace storage requires Linux")
}
