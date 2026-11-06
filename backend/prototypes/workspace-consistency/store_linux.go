// Package workspace is a Linux-only, throwaway consistency probe, not a service.
// The storage root is private to the service; one local filesystem and flock
// coordinate all callers. No candidate/container may access this root.
package workspace

import (
	"context"
	"crypto/sha256"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"path"
	"path/filepath"
	"reflect"
	"sort"
	"strconv"
	"strings"
	"syscall"
	"time"
	"unicode/utf8"
)

var (
	ErrConflict = errors.New("baseline conflict")
	ErrKeyReuse = errors.New("request key reused with different payload")
	ErrNotFound = errors.New("no committed receipt")
	ErrFrozen   = errors.New("workspace frozen")
	ErrPath     = errors.New("invalid or protected path")
)

// Request describes a batch; nil content deletes a path. ID is scoped to this
// workspace incarnation. Callers must not reuse it for another payload.
type Request struct {
	ID      string
	Base    uint64
	Changes map[string]*string
}

// Receipt is stored atomically with the tree; Changed is false for no-op saves.
type Receipt struct {
	ID       string
	Revision uint64
	Changed  bool
	Frozen   bool
}

type entry struct {
	Fingerprint string
	Receipt     Receipt
}

type manifest struct {
	Revision uint64
	Frozen   bool
	Receipts map[string]entry
}

// View is a detached, consistent copy, never an alias of service-owned files.
type View struct {
	Revision uint64
	Frozen   bool
	Files    map[string]string
}

// Store contains configuration only. Open does not create or reset a workspace.
// Hook is a fault-injection seam used only by this probe.
type Store struct {
	root    string
	allowed map[string]bool
	hook    func(string) error
}

// Open configures an existing or new private root and exact editable paths.
// Production workspace creation/identity and policy persistence are out of scope.
func Open(root string, allowed []string, hook func(string) error) *Store {
	policy := make(map[string]bool)
	for _, p := range allowed {
		policy[p] = true
	}
	return &Store{root: root, allowed: policy, hook: hook}
}

func (s *Store) point(name string) error {
	if s.hook != nil {
		return s.hook(name)
	}
	return nil
}

// withLock uses a separate descriptor per call, including within one process.
// Nonblocking flock polling permits cancellation while waiting.
func (s *Store) withLock(ctx context.Context, fn func() error) (err error) {
	if err = os.MkdirAll(s.root, 0700); err != nil {
		return err
	}
	lock, err := os.OpenFile(filepath.Join(s.root, "lock"), os.O_CREATE|os.O_RDWR, 0600)
	if err != nil {
		return err
	}
	defer func() { err = errors.Join(err, lock.Close()) }()
	for {
		if err = ctx.Err(); err != nil {
			return err
		}
		err = syscall.Flock(int(lock.Fd()), syscall.LOCK_EX|syscall.LOCK_NB)
		if err == nil {
			break
		}
		if !errors.Is(err, syscall.EWOULDBLOCK) {
			return err
		}
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-time.After(time.Millisecond):
		}
	}
	defer func() { err = errors.Join(err, syscall.Flock(int(lock.Fd()), syscall.LOCK_UN)) }()
	return fn()
}

// load ignores unpublished stage directories. A malformed published commit
// fails closed; it must never silently roll back to an older successful version.
func (s *Store) load() (manifest, map[string]string, uint64, error) {
	m := manifest{Receipts: make(map[string]entry)}
	dirs, err := os.ReadDir(s.root)
	if err != nil {
		return m, nil, 0, err
	}
	var seq uint64
	var latest string
	for _, d := range dirs {
		if !strings.HasPrefix(d.Name(), "commit-") {
			continue
		}
		n, err := strconv.ParseUint(strings.TrimPrefix(d.Name(), "commit-"), 10, 64)
		if err != nil || !d.IsDir() || n == 0 {
			return m, nil, 0, errors.New("invalid commit directory")
		}
		if n > seq {
			seq, latest = n, d.Name()
		}
	}
	if seq == 0 {
		return m, make(map[string]string), 0, nil
	}
	data, err := os.ReadFile(filepath.Join(s.root, latest, "manifest.json"))
	if err != nil {
		return m, nil, 0, err
	}
	if err = json.Unmarshal(data, &m); err != nil {
		return m, nil, 0, err
	}
	if m.Receipts == nil {
		return m, nil, 0, errors.New("missing receipts")
	}
	files, err := readTree(filepath.Join(s.root, latest, "tree"))
	return m, files, seq, err
}

// Read returns the current revision and all fixture files under the same lock.
func (s *Store) Read(ctx context.Context) (v View, err error) {
	err = s.withLock(ctx, func() error {
		m, files, _, err := s.load()
		v = View{Revision: m.Revision, Frozen: m.Frozen, Files: files}
		return err
	})
	return
}

