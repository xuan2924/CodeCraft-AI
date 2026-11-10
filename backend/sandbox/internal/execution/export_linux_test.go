package execution

import "time"

// ClockForTest configures authorization time before starting workers.
// It is compiled only into the test binary, never the production service.
func ClockForTest(runner *Runner, now func() time.Time) { runner.now = now }
