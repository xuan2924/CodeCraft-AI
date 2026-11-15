package ai.codecraft.modules.skills.config;

import ai.codecraft.common.web.RequestBodyPolicy;
import ai.codecraft.modules.skills.model.dto.SkillPackageRequest;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
public class SkillJsonConfiguration implements WebMvcConfigurer {
    public static final int MAX_REQUEST_BYTES = 2 * 1024 * 1024;
    private final ObjectMapper json;
    public SkillJsonConfiguration(@Qualifier("applicationJson") ObjectMapper primary) {
        json = primary.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        json.coercionConfigFor(LogicalType.Integer).setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
            .setCoercion(CoercionInputShape.String, CoercionAction.Fail);
        json.coercionConfigFor(LogicalType.Textual).setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
            .setCoercion(CoercionInputShape.Float, CoercionAction.Fail).setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
        json.getFactory().setStreamReadConstraints(StreamReadConstraints.builder().maxStringLength(MAX_REQUEST_BYTES)
            .maxDocumentLength(MAX_REQUEST_BYTES).maxNestingDepth(8).build());
    }
    @Bean
    static RequestBodyPolicy skillRequestBodies() {
        return request -> ("POST".equals(request.getMethod()) && "/api/skills".equals(request.getServletPath()))
            || ("PUT".equals(request.getMethod()) && request.getServletPath().matches("/api/skills/[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")) ? MAX_REQUEST_BYTES : 0;
    }
    @Override
    public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
        for (var converter : converters) {
            if (converter instanceof MappingJackson2HttpMessageConverter jackson) {
                jackson.registerObjectMappersForType(SkillPackageRequest.class, mappings -> {
                    mappings.put(MediaType.APPLICATION_JSON, json);
                    mappings.put(MediaType.valueOf("application/*+json"), json);
                });
            }
        }
    }
}
