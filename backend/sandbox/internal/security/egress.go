// Package security contains the execution boundary's network and syscall policy.
package security

import (
	"context"
	"errors"
	"io"
	"net"
	"net/http"
	"net/netip"
	"strings"
	"time"
)

// DefaultAllowlist is exact host:port matching, never a suffix or wildcard rule.
const DefaultAllowlist = "repo.maven.apache.org:443,registry.npmjs.org:443,repo1.maven.org:443"

// Proxy resolves each new connection once, validates every answer, and dials only
// the checked numeric address. Neither environment proxies nor a second resolver
// can substitute a private target after validation.
type Proxy struct {
	allowed map[string]bool
	lookup  func(context.Context, string) ([]net.IPAddr, error)
	dial    func(context.Context, string, string) (net.Conn, error)
	slots   chan struct{}
}

// NewProxy validates an administrator-owned exact destination allowlist.
func NewProxy(allowlist string) (*Proxy, error) {
	p := &Proxy{allowed: map[string]bool{}, lookup: net.DefaultResolver.LookupIPAddr,
		dial: (&net.Dialer{Timeout: 10 * time.Second}).DialContext, slots: make(chan struct{}, 64)}
	for _, entry := range strings.Split(allowlist, ",") {
		host, port, err := net.SplitHostPort(strings.TrimSpace(entry))
		if err != nil || host == "" || strings.ContainsAny(host, "*/%@") || (port != "443" && port != "80") || net.ParseIP(host) != nil {
			return nil, errors.New("invalid egress allowlist entry (exact domain:80 or domain:443 required)")
		}
		p.allowed[strings.ToLower(host)+":"+port] = true
	}
	return p, nil
}

var denied = []netip.Prefix{
	netip.MustParsePrefix("0.0.0.0/8"), netip.MustParsePrefix("10.0.0.0/8"), netip.MustParsePrefix("100.64.0.0/10"),
	netip.MustParsePrefix("127.0.0.0/8"), netip.MustParsePrefix("169.254.0.0/16"), netip.MustParsePrefix("172.16.0.0/12"),
	netip.MustParsePrefix("192.168.0.0/16"), netip.MustParsePrefix("192.0.0.0/24"), netip.MustParsePrefix("192.0.2.0/24"),
	netip.MustParsePrefix("198.18.0.0/15"), netip.MustParsePrefix("198.51.100.0/24"), netip.MustParsePrefix("203.0.113.0/24"),
	netip.MustParsePrefix("240.0.0.0/4"), netip.MustParsePrefix("fc00::/7"), netip.MustParsePrefix("fe80::/10"),
	netip.MustParsePrefix("2001::/23"), netip.MustParsePrefix("2001:db8::/32"), netip.MustParsePrefix("64:ff9b::/96"), netip.MustParsePrefix("2002::/16"),
}

func publicIP(ip net.IP) bool {
	addr, ok := netip.AddrFromSlice(ip)
	if !ok {
		return false
	}
	addr = addr.Unmap()
	if !addr.IsGlobalUnicast() || addr.IsPrivate() || addr.IsLoopback() || addr.IsUnspecified() {
		return false
	}
	// Only globally allocated IPv6 unicast; excludes translation/transition ranges.
	if addr.Is6() && !netip.MustParsePrefix("2000::/3").Contains(addr) {
		return false
	}
	for _, prefix := range denied {
		if prefix.Contains(addr) {
			return false
		}
	}
	return true
}

