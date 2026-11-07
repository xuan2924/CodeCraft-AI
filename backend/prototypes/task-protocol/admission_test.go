package protocol

import "testing"

func TestFrozenExpiredWorkspaceAdmitsOnlySystemFinalTest(t *testing.T) {
	workspace := NewWorkspace(100)
	if _, err := workspace.Apply(WriteRequest{ID: "save", Fingerprint: "a", Epoch: 1, NotAfter: 100}, 90); err != nil {
		t.Fatal(err)
	}
	if err := workspace.Close(1, "end"); err != nil {
		t.Fatal(err)
	}
	if _, err := workspace.Freeze(2); err != nil {
		t.Fatal(err)
	}
	workspace = RestoreWorkspace(workspace.Checkpoint())
	tasks := NewTasks()
	manual := TestDispatch{TaskID: "late-manual", Purpose: "manual", Epoch: 1, Revision: 1, NotAfter: 100}
	if _, err := workspace.StartTest(tasks, manual, 101); err == nil {
		t.Fatal("late manual task admitted")
	}
	final := TestDispatch{TaskID: "final-1", Purpose: "final", Epoch: 2, Revision: 1, FreezeTransition: "end", AttemptID: "attempt-1", NotAfter: 700}
	result, err := workspace.StartTest(tasks, final, 101)
	if err != nil || result.State != "running" {
		t.Fatalf("final test rejected after exercise deadline: %+v %v", result, err)
	}
	if _, err := workspace.StartTest(tasks, final, 102); err != nil {
		t.Fatal(err)
	}
	if !workspace.View().Frozen || !workspace.View().Closed {
		t.Fatal("final test reopened writes")
	}
	if _, err := workspace.Apply(WriteRequest{ID: "late-save", Fingerprint: "b", Epoch: 2, Base: 1, NotAfter: 700}, 102); err == nil {
		t.Fatal("final grant authorized writing")
	}
}

func TestFinalTestRequiresMatchingFreezeAttemptAndBudget(t *testing.T) {
	workspace := NewWorkspace(100)
	grant := TestDispatch{TaskID: "final-1", Purpose: "final", Epoch: 2, Revision: 0, FreezeTransition: "end", AttemptID: "attempt-1", NotAfter: 700}
	if _, err := workspace.StartTest(NewTasks(), grant, 101); err == nil {
		t.Fatal("unfrozen final input accepted")
	}
	if err := workspace.Close(1, "end"); err != nil {
		t.Fatal(err)
	}
	if _, err := workspace.Freeze(2); err != nil {
		t.Fatal(err)
	}
	for _, change := range []func(*TestDispatch){
		func(g *TestDispatch) { g.FreezeTransition = "other" },
		func(g *TestDispatch) { g.Revision = 1 },
		func(g *TestDispatch) { g.AttemptID = "" },
		func(g *TestDispatch) { g.Epoch = 1 },
		func(g *TestDispatch) { g.NotAfter = 101 },
		func(g *TestDispatch) { g.Purpose = "manual" },
	} {
		invalid := grant
		change(&invalid)
		if _, err := workspace.StartTest(NewTasks(), invalid, 101); err == nil {
			t.Fatalf("invalid grant accepted: %+v", invalid)
		}
	}
	tasks := NewTasks()
	tasks.Stop("final-1") // durable budget cancellation precedes delayed dispatch
	result, err := workspace.StartTest(tasks, grant, 102)
	if err != nil || result.State != "stopped" {
		t.Fatalf("final dispatch escaped cancellation: %+v %v", result, err)
	}
}
