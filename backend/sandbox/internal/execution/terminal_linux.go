package execution

import (
	"context"
	"errors"
	"io"
	"net"
	"strings"
	"sync"
	"time"

	"github.com/docker/docker/api/types/container"
)

// ConfigureTerminalEnvironment installs a trusted, process-owned grant provider
// before serving. Browser/protobuf input cannot select environment variables.
// Values are never stored in Input, recovery labels, or application logs.
func (r *Runner) ConfigureTerminalEnvironment(provider func(context.Context, Input) ([]string, error)) error {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.running || r.closed {
		return ErrBusy
	}
	r.terminalEnvironment = provider
	return nil
}

func (r *Runner) terminalGrant(ctx context.Context, input Input) ([]string, error) {
	if input.Command == nil || !input.Command.Terminal || r.terminalEnvironment == nil {
		return nil, nil
	}
	values, err := r.terminalEnvironment(ctx, input)
	if err != nil {
		return nil, errors.New("terminal environment grant unavailable")
	}
	seen := map[string]bool{}
	size := 0
	for _, value := range values {
		key, _, ok := strings.Cut(value, "=")
		size += len(value)
		if !ok || seen[key] || size > 4096 || strings.ContainsAny(value, "\x00\r\n") || (key != "ANTHROPIC_BASE_URL" && key != "OPENAI_BASE_URL" && key != "CODECRAFT_PROXY_TOKEN" && key != "ANTHROPIC_API_KEY" && key != "OPENAI_API_KEY") {
			return nil, errors.New("invalid terminal environment grant")
		}
		seen[key] = true
	}
	var token string
	for _, value := range values {
		if strings.HasPrefix(value, "CODECRAFT_PROXY_TOKEN=") {
			token = strings.TrimPrefix(value, "CODECRAFT_PROXY_TOKEN=")
		}
	}
	for _, value := range values {
		key, secret, _ := strings.Cut(value, "=")
		if (key == "ANTHROPIC_API_KEY" || key == "OPENAI_API_KEY") && (token == "" || secret != token) {
			return nil, errors.New("model key must be an ephemeral proxy token alias")
		}
	}
	return append([]string(nil), values...), nil
}

// ErrTerminalAttached rejects a second transport without cancelling the first.
var ErrTerminalAttached = errors.New("terminal already attached")

// Terminal owns a single Docker-allocated real PTY. Output pressure is bounded:
// a slow consumer terminates the session instead of blocking process cleanup.
type Terminal struct {
	mu           sync.Mutex
	conn         net.Conn
	output       chan []byte
	closed       chan struct{}
	once         sync.Once
	attached     bool
	lastActivity time.Time
	runner       *Runner
	input        Input
}

func (r *Runner) newTerminal(input Input, conn net.Conn) *Terminal {
	t := &Terminal{conn: conn, output: make(chan []byte, 32), closed: make(chan struct{}), lastActivity: time.Now(), runner: r, input: input}
	r.terminalMu.Lock()
	defer r.terminalMu.Unlock()
	if r.terminals == nil {
		r.terminals = map[string]*Terminal{}
	}
	r.terminals[input.ExecutionID] = t
	return t
}

func (t *Terminal) finish() { t.once.Do(func() { close(t.closed) }) }

// Write is the stdout sink. It copies bounded fragments and never blocks Docker's
// log-draining goroutine. A full queue fences the execution, not just the socket.
func (t *Terminal) Write(data []byte) (int, error) {
	total := len(data)
	for len(data) > 0 {
		size := min(len(data), 16*1024)
		chunk := append([]byte(nil), data[:size]...)
		data = data[size:]
		select {
		case t.output <- chunk:
		default:
			t.finish()
			return 0, errors.New("terminal output backpressure")
		}
	}
	return total, nil
}

// Output and Done are receive-only; the runner retains close ownership.
func (t *Terminal) Output() <-chan []byte { return t.output }
func (t *Terminal) Done() <-chan struct{} { return t.closed }

// Input writes raw terminal bytes (including Ctrl+C) to Docker's controlling PTY.
func (t *Terminal) Input(ctx context.Context, data []byte) error {
	if len(data) == 0 || len(data) > 16*1024 {
		return errors.New("terminal input exceeds frame bound")
	}
	if err := ctx.Err(); err != nil {
		return err
	}
	t.mu.Lock()
	defer t.mu.Unlock()
	select {
	case <-t.closed:
		return io.EOF
	default:
	}
	if err := t.conn.SetWriteDeadline(time.Now().Add(5 * time.Second)); err != nil {
		return err
	}
	_, err := t.conn.Write(data)
	if err == nil {
		t.lastActivity = time.Now()
	}
	return err
}

