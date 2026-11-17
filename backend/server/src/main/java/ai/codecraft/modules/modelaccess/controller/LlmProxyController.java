package ai.codecraft.modules.modelaccess.controller;

import ai.codecraft.modules.modelaccess.security.ProxyTokens;
import ai.codecraft.modules.modelaccess.service.LlmProxyService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class LlmProxyController {
    private final ProxyTokens tokens;
    private final LlmProxyService proxy;
    private final ObjectMapper json;
    public LlmProxyController(ProxyTokens tokens, LlmProxyService proxy, ObjectMapper json) { this.tokens = tokens; this.proxy = proxy; this.json = json; }
    @PostMapping("/internal/llm-grants")
    public void issue(HttpServletRequest request, HttpServletResponse response) throws IOException {
        byte[] bytes = request.getInputStream().readNBytes(4097);
        if (bytes.length > 4096) { response.setStatus(413); return; }
        try {
            var node = json.readTree(bytes);
            String task = UUID.fromString(node.path("task").asText()).toString();
            String exercise = UUID.fromString(node.path("exercise").asText()).toString();
            String token = tokens.issue(task, exercise, node.path("epoch").asLong(-1));
            response.setContentType("application/json");
            json.writeValue(response.getOutputStream(), Map.of("token", token));
        } catch (RuntimeException failure) { response.setStatus(403); }
    }
    @PostMapping("/internal/llm-proxy/**")
    public void forward(HttpServletRequest request, HttpServletResponse response) throws IOException {
        byte[] bytes = request.getInputStream().readNBytes(1024 * 1024 + 1);
        if (bytes.length > 1024 * 1024) { response.setStatus(413); return; }
        if (request.getQueryString() != null) { response.setStatus(400); return; }
        try {
            proxy.forward((String) request.getAttribute("codecraft.proxy.token"), request.getServletPath(), bytes, request.getHeader("anthropic-beta"), new LlmProxyService.Output() {
                @Override public void setStatus(int status) { response.setStatus(status); }
                @Override public void setContentType(String type) { response.setContentType(type); }
                @Override public boolean isCommitted() { return response.isCommitted(); }
                @Override public java.io.OutputStream getOutputStream() throws IOException { return response.getOutputStream(); }
                @Override public void flushBuffer() throws IOException { response.flushBuffer(); }
            });
        }
        catch (RuntimeException failure) { if (!response.isCommitted()) response.setStatus(403); }
    }
}
