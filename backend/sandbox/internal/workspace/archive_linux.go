package workspace

import (
	"bytes"
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"regexp"
	"strings"
	"syscall"
	"time"
	"unicode/utf8"

	rpc "codecraft.local/sandbox/internal/gen"
	"golang.org/x/sys/unix"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"
	"google.golang.org/protobuf/proto"
)

// S3Config selects a trusted path-style S3 endpoint. Configure before serving.
type S3Config struct{ Endpoint, Bucket, Region, AccessKey, SecretKey string }
type snapshotArchive interface {
	save(context.Context, int, *record, []*rpc.GateResponse) (string, error)
	get(context.Context, string, string, int64) ([]byte, error)
}

type frozenArchive struct {
	base, region         string
	accessKey, secretKey string
	client               *http.Client
	now                  func() time.Time
}

// ConfigureFrozenArchive enables freeze-only remote persistence. Partial
// configuration fails closed; no ambient AWS credentials or endpoint discovery.
func (s *Service) ConfigureFrozenArchive(c S3Config) error {
	if c == (S3Config{}) {
		return nil
	}
	u, err := url.Parse(c.Endpoint)
	if err != nil || (u.Scheme != "http" && u.Scheme != "https") || u.Hostname() == "" || u.User != nil || u.RawQuery != "" || u.Fragment != "" || (u.Path != "" && u.Path != "/") || !regexp.MustCompile(`^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$`).MatchString(c.Bucket) || c.Region == "" || c.AccessKey == "" || c.SecretKey == "" {
		return errors.New("invalid frozen archive configuration")
	}
	s.archive = &frozenArchive{base: strings.TrimRight(u.String(), "/") + "/" + c.Bucket, region: c.Region, accessKey: c.AccessKey, secretKey: c.SecretKey, client: &http.Client{Timeout: 30 * time.Second, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}, now: time.Now}
	return nil
}
func (a *frozenArchive) request(ctx context.Context, method, key string, body []byte) (*http.Response, error) {
	req, err := http.NewRequestWithContext(ctx, method, a.base+"/"+key, bytes.NewReader(body))
	if err != nil {
		return nil, err
	}
	sha := sum(body)
	req.Header.Set("X-Amz-Content-Sha256", sha)
	if method == http.MethodPut {
		req.Header.Set("If-None-Match", "*")
	}
	a.sign(req, sha)
	return a.client.Do(req)
}

// Keys are generated internally (kind/hex digest); requests never carry a query.
func (a *frozenArchive) sign(req *http.Request, payloadHash string) {
	stamp := a.now().UTC().Format("20060102T150405Z")
	day := stamp[:8]
	req.Header.Set("X-Amz-Date", stamp)
	headers := "host:" + req.URL.Host + "\n"
	names := "host"
	if match := req.Header.Get("If-None-Match"); match != "" {
		headers += "if-none-match:" + match + "\n"
		names += ";if-none-match"
	}
	headers += "x-amz-content-sha256:" + payloadHash + "\nx-amz-date:" + stamp + "\n"
	names += ";x-amz-content-sha256;x-amz-date"
	canonical := strings.Join([]string{req.Method, req.URL.EscapedPath(), "", headers, names, payloadHash}, "\n")
	scope := day + "/" + a.region + "/s3/aws4_request"
	key := []byte("AWS4" + a.secretKey)
	mac := func(k []byte, text string) []byte {
		h := hmac.New(sha256.New, k)
		h.Write([]byte(text))
		return h.Sum(nil)
	}
	for _, field := range []string{day, a.region, "s3", "aws4_request"} {
		key = mac(key, field)
	}
	signature := hex.EncodeToString(mac(key, "AWS4-HMAC-SHA256\n"+stamp+"\n"+scope+"\n"+sum([]byte(canonical))))
	req.Header.Set("Authorization", "AWS4-HMAC-SHA256 Credential="+a.accessKey+"/"+scope+", SignedHeaders="+names+", Signature="+signature)
}

func (a *frozenArchive) get(ctx context.Context, kind, sha string, limit int64) ([]byte, error) {
	if !digest.MatchString(sha) {
		return nil, errors.New("invalid archive object identity")
	}
	res, err := a.request(ctx, "GET", kind+"/"+sha, nil)
	if err != nil {
		return nil, err
	}
	defer res.Body.Close()
	if res.StatusCode != 200 {
		return nil, fmt.Errorf("archive GET status %d", res.StatusCode)
	}
	data, err := io.ReadAll(io.LimitReader(res.Body, limit+1))
	if err != nil {
		return nil, err
	}
	if int64(len(data)) > limit || sum(data) != sha {
		return nil, errors.New("invalid archive object")
	}
	return data, nil
}
func (a *frozenArchive) put(ctx context.Context, kind string, body []byte) (string, error) {
	sha := sum(body)
	res, err := a.request(ctx, "PUT", kind+"/"+sha, body)
	if err != nil {
		return "", err
	}
	if err = res.Body.Close(); err != nil {
		return "", err
	}
	switch res.StatusCode {
	case 200, 201, 204:
		return sha, nil
	case http.StatusPreconditionFailed:
		_, err = a.get(ctx, kind, sha, int64(len(body)))
		return sha, err
	default:
		return "", fmt.Errorf("archive PUT status %d", res.StatusCode)
	}
}

