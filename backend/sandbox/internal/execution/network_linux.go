package execution

import (
	"context"
	"errors"
	"strings"
	"time"

	"github.com/docker/docker/api/types/filters"
	"github.com/docker/docker/api/types/network"
	"github.com/docker/docker/errdefs"
)

// ConfigureEgress connects only this trusted coordinator to per-execution internal
// bridges. No two untrusted commands share an L2 network. Called before serving.
func (r *Runner) ConfigureEgress(ctx context.Context, coordinator string) error {
	if coordinator == "" {
		return errors.New("missing egress coordinator identity")
	}
	r.coordinator = coordinator
	networks, err := r.docker.NetworkList(ctx, network.ListOptions{Filters: filters.NewArgs(filters.Arg("label", "codecraft.execution.volume="+r.volume))})
	if err != nil {
		return err
	}
	for _, found := range networks {
		id := found.Labels["codecraft.execution.id"]
		record, err := r.Query(ctx, id)
		if err != nil || found.Labels["codecraft.execution.owner"] != r.owner || found.Name != r.executionNetwork(id) {
			return errors.New("unregistered command network requires reconciliation")
		}
		if record.Quiescent && record.Cleanup == "SUCCEEDED" {
			if err := r.removeNetwork(ctx, record.Input); err != nil {
				return err
			}
		} else {
			r.blocked = true
		}
	}
	return nil
}

func (r *Runner) executionNetwork(id string) string { return r.commandNetwork + "-" + id }

func (r *Runner) prepareNetwork(ctx context.Context, input Input) error {
	if input.Command == nil || r.commandNetwork == "" {
		return nil
	}
	if r.coordinator == "" {
		return errors.New("egress coordinator is not configured")
	}
	name := r.executionNetwork(input.ExecutionID)
	labels := map[string]string{"codecraft.execution.volume": r.volume, "codecraft.execution.owner": r.owner, "codecraft.execution.id": input.ExecutionID}
	if _, err := r.docker.NetworkCreate(ctx, name, network.CreateOptions{Driver: "bridge", Internal: true, Labels: labels}); err != nil {
		return err
	}
	return r.docker.NetworkConnect(ctx, name, r.coordinator, &network.EndpointSettings{Aliases: []string{"egress"}})
}

func (r *Runner) removeNetwork(ctx context.Context, input Input) error {
	if input.Command == nil || r.commandNetwork == "" {
		return nil
	}
	call, cancel := context.WithTimeout(context.WithoutCancel(ctx), 5*time.Second)
	defer cancel()
	name := r.executionNetwork(input.ExecutionID)
	found, err := r.docker.NetworkInspect(call, name, network.InspectOptions{})
	if errdefs.IsNotFound(err) {
		return nil
	}
	if err != nil {
		return err
	}
	if !found.Internal || found.Labels["codecraft.execution.owner"] != r.owner || found.Labels["codecraft.execution.id"] != input.ExecutionID || found.Labels["codecraft.execution.volume"] != r.volume {
		return errors.New("command network ownership mismatch")
	}
	// A restart may change the coordinator container ID. Disconnect only endpoints
	// other than the execution container after its removal was durably confirmed.
	for id, endpoint := range found.Containers {
		if endpoint.Name == "cc-execution-"+input.ExecutionID || !strings.HasPrefix(id, r.coordinator) {
			return errors.New("command endpoint still present")
		}
		if err := r.docker.NetworkDisconnect(call, found.ID, id, true); err != nil && !errdefs.IsNotFound(err) {
			return err
		}
	}
	err = r.docker.NetworkRemove(call, found.ID)
	if errdefs.IsNotFound(err) {
		return nil
	}
	return err
}
