package protocol

// Event is a model envelope; production adds stream identity and typed payloads.
type Event struct {
	Sequence   int
	Kind, Data string
}

// EventSnapshot is captured atomically with its cursor, not in separate reads.
type EventSnapshot struct {
	Cursor    int
	Truncated bool
	Result    string
	Events    []Event
}

// EventBatch instructs clients to replace state from a snapshot if replay has gaps.
type EventBatch struct {
	Reset  bool
	Events []Event
}

// Events models a bounded retained stream and independently queryable result.
type Events struct {
	limit int
	state EventSnapshot
}

// NewEvents creates a bounded model stream; limits must be positive.
func NewEvents(limit int) *Events {
	if limit < 1 {
		panic("event limit must be positive")
	}
	return &Events{limit: limit}
}

// Append atomically updates the retained stream and materialized terminal result.
func (s *Events) Append(kind, data string) {
	s.state.Cursor++
	s.state.Events = append(s.state.Events, Event{s.state.Cursor, kind, data})
	if kind == "result" {
		s.state.Result = data
	}
	if len(s.state.Events) > s.limit {
		s.state.Events = append([]Event(nil), s.state.Events[len(s.state.Events)-s.limit:]...)
		s.state.Truncated = true
	}
}

// Snapshot returns an isolated view with a consistent high-water cursor.
func (s *Events) Snapshot() EventSnapshot {
	state := s.state
	state.Events = append([]Event(nil), state.Events...)
	return state
}

// Subscribe returns retained events after the cursor or an explicit reset signal.
func (s *Events) Subscribe(after int) EventBatch {
	if after < 0 || after > s.state.Cursor {
		return EventBatch{Reset: true}
	}
	if len(s.state.Events) > 0 && after < s.state.Events[0].Sequence-1 {
		return EventBatch{Reset: true}
	}
	batch := EventBatch{}
	for _, event := range s.state.Events {
		if event.Sequence > after {
			batch.Events = append(batch.Events, event)
		}
	}
	return batch
}