// Query resolves an unknown write outcome without re-executing the request.
// NotFound means no published receipt at this serialized instant, not permission
// to resend a possibly delayed RPC. Java must first fence/drain that dispatch.
func (s *Store) Query(ctx context.Context, id string) (r Receipt, err error) {
	err = s.withLock(ctx, func() error {
		m, _, _, err := s.load()
		if err != nil {
			return err
		}
		e, ok := m.Receipts[id]
		if !ok {
			return ErrNotFound
		}
		r = e.Receipt
		return nil
	})
	return
}

// Apply publishes all changes and the receipt together. Errors after the rename
// are unknown outcomes: callers must Query, not infer rollback from an error.
func (s *Store) Apply(ctx context.Context, req Request) (r Receipt, err error) {
	payload, err := json.Marshal(req)
	if err != nil {
		return r, err
	}
	fingerprint := fmt.Sprintf("%x", sha256.Sum256(append([]byte("apply:"), payload...)))
	err = s.withLock(ctx, func() error {
		m, files, seq, err := s.load()
		if err != nil {
			return err
		}
		if req.ID == "" {
			return errors.New("empty request ID")
		}
		if old, ok := m.Receipts[req.ID]; ok {
			if old.Fingerprint != fingerprint {
				return ErrKeyReuse
			}
			r = old.Receipt
			return nil
		}
		if m.Frozen {
			return ErrFrozen
		}
		if m.Revision != req.Base {
			return ErrConflict
		}
		next := make(map[string]string, len(files))
		for p, v := range files {
			next[p] = v
		}
		for p, v := range req.Changes {
			if !safePath(p) || !s.allowed[p] {
				return ErrPath
			}
			if v == nil {
				delete(next, p)
			} else {
				if !utf8.ValidString(*v) || strings.ContainsRune(*v, 0) {
					return errors.New("not text")
				}
				next[p] = *v
			}
		}
		changed := !reflect.DeepEqual(files, next)
		if changed {
			m.Revision++
		}
		r = Receipt{ID: req.ID, Revision: m.Revision, Changed: changed}
		m.Receipts[req.ID] = entry{Fingerprint: fingerprint, Receipt: r}
		return s.publish(ctx, m, next, seq+1)
	})
	return
}

// Freeze durably closes admission under the write lock without incrementing the
// content revision. It waits for earlier writes and is recoverable by request ID.
func (s *Store) Freeze(ctx context.Context, id string) (r Receipt, err error) {
	err = s.withLock(ctx, func() error {
		m, files, seq, err := s.load()
		if err != nil {
			return err
		}
		if id == "" {
			return errors.New("empty request ID")
		}
		if old, ok := m.Receipts[id]; ok {
			if old.Fingerprint != "freeze" {
				return ErrKeyReuse
			}
			r = old.Receipt
			return nil
		}
		m.Frozen = true
		r = Receipt{ID: id, Revision: m.Revision, Frozen: true}
		m.Receipts[id] = entry{Fingerprint: "freeze", Receipt: r}
		return s.publish(ctx, m, files, seq+1)
	})
	return
}

// Copy materializes a detached tree for exactly the expected current revision.
// The destination must not exist and its parent must be service-owned. A failed
// copy is not published. This conservative probe holds the lock during copying.
func (s *Store) Copy(ctx context.Context, expected uint64, dest string) (revision uint64, err error) {
	err = s.withLock(ctx, func() (err error) {
		m, files, _, err := s.load()
		if err != nil {
			return err
		}
		if m.Revision != expected {
			return ErrConflict
		}
		if _, err = os.Lstat(dest); !errors.Is(err, os.ErrNotExist) {
			if err == nil {
				return errors.New("copy destination exists")
			}
			return err
		}
		stage, err := os.MkdirTemp(filepath.Dir(dest), "copy-")
		if err != nil {
			return err
		}
		defer func() { err = errors.Join(err, os.RemoveAll(stage)) }()
		if err = s.point("copy-start"); err != nil {
			return err
		}
		if err = writeTree(ctx, filepath.Join(stage, "tree"), files, nil); err != nil {
			return err
		}
		if err = ctx.Err(); err != nil {
			return err
		}
		if err = os.Rename(filepath.Join(stage, "tree"), dest); err != nil {
			return err
		}
		revision = m.Revision
		return syncDir(filepath.Dir(dest))
	})
	return
}

func safePath(p string) bool {
	if p == "" || p == "." || path.IsAbs(p) || path.Clean(p) != p {
		return false
	}
	for _, part := range strings.Split(p, "/") {
		if part == ".." || strings.ContainsAny(part, "\\:\x00") {
			return false
		}
	}
	return true
}

