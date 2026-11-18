package ai.codecraft.modules.workspace.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.codecraft.modules.identity.model.dto.SessionAuthorizationDTO;
import ai.codecraft.modules.identity.service.IdentityTransactionService;
import ai.codecraft.modules.workspace.model.dto.ProblemResponse;
import ai.codecraft.modules.workspace.model.entity.ExerciseEntity;
import ai.codecraft.modules.workspace.model.entity.ProblemVersionEntity;
import ai.codecraft.modules.workspace.repository.ExerciseRepository;
import ai.codecraft.modules.workspace.repository.ProblemRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ExerciseRequirementsServiceTest {
    @Test
    void copilotAndInterviewerOnlyReceiveTheStageAlreadyPublishedByJava() {
        var exercises = mock(ExerciseRepository.class);
        var problems = mock(ProblemRepository.class);
        var publications = mock(ProblemPublicationReader.class);
        var identity = mock(IdentityTransactionService.class);
        var service = new ExerciseRequirementsService(exercises, problems, publications, identity);
        var now = Instant.parse("2026-01-01T00:00:00Z");
        when(identity.authorizePrivateDelivery("session")).thenReturn(new SessionAuthorizationDTO("owner", "USER", false, 1, now.plusSeconds(60)));
        when(exercises.find("exercise")).thenReturn(new ExerciseEntity("exercise", "owner", "version", "instance", "prepare", "ACTIVE", 1L, "[]", 1, now, now, now.plusSeconds(60), null, null, null));
        var version = new ProblemVersionEntity("version", "cart", "1.1.0", "digest", "image", "linux/amd64", "secret raw artifact");
        when(problems.find("version")).thenReturn(version);
        when(publications.describe(version)).thenReturn(problem("Public base"));
        when(publications.challenge(version)).thenReturn(problem("Published discount challenge"));
        assertThat(service.read("session", "exercise")).contains("BASE", "Public base").doesNotContain("discount", "secret raw artifact");
        verify(publications, never()).challenge(version);
        when(exercises.challengeEntered("exercise")).thenReturn(true);
        assertThat(service.read("session", "exercise")).contains("CHALLENGE", "Public base", "Published discount challenge").doesNotContain("secret raw artifact");
    }
    private ProblemResponse problem(String requirements) {
        return new ProblemResponse("version", "cart", "1.1.0", "Cart", requirements, 2700, List.of(), Map.of(), "digest", "image", "linux/amd64", "LOCAL", true, false, "JAVA_MAVEN", true);
    }
}