// The digest covers the exact frozen identity, file manifest, mutation receipts
// and gate chain. No execution tree, task/copy record or cache is archived.
type archiveManifest struct {
	Version int                 `json:"version"`
	Record  *record             `json:"record"`
	Gates   []*rpc.GateResponse `json:"gates"`
}

const archiveManifestLimit = manifestLimit + 4<<20

func (a *frozenArchive) save(ctx context.Context, fd int, r *record, gates []*rpc.GateResponse) (string, error) {
	seen := map[string]bool{}
	for _, path := range r.Snapshot.Paths {
		h := r.Hashes[path]
		if seen[h] {
			continue
		}
		data, err := readPath(fd, "blobs/"+h)
		if err != nil {
			return "", err
		}
		if sum(data) != h {
			return "", errors.New("corrupt blob")
		}
		if _, err = a.put(ctx, "blobs", data); err != nil {
			return "", err
		}
		seen[h] = true
	}
	r.Snapshot.ContentSha256 = nil
	data, err := json.Marshal(archiveManifest{1, r, gates})
	if err != nil {
		return "", err
	}
	if len(data) > archiveManifestLimit {
		return "", errManifestCapacity
	}
	return a.put(ctx, "manifests", data)
}

// HydrateFrozen installs only a complete, verified frozen instance. It never
// rebinds routing or replaces an existing live workspace or deletion fence.
func (s *Service) HydrateFrozen(ctx context.Context, q *rpc.HydrateFrozenRequest) (*rpc.GateResponse, error) {
	release, err := s.enter(ctx, q.GetWorkspaceInstanceId())
	if err != nil {
		return nil, err
	}
	defer release()
	if s.archive == nil || q.DestinationStorageId != s.storageID {
		return nil, status.Error(codes.FailedPrecondition, "archive unavailable or destination storage mismatch")
	}
	if !uuid.MatchString(q.WorkspaceInstanceId) || !uuid.MatchString(q.FreezeOperationId) || !digest.MatchString(q.ArchiveSha256) {
		return nil, status.Error(codes.InvalidArgument, "invalid frozen archive identity")
	}
	id := q.WorkspaceInstanceId
	root, err := directory(-1, s.root, false)
	if err != nil {
		return nil, storageError(err)
	}
	defer syscall.Close(root)
	fd, err := s.instance(id, false)
	if err == nil {
		defer syscall.Close(fd)
		gates, err := gateRecords(fd, id)
		if err != nil {
			return nil, storageError(err)
		}
		if len(gates) > 0 {
			last := gates[len(gates)-1]
			if last.State == "FROZEN" && last.ArchiveSha256 == q.ArchiveSha256 && last.TransitionId == q.FreezeOperationId {
				if err = verifyFrozen(ctx, fd, last); err != nil {
					return nil, storageError(err)
				}
				if err = s.syncHydrateRoot(root); err != nil {
					return nil, storageError(err)
				}
				return last, nil
			}
		}
		return nil, status.Error(codes.AlreadyExists, "destination workspace exists")
	}
	if status.Code(err) != codes.NotFound {
		return nil, err
	}
	data, err := s.archive.get(ctx, "manifests", q.ArchiveSha256, archiveManifestLimit)
	if err != nil {
		return nil, storageError(err)
	}
	var m archiveManifest
	if err = json.Unmarshal(data, &m); err != nil || m.Version != 1 || m.Record == nil || m.Record.Snapshot == nil || m.Record.Snapshot.WorkspaceInstanceId != id || !m.Record.Snapshot.Prepared || len(m.Gates) < 2 || len(m.Record.Hashes) > maxWorkspaceFiles {
		return nil, status.Error(codes.DataLoss, "invalid frozen manifest")
	}
	last := m.Gates[len(m.Gates)-1]
	if last.GetState() != "FROZEN" || last.TransitionId != q.FreezeOperationId || last.FrozenRevision != m.Record.Snapshot.Revision || last.ArchiveSha256 != "" {
		return nil, status.Error(codes.DataLoss, "freeze identity mismatch")
	}
	last.ArchiveSha256 = q.ArchiveSha256
	if err := s.validateArchivePaths(m.Record); err != nil {
		return nil, err
	}
	owned, err := os.OpenRoot(fmt.Sprintf("/proc/self/fd/%d", root))
	if err != nil {
		return nil, storageError(err)
	}
	defer owned.Close()
	stage := "hydrate-" + id
	if err = owned.RemoveAll(stage); err != nil {
		return nil, storageError(err)
	}
	if err = syscall.Mkdirat(root, stage, 0700); err != nil {
		return nil, storageError(err)
	}
	defer owned.RemoveAll(stage)
	staged, err := directory(root, stage, false)
	if err != nil {
		return nil, storageError(err)
	}
	defer syscall.Close(staged)
	files := make(map[string]string, len(m.Record.Hashes))
	total := 0
	for path, sha := range m.Record.Hashes {
		if !validPath(path) || !digest.MatchString(sha) {
			return nil, status.Error(codes.DataLoss, "invalid archived file")
		}
		content, err := s.archive.get(ctx, "blobs", sha, fileLimit)
		if err != nil {
			return nil, storageError(err)
		}
		total += len(content)
		if total > maxWorkspaceBytes || !utf8.Valid(content) || bytes.IndexByte(content, 0) >= 0 {
			return nil, status.Error(codes.DataLoss, "invalid archived text")
		}
		files[path] = string(content)
	}
	if err = publish(ctx, staged, uint64(len(m.Record.Mutations)), m.Record, files, nil, s.now, time.Time{}); err != nil {
		return nil, storageError(err)
	}
	for i, g := range m.Gates {
		if g == nil || g.WorkspaceInstanceId != id || g.Epoch != uint64(i)+1 {
			return nil, status.Error(codes.DataLoss, "invalid archived gate sequence")
		}
		if err = publishGate(ctx, staged, g); err != nil {
			return nil, storageError(err)
		}
	}
	if _, err = gateRecords(staged, id); err != nil {
		return nil, storageError(err)
	}
	if err = verifyFrozen(ctx, staged, last); err != nil {
		return nil, storageError(err)
	}
	if err = ctx.Err(); err != nil {
		return nil, storageError(err)
	}
	if err = unix.Renameat2(root, stage, root, id, unix.RENAME_NOREPLACE); err != nil {
		return nil, storageError(err)
	}
	if err = s.syncHydrateRoot(root); err != nil {
		return nil, storageError(err)
	}
	return last, nil
}

