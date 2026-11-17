package ai.codecraft.modules.workspace.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.identity.model.dto.SessionAuthorizationDTO;
import ai.codecraft.modules.identity.service.IdentitySecrets;
import ai.codecraft.modules.identity.service.IdentityTransactionService;
import ai.codecraft.modules.workspace.model.entity.CommandTaskEntity;
import ai.codecraft.modules.workspace.model.entity.ExerciseEntity;
import ai.codecraft.modules.workspace.model.entity.WorkspaceControlEntity;
import ai.codecraft.modules.workspace.repository.ExerciseRepository;
import ai.codecraft.modules.workspace.repository.TerminalRepository;
import ai.codecraft.modules.workspace.repository.WorkspaceWriteRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class TerminalStoreTest {
    private final Instant now=Instant.parse("2026-01-01T00:00:00Z");
    private final CommandTaskStore commands=mock(CommandTaskStore.class);
    private final TerminalRepository tickets=mock(TerminalRepository.class);
    private final IdentityTransactionService identity=mock(IdentityTransactionService.class);
    private final WorkspaceWriteRepository writes=mock(WorkspaceWriteRepository.class);
    private final ExerciseRepository exercises=mock(ExerciseRepository.class);
    private final TerminalStore store=new TerminalStore(commands,tickets,identity,mock(IdentitySecrets.class),writes,exercises,Clock.fixed(now,ZoneOffset.UTC));

    private CommandTaskEntity active(){
        when(identity.authorizeMutation("session")).thenReturn(new SessionAuthorizationDTO("user","USER",false,1,now.plusSeconds(60)));
        var task=mock(CommandTaskEntity.class);
        when(task.userId()).thenReturn("user");when(task.sessionHash()).thenReturn("session");when(task.actorAuthVersion()).thenReturn(1L);
        when(task.gateEpoch()).thenReturn(2L);when(task.notAfter()).thenReturn(now.plusSeconds(60));when(task.revision()).thenReturn(1L);
        when(commands.read("session","exercise","task")).thenReturn(task);when(commands.terminal("task")).thenReturn(true);
        when(writes.control("exercise")).thenReturn(new WorkspaceControlEntity("exercise",1,2L,"OPEN","hash",null,null));
        var exercise=mock(ExerciseEntity.class);when(exercise.state()).thenReturn("ACTIVE");when(exercise.revision()).thenReturn(1L);when(exercises.find("exercise")).thenReturn(exercise);
        return task;
    }
    @Test void oneTimeTicketCannotReplayAndIsBoundToOwnerSessionExercise(){
        active();String token="t".repeat(43);
        when(tickets.lookup(IdentitySecrets.digest(token),"exercise","session","user")).thenReturn("task");
        when(tickets.consume("task",now)).thenReturn(1,0);
        assertThat(store.consume("session","exercise",token)).isNotNull();
        assertThatThrownBy(()->store.consume("session","exercise",token)).isInstanceOf(BusinessException.class).hasMessage("TERMINAL_TICKET_INVALID");
        assertThatThrownBy(()->store.consume("session","other",token)).isInstanceOf(BusinessException.class).hasMessage("TERMINAL_TICKET_INVALID");
        assertThatThrownBy(()->store.consume("session","exercise","invalid")).isInstanceOf(BusinessException.class).hasMessage("TERMINAL_TICKET_INVALID");
    }
    @Test void proxyAndTerminalAuthorityRequireActiveExercise(){
        active();
        var exercise=exercises.find("exercise");
        for(String state:java.util.List.of("PAUSING","PAUSED","RESUMING","CLOSING","ENDED")){
            when(exercise.state()).thenReturn(state);
            assertThatThrownBy(()->store.authorize("session","exercise","task")).hasMessage("TERMINAL_AUTHORITY_ENDED");
        }
    }
    @Test void closedGateChangedEpochRevokedIdentityAndDeadlineRejectInput(){
        var task=active();
        for(var control:java.util.List.of(new WorkspaceControlEntity("exercise",1,2L,"CLOSED","hash",null,null),new WorkspaceControlEntity("exercise",1,4L,"OPEN","hash",null,null))){
            when(writes.control("exercise")).thenReturn(control);
            assertThatThrownBy(()->store.authorize("session","exercise","task")).hasMessage("TERMINAL_AUTHORITY_ENDED");
        }
        active();when(task.sessionHash()).thenReturn("other-session");
        when(commands.read("session","exercise","task")).thenReturn(task);
        assertThatThrownBy(()->store.authorize("session","exercise","task")).hasMessage("TERMINAL_AUTHORITY_ENDED");
        task=active();when(task.notAfter()).thenReturn(now);
        assertThatThrownBy(()->store.authorize("session","exercise","task")).hasMessage("TERMINAL_AUTHORITY_ENDED");
        active();when(identity.authorizeMutation(any())).thenReturn(new SessionAuthorizationDTO("user","USER",false,2,now.plusSeconds(60)));
        assertThatThrownBy(()->store.authorize("session","exercise","task")).hasMessage("TERMINAL_AUTHORITY_ENDED");
    }
}
