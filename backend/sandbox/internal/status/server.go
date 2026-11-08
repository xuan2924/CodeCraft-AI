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
func NewServer() *grpc.Server {
	server := grpc.NewServer(grpc.MaxConcurrentStreams(32), grpc.MaxRecvMsgSize(1024))
	statusv1.RegisterSandboxStatusServer(server, service{})
	return server
}
