package ai.codecraft.common.config;

import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.context.annotation.Primary;
import java.time.Clock;
import ai.codecraft.common.web.RequestBodyAdmission;
import ai.codecraft.common.web.RequestBodyPolicy;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ApplicationConfiguration {
    @Bean
    @Primary
    ObjectMapper applicationJson(Jackson2ObjectMapperBuilder builder) { return builder.build(); }

    @Bean
    RequestBodyAdmission requestBodyAdmission(ObjectProvider<RequestBodyPolicy> policies) {
        return new RequestBodyAdmission(policies.orderedStream().toList());
    }

    @Bean
    Clock applicationClock() { return Clock.systemUTC(); }

    @Bean
    Jackson2ObjectMapperBuilderCustomizer boundedJson() {
        return builder -> builder.postConfigurer(mapper -> mapper.getFactory().setStreamReadConstraints(
            StreamReadConstraints.builder().maxStringLength(4096).maxNestingDepth(16).maxDocumentLength(RequestBodyAdmission.SMALL_JSON_BYTES).build()));
    }

}