// Resize changes the real PTY size; the kernel delivers SIGWINCH to its foreground job.
func (t *Terminal) Resize(ctx context.Context, cols, rows uint32) error {
	if cols < 1 || cols > 500 || rows < 1 || rows > 300 {
		return errors.New("invalid terminal dimensions")
	}
	call, cancel := context.WithTimeout(ctx, 5*time.Second)
	defer cancel()
	err := t.runner.docker.ContainerResize(call, "cc-execution-"+t.input.ExecutionID, container.ResizeOptions{Width: uint(cols), Height: uint(rows)})
	if err == nil {
		t.mu.Lock()
		t.lastActivity = time.Now()
		t.mu.Unlock()
	}
	return err
}

// Terminal returns an exact owned, single-attach PTY; it never starts an execution.
func (r *Runner) Terminal(ctx context.Context, identity TaskIdentity) (*Terminal, error) {
	r.terminalMu.Lock()
	defer r.terminalMu.Unlock()
	t := r.terminals[identity.ExecutionID]
	record, err := r.Query(ctx, identity.ExecutionID)
	if err != nil {
		return nil, err
	}
	if t == nil || record.State != "RUNNING" {
		return nil, ErrBusy
	}
	if !identity.matches(t.input) {
		return nil, ErrConflict
	}
	t.mu.Lock()
	defer t.mu.Unlock()
	if t.attached {
		return nil, ErrTerminalAttached
	}
	t.attached = true
	return t, nil
}

func (r *Runner) terminalExpired(id string) bool {
	r.terminalMu.Lock()
	t := r.terminals[id]
	r.terminalMu.Unlock()
	if t == nil {
		return false
	}
	select {
	case <-t.closed:
		return true
	default:
	}
	t.mu.Lock()
	defer t.mu.Unlock()
	timeout := 5 * time.Minute
	if !t.attached {
		timeout = 30 * time.Second
	}
	return time.Since(t.lastActivity) >= timeout
}

// TerminalBusy prevents a second writer from racing an admitted development copy.
func (r *Runner) TerminalBusy(ctx context.Context, workspace, publishingExecution string) bool {
	r.stateMu.Lock()
	defer r.stateMu.Unlock()
	for id := range r.pendingTerminals[workspace] {
		record, err := r.Query(ctx, id)
		if err != nil || !record.Quiescent || (record.CopyCleanup != "SUCCEEDED" && id != publishingExecution) {
			return true
		}
	}
	return false
}

// indexTerminal runs under stateMu (or during construction). Only unresolved
// registrations are retained, including those awaiting copy publication.
func (r *Runner) indexTerminal(record Result) {
	in := record.Input
	if in.Command == nil || !in.Command.Terminal {
		return
	}
	entries := r.pendingTerminals[in.WorkspaceID]
	if record.Quiescent && record.Cleanup == "SUCCEEDED" && record.CopyCleanup == "SUCCEEDED" {
		delete(entries, in.ExecutionID)
		if len(entries) == 0 {
			delete(r.pendingTerminals, in.WorkspaceID)
		}
		return
	}
	if entries == nil {
		if r.pendingTerminals == nil {
			r.pendingTerminals = make(map[string]map[string]TaskIdentity)
		}
		entries = make(map[string]TaskIdentity)
		r.pendingTerminals[in.WorkspaceID] = entries
	}
	entries[in.ExecutionID] = TaskIdentity{WorkspaceID: in.WorkspaceID, ExerciseID: in.ExerciseID, TaskID: in.TaskID, ExecutionID: in.ExecutionID}
}

// QuiesceTerminal stops only the stream's exact instance. A late disconnect must
// never terminate a newly opened terminal for the same exercise.
func (r *Runner) QuiesceTerminal(ctx context.Context, identity TaskIdentity) error {
	record, err := r.Stop(ctx, identity)
	if err != nil {
		return err
	}
	if record == nil {
		return errors.New("registered terminal lacks quiescence evidence")
	}
	if record.Input.Command == nil || !record.Input.Command.Terminal {
		return ErrConflict
	}
	for {
		if record.Quiescent && record.Cleanup == "SUCCEEDED" {
			return nil
		}
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-time.After(25 * time.Millisecond):
		}
		observed, readErr := r.Query(ctx, identity.ExecutionID)
		if readErr != nil {
			return readErr
		}
		record = &observed
	}
}

// QuiesceTerminals is called after a durable write fence. It waits for process
// quiescence only, not copy publication (whose caller may hold the workspace lock).
func (r *Runner) QuiesceTerminals(ctx context.Context, workspace string) error {
	r.stateMu.Lock()
	var pending []TaskIdentity
	for _, identity := range r.pendingTerminals[workspace] {
		pending = append(pending, identity)
	}
	r.stateMu.Unlock()
	for _, identity := range pending {
		if err := r.QuiesceTerminal(ctx, identity); err != nil {
			return err
		}
	}
	return ctx.Err()
}
