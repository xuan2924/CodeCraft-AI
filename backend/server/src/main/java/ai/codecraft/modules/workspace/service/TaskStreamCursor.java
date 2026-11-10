package ai.codecraft.modules.workspace.service;

import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.workspace.model.entity.TaskStreamEntity;
import ai.codecraft.modules.workspace.model.exception.WorkspaceErrorCode;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

final class TaskStreamCursor {
    private TaskStreamCursor() {}
    static String encode(TaskStreamEntity stream, long sequence) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString((stream.taskId() + "." + stream.streamId() + "." + sequence).getBytes(StandardCharsets.UTF_8));
    }
    static long decode(TaskStreamEntity stream, String cursor) {
        if (cursor == null || cursor.isBlank()) return 0;
        try {
            if (cursor.length() > 160 || !cursor.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException();
            String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String prefix = stream.taskId() + "." + stream.streamId() + ".";
            if (!decoded.startsWith(prefix)) throw new IllegalArgumentException();
            long sequence = Long.parseLong(decoded.substring(prefix.length()));
            if (sequence < 0 || sequence > stream.sequence() || sequence < stream.firstSequence() - 1
                || !encode(stream, sequence).equals(cursor)) throw new IllegalArgumentException();
            return sequence;
        } catch (IllegalArgumentException error) { throw new BusinessException(WorkspaceErrorCode.CURSOR_EXPIRED); }
    }
}
