package protocol

import (
	"testing"
)

func TestUnknownExecutionKeepsSlotUntilQuiescent(t *testing.T) {
	tasks := NewTasks()
	if _, err := tasks.Start("first", "revision-1"); err != nil {
		t.Fatal(err)
	}
	tasks = RestoreTasks(tasks.Checkpoint())
	tasks.Interrupt("first")
	if tasks.Query("first").State != "interrupted" {
		t.Fatal("unknown result reported as success")
	}
	if _, err := tasks.Start("second", "revision-2"); err == nil {
		t.Fatal("unconfirmed process freed slot")
	}
	tasks.ConfirmQuiescent("first")
	if _, err := tasks.Start("second", "revision-2"); err != nil {
		t.Fatal(err)
	}
}

func TestDuplicateStartAndCancellationTombstone(t *testing.T) {
	tasks := NewTasks()
	if _, err := tasks.Start("first", "revision-1"); err != nil {
		t.Fatal(err)
	}
	if _, err := tasks.Start("first", "revision-1"); err != nil {
		t.Fatal(err)
	}
	if _, err := tasks.Start("first", "revision-2"); err == nil {
		t.Fatal("key reused")
	}
	if _, err := tasks.Start("second", "revision-1"); err == nil {
		t.Fatal("two active executions")
	}
	tasks.Stop("first")
	tasks.Complete("first", "passed", false)
	if tasks.Query("first").State != "stopping" {
		t.Fatal("stop acknowledged before process exit")
	}
	tasks.Complete("first", "passed", true)
	tasks.Stop("late")
	tasks = RestoreTasks(tasks.Checkpoint())
	task, err := tasks.Start("late", "revision-1")
	if err != nil || task.State != "stopped" {
		t.Fatalf("late dispatch escaped tombstone: %+v %v", task, err)
	}
}

func TestStopAndCompletionHaveOneTerminalResult(t *testing.T) {
	for _, stopFirst := range []bool{false, true} {
		t.Run(map[bool]string{true: "stop-first", false: "complete-first"}[stopFirst], func(t *testing.T) {
			tasks := NewTasks()
			if _, err := tasks.Start("test-1", "revision-7"); err != nil {
				t.Fatal(err)
			}
			if stopFirst {
				tasks.Stop("test-1")
			}
			tasks.Complete("test-1", "passed", true)
			tasks.Stop("test-1")
			tasks.Complete("test-1", "failed", true)
			want := "passed"
			if stopFirst {
				want = "stopped"
			}
			if tasks.Query("test-1").State != want {
				t.Fatalf("expected %s: %+v", want, tasks.Query("test-1"))
			}
		})
	}
}
