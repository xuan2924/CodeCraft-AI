package protocol

import "testing"

func TestTruncationResetsAndResultSurvivesEviction(t *testing.T) {
	stream := NewEvents(2)
	stream.Append("result", "passed")
	stream.Append("stdout", "one")
	stream.Append("stdout", "two")
	if !stream.Subscribe(0).Reset {
		t.Fatal("gap was hidden")
	}
	snapshot := stream.Snapshot()
	if !snapshot.Truncated || snapshot.Result != "passed" || len(snapshot.Events) != 2 {
		t.Fatalf("snapshot lost result: %+v", snapshot)
	}
	if !stream.Subscribe(99).Reset {
		t.Fatal("future cursor accepted")
	}
	client := NewEventConsumer(snapshot.Cursor)
	if client.Accept(Event{Sequence: 3}) {
		t.Fatal("duplicate accepted")
	}
	if !client.Accept(Event{Sequence: 4}) {
		t.Fatal("next event rejected")
	}
	if client.Accept(Event{Sequence: 6}) || !client.NeedsSnapshot() {
		t.Fatal("gap accepted")
	}
}

func TestRevokedConnectionFailsClosedOnBatchAndIdleCheck(t *testing.T) {
	for _, notify := range []bool{false, true} {
		session := &Session{Valid: true, Available: true, Expires: 100}
		connection := Connect(session, 0)
		session.Valid = false
		if notify {
			connection.NotifyRevoked()
		}
		connection.Tick(5)
		if !connection.IsClosed() {
			t.Fatal("revoked stream still open")
		}
	}
	session := &Session{Valid: true, Available: true, Expires: 100}
	connection := Connect(session, 0)
	session.Available = false
	if connection.CanSend(1) {
		t.Fatal("database failure leaked a batch")
	}
	session = &Session{Valid: true, Available: true, Expires: 5}
	connection = Connect(session, 0)
	if connection.CanSend(5) {
		t.Fatal("expiry boundary accepted")
	}
}

func TestSnapshotThenSubscribeHasNoGap(t *testing.T) {
	stream := NewEvents(3)
	stream.Append("stdout", "one")
	snapshot := stream.Snapshot()
	stream.Append("stderr", "two")
	stream.Append("result", "passed")
	batch := stream.Subscribe(snapshot.Cursor)
	if batch.Reset || len(batch.Events) != 2 || batch.Events[0].Sequence != 2 || batch.Events[1].Data != "passed" {
		t.Fatalf("snapshot/subscribe lost events: %+v", batch)
	}
	if stream.Snapshot().Result != "passed" {
		t.Fatal("terminal result not queryable")
	}
}
