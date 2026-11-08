package ai.codecraft.status;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import java.net.InetAddress;
import java.net.UnknownHostException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class StatusConfiguration {
    @Bean(destroyMethod = "shutdownNow")
    ManagedChannel sandboxChannel(@Value("${tracer.sandbox-port}") int port) {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("SANDBOX_PORT must be between 1 and 65535");
        }
        return ManagedChannelBuilder.forAddress("127.0.0.1", port).usePlaintext()
            .maxInboundMessageSize(1024).build();
    }

    @Bean
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> loopbackOnly() throws UnknownHostException {
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        return factory -> factory.setAddress(loopback);
    }
}
