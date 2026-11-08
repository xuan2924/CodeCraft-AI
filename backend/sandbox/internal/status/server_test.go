package status_test

import (
	"context"
	"net"
	"testing"
	"time"

	statusv1 "codecraft.local/sandbox/internal/gen"
	"codecraft.local/sandbox/internal/status"
	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials/insecure"
)

func TestCheckReportsServingOverGRPC(t *testing.T) {
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	server := status.NewServer()
	done := make(chan error, 1)
	go func() { done <- server.Serve(listener) }()
	t.Cleanup(func() {
		server.Stop()
		if err := <-done; err != nil {
			t.Errorf("serve: %v", err)
		}
	})
	connection, err := grpc.NewClient(listener.Addr().String(), grpc.WithTransportCredentials(insecure.NewCredentials()))
	if err != nil {
		t.Fatal(err)
	}
	defer connection.Close()
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	response, err := statusv1.NewSandboxStatusClient(connection).Check(ctx, &statusv1.CheckRequest{})
	if err != nil {
		t.Fatal(err)
	}
	if !response.GetServing() {
		t.Fatal("running service reported unavailable")
	}
}
