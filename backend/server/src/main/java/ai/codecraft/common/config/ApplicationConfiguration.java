package ai.codecraft.common.config;

import com.fasterxml.jackson.core.StreamReadConstraints;
import java.time.Clock;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ApplicationConfiguration {
    @Bean
    Clock applicationClock() { return Clock.systemUTC(); }

    @Bean
    Jackson2ObjectMapperBuilderCustomizer boundedJson() {
        return builder -> builder.postConfigurer(mapper -> mapper.getFactory().setStreamReadConstraints(
            StreamReadConstraints.builder().maxStringLength(4096).maxNestingDepth(16).maxDocumentLength(16384).build()));
    }

}
