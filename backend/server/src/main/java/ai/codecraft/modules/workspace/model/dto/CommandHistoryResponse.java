package ai.codecraft.modules.workspace.model.dto;

import java.util.List;

/** History contains metadata, not retained log bodies; details are fetched on selection. */
public record CommandHistoryResponse(List<CommandTaskResponse> commands, String nextCursor) {
    public CommandHistoryResponse { commands = List.copyOf(commands); }
}
