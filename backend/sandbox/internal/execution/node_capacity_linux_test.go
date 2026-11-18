package execution

import "testing"

func TestNodeCapacityFailsClosedDuringRecoveryAndShutdown(t *testing.T) {
	runner := &Runner{active: map[string]Input{"one": {}}, concurrency: 4}
	active, maximum, ready := runner.Capacity()
	if active != 1 || maximum != 4 || !ready {
		t.Fatalf("invalid capacity: %d %d %v", active, maximum, ready)
	}
	runner.blocked = true
	if _, _, ready = runner.Capacity(); ready {
		t.Fatal("unresolved recovery advertised readiness")
	}
	runner.blocked, runner.closed = false, true
	if _, _, ready = runner.Capacity(); ready {
		t.Fatal("closed runner advertised readiness")
	}
}
