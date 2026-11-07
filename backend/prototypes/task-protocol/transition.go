package protocol

// TakeoverState represents Java's durable transition intent, not a JVM lock.
type TakeoverState struct {
	ID, Phase     string
	ExpectedEpoch int
}

// Takeover models recovery of barrier/activation acknowledgements only.
// Real Java must also reconcile outstanding receipts and validate current identity.
type Takeover struct{ state TakeoverState }

// BeginTakeover closes Java admission by persisting an intent before any dispatch.
func BeginTakeover(id string, expected int) *Takeover {
	return &Takeover{TakeoverState{ID: id, Phase: "closing", ExpectedEpoch: expected}}
}

// RestoreTakeover reconstructs a transition from a simulated Java checkpoint.
func RestoreTakeover(state TakeoverState) *Takeover { return &Takeover{state} }

// Checkpoint returns the intent persisted independently of Go's gate record.
func (t *Takeover) Checkpoint() TakeoverState { return t.state }

// Complete means activation was confirmed, not merely requested.
func (t *Takeover) Complete() bool { return t.state.Phase == "complete" }

// Advance retries one idempotent protocol step, committing Java phase after its reply.
func (t *Takeover) Advance(workspace *Workspace) error {
	switch t.state.Phase {
	case "closing":
		if err := workspace.Close(t.state.ExpectedEpoch, t.state.ID); err != nil {
			return err
		}
		t.state.Phase = "activating"
	case "activating":
		if err := workspace.Open(t.state.ExpectedEpoch+1, t.state.ID); err != nil {
			return err
		}
		t.state.Phase = "complete"
	}
	return nil
}
