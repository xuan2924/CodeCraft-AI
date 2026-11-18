package ai.codecraft.modules.workspace.stream;

import ai.codecraft.modules.workspace.gateway.SandboxWorkspaceGateway;
import ai.codecraft.modules.workspace.model.entity.CommandTaskEntity;
import ai.codecraft.modules.workspace.service.CommandTaskStore;
import ai.codecraft.modules.workspace.service.TerminalStore;
import ai.codecraft.workspace.v1.QueryTaskRequest;
import ai.codecraft.workspace.v1.TerminalClientFrame;
import ai.codecraft.workspace.v1.TerminalServerFrame;
import ai.codecraft.workspace.v1.TerminalOpen;
import ai.codecraft.workspace.v1.TerminalSize;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.ByteString;
import io.grpc.stub.ClientCallStreamObserver;
import io.grpc.stub.ClientResponseObserver;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.SubProtocolCapable;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

/** One bounded virtual-thread input pump and output pump per authenticated PTY. */
@Component
public class TerminalGateway extends AbstractWebSocketHandler implements SubProtocolCapable {
    private static final Logger LOG=LoggerFactory.getLogger(TerminalGateway.class);
    private static final CloseStatus BUSY = new CloseStatus(1013, "terminal backpressure");
    private final TerminalStore store;
    private final CommandTaskStore commands;
    private final SandboxWorkspaceGateway sandbox;
    private final TerminalConnections connections;
    private final ObjectMapper json;
    private final Map<String,Bridge> bridges=new ConcurrentHashMap<>();

    public TerminalGateway(TerminalStore store, CommandTaskStore commands, SandboxWorkspaceGateway sandbox,
        TerminalConnections connections, @Qualifier("workspaceJson") ObjectMapper json) {
        this.store=store;this.commands=commands;this.sandbox=sandbox;this.connections=connections;this.json=json;
    }
    @Override public List<String> getSubProtocols() { return List.of("codecraft.terminal.v1"); }

