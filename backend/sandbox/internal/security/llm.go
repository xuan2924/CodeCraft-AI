package security

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io"
	"log"
	"net/http"
	"net/http/httputil"
	"net/url"
	"time"
)

// LLMChannel exposes only three model endpoints, never the grant endpoint or a
// general CONNECT tunnel to the control plane. It owns no upstream model key.
type LLMChannel struct {
	target *url.URL
	key    string
	client *http.Client
	relay  *httputil.ReverseProxy
}

// NewLLMChannel takes trusted coordinator configuration, not container input.
func NewLLMChannel(base, key string) (*LLMChannel, error) {
	target, err := url.Parse(base)
	if err != nil || target.Host == "" || (target.Scheme != "http" && target.Scheme != "https") || target.User != nil || target.RawQuery != "" || target.Fragment != "" || (target.Path != "" && target.Path != "/") || len(key) != 64 {
		return nil, errors.New("invalid LLM control channel")
	}
	transport := &http.Transport{Proxy: nil, ResponseHeaderTimeout: 30 * time.Second}
	channel := &LLMChannel{target: target, key: key, client: &http.Client{Transport: transport, Timeout: 10 * time.Second, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}}
	channel.relay = &httputil.ReverseProxy{Transport: transport, FlushInterval: -1, ErrorLog: log.New(io.Discard, "", 0),
		Rewrite: func(r *httputil.ProxyRequest) {
			r.SetURL(target)
			r.Out.Host = target.Host
			r.Out.Header = make(http.Header)
			for _, name := range []string{"Content-Type", "Authorization", "X-Api-Key", "X-Codecraft-Proxy-Token", "Anthropic-Beta"} {
				if value := r.In.Header.Get(name); value != "" {
					r.Out.Header.Set(name, value)
				}
			}
		},
		ModifyResponse: func(r *http.Response) error {
			kind := r.Header.Get("Content-Type")
			r.Header = make(http.Header)
			r.Header.Set("Content-Type", kind)
			r.Header.Set("Cache-Control", "no-store")
			return nil
		},
		ErrorHandler: func(w http.ResponseWriter, r *http.Request, err error) {
			http.Error(w, "model proxy unavailable", http.StatusBadGateway)
		},
	}
	return channel, nil
}

// Handler keeps package egress separate from the narrow internal model route.
func (c *LLMChannel) Handler(packages http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		path := r.URL.EscapedPath()
		allowed := path == "/internal/llm-proxy/anthropic/v1/messages" || path == "/internal/llm-proxy/openai/v1/chat/completions" || path == "/internal/llm-proxy/openai/v1/responses"
		if allowed && r.Method == http.MethodPost && r.URL.RawQuery == "" && r.URL.User == nil && (r.URL.Host == "" || r.URL.Host == "egress:3128") {
			r.Body = http.MaxBytesReader(w, r.Body, 1024*1024)
			c.relay.ServeHTTP(w, r)
			return
		}
		packages.ServeHTTP(w, r)
	})
}

// Grant obtains an ephemeral token for an already-admitted terminal. Neither
// Input, Docker recovery labels nor application logs retain the returned value.
func (c *LLMChannel) Grant(ctx context.Context, task, exercise string, epoch uint64) ([]string, error) {
	body, err := json.Marshal(map[string]any{"task": task, "exercise": exercise, "epoch": epoch})
	if err != nil {
		return nil, errors.New("proxy grant unavailable")
	}
	target := *c.target
	target.Path = "/internal/llm-grants"
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, target.String(), bytes.NewReader(body))
	if err != nil {
		return nil, errors.New("proxy grant unavailable")
	}
	req.Header.Set("X-Codecraft-Internal-Key", c.key)
	req.Header.Set("Content-Type", "application/json")
	response, err := c.client.Do(req)
	if err != nil {
		return nil, errors.New("proxy grant unavailable")
	}
	defer response.Body.Close()
	if response.StatusCode != 200 {
		return nil, errors.New("proxy grant denied")
	}
	var grant struct {
		Token string `json:"token"`
	}
	data, err := io.ReadAll(io.LimitReader(response.Body, 4097))
	if err != nil || len(data) > 4096 || json.Unmarshal(data, &grant) != nil || len(grant.Token) < 32 || len(grant.Token) > 1024 {
		return nil, errors.New("invalid proxy grant")
	}
	return []string{"ANTHROPIC_BASE_URL=http://egress:3128/internal/llm-proxy/anthropic", "OPENAI_BASE_URL=http://egress:3128/internal/llm-proxy/openai/v1", "CODECRAFT_PROXY_TOKEN=" + grant.Token, "ANTHROPIC_API_KEY=" + grant.Token, "OPENAI_API_KEY=" + grant.Token}, nil
}
