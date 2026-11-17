package execution

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/docker/docker/api/types/network"
	"github.com/docker/docker/client"
)

func TestCommandNetworkIsPrivatePerExecutionAndCleanupChecksOwnership(t *testing.T) {
	input := commandInput()
	created := false
	connected := false
	removed := false
	foreign := false
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		switch {
		case strings.HasSuffix(r.URL.Path, "/networks/create"):
			var request struct {
				Name string
				network.CreateOptions
			}
			if err := json.NewDecoder(r.Body).Decode(&request); err != nil {
				t.Error(err)
			}
			if !request.Internal || request.Driver != "bridge" || request.Name != "private-"+input.ExecutionID || request.Labels["codecraft.execution.id"] != input.ExecutionID {
				t.Error("network isolation/receipt missing")
			}
			created = true
			_, _ = w.Write([]byte(`{"Id":"network-id"}`))
		case strings.HasSuffix(r.URL.Path, "/connect"):
			connected = true
			_, _ = w.Write([]byte(`{}`))
		case strings.HasSuffix(r.URL.Path, "/disconnect"):
			_, _ = w.Write([]byte(`{}`))
		case r.Method == "GET":
			owner := "owner"
			if foreign {
				owner = "foreign"
			}
			_ = json.NewEncoder(w).Encode(map[string]any{"Id": "network-id", "Internal": true, "Labels": map[string]string{"codecraft.execution.owner": owner, "codecraft.execution.volume": "volume", "codecraft.execution.id": input.ExecutionID}, "Containers": map[string]any{"coordinator-id": map[string]string{"Name": "sandbox"}}})
		case r.Method == "DELETE":
			removed = true
			_, _ = w.Write([]byte(`{}`))
		default:
			t.Errorf("unexpected network call: %s %s", r.Method, r.URL.Path)
		}
	}))
	defer server.Close()
	docker, err := client.NewClientWithOpts(client.WithHost(server.URL), client.WithVersion("1.45"))
	if err != nil {
		t.Fatal(err)
	}
	defer docker.Close()
	runner := &Runner{docker: docker, commandNetwork: "private", coordinator: "coordinator-id", owner: "owner", volume: "volume"}
	if err := runner.prepareNetwork(context.Background(), input); err != nil {
		t.Fatal(err)
	}
	if !created || !connected {
		t.Fatal("proxy not connected to private network")
	}
	foreign = true
	if err := runner.removeNetwork(context.Background(), input); err == nil || removed {
		t.Fatal("foreign network removed")
	}
	foreign = false
	if err := runner.removeNetwork(context.Background(), input); err != nil || !removed {
		t.Fatalf("owned network not cleaned: %v", err)
	}
	other := input
	other.ExecutionID = "other"
	if runner.executionNetwork(input.ExecutionID) == runner.executionNetwork(other.ExecutionID) {
		t.Fatal("commands share a bridge")
	}
}
