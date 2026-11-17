package ai.codecraft.modules.copilot.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.codecraft.infrastructure.stream.TransientTaskStreamHub;
import ai.codecraft.modules.copilot.model.dto.CopilotDeliveryDTO;
import ai.codecraft.modules.copilot.model.dto.CopilotPartialResponse;
import ai.codecraft.modules.copilot.model.dto.CopilotTaskResponse;
import ai.codecraft.modules.workspace.service.StorageCapacityService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class CopilotPreviewDeliveryTest {
    @Test
    void onlyAuthorizedCurrentRunningTurnGetsTransientFrameWithoutAdvancingCursor() {
        var store = mock(CopilotTaskStore.class);
        var hub = new TransientTaskStreamHub();
        var service = new CopilotTaskService(store, mock(StorageCapacityService.class), new ObjectMapper(), mock(CopilotReferences.class), hub);
        when(store.delivery("session", "task")).thenReturn(snapshot("RUNNING", "turn"));
        var first = service.batch("session", "exercise", "task", null, true);
        byte[] frame = "event:copilot_preview\ndata:{}\n\n".getBytes(StandardCharsets.UTF_8);
        hub.preview("task", "turn", frame);
        var preview = service.batch("session", "exercise", "task", first.cursor(), true);
        assertEquals(first.cursor(), preview.cursor());
        assertEquals(new String(frame, StandardCharsets.UTF_8), new String(preview.bytes(), StandardCharsets.UTF_8));
        assertFalse(new String(preview.bytes(), StandardCharsets.UTF_8).contains("id:"));
        assertEquals(0, service.batch("session", "exercise", "task", first.cursor(), false).bytes().length);
        when(store.delivery("session", "task")).thenReturn(snapshot("SUCCEEDED", "turn"));
        assertFalse(new String(service.batch("session", "exercise", "task", first.cursor(), true).bytes(), StandardCharsets.UTF_8).contains("copilot_preview"));
        when(store.delivery("session", "task")).thenReturn(snapshot("RUNNING", "next-turn"));
        assertFalse(new String(service.batch("session", "exercise", "task", first.cursor(), true).bytes(), StandardCharsets.UTF_8).contains("copilot_preview"));
        verify(store, times(5)).delivery("session", "task");
        assertTrue(new String(first.bytes(), StandardCharsets.UTF_8).startsWith("id:"));
    }

    private static CopilotDeliveryDTO snapshot(String state, String turn) {
        return new CopilotDeliveryDTO("user", new CopilotTaskResponse("task", "exercise", state, null, false, null, null), "0", false,
            new CopilotPartialResponse(turn, "0", "", false, "", false));
    }
}
