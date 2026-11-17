package security

import _ "embed"

// SeccompProfile is embedded because the coordinator image has no filesystem tools.
// Default deny also blocks new kernel syscalls until explicitly reviewed.
//
//go:embed seccomp.json
var SeccompProfile string
