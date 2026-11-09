// Package status exposes only sandbox process liveness, not execution readiness.
package status

import (
	"context"

	statusv1 "codecraft.local/sandbox/internal/gen"
	"google.golang.org/grpc"
)

type service struct {
	statusv1.UnimplementedSandboxStatusServer
}

func (service) Check(ctx context.Context, _ *statusv1.CheckRequest) (*statusv1.CheckResponse, error) {
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	return &statusv1.CheckResponse{Serving: true}, nil
}

// NewServer creates a bounded gRPC server; the caller owns Serve and Stop.
func NewServer(options ...grpc.ServerOption) *grpc.Server {
	defaults := []grpc.ServerOption{grpc.MaxConcurrentStreams(32), grpc.MaxRecvMsgSize(16 * 1024)}
	server := grpc.NewServer(append(defaults, options...)...)
	statusv1.RegisterSandboxStatusServer(server, service{})
	return server
}
