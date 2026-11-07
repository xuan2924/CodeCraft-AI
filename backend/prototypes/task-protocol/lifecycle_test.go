package protocol

import "testing"

func TestFinalEnvironmentFailureIsBoundedAndKeepsFrozenVersion(t *testing.T) {
	exercise := NewExercise(3)
	if err := exercise.BeginFinalTesting(7); err != nil {
		t.Fatal(err)
	}
	for i := 0; i < 3; i++ {
		exercise.FinalResult("environment_error")
	}
	state := exercise.View()
	if state.Phase != "ended" || state.OccupiesSlot || state.FinalOutcome != "unavailable" || state.FinalRevision != 7 || state.Attempts != 3 {
		t.Fatalf("unbounded or misleading finalization: %+v", state)
	}
	exercise.FinalResult("passed")
	if exercise.View().FinalOutcome != "unavailable" {
		t.Fatal("late result rewrote final outcome")
	}
}

func TestFinalizationBudgetEndsEvenWithoutAnyResult(t *testing.T) {
	exercise := NewExercise(3)
	if err := exercise.BeginFinalTesting(8); err != nil {
		t.Fatal(err)
	}
	exercise.FinalBudgetExpired()
	if exercise.View().OccupiesSlot || exercise.View().FinalOutcome != "unavailable" {
		t.Fatal("hung final test held slot forever")
	}
}

func TestPreparationFailureCanRetryOrReleaseActiveSlot(t *testing.T) {
	exercise := NewExercise(3)
	exercise.PreparationFailed()
	if !exercise.View().OccupiesSlot {
		t.Fatal("failed preparation silently released slot")
	}
	if err := exercise.RetryPreparation(); err != nil {
		t.Fatal(err)
	}
	exercise.PreparationFailed()
	if err := exercise.Abandon(); err != nil {
		t.Fatal(err)
	}
	state := exercise.View()
	if state.Phase != "ended" || state.OccupiesSlot || !state.CleanupPending {
		t.Fatalf("abandon: %+v", state)
	}
}
