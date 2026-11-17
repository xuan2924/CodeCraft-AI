package ai.codecraft.modules.workspace.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.codecraft.modules.workspace.gateway.SandboxWorkspaceGateway;
import ai.codecraft.modules.workspace.model.entity.CommandTaskEntity;
import ai.codecraft.modules.workspace.stream.TerminalConnections;
import ai.codecraft.modules.workspace.stream.TerminalGateway;
import ai.codecraft.workspace.v1.CommandSnapshot;
import ai.codecraft.workspace.v1.TerminalClientFrame;
import ai.codecraft.workspace.v1.TerminalServerFrame;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.google.protobuf.ByteString;
import io.grpc.Status;
import io.grpc.stub.ClientCallStreamObserver;
import io.grpc.stub.ClientResponseObserver;
import io.grpc.stub.StreamObserver;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

class TerminalGatewayTest {
    @Test @SuppressWarnings("unchecked")
    void virtualPumpsMapRawFramesAndAbnormalGrpcCloseStopsTheOwnedTask() throws Exception {
        var store=mock(TerminalStore.class);var commands=mock(CommandTaskStore.class);var sandbox=mock(SandboxWorkspaceGateway.class);
        var task=mock(CommandTaskEntity.class);
        when(task.id()).thenReturn("task");when(task.exerciseId()).thenReturn("exercise");when(task.workspaceInstanceId()).thenReturn("workspace");
        when(task.executionInstanceId()).thenReturn("execution");when(task.gateEpoch()).thenReturn(2L);when(task.dispatched()).thenReturn(true);
        when(store.authorize("session","exercise","task")).thenReturn(task);when(commands.read("session","exercise","task")).thenReturn(task);
        when(sandbox.command(task)).thenReturn(CommandSnapshot.getDefaultInstance());
        var received=new ArrayBlockingQueue<TerminalClientFrame>(8);
        StreamObserver<TerminalClientFrame> requests=mock(StreamObserver.class);
        doAnswer(call->{received.add(call.getArgument(0));return null;}).when(requests).onNext(any());
        ClientCallStreamObserver<TerminalClientFrame> control=mock(ClientCallStreamObserver.class);when(control.isReady()).thenReturn(true);
        var observer=new AtomicReference<ClientResponseObserver<TerminalClientFrame,TerminalServerFrame>>();
        when(sandbox.terminal(any())).thenAnswer(call->{ClientResponseObserver<TerminalClientFrame,TerminalServerFrame> sink=call.getArgument(0);observer.set(sink);sink.beforeStart(control);sink.onNext(TerminalServerFrame.newBuilder().setOpened(true).build());return requests;});
        var socket=mock(WebSocketSession.class);when(socket.getId()).thenReturn("socket");when(socket.isOpen()).thenReturn(true);
        when(socket.getAttributes()).thenReturn(Map.of("terminal.task",task,"terminal.session","session"));
        var sent=new ArrayBlockingQueue<WebSocketMessage<?>>(8);
        doAnswer(call->{sent.add(call.getArgument(0));return null;}).when(socket).sendMessage(any());
        var stopped=new CountDownLatch(1);doAnswer(call->{stopped.countDown();return null;}).when(store).close("task");
        var gateway=new TerminalGateway(store,commands,sandbox,new TerminalConnections(),JsonMapper.builder().build());
        gateway.afterConnectionEstablished(socket);
        assertThat(received.poll(5,TimeUnit.SECONDS).getOpen().getGateEpoch()).isEqualTo(2);
        assertThat(sent.poll(5,TimeUnit.SECONDS)).isInstanceOf(TextMessage.class);
        gateway.handleMessage(socket,new BinaryMessage(new byte[]{3,4,65}));
        assertThat(received.poll(5,TimeUnit.SECONDS).getStdin().toByteArray()).containsExactly((byte)3,(byte)4,(byte)65);
        gateway.handleMessage(socket,new TextMessage("{\"type\":\"resize\",\"cols\":100,\"rows\":40}"));
        assertThat(received.poll(5,TimeUnit.SECONDS).getResize().getRows()).isEqualTo(40);
        observer.get().onNext(TerminalServerFrame.newBuilder().setOutput(ByteString.copyFromUtf8("pty-output")).build());
        assertThat(sent.poll(5,TimeUnit.SECONDS)).isInstanceOf(BinaryMessage.class);
        observer.get().onError(Status.UNAVAILABLE.asRuntimeException());
        assertThat(stopped.await(5,TimeUnit.SECONDS)).isTrue();
        verify(socket).close(CloseStatus.SERVER_ERROR);verify(control).cancel(anyString(),isNull());verify(store).close("task");
    }
}
