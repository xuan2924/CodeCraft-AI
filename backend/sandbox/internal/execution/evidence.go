package execution

import (
	"bytes"
	cryptoSHA256 "crypto/sha256"
	"encoding/xml"
	"errors"
	"fmt"
	"io"
	"regexp"
	"strings"
	"sync"
)

const reportLimit = 2 * 1024 * 1024
const compilationReportLimit = 16 * 1024

var compilationCode = regexp.MustCompile(`^compiler\.err\.[a-zA-Z0-9_.]{1,100}$`)

func validCompilationReport(data []byte) bool {
	// Previously persisted fixed-image v1 evidence remains readable.
	if bytes.Equal(data, []byte("compiler-failure-v1\ncompile\n")) || bytes.Equal(data, []byte("compiler-failure-v1\ntestCompile\n")) {
		return true
	}
	if len(data) > compilationReportLimit || !bytes.HasSuffix(data, []byte("\n")) {
		return false
	}
	lines := strings.Split(strings.TrimSuffix(string(data), "\n"), "\n")
	if len(lines) < 2 || len(lines) > 129 || lines[0] != "compiler-source-errors-v2" {
		return false
	}
	seen := map[string]bool{}
	for _, code := range lines[1:] {
		if !compilationCode.MatchString(code) || seen[code] || strings.HasPrefix(code, "compiler.err.locn.") || strings.HasPrefix(code, "compiler.err.proc.") {
			return false
		}
		switch code {
		case "compiler.err.class.cant.write", "compiler.err.error.writing.file", "compiler.err.error.reading.file", "compiler.err.io.exception", "compiler.err.cant.read.file", "compiler.err.cant.access", "compiler.err.unsupported.encoding", "compiler.err.no.java.lang":
			return false
		}
		seen[code] = true
	}
	return true
}

// Evidence identifies retained authoritative bytes, not untrusted console text.
// The execution identity plus Kind locates the artifact in the durable result.
type Evidence struct {
	Kind, SHA256 string
	Bytes        uint64
}

// Evidence returns bounded metadata only for a validated grading artifact.
func (r Result) Evidence() *Evidence {
	var kind string
	var data []byte
	if r.State == "FAILED" && r.Reason == "compilation_failed" && validCompilationReport(r.CompilationReport) {
		kind, data = "COMPILATION_DIAGNOSTICS", r.CompilationReport
	} else if r.State == "PASSED" || r.State == "FAILED" {
		if r.ExitCode == nil {
			return nil
		}
		state, err := classifyReport(r.Report, r.Input, *r.ExitCode)
		if err != nil || state != r.State {
			return nil
		}
		kind, data = "SUREFIRE_XML", r.Report
	} else {
		return nil
	}
	return &Evidence{Kind: kind, SHA256: fmt.Sprintf("%x", cryptoSHA256.Sum256(data)), Bytes: uint64(len(data))}
}

type tailLog struct {
	mu      sync.Mutex
	limit   int
	size    int
	dropped uint64
	chunks  []LogChunk
}
type channelWriter struct {
	log     *tailLog
	channel string
}

func (w channelWriter) Write(data []byte) (int, error) {
	length := len(data)
	w.log.mu.Lock()
	defer w.log.mu.Unlock()
	for len(data) > 0 {
		size := min(len(data), 16*1024)
		w.log.chunks = append(w.log.chunks, LogChunk{Channel: w.channel, Data: bytes.Clone(data[:size])})
		w.log.size += size
		data = data[size:]
		// Byte limits alone do not bound metadata when a program alternates tiny
		// stdout/stderr writes. Bound the retained fragment count independently.
		for len(w.log.chunks) > 4096 {
			discard := len(w.log.chunks[0].Data)
			w.log.dropped += uint64(discard)
			w.log.size -= discard
			w.log.chunks[0] = LogChunk{}
			w.log.chunks = w.log.chunks[1:]
		}
		for w.log.size > w.log.limit {
			discard := min(w.log.size-w.log.limit, len(w.log.chunks[0].Data))
			w.log.chunks[0].Data = w.log.chunks[0].Data[discard:]
			w.log.size -= discard
			w.log.dropped += uint64(discard)
			if len(w.log.chunks[0].Data) == 0 {
				w.log.chunks[0] = LogChunk{}
				w.log.chunks = w.log.chunks[1:]
			}
		}
	}
	return length, nil
}
func (l *tailLog) snapshot() ([]LogChunk, uint64) {
	l.mu.Lock()
	defer l.mu.Unlock()
	result := make([]LogChunk, len(l.chunks))
	for i, chunk := range l.chunks {
		result[i] = LogChunk{Channel: chunk.Channel, Data: bytes.Clone(chunk.Data)}
	}
	return result, l.dropped
}

type suiteReport struct {
	XMLName  xml.Name     `xml:"testsuite"`
	Name     string       `xml:"name,attr"`
	Tests    *int         `xml:"tests,attr"`
	Failures *int         `xml:"failures,attr"`
	Errors   *int         `xml:"errors,attr"`
	Skipped  *int         `xml:"skipped,attr"`
	Cases    []caseReport `xml:"testcase"`
}
type caseReport struct {
	Name    string   `xml:"name,attr"`
	Class   string   `xml:"classname,attr"`
	Failure []string `xml:"failure"`
	Error   []string `xml:"error"`
	Skipped []string `xml:"skipped"`
}

// classifyReport requires the complete authority suite and cross-checks each
// testcase against summary counters and the controlled Maven exit status.
// XML never resolves external entities; logs are not an input to this decision.
func classifyReport(data []byte, input Input, exit int) (string, error) {
	if len(data) == 0 || len(data) > reportLimit {
		return "", errors.New("missing or excessive authority report")
	}
	decoder := xml.NewDecoder(bytes.NewReader(data))
	var report suiteReport
	if err := decoder.Decode(&report); err != nil {
		return "", fmt.Errorf("invalid authority XML: %w", err)
	}
	for {
		token, err := decoder.Token()
		if errors.Is(err, io.EOF) {
			break
		}
		if err != nil {
			return "", err
		}
		if text, ok := token.(xml.CharData); !ok || len(bytes.TrimSpace(text)) != 0 {
			return "", errors.New("trailing authority XML")
		}
	}
	if report.Name != input.Suite || report.Tests == nil || report.Failures == nil || report.Errors == nil || report.Skipped == nil || *report.Tests != len(input.Tests) || len(report.Cases) != len(input.Tests) {
		return "", errors.New("incomplete authority suite")
	}
	expected := map[string]bool{}
	for _, name := range input.Tests {
		expected[name] = true
	}
	failures, errs, skips := 0, 0, 0
	for _, test := range report.Cases {
		if !expected[test.Name] || test.Class != input.Suite || len(test.Failure)+len(test.Error)+len(test.Skipped) > 1 {
			return "", errors.New("unexpected authority testcase")
		}
		delete(expected, test.Name)
		failures += len(test.Failure)
		errs += len(test.Error)
		skips += len(test.Skipped)
	}
	if *report.Failures != failures || *report.Errors != errs || *report.Skipped != skips || skips != 0 || len(expected) != 0 {
		return "", errors.New("authority counts disagree or tests skipped")
	}
	if failures+errs == 0 && exit == 0 {
		return "PASSED", nil
	}
	if failures+errs > 0 && exit == 1 {
		return "FAILED", nil
	}
	return "", errors.New("authority report and process exit disagree")
}
