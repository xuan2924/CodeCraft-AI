package ai.codecraft.modules.workspace.config;

import ai.codecraft.modules.workspace.service.ProblemService;
import java.nio.file.Path;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class ProblemPublicationBootstrap implements ApplicationRunner {
    private final WorkspaceProperties properties;
    private final ProblemService problems;

    public ProblemPublicationBootstrap(WorkspaceProperties properties, ProblemService problems) {
        this.properties = properties;
        this.problems = problems;
    }

    @Override
    public void run(ApplicationArguments arguments) throws Exception {
        if (!properties.publicationFile().isBlank()) {
            problems.publish(Path.of(properties.publicationFile()));
        }
    }
}
