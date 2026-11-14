package ai.codecraft.modules.workspace.service;

import ai.codecraft.modules.workspace.model.dto.TaskStreamBatchDTO;

/** Authenticated bounded event source for the shared nonblocking exercise transport. */
@FunctionalInterface
public interface TaskStreamSource {
    TaskStreamBatchDTO batch(String session, String exercise, String task, String cursor, boolean deliver);
}
