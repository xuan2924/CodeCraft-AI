package protocol

import (
	"errors"
	"testing"
)

func TestNotFoundIsNotCancellation(t *testing.T) {
	gate := NewWorkspace(100)
	request := WriteRequest{ID: "late", Fingerprint: "a", Epoch: 1, NotAfter: 90}
	if _, found := gate.Query("late"); found {
		t.Fatal("unexpected receipt")
	}
	if _, err := gate.Apply(request, 10); err != nil {
		t.Fatal(err)
	}
	if _, err := gate.Apply(request, 11); err != nil {
		t.Fatal(err)
	}
	if gate.View().Revision != 1 {
		t.Fatal("replay changed content twice")
	}
	request.Fingerprint = "different"
	if _, err := gate.Apply(request, 12); !errors.Is(err, ErrConflict) {
		t.Fatal("key reused")
	}
}

func TestDeadlineAndFreezeRejectLatePublication(t *testing.T) {
	gate := NewWorkspace(100)
	request := WriteRequest{ID: "save", Fingerprint: "a", Epoch: 1, NotAfter: 80}
	if _, err := gate.Apply(request, 80); !errors.Is(err, ErrExpired) {
		t.Fatal("session expiry ignored")
	}
	request.NotAfter = 200
	if _, err := gate.Apply(request, 100); !errors.Is(err, ErrExpired) {
		t.Fatal("deadline ignored")
	}
	if err := gate.Close(1, "end"); err != nil {
		t.Fatal(err)
	}
	revision, err := gate.Freeze(2)
	if err != nil || revision != 0 {
		t.Fatalf("freeze: %d %v", revision, err)
	}
	gate = RestoreWorkspace(gate.Checkpoint())
	if err := gate.Open(2, "end"); !errors.Is(err, ErrFenced) {
		t.Fatal("frozen workspace reopened")
	}
}

func TestStaleFenceCannotReopenAfterAnotherTakeover(t *testing.T) {
	gate := NewWorkspace(100)
	for _, err := range []error{gate.Close(1, "first"), gate.Open(2, "first"), gate.Close(3, "second")} {
		if err != nil {
			t.Fatal(err)
		}
	}
	if err := gate.Open(2, "first"); !errors.Is(err, ErrFenced) {
		t.Fatal("late activation accepted")
	}
	if err := gate.Open(4, "wrong-transition"); !errors.Is(err, ErrFenced) {
		t.Fatal("wrong transition accepted")
	}
	if err := gate.Open(4, "second"); err != nil {
		t.Fatal(err)
	}
}

func TestTakeoverOrdersOldWritesAcrossRecovery(t *testing.T) {
	for _, writeFirst := range []bool{false, true} {
		t.Run(map[bool]string{true: "write-first", false: "fence-first"}[writeFirst], func(t *testing.T) {
			gate := NewWorkspace(100)
			old := WriteRequest{ID: "save-1", Fingerprint: "content-a", Epoch: 1, Base: 0, NotAfter: 90}
			if writeFirst {
				if _, err := gate.Apply(old, 10); err != nil {
					t.Fatal(err)
				}
			}
			if err := gate.Close(1, "takeover-1"); err != nil {
				t.Fatal(err)
			}
			gate = RestoreWorkspace(gate.Checkpoint()) // simulated restart, not a real disk
			if err := gate.Open(2, "takeover-1"); err != nil {
				t.Fatal(err)
			}
			receipt, err := gate.Apply(old, 11)
			if writeFirst {
				if err != nil || receipt.Revision != 1 {
					t.Fatalf("lost original receipt: %+v %v", receipt, err)
				}
			} else if !errors.Is(err, ErrFenced) {
				t.Fatalf("late write accepted: %v", err)
			}
			if gate.View().Epoch != 3 {
				t.Fatal("takeover did not advance generation")
			}
		})
	}
}
