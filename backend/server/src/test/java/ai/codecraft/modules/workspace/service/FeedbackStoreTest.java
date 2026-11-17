package ai.codecraft.modules.workspace.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.identity.model.dto.SessionAuthorizationDTO;
import ai.codecraft.modules.identity.service.IdentitySecrets;
import ai.codecraft.modules.identity.service.IdentityTransactionService;
import ai.codecraft.modules.workspace.model.dto.ExerciseReportResponse;
import ai.codecraft.modules.workspace.model.dto.FeedbackRequest;
import ai.codecraft.modules.workspace.model.entity.ExerciseEntity;
import ai.codecraft.modules.workspace.model.entity.FeedbackEntity;
import ai.codecraft.modules.workspace.model.exception.WorkspaceErrorCode;
import ai.codecraft.modules.workspace.repository.ExerciseRepository;
import ai.codecraft.modules.workspace.repository.FeedbackRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FeedbackStoreTest {
    private static final String TARGET = "00000000-0000-0000-0000-000000000001";
    private final Instant now = Instant.parse("2026-01-01T00:00:00Z");
    private final FeedbackRepository repository = mock(FeedbackRepository.class);
    private final ExerciseRepository exercises = mock(ExerciseRepository.class);
    private final ExerciseReportStore reports = mock(ExerciseReportStore.class);
    private final IdentityTransactionService identity = mock(IdentityTransactionService.class);
    private final IdentitySecrets secrets = mock(IdentitySecrets.class);
    private final FeedbackStore store = new FeedbackStore(repository, exercises, reports, mock(InterviewerStore.class), identity, secrets, mock(StorageCapacityStore.class), Clock.fixed(now, ZoneOffset.UTC));
    @BeforeEach
    void setup() {
        var user = new SessionAuthorizationDTO("owner", "USER", false, 1, now.plusSeconds(3600));
        var admin = new SessionAuthorizationDTO("admin", "ADMIN", false, 1, now.plusSeconds(3600));
        when(identity.authorizeMutation("session")).thenReturn(user);
        when(identity.authorizePrivateDelivery("session")).thenReturn(user);
        when(identity.authorizeMutation("admin")).thenReturn(admin);
        when(identity.authorizePrivateDelivery("admin")).thenReturn(admin);
        when(exercises.find("exercise")).thenReturn(new ExerciseEntity("exercise", "owner", "problem", "instance", "prepare", "ENDED", 7L, "[]", 1, now, now, null, null, null, null));
        when(repository.live("exercise", now)).thenReturn(true);
        when(reports.view("session", "exercise")).thenReturn(new ExerciseReportResponse("exercise", TARGET, 2, "FAILED", "7", "UNAVAILABLE", null, true, "platform-version", 3, "INVALID_RESPONSE", null, List.of(), List.of(), null));
        when(reports.encode(any())).thenAnswer(call -> new ObjectMapper().writeValueAsString(call.getArgument(0, Object.class)));
        when(secrets.fingerprint(anyString())).thenAnswer(call -> HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(call.getArgument(0, String.class).getBytes(StandardCharsets.UTF_8))));
    }
    private FeedbackRequest input(List<String> evidence) { return new FeedbackRequest("REPORT", TARGET, 2, "请检查评价", evidence, null); }
    @Test
    void defaultPreviewContainsOnlyTargetAndExplicitDescriptionNotPrivateSources() {
        var preview = store.preview("session", "exercise", input(List.of()));
        assertThat(preview.snapshot()).contains("\"evidence\":[]", "platform-version", "UNAVAILABLE", "请检查评价");
        verify(reports, never()).evidence(anyString(), anyString(), anyLong(), anyString());
        assertThat(preview.fingerprint()).hasSize(64).isEqualTo(store.preview("session", "exercise", input(List.of())).fingerprint());
    }
    @Test
    void foreignEvidenceAndStaleGenerationAreRejectedBeforePersistence() {
        when(reports.evidence("session", "exercise", 2, "foreign-source")).thenThrow(new BusinessException(WorkspaceErrorCode.RESOURCE_NOT_FOUND));
        assertThatThrownBy(() -> store.preview("session", "exercise", input(List.of("foreign-source")))).hasMessage("RESOURCE_NOT_FOUND");
        assertThatThrownBy(() -> store.preview("session", "exercise", new FeedbackRequest("REPORT", TARGET, 1, "描述", List.of(), null))).hasMessage("REVISION_CONFLICT");
        verify(repository, never()).insert(any());
    }
    @Test
    void ownerMismatchDeletedSourceAndAdminCannotExpandIntoPrivateExercise() {
        when(identity.authorizeMutation("other")).thenReturn(new SessionAuthorizationDTO("other", "USER", false, 1, now.plusSeconds(3600)));
        assertThatThrownBy(() -> store.preview("other", "exercise", input(List.of()))).hasMessage("RESOURCE_NOT_FOUND");
        assertThatThrownBy(() -> store.preview("admin", "exercise", input(List.of()))).hasMessage("FORBIDDEN");
        when(repository.live("exercise", now)).thenReturn(false);
        assertThatThrownBy(() -> store.preview("session", "exercise", input(List.of()))).hasMessage("RESOURCE_NOT_FOUND");
    }
    @Test
    void emptyOrOversizedInputAndUnpreviewedSubmitAreRejected() {
        assertThatThrownBy(() -> store.preview("session", "exercise", new FeedbackRequest(null, TARGET, 2, "描述", List.of(), null))).hasMessage("INVALID_REQUEST");
        for (String text : List.of(" ", "汉".repeat(1400), "bad\0text")) {
            assertThatThrownBy(() -> store.preview("session", "exercise", new FeedbackRequest("REPORT", TARGET, 2, text, List.of(), null))).hasMessage("INVALID_REQUEST");
        }
        assertThatThrownBy(() -> store.submit("session", "exercise", "key", input(List.of()), null)).hasMessage("REVISION_CONFLICT");
        verify(repository, never()).insert(any());
    }
    @Test
    void adminReplyIsOneShotIdempotentAndDeletionFenced() {
        var pending = new FeedbackEntity(TARGET, "exercise", "owner", "submit", "digest", "authorized snapshot", "PENDING", null, null, now, null);
        var handled = new FeedbackEntity(TARGET, "exercise", "owner", "submit", "digest", "authorized snapshot", "HANDLED", "已核对", "reply-key", now, now);
        when(repository.find(TARGET)).thenReturn(pending, pending, handled);
        when(repository.reply(TARGET, "reply-key", "已核对", now)).thenReturn(1);
        assertThat(store.reply("admin", TARGET, "reply-key", "已核对").state()).isEqualTo("HANDLED");
        assertThat(store.reply("admin", TARGET, "reply-key", "已核对").reply()).isEqualTo("已核对");
        assertThatThrownBy(() -> store.reply("admin", TARGET, "another", "更改回复")).hasMessage("IDEMPOTENCY_CONFLICT");
        when(repository.live("exercise", now)).thenReturn(false);
        assertThatThrownBy(() -> store.reply("admin", TARGET, "reply-key", "已核对")).hasMessage("RESOURCE_NOT_FOUND");
        assertThatThrownBy(() -> store.reply("session", TARGET, "reply-key", "伪造管理员")).hasMessage("FORBIDDEN");
    }
    @Test
    void administrativeListIsNotAvailableToUsersAndUserListIsOwnerScoped() {
        when(repository.list("owner", null, now)).thenReturn(List.of());
        assertThat(store.list("session", false, null)).isEmpty();
        assertThatThrownBy(() -> store.list("session", true, null)).hasMessage("FORBIDDEN");
        verify(repository, never()).list(null, null, now);
    }
}
