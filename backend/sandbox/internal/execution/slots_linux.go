package execution

import (
	"errors"
	"net/url"
)

// Configure sets deployment limits before the runner is published to workers.
// Commands remain offline unless an isolated network and proxy are both supplied.
func (r *Runner) Configure(concurrency int, network, proxy string) error {
	if concurrency < 1 || concurrency > 64 {
		return errors.New("execution concurrency must be between 1 and 64")
	}
	if (network == "") != (proxy == "") {
		return errors.New("command network and proxy must be configured together")
	}
	if network == "bridge" || network == "host" || network == "none" {
		return errors.New("command network must be isolated")
	}
	if proxy != "" {
		u, err := url.Parse(proxy)
		if err != nil || u.Scheme != "http" || u.Host == "" || u.User != nil || u.RawQuery != "" || u.Fragment != "" || u.Path != "" {
			return errors.New("invalid command proxy URL")
		}
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.running || r.closed {
		return ErrBusy
	}
	r.concurrency, r.commandNetwork, r.proxyURL = concurrency, network, proxy
	return nil
}

// Capacity reports readiness and occupied workers without granting a slot.
func (r *Runner) Capacity() (int, int, bool) {
	r.mu.Lock()
	defer r.mu.Unlock()
	return len(r.active), r.concurrency, !r.blocked && !r.closed
}

// Caller owns mu. A workspace and its exercise each hold an exclusive slot.
func (r *Runner) capacityBusy(input Input) bool {
	if len(r.active) >= r.concurrency {
		return true
	}
	for _, current := range r.active {
		if current.WorkspaceID == input.WorkspaceID || (input.ExerciseID != "" && current.ExerciseID == input.ExerciseID) {
			return true
		}
	}
	return false
}

func (r *Runner) reserve(input Input) {
	if !r.running {
		r.done = make(chan struct{})
	}
	r.running = true
	r.active[input.ExecutionID] = input
}
