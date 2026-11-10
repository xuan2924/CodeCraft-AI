package workspace

import (
	"context"
	"time"
)

// ClockForTest replaces business time before serving; it is not part of the production API.
func ClockForTest(service *Service, now func() time.Time) { service.now = now }

// BeforeMutationPublishForTest installs a test barrier after staging/fsync and
// before the publication deadline checks. Call only before starting the server.
func BeforeMutationPublishForTest(service *Service, barrier func(context.Context)) {
	service.beforeMutationPublish = barrier
}