func (p *Proxy) connect(ctx context.Context, authority string) (net.Conn, error) {
	ctx, cancel := context.WithTimeout(ctx, 10*time.Second)
	defer cancel()
	host, port, err := net.SplitHostPort(authority)
	if err != nil || !p.allowed[strings.ToLower(host)+":"+port] {
		return nil, errors.New("egress destination denied")
	}
	answers, err := p.lookup(ctx, host)
	if err != nil || len(answers) == 0 {
		return nil, errors.New("egress DNS unavailable")
	}
	for _, answer := range answers {
		if answer.Zone != "" || !publicIP(answer.IP) {
			return nil, errors.New("egress address denied")
		}
	}
	var dialErr error
	for _, answer := range answers {
		conn, err := p.dial(ctx, "tcp", net.JoinHostPort(answer.IP.String(), port))
		if err == nil {
			return conn, nil
		}
		dialErr = errors.Join(dialErr, err)
		if ctx.Err() != nil {
			break
		}
	}
	return nil, dialErr
}

// ServeHTTP handles forward HTTP and HTTPS CONNECT with bounded lifetime/pressure.
func (p *Proxy) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	select {
	case p.slots <- struct{}{}:
		defer func() { <-p.slots }()
	default:
		http.Error(w, "proxy busy", 503)
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 5*time.Minute)
	defer cancel()
	if r.Method == http.MethodConnect {
		upstream, err := p.connect(ctx, r.Host)
		if err != nil {
			http.Error(w, "egress denied", 403)
			return
		}
		defer upstream.Close()
		hijacker, ok := w.(http.Hijacker)
		if !ok {
			http.Error(w, "tunnel unavailable", 500)
			return
		}
		client, buffered, err := hijacker.Hijack()
		if err != nil {
			return
		}
		defer client.Close()
		deadline := time.Now().Add(5 * time.Minute)
		_ = client.SetDeadline(deadline)
		_ = upstream.SetDeadline(deadline)
		stop := context.AfterFunc(ctx, func() { _ = client.Close(); _ = upstream.Close() })
		defer stop()
		if _, err = buffered.WriteString("HTTP/1.1 200 Connection Established\r\n\r\n"); err != nil {
			return
		}
		if err = buffered.Flush(); err != nil {
			return
		}
		done := make(chan struct{})
		go func() { _, _ = io.Copy(upstream, buffered); _ = upstream.Close(); close(done) }()
		_, _ = io.Copy(client, upstream)
		_ = client.Close()
		_ = upstream.Close()
		<-done
		return
	}
	if r.URL.Scheme != "http" || r.URL.Host == "" || r.URL.User != nil || r.Header.Get("Upgrade") != "" {
		http.Error(w, "unsupported proxy request", 400)
		return
	}
	authority := r.URL.Host
	if r.URL.Port() == "" {
		authority = net.JoinHostPort(r.URL.Hostname(), "80")
	}
	upstream, err := p.connect(ctx, authority)
	if err != nil {
		http.Error(w, "egress denied", 403)
		return
	}
	defer upstream.Close()
	_ = upstream.SetDeadline(time.Now().Add(5 * time.Minute))
	transport := &http.Transport{DialContext: func(context.Context, string, string) (net.Conn, error) { return upstream, nil }, DisableKeepAlives: true, ResponseHeaderTimeout: 30 * time.Second}
	defer transport.CloseIdleConnections()
	request := r.Clone(ctx)
	request.RequestURI = ""
	request.Host = request.URL.Host
	stripHopHeaders(request.Header)
	response, err := transport.RoundTrip(request)
	if err != nil {
		http.Error(w, "egress unavailable", 502)
		return
	}
	defer response.Body.Close()
	stripHopHeaders(response.Header)
	for name, values := range response.Header {
		for _, value := range values {
			w.Header().Add(name, value)
		}
	}
	w.WriteHeader(response.StatusCode)
	_, _ = io.Copy(w, response.Body)
}

func stripHopHeaders(h http.Header) {
	for _, name := range strings.Split(h.Get("Connection"), ",") {
		h.Del(strings.TrimSpace(name))
	}
	for _, name := range []string{"Connection", "Proxy-Connection", "Proxy-Authorization", "Proxy-Authenticate", "Keep-Alive", "TE", "Trailer", "Transfer-Encoding", "Upgrade"} {
		h.Del(name)
	}
}
