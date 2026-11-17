package security

import (
	"context"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestLLMChannelGrantAndExactRoutes(t *testing.T) {
	calls := 0
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls++
		if r.URL.Path == "/internal/llm-grants" {
			if r.Header.Get("X-Codecraft-Internal-Key") != strings.Repeat("a", 64) {
				t.Error("missing internal authentication")
			}
			_, _ = io.WriteString(w, `{"token":"`+strings.Repeat("t", 64)+`"}`)
			return
		}
		if r.URL.Path != "/internal/llm-proxy/openai/v1/responses" || r.Header.Get("X-Codecraft-Internal-Key") != "" || r.Header.Get("Authorization") != "Bearer ephemeral" {
			t.Error("unsafe relay")
		}
		w.Header().Set("X-Leak", "private")
		w.Header().Set("Content-Type", "text/event-stream")
		_, _ = io.WriteString(w, "data: {}\n\n")
	}))
	defer upstream.Close()
	channel, err := NewLLMChannel(upstream.URL, strings.Repeat("a", 64))
	if err != nil {
		t.Fatal(err)
	}
	env, err := channel.Grant(context.Background(), "task", "exercise", 7)
	if err != nil || len(env) != 5 {
		t.Fatal("grant failed", err)
	}
	for _, entry := range env {
		if strings.Contains(entry, strings.Repeat("a", 64)) {
			t.Fatal("control secret leaked")
		}
	}
	denied := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(403) })
	handler := channel.Handler(denied)
	for _, path := range []string{"/internal/llm-grants", "/api/admin", "/internal/llm-proxy/openai/v1/responses?x=y", "/internal/llm-proxy/openai/v1/../responses", "/internal/llm-proxy/openai/v1/%72esponses", "http://evil.example/internal/llm-proxy/openai/v1/responses"} {
		r := httptest.NewRequest(http.MethodPost, path, strings.NewReader("{}"))
		w := httptest.NewRecorder()
		handler.ServeHTTP(w, r)
		if w.Code != 403 {
			t.Fatalf("unsafe route admitted: %s", path)
		}
	}
	r := httptest.NewRequest(http.MethodPost, "http://egress:3128/internal/llm-proxy/openai/v1/responses", strings.NewReader("{}"))
	r.Header.Set("Authorization", "Bearer ephemeral")
	r.Header.Set("X-Codecraft-Internal-Key", "attacker")
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, r)
	if w.Code != 200 || w.Body.String() != "data: {}\n\n" || w.Header().Get("X-Leak") != "" {
		t.Fatal("incorrect relay")
	}
	if calls != 2 {
		t.Fatalf("denied requests reached control plane: %d", calls)
	}
}

func TestLLMGrantRejectsRedirectAndErrors(t *testing.T) {
	for _, status := range []int{302, 403, 500} {
		server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			w.Header().Set("Location", "http://127.0.0.1/")
			w.WriteHeader(status)
		}))
		channel, err := NewLLMChannel(server.URL, strings.Repeat("a", 64))
		if err != nil {
			t.Fatal(err)
		}
		if _, err = channel.Grant(context.Background(), "task", "exercise", 1); err == nil {
			t.Fatal("grant failure admitted")
		}
		server.Close()
	}
}
