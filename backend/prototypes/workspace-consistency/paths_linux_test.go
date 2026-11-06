package workspace_test

import (
	"context"
	"errors"
	"os"
	"path/filepath"
	"sync"
	"syscall"
	"testing"

	workspace "codecraft/workspace-consistency-probe"
)

func TestInvalidProtectedAndOverlappingPaths(t *testing.T) {
	root := t.TempDir()
	s := seed(t, root)
	for _, p := range []string{"../outside", "/absolute", "src/../a.txt", "src//a.txt", "src\\a.txt", "src/a.txt:stream", "pom.xml", "other-workspace/a.txt"} {
		_, err := s.Apply(context.Background(), workspace.Request{ID: p, Base: 1, Changes: map[string]*string{p: text("bad"), "src/b.txt": text("partial")}})
		if !errors.Is(err, workspace.ErrPath) {
			t.Fatalf("path %q: %v", p, err)
		}
		assertVersion(t, s, 1, "old-a", "old-b")
	}
	overlap := workspace.Open(root, []string{"src", "src/a.txt"}, nil)
	_, err := overlap.Apply(context.Background(), workspace.Request{ID: "overlap", Base: 1, Changes: map[string]*string{"src": text("file")}})
	if err == nil {
		t.Fatal("file/directory overlap accepted")
	}
	assertVersion(t, s, 1, "old-a", "old-b")
}

func treeFixture(t *testing.T, root string) string {
	t.Helper()
	dirs, err := filepath.Glob(filepath.Join(root, "commit-*", "tree"))
	if err != nil || len(dirs) != 1 {
		t.Fatalf("fault fixture: %v %v", dirs, err)
	}
	return dirs[0]
}

func TestLinksAndSpecialFilesFailClosed(t *testing.T) {
	for _, kind := range []string{"external-symlink", "internal-symlink", "directory-symlink", "hardlink", "fifo"} {
		t.Run(kind, func(t *testing.T) {
			root := t.TempDir()
			s := seed(t, root)
			tree := treeFixture(t, root)
			victim := filepath.Join(tree, "src/a.txt")
			outside := filepath.Join(t.TempDir(), "secret")
			if err := os.WriteFile(outside, []byte("secret"), 0600); err != nil {
				t.Fatal(err)
			}
			if err := os.Remove(victim); err != nil {
				t.Fatal(err)
			}
			var err error
			switch kind {
			case "external-symlink":
				err = os.Symlink(outside, victim)
			case "internal-symlink":
				err = os.Symlink("b.txt", victim)
			case "directory-symlink":
				if err = os.RemoveAll(filepath.Join(tree, "src")); err != nil {
					t.Fatal(err)
				}
				err = os.Symlink(filepath.Dir(outside), filepath.Join(tree, "src"))
			case "hardlink":
				err = os.Link(outside, victim)
			case "fifo":
				err = syscall.Mkfifo(victim, 0600)
			}
			if err != nil {
				t.Fatal(err)
			}
			if _, err = s.Read(context.Background()); err == nil {
				t.Fatal("unsafe read accepted")
			}
			if _, err = s.Apply(context.Background(), batch()); err == nil {
				t.Fatal("unsafe tree accepted for write")
			}
			if _, err = s.Copy(context.Background(), 1, filepath.Join(t.TempDir(), "copy")); err == nil {
				t.Fatal("unsafe copy accepted")
			}
			data, err := os.ReadFile(outside)
			if err != nil || string(data) != "secret" {
				t.Fatalf("outside altered: %q %v", data, err)
			}
		})
	}
}

func TestDirectorySwapCannotReadOutside(t *testing.T) {
	root := t.TempDir()
	s := seed(t, root)
	tree := treeFixture(t, root)
	source, parked := filepath.Join(tree, "src"), filepath.Join(tree, "parked")
	outside := t.TempDir()
	if err := os.WriteFile(filepath.Join(outside, "a.txt"), []byte("SECRET"), 0600); err != nil {
		t.Fatal(err)
	}
	// Adversarial storage fault: normal users never get write access to this root.
	stop := make(chan struct{})
	result := make(chan error, 1)
	var wg sync.WaitGroup
	wg.Go(func() {
		for {
			select {
			case <-stop:
				result <- nil
				return
			default:
			}
			if err := os.Rename(source, parked); err != nil {
				result <- err
				return
			}
			if err := os.Symlink(outside, source); err != nil {
				result <- err
				return
			}
			if err := os.Remove(source); err != nil {
				result <- err
				return
			}
			if err := os.Rename(parked, source); err != nil {
				result <- err
				return
			}
		}
	})
	leaked := false
	for range 200 {
		v, err := s.Read(context.Background())
		if err == nil {
			for _, content := range v.Files {
				if content == "SECRET" {
					leaked = true
				}
			}
		}
	}
	close(stop)
	wg.Wait()
	if err := <-result; err != nil {
		t.Fatal(err)
	}
	if leaked {
		t.Fatal("symlink race leaked outside contents")
	}
}