func syncDir(p string) (err error) {
	f, err := os.Open(p)
	if err != nil {
		return err
	}
	defer func() { err = errors.Join(err, f.Close()) }()
	return f.Sync()
}

func writeFile(p string, data []byte) (err error) {
	f, err := os.OpenFile(p, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0600)
	if err != nil {
		return err
	}
	defer func() { err = errors.Join(err, f.Close()) }()
	if _, err = f.Write(data); err != nil {
		return err
	}
	return f.Sync()
}

func writeTree(ctx context.Context, dir string, files map[string]string, hook func(string) error) error {
	if err := os.Mkdir(dir, 0700); err != nil {
		return err
	}
	paths := make([]string, 0, len(files))
	for p := range files {
		paths = append(paths, p)
	}
	sort.Strings(paths)
	dirs := map[string]bool{dir: true}
	for _, p := range paths {
		if err := ctx.Err(); err != nil {
			return err
		}
		if !safePath(p) {
			return ErrPath
		}
		target := filepath.Join(dir, filepath.FromSlash(p))
		parent := filepath.Dir(target)
		if err := os.MkdirAll(parent, 0700); err != nil {
			return err
		}
		for d := parent; d != dir; d = filepath.Dir(d) {
			dirs[d] = true
		}
		if err := writeFile(target, []byte(files[p])); err != nil {
			return err
		}
		if hook != nil {
			if err := hook("file-written"); err != nil {
				return err
			}
		}
	}
	order := make([]string, 0, len(dirs))
	for d := range dirs {
		order = append(order, d)
	}
	sort.Sort(sort.Reverse(sort.StringSlice(order)))
	for _, d := range order {
		if err := syncDir(d); err != nil {
			return err
		}
	}
	return nil
}

func (s *Store) publish(ctx context.Context, m manifest, files map[string]string, seq uint64) (err error) {
	stage, err := os.MkdirTemp(s.root, "stage-")
	if err != nil {
		return err
	}
	defer func() { err = errors.Join(err, os.RemoveAll(stage)) }()
	if err = writeTree(ctx, filepath.Join(stage, "tree"), files, s.point); err != nil {
		return err
	}
	data, err := json.Marshal(m)
	if err != nil {
		return err
	}
	if err = writeFile(filepath.Join(stage, "manifest.json"), data); err != nil {
		return err
	}
	if err = syncDir(stage); err != nil {
		return err
	}
	if err = s.point("before-publish"); err != nil {
		return err
	}
	if err = ctx.Err(); err != nil {
		return err
	}
	if err = os.Rename(stage, filepath.Join(s.root, fmt.Sprintf("commit-%020d", seq))); err != nil {
		return err
	}
	if err = s.point("after-rename"); err != nil {
		return err
	}
	if err = syncDir(s.root); err != nil {
		return err
	}
	return s.point("after-publish")
}

// Descriptor-relative traversal refuses every symlink, including internal ones.
// O_NONBLOCK prevents a substituted FIFO from hanging before fstat rejects it.
// Open descriptors pin ancestors against path replacement during traversal.
func readTree(dir string) (map[string]string, error) {
	fd, err := syscall.Open(dir, syscall.O_RDONLY|syscall.O_DIRECTORY|syscall.O_NOFOLLOW|syscall.O_CLOEXEC, 0)
	if err != nil {
		return nil, err
	}
	files := make(map[string]string)
	err = walkTree(os.NewFile(uintptr(fd), dir), "", files)
	return files, err
}

// walkTree owns f, including on error.
func walkTree(f *os.File, prefix string, files map[string]string) (err error) {
	defer func() { err = errors.Join(err, f.Close()) }()
	entries, err := f.ReadDir(-1)
	if err != nil {
		return err
	}
	for _, e := range entries {
		name := path.Join(prefix, e.Name())
		fd, err := syscall.Openat(int(f.Fd()), e.Name(), syscall.O_RDONLY|syscall.O_NOFOLLOW|syscall.O_NONBLOCK|syscall.O_CLOEXEC, 0)
		if err != nil {
			return err
		}
		child := os.NewFile(uintptr(fd), e.Name())
		var st syscall.Stat_t
		if err = syscall.Fstat(fd, &st); err != nil {
			return errors.Join(err, child.Close())
		}
		switch st.Mode & syscall.S_IFMT {
		case syscall.S_IFDIR:
			if err = walkTree(child, name, files); err != nil {
				return err
			}
		case syscall.S_IFREG:
			if st.Nlink != 1 {
				return errors.Join(errors.New("hard link forbidden"), child.Close())
			}
			data, readErr := io.ReadAll(child)
			if err = errors.Join(readErr, child.Close()); err != nil {
				return err
			}
			files[name] = string(data)
		default:
			return errors.Join(errors.New("special file forbidden"), child.Close())
		}
	}
	return nil
}
