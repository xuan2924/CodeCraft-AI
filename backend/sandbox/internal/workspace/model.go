// Package workspace owns immutable workspace files behind the internal gRPC boundary.
package workspace

import rpc "codecraft.local/sandbox/internal/gen"

// MaxBatchRequestBytes bounds gRPC decoding before application validation. It
// accommodates 10 MiB of text plus bounded paths and protobuf metadata.
const MaxBatchRequestBytes = 12 * 1024 * 1024

// Publication is a trusted, previously validated problem version. Reference
// solutions are not part of this input and must never enter a workspace.
type Publication struct {
	Identity     *rpc.ProblemIdentity
	InitialFiles map[string]string
}