// A matching publication identity does not by itself authorize restoring an
// arbitrary build descriptor, guidance file, or path outside the editable roots.
func (s *Service) validateArchivePaths(r *record) error {
	for _, p := range s.publications {
		if !proto.Equal(p.Identity, r.Snapshot.Problem) {
			continue
		}
		for path, content := range p.InitialFiles {
			if !editablePath(path) && r.Hashes[path] != sum([]byte(content)) {
				return status.Error(codes.DataLoss, "protected archive file differs from publication")
			}
		}
		for path := range r.Hashes {
			_, published := p.InitialFiles[path]
			if !validPath(path) || (!published && !editablePath(path)) {
				return status.Error(codes.DataLoss, "invalid archived path")
			}
			for prefix := path; strings.Contains(prefix, "/"); {
				prefix = prefix[:strings.LastIndexByte(prefix, '/')]
				if _, ok := r.Hashes[prefix]; ok {
					return status.Error(codes.DataLoss, "archive file and directory conflict")
				}
			}
		}
		return nil
	}
	return status.Error(codes.FailedPrecondition, "problem version not published on destination")
}

// A process crash during hydrate can leave an unpublished sibling directory.
// The caller owns the volume exclusively at startup, before any RPC can run.
func cleanupHydrateStages(fd int) error {
	owned, err := os.OpenRoot(fmt.Sprintf("/proc/self/fd/%d", fd))
	if err != nil {
		return err
	}
	defer owned.Close()
	dir, err := owned.Open(".")
	if err != nil {
		return err
	}
	defer dir.Close()
	changed := false
	for {
		entries, err := dir.ReadDir(128)
		if errors.Is(err, io.EOF) {
			break
		}
		if err != nil {
			return err
		}
		for _, e := range entries {
			id, ok := strings.CutPrefix(e.Name(), "hydrate-")
			if ok && uuid.MatchString(id) {
				if err := owned.RemoveAll(e.Name()); err != nil {
					return err
				}
				changed = true
			}
		}
	}
	if changed {
		return syscall.Fsync(fd)
	}
	return nil
}
