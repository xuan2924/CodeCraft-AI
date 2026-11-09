package ai.codecraft.modules.status.config;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class StatusConfiguration {
    @Bean(destroyMethod = "shutdownNow")
    ManagedChannel sandboxChannel(StatusProperties properties) {
        return ManagedChannelBuilder.forAddress(properties.sandboxHost(), properties.sandboxPort()).usePlaintext()
            .maxInboundMessageSize(1024).build();
    }

}