    @Override public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        var task=(CommandTaskEntity)session.getAttributes().get("terminal.task");
        String owner=(String)session.getAttributes().get("terminal.session");
        if(task==null||owner==null){session.close(CloseStatus.POLICY_VIOLATION);return;}
        session.setBinaryMessageSizeLimit(16384);session.setTextMessageSizeLimit(1024);
        var bridge=new Bridge(new ConcurrentWebSocketSessionDecorator(session,5000,128*1024),task,owner);
        try {
            if(!connections.register(task.id(),task.exerciseId(),()->bridge.close(CloseStatus.POLICY_VIOLATION))){session.close(BUSY);return;}
        } catch (RuntimeException unavailable) { session.close(BUSY); return; }
        bridges.put(session.getId(),bridge);
        Thread.startVirtualThread(bridge::output);
        Thread.startVirtualThread(bridge::input);
    }

    @Override protected void handleBinaryMessage(WebSocketSession session,BinaryMessage message) {
        Bridge bridge=bridges.get(session.getId());if(bridge==null)return;
        if(!message.isLast()||message.getPayloadLength()==0||message.getPayloadLength()>16384){bridge.close(CloseStatus.TOO_BIG_TO_PROCESS);return;}
        bridge.enqueue(TerminalClientFrame.newBuilder().setStdin(ByteString.copyFrom(message.getPayload())).build());
    }
    @Override protected void handleTextMessage(WebSocketSession session,TextMessage message) {
        Bridge bridge=bridges.get(session.getId());if(bridge==null)return;
        try {bridge.enqueue(control(json,message.getPayload()));}
        catch(RuntimeException|IOException failure){bridge.close(CloseStatus.BAD_DATA);}
    }
    public static TerminalClientFrame control(ObjectMapper json,String payload) throws IOException {
        if(payload.length()>1024)throw new IllegalArgumentException("Terminal control frame too large");
        var node=json.readTree(payload);
        String type=node.path("type").asText();
        return switch(type){
            case "heartbeat" -> TerminalClientFrame.newBuilder().setHeartbeat(true).build();
            case "sync" -> TerminalClientFrame.newBuilder().setSync(true).build();
            case "resize" -> {
                if(!node.path("cols").isIntegralNumber()||!node.path("rows").isIntegralNumber()||!node.path("cols").canConvertToInt()||!node.path("rows").canConvertToInt())throw new IllegalArgumentException("Invalid PTY size");
                int cols=node.path("cols").asInt(),rows=node.path("rows").asInt();
                if(cols<1||cols>500||rows<1||rows>300)throw new IllegalArgumentException("Invalid PTY size");
                yield TerminalClientFrame.newBuilder().setResize(TerminalSize.newBuilder().setCols(cols).setRows(rows)).build();
            }
            default -> throw new IllegalArgumentException("Unsupported terminal control frame");
        };
    }
    @Override public void afterConnectionClosed(WebSocketSession session,CloseStatus status) {Bridge bridge=bridges.get(session.getId());if(bridge!=null)bridge.close(status);}
    @Override public void handleTransportError(WebSocketSession session,Throwable exception) {Bridge bridge=bridges.get(session.getId());if(bridge!=null)bridge.close(CloseStatus.SERVER_ERROR);}

    @Scheduled(fixedDelay=1000)
    public void expireUnattachedTickets() {
        for(String task:store.abandoned())try{store.close(task);}catch(RuntimeException failure){LOG.warn("Terminal expiry remains unresolved: task={} type={}",task,failure.getClass().getSimpleName());}
    }

    private final class Bridge implements ClientResponseObserver<TerminalClientFrame,TerminalServerFrame> {
        final WebSocketSession socket;
        final CommandTaskEntity original;
        final String session;
        final ArrayBlockingQueue<TerminalClientFrame> incoming=new ArrayBlockingQueue<>(32);
        final ArrayBlockingQueue<TerminalServerFrame> outgoing=new ArrayBlockingQueue<>(32);
        final AtomicBoolean closed=new AtomicBoolean();
        volatile ClientCallStreamObserver<TerminalClientFrame> call;
        volatile boolean completed;
        volatile boolean failed;
        volatile boolean syncing;
        Bridge(WebSocketSession socket,CommandTaskEntity task,String session){this.socket=socket;this.original=task;this.session=session;}
        void enqueue(TerminalClientFrame frame){if(!closed.get()&&!incoming.offer(frame))close(BUSY);}
        @Override public void beforeStart(ClientCallStreamObserver<TerminalClientFrame> stream){call=stream;stream.disableAutoRequestWithInitial(1);}
        @Override public void onNext(TerminalServerFrame frame){
            if(closed.get())return;
            if(frame.getOutput().size()>16384||!outgoing.offer(frame))close(BUSY);
        }
        @Override public void onError(Throwable failure){failed=true;outgoing.offer(TerminalServerFrame.newBuilder().setError("TERMINAL_UNAVAILABLE").build());completed=true;}
        @Override public void onCompleted(){completed=true;}

        void input() {
            try {
                CommandTaskEntity task=null;
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
                while(!closed.get()&&System.nanoTime()<deadline){
                    task=store.authorize(session,original.exerciseId(),original.id());
                    if(task.dispatched()&&sandbox.command(task)!=null)break;
                    Thread.sleep(100);
                }
                if(closed.get())return;
                if(task==null||!task.dispatched()||sandbox.command(task)==null)throw new IllegalStateException("Terminal dispatch timeout");
                var stream=sandbox.terminal(task.workspaceInstanceId(),this);
                if(closed.get()){call.cancel("terminal closed",null);return;}
                var identity=QueryTaskRequest.newBuilder().setExerciseId(task.exerciseId()).setTaskId(task.id())
                    .setWorkspaceInstanceId(task.workspaceInstanceId()).setExecutionInstanceId(task.executionInstanceId());
                stream.onNext(TerminalClientFrame.newBuilder().setOpen(TerminalOpen.newBuilder().setIdentity(identity)
                    .setGateEpoch(task.gateEpoch()).setCols(80).setRows(24).setTerm("xterm-256color")).build());
                while(!closed.get()&&!completed){
                    var frame=incoming.poll(1,TimeUnit.SECONDS);
                    var current=commands.read(session,original.exerciseId(),original.id());
                    if(!syncing&&!current.quiescent())store.authorize(session,original.exerciseId(),original.id());
                    if(frame!=null&&!syncing&&!current.quiescent()){
                        if(!call.isReady())throw new IllegalStateException("Terminal input backpressure");
                        stream.onNext(frame);
                        if(frame.hasSync())syncing=true;
                    }
                }
            } catch(InterruptedException failure){Thread.currentThread().interrupt();close(CloseStatus.GOING_AWAY);}
            catch(RuntimeException failure){close(CloseStatus.POLICY_VIOLATION);}
        }
        void output(){
            try {
                while(!closed.get()){
                    var frame=outgoing.poll(500,TimeUnit.MILLISECONDS);
                    if(frame==null){if(completed){close(failed?CloseStatus.SERVER_ERROR:CloseStatus.NORMAL);return;}continue;}
                    if(frame.hasOutput())socket.sendMessage(new BinaryMessage(frame.getOutput().toByteArray()));
                    else if(frame.hasOpened())socket.sendMessage(new TextMessage(json.writeValueAsString(Map.of("type","opened","commandId",original.id()))));
                    else if(frame.hasExit()){
                        var snapshot=frame.getExit();
                        long revision=snapshot.hasMutation()?snapshot.getMutation().getRevision():snapshot.getExecution().getRevision();
                        socket.sendMessage(new TextMessage(json.writeValueAsString(Map.of("type","exit","revision",Long.toUnsignedString(revision),
                            "exitCode",snapshot.getExecution().getExitCode(),"hasExitCode",snapshot.getExecution().hasExitCode(),
                            "state",snapshot.getExecution().getState(),"synchronization",snapshot.getSynchronization(),"reason",snapshot.getSynchronizationReason()))));
                        close(CloseStatus.NORMAL);return;
                    } else if(frame.hasError()){socket.sendMessage(new TextMessage("{\"type\":\"error\",\"code\":\"TERMINAL_UNAVAILABLE\"}"));}
                    if(call!=null&&!completed)call.request(1);
                }
            }catch(InterruptedException failure){Thread.currentThread().interrupt();close(CloseStatus.GOING_AWAY);}
            catch(IOException|RuntimeException failure){close(CloseStatus.SERVER_ERROR);}
        }
        void close(CloseStatus status){
            if(!closed.compareAndSet(false,true))return;
            bridges.remove(socket.getId(),this);connections.remove(original.id());
            try{if(call!=null)call.cancel("terminal closed",null);}catch(RuntimeException failure){LOG.debug("Terminal RPC already unavailable: task={}",original.id());}
            try{socket.close(status);}catch(IOException|RuntimeException failure){LOG.debug("Terminal socket already unavailable: task={}",original.id());}
            Thread.startVirtualThread(()->{try{store.close(original.id());}catch(RuntimeException failure){LOG.warn("Terminal close remains unresolved: task={} type={}",original.id(),failure.getClass().getSimpleName());}});
        }
    }
}
