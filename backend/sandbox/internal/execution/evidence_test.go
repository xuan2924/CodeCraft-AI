package execution_test

import (
	"crypto/sha256"
	"fmt"
	"strings"
	"testing"

	"codecraft.local/sandbox/internal/execution"
)

func TestEvidenceValidatesPublicGradingContract(t *testing.T) {
	passing := `<testsuite name="Authority" tests="2" failures="0" errors="0" skipped="0"><testcase name="one" classname="Authority"/><testcase name="two" classname="Authority"/></testsuite>`
	failing := strings.Replace(strings.Replace(passing, `failures="0"`, `failures="1"`, 1), `name="one" classname="Authority"/>`, `name="one" classname="Authority"><failure>wrong answer</failure></testcase>`, 1)
	cases := []struct {
		name, xml, state string
		exit             int
		valid            bool
	}{
		{"passed", passing, "PASSED", 0, true}, {"failed", failing, "FAILED", 1, true},
		{"wrong suite", strings.ReplaceAll(passing, "Authority", "Untrusted"), "PASSED", 0, false},
		{"duplicate case", strings.Replace(passing, `name="two"`, `name="one"`, 1), "PASSED", 0, false},
		{"missing case", strings.Replace(passing, `<testcase name="two" classname="Authority"/>`, "", 1), "PASSED", 0, false},
		{"wrong class", strings.Replace(passing, `classname="Authority"`, `classname="Other"`, 1), "PASSED", 0, false},
		{"summary disagrees", strings.Replace(failing, `failures="1"`, `failures="0"`, 1), "FAILED", 1, false},
		{"skipped", strings.ReplaceAll(strings.ReplaceAll(failing, "failure", "skipped"), `skippeds="1"`, `failures="0"`), "FAILED", 1, false},
		{"exit disagrees", passing, "PASSED", 1, false},
		{"state disagrees", passing, "FAILED", 0, false},
		{"missing", "", "PASSED", 0, false},
		{"trailing XML", passing + "<extra/>", "PASSED", 0, false},
		{"oversize", strings.Repeat("x", 2*1024*1024+1), "PASSED", 0, false},
		{"non grading state", passing, "ENVIRONMENT_ERROR", 0, false},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			result := execution.Result{Input: execution.Input{Suite: "Authority", Tests: []string{"one", "two"}}, State: tc.state, Report: []byte(tc.xml), ExitCode: &tc.exit}
			evidence := result.Evidence()
			if (evidence != nil) != tc.valid {
				t.Fatalf("evidence validity=%v, expected %v", evidence != nil, tc.valid)
			}
			if evidence != nil && (evidence.Kind != "SUREFIRE_XML" || evidence.Bytes != uint64(len(tc.xml)) || evidence.SHA256 != fmt.Sprintf("%x", sha256.Sum256([]byte(tc.xml)))) {
				t.Fatalf("incorrect evidence metadata: %+v", evidence)
			}
			result.ExitCode = nil
			if result.Evidence() != nil {
				t.Fatal("missing exit status must not yield grading evidence")
			}
		})
	}
}

func TestEvidenceDistinguishesSourceCompilationFromInfrastructureFailure(t *testing.T) {
	for _, tc := range []struct {
		report string
		valid  bool
	}{
		{"compiler-source-errors-v2\ncompiler.err.cant.resolve\n", true},
		{"compiler-failure-v1\ncompile\n", false},
		{"compiler-failure-v1\ntestCompile\n", false},
		{"compiler-source-errors-v2\ncompiler.err.class.cant.write\n", false},
		{"compiler-source-errors-v2\ncompiler.err.locn.bad.module-info\n", false},
		{"compiler-source-errors-v2\ncompiler.err.cant.resolve\ncompiler.err.cant.resolve\n", false},
		{"compiler-source-errors-v2\n", false}, {"BUILD SUCCESS", false},
	} {
		result := execution.Result{State: "FAILED", Reason: "compilation_failed", CompilationReport: []byte(tc.report)}
		if (result.Evidence() != nil) != tc.valid {
			t.Errorf("unexpected compilation evidence for %q", tc.report)
		}
	}
}
