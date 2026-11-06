package workspace_test

import (
	"context"
	"errors"
	"os"
	"path/filepath"
	"sync"
	"testing"
	"time"

	workspace "codecraft/workspace-consistency-probe"
)

func TestConcurrentDuplicateAndDifferentRequests(t *testing.T) {
	ctx := context.Background()
	root := t.TempDir()
	s := seed(t, root)
	var wg sync.WaitGroup
	errs := make(chan error, 8)
	for range 8 {
		wg.Go(func() {
			r, err := workspace.Open(root, editable, nil).Apply(ctx, batch())
			if err == nil && r.Revision != 2 {
				err = errors.New("wrong receipt revision")
			}
			errs <- err
		})
	}
	wg.Wait()
	close(errs)
	for err := range errs {
		if err != nil {
			t.Fatal(err)
		}
	}
	assertVersion(t, s, 2, "new-a", "new-b")
	start := make(chan struct{})
	results := make(chan error, 2)
	for _, id := range []string{"one", "two"} {
		wg.Go(func() {
			<-start
			_, err := s.Apply(ctx, workspace.Request{ID: id, Base: 2, Changes: map[string]*string{"src/a.txt": text(id)}})
			results <- err
		})
	}
	close(start)
	wg.Wait()
	first, second := <-results, <-results
	if !((first == nil && errors.Is(second, workspace.ErrConflict)) || (second == nil && errors.Is(first, workspace.ErrConflict))) {
		t.Fatalf("concurrent baselines: %v %v", first, second)
	}
}

func TestCopyAndWriteNeverMixRevisions(t *testing.T) {
	root := t.TempDir()
	s := seed(t, root)
	copying, release := make(chan struct{}), make(chan struct{})
	copier := workspace.Open(root, editable, func(point string) error {
		if point == "copy-start" {
			close(copying)
			<-release
		}
		return nil
	})
	dest := filepath.Join(t.TempDir(), "snapshot")
	done := make(chan error, 1)
	go func() { _, err := copier.Copy(context.Background(), 1, dest); done <- err }()
	<-copying
	// The write must wait for the snapshot lock and honor its deadline.
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Millisecond)
	_, err := s.Apply(ctx, batch())
	cancel()
	close(release)
	copyErr := <-done
	if !errors.Is(err, context.DeadlineExceeded) || copyErr != nil {
		t.Fatalf("coordination: write=%v copy=%v", err, copyErr)
	}
	if _, err = s.Apply(context.Background(), batch()); err != nil {
		t.Fatal(err)
	}
	for p, want := range map[string]string{"src/a.txt": "old-a", "src/b.txt": "old-b"} {
		got, err := os.ReadFile(filepath.Join(dest, p))
		if err != nil || string(got) != want {
			t.Fatalf("mixed snapshot %s: %q %v", p, got, err)
		}
	}
	assertVersion(t, s, 2, "new-a", "new-b")
	if _, err = s.Copy(context.Background(), 1, filepath.Join(t.TempDir(), "stale")); !errors.Is(err, workspace.ErrConflict) {
		t.Fatalf("stale copy: %v", err)
	}
}

func TestFreezeOrdersAfterInflightWrite(t *testing.T) {
	root := t.TempDir()
	s := seed(t, root)
	writing, release := make(chan struct{}), make(chan struct{})
	writer := workspace.Open(root, editable, func(point string) error {
		if point == "before-publish" {
			close(writing)
			<-release
		}
		return nil
	})
	done := make(chan error, 1)
	go func() { _, err := writer.Apply(context.Background(), batch()); done <- err }()
	<-writing
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Millisecond)
	_, err := s.Freeze(ctx, "freeze")
	cancel()
	close(release)
	writeErr := <-done
	if !errors.Is(err, context.DeadlineExceeded) || writeErr != nil {
		t.Fatalf("freeze wait: %v write: %v", err, writeErr)
	}
	r, err := s.Freeze(context.Background(), "freeze")
	if err != nil || r.Revision != 2 {
		t.Fatalf("freeze: %+v %v", r, err)
	}
	_, err = s.Apply(context.Background(), workspace.Request{ID: "late", Base: 2, Changes: map[string]*string{"src/a.txt": text("late")}})
	if !errors.Is(err, workspace.ErrFrozen) {
		t.Fatalf("write after freeze: %v", err)
	}
	assertVersion(t, s, 2, "new-a", "new-b")
}

func TestCancellationBeforePublishLeavesOldVersion(t *testing.T) {
	root := t.TempDir()
	s := seed(t, root)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	writer := workspace.Open(root, editable, func(point string) error {
		if point == "before-publish" {
			cancel()
		}
		return nil
	})
	if _, err := writer.Apply(ctx, batch()); !errors.Is(err, context.Canceled) {
		t.Fatalf("cancel: %v", err)
	}
	assertVersion(t, s, 1, "old-a", "old-b")
}
