package ai.codecraft.modules.workspace.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ai.codecraft.modules.identity.security.IdentityHttp;
import ai.codecraft.modules.identity.service.IdentitySecrets;
import ai.codecraft.modules.workspace.config.TerminalWebSocketConfiguration.TicketHandshake;
import ai.codecraft.modules.workspace.model.entity.CommandTaskEntity;
import ai.codecraft.modules.workspace.stream.TerminalConnections;
import ai.codecraft.modules.workspace.stream.TerminalGateway;
import com.fasterxml.jackson.databind.json.JsonMapper;
import jakarta.servlet.http.Cookie;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.socket.WebSocketHandler;

class TerminalTransportTest {
    @Test void handshakeRequiresExactOriginCookieAndNonEchoedSingleUseProtocol(){
        String exercise="11111111-1111-4111-8111-111111111111",token="t".repeat(43),cookie="c".repeat(43);
        var store=mock(TerminalStore.class);var handshake=new TicketHandshake(store,"http://localhost:5173");
        var request=new MockHttpServletRequest("GET","/api/exercises/"+exercise+"/terminal/ws");
        request.setCookies(new Cookie(IdentityHttp.SESSION_COOKIE,cookie));
        request.addHeader("Sec-WebSocket-Protocol","codecraft.terminal.v1,ticket."+token);
        var response=new MockHttpServletResponse();var attrs=new HashMap<String,Object>();
        assertThat(handshake.beforeHandshake(new ServletServerHttpRequest(request),new ServletServerHttpResponse(response),mock(WebSocketHandler.class),attrs)).isFalse();
        verifyNoInteractions(store);
        request.addHeader("Origin","https://evil.example");
        assertThat(handshake.beforeHandshake(new ServletServerHttpRequest(request),new ServletServerHttpResponse(response),mock(WebSocketHandler.class),attrs)).isFalse();
        verifyNoInteractions(store);
        request.removeHeader("Origin");request.addHeader("Origin","http://localhost:5173");
        var task=mock(CommandTaskEntity.class);when(store.consume(IdentitySecrets.digest(cookie),exercise,token)).thenReturn(task);
        assertThat(handshake.beforeHandshake(new ServletServerHttpRequest(request),new ServletServerHttpResponse(response),mock(WebSocketHandler.class),attrs)).isTrue();
        verify(store).consume(IdentitySecrets.digest(cookie),exercise,token);
        assertThat(attrs).containsEntry("terminal.task",task).doesNotContainValue(token);
        request.setQueryString("ticket="+token);
        assertThat(handshake.beforeHandshake(new ServletServerHttpRequest(request),new ServletServerHttpResponse(response),mock(WebSocketHandler.class),attrs)).isFalse();
    }
    @Test void frameMappingRejectsInvalidDimensionsAndOnlyAcceptsExplicitSync(){
        var json=JsonMapper.builder().build();
        try {
            assertThat(TerminalGateway.control(json,"{\"type\":\"resize\",\"cols\":100,\"rows\":40}").getResize().getCols()).isEqualTo(100);
            assertThat(TerminalGateway.control(json,"{\"type\":\"sync\"}").hasSync()).isTrue();
            assertThat(TerminalGateway.control(json,"{\"type\":\"heartbeat\"}").hasHeartbeat()).isTrue();
        }catch(Exception failure){throw new AssertionError(failure);}
        for(String payload:java.util.List.of("{}","{\"type\":\"resize\",\"cols\":0,\"rows\":40}","{\"type\":\"resize\",\"cols\":1.5,\"rows\":40}","{\"type\":\"open\"}")){
            assertThatThrownBy(()->TerminalGateway.control(json,payload)).isInstanceOf(IllegalArgumentException.class);
        }
    }
    @Test void pauseAndFinalizationProactivelyCloseOwnedTransports(){
        var connections=new TerminalConnections();var stopped=new AtomicInteger();
        connections.register("pause-task","exercise",stopped::incrementAndGet);
        var pause=mock(ExercisePauseStore.class);var workspace=mock(WorkspaceCoordinationStore.class);
        var exercise=mock(ai.codecraft.modules.workspace.model.entity.ExerciseEntity.class);
        when(exercise.state()).thenReturn("PAUSING");when(workspace.exercise("exercise")).thenReturn(exercise);
        var pending=mock(ai.codecraft.modules.workspace.model.entity.ExercisePauseEntity.class);
        when(pending.pauseOperationId()).thenReturn("pause");when(pause.find("exercise")).thenReturn(pending);
        new ExercisePauseCoordinator(pause,workspace,mock(WorkspaceWriteCoordinator.class),mock(TestTaskCoordinator.class),
            mock(CommandTaskStore.class),mock(CommandTaskCoordinator.class),mock(FinalizationStore.class),connections).drive("exercise");
        assertThat(stopped.get()).isEqualTo(1);connections.remove("pause-task");
        connections.register("final-task","exercise",stopped::incrementAndGet);
        var finals=mock(FinalizationStore.class);var value=mock(ai.codecraft.modules.workspace.model.entity.FinalizationEntity.class);
        when(value.exerciseId()).thenReturn("exercise");when(value.frozenEpoch()).thenReturn(4L);when(finals.find("exercise")).thenReturn(value);
        new FinalizationCoordinator(finals,workspace,mock(WorkspaceWriteCoordinator.class),mock(TestTaskCoordinator.class),
            mock(ai.codecraft.modules.workspace.gateway.SandboxWorkspaceGateway.class),mock(StorageCapacityService.class),connections).drive("exercise");
        assertThat(stopped.get()).isEqualTo(2);
    }

    @Test void lifecycleCloseIsExerciseScopedAndCapacityBounded(){
        var connections=new TerminalConnections();var stopped=new AtomicInteger();
        assertThat(connections.register("task","exercise",stopped::incrementAndGet)).isTrue();
        assertThat(connections.register("task","exercise",stopped::incrementAndGet)).isFalse();
        connections.closeExercise("other");assertThat(stopped.get()).isZero();
        connections.closeExercise("exercise");assertThat(stopped.get()).isEqualTo(1);
        connections.remove("task");
        for(int i=0;i<64;i++)assertThat(connections.register("task"+i,"other",()->{})).isTrue();
        assertThat(connections.register("overflow","other",()->{})).isFalse();
    }
}
