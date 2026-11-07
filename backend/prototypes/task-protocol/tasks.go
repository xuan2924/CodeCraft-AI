package protocol

// Task is Go's model execution receipt. Quiescent means no process can still run.
type Task struct {
	ID, Fingerprint, State string
	Quiescent              bool
}

// Tasks models one exercise's execution slot and durable execution records.
type Tasks struct{ records map[string]Task }

// NewTasks creates an empty model registry.
func NewTasks() *Tasks { return &Tasks{records: map[string]Task{}} }

// Checkpoint copies execution records as a simulated persistent snapshot.
func (s *Tasks) Checkpoint() []Task {
	records := make([]Task, 0, len(s.records))
	for _, task := range s.records {
		records = append(records, task)
	}
	return records
}

// RestoreTasks recovers a model registry, without reissuing Start.
func RestoreTasks(records []Task) *Tasks {
	tasks := NewTasks()
	for _, task := range records {
		tasks.records[task.ID] = task
	}
	return tasks
}

// Interrupt records an unconfirmable outcome without claiming process termination.
func (s *Tasks) Interrupt(id string) {
	task, found := s.records[id]
	if !found || terminal(task.State) {
		return
	}
	task.State = "interrupted"
	s.records[id] = task
}

// ConfirmQuiescent releases a quarantined execution slot after external fencing.
func (s *Tasks) ConfirmQuiescent(id string) {
	task, found := s.records[id]
	if !found || !terminal(task.State) {
		return
	}
	task.Quiescent = true
	s.records[id] = task
}

// Query returns an empty record for absence; absence does not cancel a late Start.
func (s *Tasks) Query(id string) Task { return s.records[id] }

// Start deduplicates before enforcing the single active execution slot.
func (s *Tasks) Start(id, fingerprint string) (Task, error) {
	if task, found := s.records[id]; found {
		if task.Fingerprint != "" && task.Fingerprint != fingerprint {
			return Task{}, ErrConflict
		}
		return task, nil
	}
	for _, task := range s.records {
		if !task.Quiescent {
			return Task{}, ErrConflict
		}
	}
	task := Task{ID: id, Fingerprint: fingerprint, State: "running"}
	s.records[id] = task
	return task, nil
}

func terminal(state string) bool {
	switch state {
	case "passed", "failed", "timed_out", "stopped", "environment_error", "interrupted":
		return true
	default:
		return false
	}
}

// Stop durably records intent; before Start it installs a cancellation tombstone.
func (s *Tasks) Stop(id string) {
	task, found := s.records[id]
	if !found {
		s.records[id] = Task{ID: id, State: "stopped", Quiescent: true}
		return
	}
	if terminal(task.State) {
		return
	}
	task.State = "stopping"
	s.records[id] = task
}

// Complete publishes a terminal result only when process termination is confirmed.
func (s *Tasks) Complete(id, result string, quiescent bool) {
	task, found := s.records[id]
	if !found || terminal(task.State) || !quiescent || !terminal(result) {
		return
	}
	if task.State == "stopping" {
		result = "stopped"
	}
	task.State, task.Quiescent = result, true
	s.records[id] = task
}
