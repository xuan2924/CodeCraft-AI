package ai.codecraft.modules.workspace.model.dto;

import java.util.List;

public record FileBatchRequest(String expectedRevision, List<FileChangeRequest> changes) {}
