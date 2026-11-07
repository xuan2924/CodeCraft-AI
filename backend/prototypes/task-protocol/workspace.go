// Package protocol is a sequential state model, not a service or durable store.
// Each method represents one atomic step; callers enumerate competing step orders.
package protocol

import "errors"

var (
	ErrFenced   = errors.New("operation fenced")
	ErrConflict = errors.New("request or revision conflict")
	ErrExpired  = errors.New("authorization or exercise expired")
)

// WriteRequest represents trusted Java dispatch metadata, never browser authority.
type WriteRequest struct {
	ID, Fingerprint       string
	Epoch, Base, NotAfter int
}

// Receipt is a committed file result, independent of response delivery.
type Receipt struct {
	Fingerprint string
	Revision    int
}

// WorkspaceState is the simulated durable record. Checkpoint is NOT disk I/O.
type WorkspaceState struct {
	Epoch, Revision, Deadline int
	Closed, Frozen            bool
	Transition                string
	Receipts                  map[string]Receipt
}

// Workspace models the shared serialization point of publication and fencing.
type Workspace struct{ state WorkspaceState }

// NewWorkspace creates an already prepared, active model workspace.
func NewWorkspace(deadline int) *Workspace {
	return &Workspace{WorkspaceState{Epoch: 1, Deadline: deadline, Receipts: map[string]Receipt{}}}
}

// RestoreWorkspace reconstructs only simulated durable state after a model crash.
func RestoreWorkspace(state WorkspaceState) *Workspace {
	workspace := &Workspace{state}
	workspace.state = workspace.Checkpoint()
	return workspace
}

// Checkpoint copies the state to prevent model restart from sharing mutable maps.
func (w *Workspace) Checkpoint() WorkspaceState {
	state := w.state
	state.Receipts = make(map[string]Receipt, len(w.state.Receipts))
	for key, receipt := range w.state.Receipts {
		state.Receipts[key] = receipt
	}
	return state
}

// View exposes current generation, revision and gate state without receipt aliases.
func (w *Workspace) View() WorkspaceState { return w.Checkpoint() }

// Query returns absence at this instant, not cancellation of a delayed request.
func (w *Workspace) Query(id string) (Receipt, bool) {
	receipt, found := w.state.Receipts[id]
	return receipt, found
}

// Apply models atomic content publication with receipt, after trusted authorization.
// An old success may be read even after fencing; it never creates a new effect.
func (w *Workspace) Apply(request WriteRequest, now int) (Receipt, error) {
	if receipt, found := w.Query(request.ID); found {
		if receipt.Fingerprint != request.Fingerprint {
			return Receipt{}, ErrConflict
		}
		return receipt, nil
	}
	if w.state.Closed || request.Epoch != w.state.Epoch {
		return Receipt{}, ErrFenced
	}
	if now >= request.NotAfter || now >= w.state.Deadline {
		return Receipt{}, ErrExpired
	}
	if request.Base != w.state.Revision {
		return Receipt{}, ErrConflict
	}
	w.state.Revision++
	receipt := Receipt{request.Fingerprint, w.state.Revision}
	w.state.Receipts[request.ID] = receipt
	return receipt, nil
}

// Close advances the durable gate generation, ordered with file publication.
func (w *Workspace) Close(expected int, transition string) error {
	if w.state.Epoch == expected+1 && w.state.Closed && w.state.Transition == transition {
		return nil
	}
	if w.state.Epoch != expected || w.state.Closed {
		return ErrFenced
	}
	w.state.Epoch++
	w.state.Closed = true
	w.state.Transition = transition
	return nil
}

// Freeze fixes the final revision only after a confirmed close barrier.
func (w *Workspace) Freeze(expected int) (int, error) {
	if w.state.Epoch != expected || !w.state.Closed {
		return 0, ErrFenced
	}
	w.state.Frozen = true
	return w.state.Revision, nil
}

// Open activates a new generation only for the transition that closed the gate.
func (w *Workspace) Open(expected int, transition string) error {
	if w.state.Frozen {
		return ErrFenced
	}
	if w.state.Epoch == expected+1 && !w.state.Closed && w.state.Transition == transition {
		return nil
	}
	if w.state.Epoch != expected || !w.state.Closed || w.state.Transition != transition {
		return ErrFenced
	}
	w.state.Epoch++
	w.state.Closed = false
	return nil
}
