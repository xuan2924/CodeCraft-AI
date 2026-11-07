package protocol

import "testing"

func TestJavaRecoveryDoesNotDeclareTakeoverBeforeDurableBarrier(t *testing.T) {
	for _, lostResponse := range []string{"close", "open"} {
		t.Run(lostResponse, func(t *testing.T) {
			workspace := NewWorkspace(100)
			transition := BeginTakeover("takeover", 1)
			if transition.Complete() {
				t.Fatal("takeover completed without barrier")
			}
			if lostResponse == "close" {
				if err := workspace.Close(1, "takeover"); err != nil {
					t.Fatal(err)
				}
			} else {
				if err := transition.Advance(workspace); err != nil {
					t.Fatal(err)
				}
				if err := workspace.Open(2, "takeover"); err != nil {
					t.Fatal(err)
				}
			}
			transition = RestoreTakeover(transition.Checkpoint())
			workspace = RestoreWorkspace(workspace.Checkpoint())
			for !transition.Complete() {
				if err := transition.Advance(workspace); err != nil {
					t.Fatal(err)
				}
			}
			if workspace.View().Epoch != 3 || workspace.View().Closed {
				t.Fatal("recovery changed generation twice")
			}
			if _, err := workspace.Apply(WriteRequest{ID: "late", Fingerprint: "a", Epoch: 1, NotAfter: 90}, 20); err == nil {
				t.Fatal("old dispatch survived completed takeover")
			}
		})
	}
}
