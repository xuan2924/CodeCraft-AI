package ai.codecraft.modules.workspace.config;

import ai.codecraft.modules.workspace.model.dto.FileBatchRequest;
import ai.codecraft.common.web.RequestBodyAdmission;
import ai.codecraft.common.web.RequestBodyPolicy;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
public class WorkspaceJsonConfiguration implements WebMvcConfigurer {
    public static final long MAX_BATCH_JSON_BYTES = RequestBodyAdmission.LARGE_JSON_BYTES;
    private final ObjectMapper workspace;

    public WorkspaceJsonConfiguration(@Qualifier("workspaceJson") ObjectMapper workspace) { this.workspace = workspace; }

    @Bean("workspaceJson")
    static ObjectMapper workspaceJson(@Qualifier("applicationJson") ObjectMapper primary) {
        var copy = primary.copy();
        copy.getFactory().setStreamReadConstraints(StreamReadConstraints.builder()
            .maxStringLength(4 * 1024 * 1024).maxDocumentLength(MAX_BATCH_JSON_BYTES).maxNestingDepth(16).build());
        return copy;
    }

    @Bean
    static RequestBodyPolicy workspaceRequestBodies() {
        var batches = java.util.regex.Pattern.compile("/api/exercises/[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}/file-batches");
        return request -> request.getMethod().equals("POST") && batches.matcher(request.getServletPath()).matches() ? MAX_BATCH_JSON_BYTES : 0;
    }

    @Override
    public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
        for (var converter : converters) {
            if (converter instanceof MappingJackson2HttpMessageConverter jackson) {
                jackson.registerObjectMappersForType(FileBatchRequest.class, registrations -> {
                    registrations.put(MediaType.APPLICATION_JSON, workspace);
                    registrations.put(MediaType.valueOf("application/*+json"), workspace);
                });
            }
        }
    }
}
