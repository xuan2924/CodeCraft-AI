package workspace

import (
	"context"
	"sync"

	"google.golang.org/grpc/status"
)

type workspaceLocks struct {
	mu   sync.Mutex
	held map[string]*workspaceLock
}
type workspaceLock struct {
	token chan struct{}
	users int
}

func (l *workspaceLocks) acquire(ctx context.Context, id string) (func(), error) {
	l.mu.Lock()
	if l.held == nil {
		l.held = make(map[string]*workspaceLock)
	}
	g := l.held[id]
	if g == nil {
		g = &workspaceLock{token: make(chan struct{}, 1)}
		l.held[id] = g
	}
	g.users++
	l.mu.Unlock()
	drop := func() {
		l.mu.Lock()
		defer l.mu.Unlock()
		g.users--
		if g.users == 0 {
			delete(l.held, id)
		}
	}
	select {
	case g.token <- struct{}{}:
		return func() { <-g.token; drop() }, nil
	case <-ctx.Done():
		drop()
		return nil, status.FromContextError(ctx.Err()).Err()
	}
}
