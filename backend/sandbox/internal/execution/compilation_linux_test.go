package execution_test

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"codecraft.local/sandbox/internal/execution"
	"github.com/docker/docker/client"
)

func TestRestrictedExecutionUnwritableCompilerOutputIsEnvironmentError(t *testing.T) {
	source, _ := os.ReadFile("/fixture/reference/src/main/java/interview/CartTotal.java")
	runner, input, docker, root := fixture(t, string(source))
	if err := runner.Close(); err != nil {
		t.Fatal(err)
	}
	httpClient := docker.HTTPClient()
	realTransport := httpClient.Transport
	httpClient.Transport = transportFunc(func(request *http.Request) (*http.Response, error) {
		if request.Method == http.MethodPost && strings.HasSuffix(request.URL.Path, "/containers/create") {
			// Trusted environment-fault fixture only: make javac's output read-only.
			// Keep /work writable so marker/status writes cannot hide misclassification.
			var config map[string]json.RawMessage
			err := json.NewDecoder(request.Body).Decode(&config)
			closeErr := request.Body.Close()
			if err != nil {
				return nil, err
			}
			if closeErr != nil {
				return nil, closeErr
			}
			var command []string
			if err = json.Unmarshal(config["Cmd"], &command); err != nil {
				return nil, err
			}
			command[len(command)-1] = "mkdir -p /work/target/classes; chmod 0555 /work/target/classes; " + command[len(command)-1]
			config["Cmd"], err = json.Marshal(command)
			if err != nil {
				return nil, err
			}
			body, err := json.Marshal(config)
			if err != nil {
				return nil, err
			}
			request.Body = io.NopCloser(bytes.NewReader(body))
			request.ContentLength = int64(len(body))
		}
		return realTransport.RoundTrip(request)
	})
	faultClient, err := client.NewClientWithOpts(client.FromEnv, client.WithHTTPClient(httpClient), client.WithAPIVersionNegotiation())
	if err != nil {
		t.Fatal(err)
	}
	defer faultClient.Close()
	faultRunner, err := execution.New(context.Background(), faultClient, root, os.Getenv("CODECRAFT_EXECUTION_VOLUME"), strings.TrimPrefix(filepath.Base(root), "registry-"))
	if err != nil {
		t.Fatal(err)
	}
	defer faultRunner.Close()
	result, err := faultRunner.Run(context.Background(), input)
	if err != nil || result.State != "ENVIRONMENT_ERROR" || !result.Quiescent || result.Cleanup != "SUCCEEDED" {
		t.Fatalf("output I/O error misclassified: state=%s reason=%s error=%v", result.State, result.Reason, err)
	}
	var output strings.Builder
	for _, chunk := range result.Logs {
		output.Write(chunk.Data)
	}
	if !strings.Contains(output.String(), "error while writing") {
		t.Fatalf("fixture failed before reaching javac output write: %s", output.String())
	}
}

func TestRestrictedExecutionCompilerResourceFailureIsEnvironmentError(t *testing.T) {
	source := "package interview; public class CartTotal { Object value = " + strings.Repeat("(", 10000) + "null" + strings.Repeat(")", 10000) + "; }"
	runner, input, _, _ := fixture(t, source)
	result, err := runner.Run(context.Background(), input)
	if err != nil || result.State != "ENVIRONMENT_ERROR" || !result.Quiescent || len(result.CompilationReport) != 0 {
		t.Fatalf("compiler resource error graded as code failure: state=%s reason=%s error=%v", result.State, result.Reason, err)
	}
	var output strings.Builder
	for _, chunk := range result.Logs {
		output.Write(chunk.Data)
	}
	if !strings.Contains(output.String(), "StackOverflowError") {
		t.Fatalf("fixture did not exhaust compiler stack: %s", output.String())
	}
}
