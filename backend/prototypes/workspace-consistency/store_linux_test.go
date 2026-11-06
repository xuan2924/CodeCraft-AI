package workspace_test

import (
	"context"
	"errors"
	"os"
	"path/filepath"
	"testing"

	workspace "codecraft/workspace-consistency-probe"
)

func TestBatchRevisionAndReceipt(t *testing.T) {
	ctx := context.Background()
	root := t.TempDir()
	s := workspace.Open(root, []string{"src/a.txt", "src/b.txt"}, nil)
	req := workspace.Request{ID: "R", Base: 0, Changes: map[string]*string{"src/a.txt": text("a"), "src/b.txt": text("b")}}
	receipt, err := s.Apply(ctx, req)
	if err != nil || receipt.Revision != 1 || !receipt.Changed {
		t.Fatalf("batch: %+v %v", receipt, err)
	}
	reopened := workspace.Open(root, []string{"src/a.txt", "src/b.txt"}, nil)
	got, err := reopened.Query(ctx, "R")
	if err != nil || got != receipt {
		t.Fatalf("persistent receipt: %+v %v", got, err)
	}
	got, err = reopened.Apply(ctx, req)
	if err != nil || got != receipt {
		t.Fatalf("duplicate: %+v %v", got, err)
	}
	view, err := reopened.Read(ctx)
	if err != nil || view.Revision != 1 || view.Files["src/a.txt"] != "a" || view.Files["src/b.txt"] != "b" {
		t.Fatalf("read: %+v %v", view, err)
	}
}

func TestNoopConflictRenameAndFreeze(t *testing.T) {
	ctx := context.Background()
	s := workspace.Open(t.TempDir(), []string{"src/a.txt", "src/b.txt"}, nil)
	if _, err := s.Apply(ctx, workspace.Request{ID: "create", Changes: map[string]*string{"src/a.txt": text("a")}}); err != nil {
		t.Fatal(err)
	}
	r, err := s.Apply(ctx, workspace.Request{ID: "noop", Base: 1, Changes: map[string]*string{"src/a.txt": text("a")}})
	if err != nil || r.Changed || r.Revision != 1 {
		t.Fatalf("noop: %+v %v", r, err)
	}
	_, err = s.Apply(ctx, workspace.Request{ID: "stale", Base: 0, Changes: map[string]*string{"src/b.txt": text("b")}})
	if !errors.Is(err, workspace.ErrConflict) {
		t.Fatalf("stale: %v", err)
	}
	_, err = s.Apply(ctx, workspace.Request{ID: "noop", Base: 1, Changes: map[string]*string{"src/a.txt": text("different")}})
	if !errors.Is(err, workspace.ErrKeyReuse) {
		t.Fatalf("key reuse: %v", err)
	}
	r, err = s.Apply(ctx, workspace.Request{ID: "rename", Base: 1, Changes: map[string]*string{"src/a.txt": nil, "src/b.txt": text("a")}})
	if err != nil || r.Revision != 2 {
		t.Fatalf("rename: %+v %v", r, err)
	}
	dest := filepath.Join(t.TempDir(), "snapshot")
	revision, err := s.Copy(ctx, 2, dest)
	if err != nil || revision != 2 {
		t.Fatalf("copy: %d %v", revision, err)
	}
	data, err := os.ReadFile(filepath.Join(dest, "src/b.txt"))
	if err != nil || string(data) != "a" {
		t.Fatalf("copy contents: %q %v", data, err)
	}
	r, err = s.Freeze(ctx, "freeze")
	if err != nil || !r.Frozen || r.Revision != 2 {
		t.Fatalf("freeze: %+v %v", r, err)
	}
	repeated, err := s.Freeze(ctx, "freeze")
	if err != nil || repeated != r {
		t.Fatalf("repeat freeze: %+v %v", repeated, err)
	}
	_, err = s.Apply(ctx, workspace.Request{ID: "late", Base: 2})
	if !errors.Is(err, workspace.ErrFrozen) {
		t.Fatalf("late save: %v", err)
	}
	v, err := s.Read(ctx)
	if err != nil || !v.Frozen || v.Revision != 2 || len(v.Files) != 1 {
		t.Fatalf("frozen view: %+v %v", v, err)
	}
}

func text(s string) *string { return &s }
