package protocol

// ExerciseState models only preparation failure and bounded final testing policy.
type ExerciseState struct {
	Phase, FinalOutcome          string
	OccupiesSlot, CleanupPending bool
	FinalRevision, Attempts      int
}

// Exercise is not the complete product FSM; timing and identity live at other seams.
type Exercise struct {
	state       ExerciseState
	maxAttempts int
}

// NewExercise starts preparation after a hypothetical successful slot reservation.
func NewExercise(maxAttempts int) *Exercise {
	if maxAttempts < 1 {
		panic("attempt limit must be positive")
	}
	return &Exercise{state: ExerciseState{Phase: "preparing", OccupiesSlot: true}, maxAttempts: maxAttempts}
}

// BeginFinalTesting injects a confirmed freeze receipt at the agreed model seam.
// It deliberately omits ready/active/closing states of the production lifecycle.
func (e *Exercise) BeginFinalTesting(revision int) error {
	if e.state.Phase != "preparing" {
		return ErrConflict
	}
	e.state.Phase, e.state.FinalRevision = "final_testing", revision
	return nil
}

func (e *Exercise) finish(outcome string) {
	e.state.Phase, e.state.FinalOutcome = "ended", outcome
	e.state.OccupiesSlot, e.state.CleanupPending = false, true
}

// FinalResult bounds environment-error retries; code failures are not retried.
func (e *Exercise) FinalResult(result string) {
	if e.state.Phase != "final_testing" {
		return
	}
	switch result {
	case "passed", "failed", "timed_out":
		e.state.Attempts++
		e.finish(result)
	case "environment_error", "interrupted":
		e.state.Attempts++
		if e.state.Attempts >= e.maxAttempts {
			e.finish("unavailable")
		}
	}
}

// FinalBudgetExpired models a durable wall-clock budget even when no result arrives.
func (e *Exercise) FinalBudgetExpired() {
	if e.state.Phase == "final_testing" {
		e.finish("unavailable")
	}
}

// View exposes the model's observable lifecycle result.
func (e *Exercise) View() ExerciseState { return e.state }

// PreparationFailed keeps the slot until an explicit retry or abandonment.
func (e *Exercise) PreparationFailed() {
	if e.state.Phase == "preparing" {
		e.state.Phase = "preparation_failed"
	}
}

// RetryPreparation starts another attempt without allocating a second exercise.
func (e *Exercise) RetryPreparation() error {
	if e.state.Phase != "preparation_failed" {
		return ErrConflict
	}
	e.state.Phase = "preparing"
	return nil
}

// Abandon ends a failed preparation; resource cleanup is independent of slot release.
func (e *Exercise) Abandon() error {
	if e.state.Phase != "preparation_failed" {
		return ErrConflict
	}
	e.state.Phase, e.state.OccupiesSlot, e.state.CleanupPending = "ended", false, true
	return nil
}
