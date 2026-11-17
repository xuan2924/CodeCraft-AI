package ai.codecraft.modules.workspace.config;

import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.identity.config.IdentitySettings;
import ai.codecraft.modules.identity.security.IdentityHttp;
import ai.codecraft.modules.workspace.service.TerminalStore;
import ai.codecraft.modules.workspace.stream.TerminalGateway;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

@Configuration
@EnableWebSocket
public class TerminalWebSocketConfiguration implements WebSocketConfigurer {
    private final TerminalGateway gateway;
    private final TerminalStore store;
    private final IdentitySettings identity;
    public TerminalWebSocketConfiguration(TerminalGateway gateway,TerminalStore store,IdentitySettings identity){this.gateway=gateway;this.store=store;this.identity=identity;}

    @Override public void registerWebSocketHandlers(WebSocketHandlerRegistry registry){
        registry.addHandler(gateway,"/api/exercises/*/terminal/ws").setAllowedOrigins(identity.publicOrigin()).addInterceptors(new TicketHandshake(store,identity.publicOrigin()));
    }
    @Bean public ServletServerContainerFactoryBean terminalWebSocketContainer(){
        var container=new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(1024);container.setMaxBinaryMessageBufferSize(16384);
        container.setAsyncSendTimeout(5000L);container.setMaxSessionIdleTimeout(60000L);
        return container;
    }

    /** The secret travels in a non-echoed subprotocol, never in a URL/access log. */
    public static final class TicketHandshake implements HandshakeInterceptor {
        private final TerminalStore store;
        private final String origin;
        public TicketHandshake(TerminalStore store,String origin){this.store=store;this.origin=origin;}
        @Override public boolean beforeHandshake(ServerHttpRequest request,ServerHttpResponse response,WebSocketHandler handler,Map<String,Object> attributes){
            if(!(request instanceof ServletServerHttpRequest servlet)||!origin.equals(request.getHeaders().getOrigin())){response.setStatusCode(HttpStatus.FORBIDDEN);return false;}
            try {
                String path=request.getURI().getPath();
                if(!path.matches("/api/exercises/[0-9a-fA-F-]{36}/terminal/ws"))throw new IllegalArgumentException("Invalid terminal path");
                String exercise=UUID.fromString(path.split("/")[3]).toString();
                var protocols=Arrays.stream(String.join(",",request.getHeaders().getOrEmpty("Sec-WebSocket-Protocol")).split(",")).map(String::trim).toList();
                var tickets=protocols.stream().filter(value->value.startsWith("ticket.")).toList();
                if(!protocols.contains("codecraft.terminal.v1")||tickets.size()!=1||request.getURI().getRawQuery()!=null)throw new IllegalArgumentException("Invalid ticket protocol");
                String session=IdentityHttp.sessionHash(servlet.getServletRequest());
                var task=store.consume(session,exercise,tickets.getFirst().substring(7));
                attributes.put("terminal.task",task);attributes.put("terminal.session",session);
                return true;
            }catch(BusinessException|IllegalArgumentException failure){response.setStatusCode(HttpStatus.FORBIDDEN);return false;}
        }
        @Override public void afterHandshake(ServerHttpRequest request,ServerHttpResponse response,WebSocketHandler handler,Exception exception){ }
    }
}
