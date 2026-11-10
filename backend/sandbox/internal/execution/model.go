// Package execution owns restricted Docker runs and their durable evidence.
// It is an internal adapter, not a browser API or an authorization authority.
package execution

import "time"

// Limits are trusted, validated publication settings, not user overrides.
type Limits struct {
	CPUs           int64 `json:"cpus"`
	MemoryMiB      int64 `json:"memoryMiB"`
	PIDs           int64 `json:"pids"`
	WorkMiB        int64 `json:"workMiB"`
	TmpMiB         int64 `json:"tmpMiB"`
	ShmMiB         int64 `json:"shmMiB"`
	TimeoutSeconds int64 `json:"timeoutSeconds"`
	LogBytes       int   `json:"logBytes"`
}

// Input identifies one already-authorized stable input. The coordinator must
// reserve its business slot and persist identity before invoking Run. Root and
// Volume are deployment configuration; Subpath must identify only this copy.
type Input struct {
	ExerciseID     string    `json:"exerciseId,omitempty"`
	TaskID         string    `json:"taskId,omitempty"`
	ProblemID      string    `json:"problemId,omitempty"`
	ProblemVersion string    `json:"problemVersion,omitempty"`
	GateEpoch      uint64    `json:"gateEpoch,omitempty"`
	ExecutionID    string    `json:"executionId"`
	WorkspaceID    string    `json:"workspaceId"`
	Revision       uint64    `json:"revision"`
	Image          string    `json:"image"`
	PackageSHA256  string    `json:"packageSha256"`
	Platform       string    `json:"platform"`
	Target         string    `json:"target"`
	Subpath        string    `json:"subpath"`
	NotAfter       time.Time `json:"notAfter"`
	Limits         Limits    `json:"limits"`
	Suite          string    `json:"suite"`
	Tests          []string  `json:"tests"`
}

// LogChunk preserves stdout/stderr separately; bytes are bounded before encoding.
type LogChunk struct {
	Channel string `json:"channel"`
	Data    []byte `json:"data"`
}

// Result keeps execution outcome, process quiescence and cleanup distinct.
// ExitCode belongs to the controlled Maven invocation, not the holding shell.
type Result struct {
	Version           uint64     `json:"stateVersion,omitempty"`
	StreamID          string     `json:"streamId,omitempty"`
	Sequence          uint64     `json:"sequence,omitempty"`
	Events            []Event    `json:"events,omitempty"`
	Input             Input      `json:"input"`
	State             string     `json:"state"`
	Reason            string     `json:"reason"`
	Intent            string     `json:"intent,omitempty"`
	ContainerID       string     `json:"containerId"`
	StartedAt         time.Time  `json:"startedAt"`
	FinishedAt        *time.Time `json:"finishedAt,omitempty"`
	ExitCode          *int       `json:"exitCode,omitempty"`
	ContainerExitCode *int       `json:"containerExitCode,omitempty"`
	OOMKilled         bool       `json:"oomKilled"`
	Quiescent         bool       `json:"quiescent"`
	Cleanup           string     `json:"cleanup"`
	CopyCleanup       string     `json:"copyCleanup,omitempty"`
	Report            []byte     `json:"report,omitempty"`
	CompilationReport []byte     `json:"compilationReport,omitempty"`
	Logs              []LogChunk `json:"logs,omitempty"`
	DroppedBytes      uint64     `json:"droppedBytes"`
	LogsIncomplete    bool       `json:"logsIncomplete"`
}
