package protocol

import "fmt"

// TestDispatch is trusted Java metadata, NOT a browser-selectable authorization.
// Final dispatches carry Java's persisted attempt ID and finalization budget end.
// FreezeTransition plus Epoch identifies the model freeze receipt; production
// uses an explicit receipt ID bound to the immutable workspace instance.
type TestDispatch struct {
	TaskID, Purpose, FreezeTransition, AttemptID string
	Epoch, Revision, NotAfter                    int
}

// StartTest models atomic admission, snapshot binding and execution registration.
// Manual requests use the answering gate/deadline; system final requests instead
// require a matching frozen input and an unexpired finalization authorization.
// Actual Java attempt persistence, authentication and Docker launch are not modeled.
func (w *Workspace) StartTest(tasks *Tasks, request TestDispatch, now int) (Task, error) {
	if request.TaskID == "" || request.Revision != w.state.Revision {
		return Task{}, ErrConflict
	}
	if now >= request.NotAfter {
		return Task{}, ErrExpired
	}
	if request.Epoch != w.state.Epoch {
		return Task{}, ErrFenced
	}
	switch request.Purpose {
	case "manual":
		if w.state.Closed || w.state.Frozen {
			return Task{}, ErrFenced
		}
		if now >= w.state.Deadline {
			return Task{}, ErrExpired
		}
	case "final":
		if !w.state.Closed || !w.state.Frozen || request.FreezeTransition != w.state.Transition || request.AttemptID == "" {
			return Task{}, ErrFenced
		}
	default:
		return Task{}, ErrConflict
	}
	// The model contains no test payload; bind all of its immutable dispatch fields.
	fingerprint := fmt.Sprintf("%q:%q:%q:%d:%d:%d", request.Purpose, request.FreezeTransition, request.AttemptID, request.Epoch, request.Revision, request.NotAfter)
	return tasks.Start(request.TaskID, fingerprint)
}
