package workspace_test

import (
	"bufio"
	"context"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"testing"
	"time"

	workspace "codecraft/workspace-consistency-probe"
)

var editable = []string{"src/a.txt", "src/b.txt"}

func seed(t *testing.T, root string) *workspace.Store {
	t.Helper()
	s := workspace.Open(root, editable, nil)
	_, err := s.Apply(context.Background(), workspace.Request{ID: "seed", Changes: map[string]*string{"src/a.txt": text("old-a"), "src/b.txt": text("old-b")}})
	if err != nil {
		t.Fatal(err)
	}
	return s
}

func batch() workspace.Request {
	return workspace.Request{ID: "R", Base: 1, Changes: map[string]*string{"src/a.txt": text("new-a"), "src/b.txt": text("new-b")}}
}

func assertVersion(t *testing.T, s *workspace.Store, revision uint64, a, b string) {
	t.Helper()
	v, err := s.Read(context.Background())
	if err != nil || v.Revision != revision || len(v.Files) != 2 || v.Files["src/a.txt"] != a || v.Files["src/b.txt"] != b {
		t.Fatalf("view: %+v %v", v, err)
	}
}

func TestPartialIOFailureAndLostResponse(t *testing.T) {
	ctx := context.Background()
	root := t.TempDir()
	s := seed(t, root)
	injected := errors.New("injected I/O failure")
	failing := workspace.Open(root, editable, func(point string) error {
		if point == "file-written" {
			return injected
		}
		return nil
	})
	if _, err := failing.Apply(ctx, batch()); !errors.Is(err, injected) {
		t.Fatalf("failure: %v", err)
	}
	assertVersion(t, s, 1, "old-a", "old-b")
	if _, err := s.Query(ctx, "R"); !errors.Is(err, workspace.ErrNotFound) {
		t.Fatalf("unexpected receipt: %v", err)
	}
	lost := workspace.Open(root, editable, func(point string) error {
		if point == "after-publish" {
			return injected
		}
		return nil
	})
	if _, err := lost.Apply(ctx, batch()); !errors.Is(err, injected) {
		t.Fatalf("lost response: %v", err)
	}
	receipt, err := s.Query(ctx, "R")
	if err != nil || receipt.Revision != 2 {
		t.Fatalf("query lost response: %+v %v", receipt, err)
	}
	assertVersion(t, s, 2, "new-a", "new-b")
	repeated, err := s.Apply(ctx, batch())
	if err != nil || repeated != receipt {
		t.Fatalf("retry: %+v %v", repeated, err)
	}
}

// This subprocess is killed by the parent, not gracefully closed or panicked.
func TestCrashChild(t *testing.T) {
	root := os.Getenv("WORKSPACE_CRASH_ROOT")
	if root == "" {
		return
	}
	phase := os.Getenv("WORKSPACE_CRASH_PHASE")
	s := workspace.Open(root, editable, func(point string) error {
		if point == phase {
			fmt.Println("READY")
			for {
				time.Sleep(time.Hour)
			}
		}
		return nil
	})
	var err error
	if os.Getenv("WORKSPACE_CRASH_FREEZE") == "1" {
		_, err = s.Freeze(context.Background(), "freeze")
	} else {
		_, err = s.Apply(context.Background(), batch())
	}
	t.Fatalf("crash point not reached: %v", err)
}

func crashAt(t *testing.T, root, phase string, freeze bool) {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	executable, err := os.Executable()
	if err != nil {
		t.Fatal(err)
	}
	cmd := exec.CommandContext(ctx, executable, "-test.run=^TestCrashChild$")
	cmd.Env = append(os.Environ(), "WORKSPACE_CRASH_ROOT="+root, "WORKSPACE_CRASH_PHASE="+phase)
	if freeze {
		cmd.Env = append(cmd.Env, "WORKSPACE_CRASH_FREEZE=1")
	}
	stdout, err := cmd.StdoutPipe()
	if err != nil {
		t.Fatal(err)
	}
	cmd.Stderr = os.Stderr
	if err = cmd.Start(); err != nil {
		t.Fatal(err)
	}
	scanner := bufio.NewScanner(stdout)
	ready := scanner.Scan() && scanner.Text() == "READY"
	killErr := cmd.Process.Kill()
	waitErr := cmd.Wait()
	if !ready || killErr != nil || waitErr == nil || ctx.Err() != nil {
		t.Fatalf("kill: ready=%v kill=%v wait=%v scan=%v timeout=%v", ready, killErr, waitErr, scanner.Err(), ctx.Err())
	}
}

func TestCrashRecoveryAtPublicationBoundaries(t *testing.T) {
	for _, phase := range []string{"file-written", "before-publish", "after-rename", "after-publish"} {
		t.Run(phase, func(t *testing.T) {
			root := t.TempDir()
			seed(t, root)
			crashAt(t, root, phase, false)
			s := workspace.Open(root, editable, nil)
			ctx, cancel := context.WithTimeout(context.Background(), time.Second)
			defer cancel()
			receipt, err := s.Query(ctx, "R")
			if phase == "file-written" || phase == "before-publish" {
				if !errors.Is(err, workspace.ErrNotFound) {
					t.Fatalf("unpublished: %+v %v", receipt, err)
				}
				assertVersion(t, s, 1, "old-a", "old-b")
			} else {
				if err != nil || receipt.Revision != 2 {
					t.Fatalf("published: %+v %v", receipt, err)
				}
				assertVersion(t, s, 2, "new-a", "new-b")
				if _, err = s.Apply(ctx, batch()); err != nil {
					t.Fatal(err)
				}
				assertVersion(t, s, 2, "new-a", "new-b")
			}
		})
	}
}

func TestFreezeSurvivesCrash(t *testing.T) {
	root := t.TempDir()
	seed(t, root)
	crashAt(t, root, "after-publish", true)
	s := workspace.Open(root, editable, nil)
	r, err := s.Query(context.Background(), "freeze")
	if err != nil || !r.Frozen || r.Revision != 1 {
		t.Fatalf("freeze receipt: %+v %v", r, err)
	}
	if _, err = s.Apply(context.Background(), batch()); !errors.Is(err, workspace.ErrFrozen) {
		t.Fatalf("frozen write: %v", err)
	}
}

func TestCorruptPublishedManifestFailsClosed(t *testing.T) {
	root := t.TempDir()
	s := seed(t, root)
	if _, err := s.Apply(context.Background(), batch()); err != nil {
		t.Fatal(err)
	}
	// Storage fault injection, not an assertion on private implementation state.
	dirs, err := filepath.Glob(filepath.Join(root, "commit-*"))
	if err != nil || len(dirs) != 2 {
		t.Fatalf("fixture: %v %v", dirs, err)
	}
	if err = os.WriteFile(filepath.Join(dirs[1], "manifest.json"), []byte("torn"), 0600); err != nil {
		t.Fatal(err)
	}
	if _, err = s.Read(context.Background()); err == nil {
		t.Fatal("must not silently fall back to older revision")
	}
	if _, err = s.Query(context.Background(), "R"); err == nil {
		t.Fatal("corrupt receipt reported as success")
	}
}
