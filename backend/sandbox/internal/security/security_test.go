package security

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

func TestProxyPinsPublicDNSAndRejectsEveryNonPublicAnswer(t *testing.T) {
	for _, ip := range []string{"127.0.0.1", "127.2.3.4", "::1", "169.254.169.254", "10.1.2.3", "172.16.1.1", "192.168.1.1", "fe80::1", "fc00::1", "0.0.0.0", "::", "::ffff:127.0.0.1", "100.64.0.1", "224.0.0.1"} {
		t.Run(ip, func(t *testing.T) {
			p, _ := NewProxy("registry.npmjs.org:443")
			p.lookup = func(context.Context, string) ([]net.IPAddr, error) {
				return []net.IPAddr{{IP: net.ParseIP("1.1.1.1")}, {IP: net.ParseIP(ip)}}, nil
			}
			p.dial = func(context.Context, string, string) (net.Conn, error) {
				t.Fatal("unsafe address dialed")
				return nil, nil
			}
			if _, err := p.connect(context.Background(), "registry.npmjs.org:443"); err == nil {
				t.Fatal("private/mixed DNS answer accepted")
			}
		})
	}
	p, _ := NewProxy("registry.npmjs.org:443")
	lookups := 0
	dialed := ""
	p.lookup = func(context.Context, string) ([]net.IPAddr, error) {
		lookups++
		if lookups > 1 {
			return []net.IPAddr{{IP: net.ParseIP("127.0.0.1")}}, nil
		}
		return []net.IPAddr{{IP: net.ParseIP("1.1.1.1")}}, nil
	}
	sentinel := errors.New("dial observed")
	p.dial = func(_ context.Context, network, address string) (net.Conn, error) {
		dialed = address
		return nil, sentinel
	}
	if _, err := p.connect(context.Background(), "registry.npmjs.org:443"); !errors.Is(err, sentinel) || dialed != "1.1.1.1:443" || lookups != 1 {
		t.Fatal("public DNS not pinned")
	}
	if _, err := p.connect(context.Background(), "registry.npmjs.org:443"); err == nil || lookups != 2 {
		t.Fatal("rebound address accepted")
	}
	for _, target := range []string{"evil.example:443", "registry.npmjs.org.evil:443", "registry.npmjs.org:22", "127.0.0.1:443"} {
		before := lookups
		if _, err := p.connect(context.Background(), target); err == nil || lookups != before {
			t.Fatal("allowlist bypass")
		}
	}
}

func TestHTTPProxyForwardsOnlyValidatedDestination(t *testing.T) {
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Proxy-Authorization") != "" {
			t.Error("proxy credentials leaked")
		}
		w.WriteHeader(http.StatusNoContent)
	}))
	defer upstream.Close()
	p, _ := NewProxy("repo.example:80")
	p.lookup = func(context.Context, string) ([]net.IPAddr, error) {
		return []net.IPAddr{{IP: net.ParseIP("1.1.1.1")}}, nil
	}
	p.dial = func(ctx context.Context, network, address string) (net.Conn, error) {
		if address != "1.1.1.1:80" {
			t.Error("unpinned dial")
		}
		return (&net.Dialer{}).DialContext(ctx, network, upstream.Listener.Addr().String())
	}
	request := httptest.NewRequest("GET", "http://repo.example/artifact", nil)
	request.Header.Set("Proxy-Authorization", "secret")
	result := httptest.NewRecorder()
	p.ServeHTTP(result, request)
	if result.Code != 204 {
		t.Fatalf("forward failed: %d", result.Code)
	}
	denied := httptest.NewRecorder()
	p.ServeHTTP(denied, httptest.NewRequest("CONNECT", "http://evil.example:443", nil))
	if denied.Code != 403 {
		t.Fatalf("CONNECT allowlist bypass: %d", denied.Code)
	}
}

func TestConnectTunnelUsesValidatedNumericAddress(t *testing.T) {
	p, _ := NewProxy("repo.example:443")
	p.lookup = func(context.Context, string) ([]net.IPAddr, error) {
		return []net.IPAddr{{IP: net.ParseIP("1.1.1.1")}}, nil
	}
	peerDone := make(chan struct{})
	p.dial = func(_ context.Context, _, address string) (net.Conn, error) {
		if address != "1.1.1.1:443" {
			t.Error("CONNECT did not pin DNS")
		}
		local, peer := net.Pipe()
		go func() { defer close(peerDone); defer peer.Close(); _, _ = io.Copy(peer, peer) }()
		return local, nil
	}
	server := httptest.NewServer(p)
	defer server.Close()
	conn, err := net.Dial("tcp", server.Listener.Addr().String())
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(5 * time.Second))
	_, _ = fmt.Fprint(conn, "CONNECT repo.example:443 HTTP/1.1\r\nHost: repo.example:443\r\n\r\n")
	reader := bufio.NewReader(conn)
	response, err := http.ReadResponse(reader, &http.Request{Method: "CONNECT"})
	if err != nil || response.StatusCode != 200 {
		t.Fatalf("CONNECT failed: %v", err)
	}
	_, _ = fmt.Fprint(conn, "ping")
	body := make([]byte, 4)
	if _, err = io.ReadFull(reader, body); err != nil || string(body) != "ping" {
		t.Fatalf("tunnel failed: %v", err)
	}
	_ = conn.Close()
	select {
	case <-peerDone:
	case <-time.After(5 * time.Second):
		t.Fatal("tunnel leaked after client close")
	}
}

func TestEmbeddedSeccompDefaultDeniesDangerousCalls(t *testing.T) {
	var profile struct {
		DefaultAction string `json:"defaultAction"`
		Syscalls      []struct {
			Names  []string          `json:"names"`
			Action string            `json:"action"`
			Args   []json.RawMessage `json:"args"`
		} `json:"syscalls"`
	}
	if err := json.Unmarshal([]byte(SeccompProfile), &profile); err != nil {
		t.Fatal(err)
	}
	if profile.DefaultAction != "SCMP_ACT_ERRNO" {
		t.Fatal("profile must default deny")
	}
	allowed := map[string]bool{}
	for _, rule := range profile.Syscalls {
		if rule.Action == "SCMP_ACT_ALLOW" {
			for _, name := range rule.Names {
				allowed[name] = true
				if name == "clone" && len(rule.Args) == 0 {
					t.Fatal("namespace clone unrestricted")
				}
			}
		}
	}
	for _, name := range []string{"ptrace", "kexec_load", "kexec_file_load", "bpf", "io_uring_setup", "io_uring_enter", "io_uring_register", "userfaultfd", "unshare", "setns", "mount", "umount2", "pivot_root", "reboot", "init_module", "finit_module", "perf_event_open", "keyctl"} {
		if allowed[name] {
			t.Errorf("dangerous syscall allowed: %s", name)
		}
	}
	for _, name := range []string{"read", "write", "execve", "futex", "mmap", "clone", "openat"} {
		if !allowed[name] {
			t.Errorf("toolchain syscall missing: %s", name)
		}
	}
}
