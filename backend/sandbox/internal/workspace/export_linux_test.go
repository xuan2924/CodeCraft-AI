package workspace

import "context"

// BeforeMutationPublishForTest installs a test barrier after staging/fsync and
// before the publication deadline checks. Call only before starting the server.
func BeforeMutationPublishForTest(service *Service, barrier func(context.Context)) {
	service.beforeMutationPublish = barrier
}
