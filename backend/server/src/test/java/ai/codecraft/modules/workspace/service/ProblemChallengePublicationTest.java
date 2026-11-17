package ai.codecraft.modules.workspace.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.codecraft.modules.workspace.model.entity.ProblemVersionEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProblemChallengePublicationTest {
    private final ObjectMapper json = new ObjectMapper();
    private final ProblemPublicationReader reader = new ProblemPublicationReader(json);

    @Test
    void publicCatalogNeverContainsTheHiddenChallengeButExplicitCompanionProjectionDoes() throws Exception {
        var artifact = publication();
        var version = version(artifact);
        var publicProblem = reader.describe(version);
        assertThat(publicProblem.hasChallenge()).isTrue();
        assertThat(publicProblem.requirements()).isEqualTo("Public base requirements");
        assertThat(publicProblem.acceptanceFiles()).containsEntry("CartTotalTest.java", "base tests");
        assertThat(json.writeValueAsString(publicProblem)).doesNotContain("Hidden discount challenge", "secret challenge tests");
        var challenge = reader.challenge(version);
        assertThat(challenge.requirements()).isEqualTo("Hidden discount challenge");
        assertThat(challenge.executionImageDigest()).isEqualTo("sha256:" + "c".repeat(64));
        assertThat(challenge.hasChallenge()).isFalse();
    }

    @Test
    void changedChallengeInputsAreRejectedEvenWhenTheBaseIdentityIsUnchanged() throws Exception {
        var artifact = publication();
        ((ObjectNode) artifact.path("challenge")).put("requirements", "tampered");
        assertThatThrownBy(() -> reader.describe(version(artifact))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void challengeWithDifferentInitialCodeIsRejectedEvenWithMatchingInputDigest() throws Exception {
        var artifact = publication();
        ((ObjectNode) artifact.path("challenge").path("initialFiles")).put("pom.xml", "different dependencies");
        sign(artifact);
        assertThatThrownBy(() -> reader.describe(version(artifact))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void publishedSuiteAndControlledInstructionFilesAreNotCartSpecific() throws Exception {
        var artifact = publication();
        artifact.remove("challenge");
        ((ObjectNode) artifact.path("receipt")).remove("challenge");
        ((ObjectNode) artifact.path("manifest")).remove("challenge");
        ((ObjectNode) artifact.path("manifest").path("execution")).put("suite", "interview.OrderSettlementTest");
        ((ObjectNode) artifact.path("acceptanceFiles")).removeAll().put("OrderSettlementTest.java", "order tests");
        ((ObjectNode) artifact.path("initialFiles")).put("AGENTS.md", "root policy").put("src/main/java/orders/AGENTS.md", "module policy");
        sign(artifact);
        assertThat(reader.describe(version(artifact)).acceptanceFiles()).containsOnlyKeys("OrderSettlementTest.java");
        ((ObjectNode) artifact.path("initialFiles")).put("outside/secret.txt", "not allowed");
        sign(artifact);
        assertThatThrownBy(() -> reader.describe(version(artifact))).hasMessage("Invalid maintainer publication contract");
    }

    @Test
    void configuredSuiteMustHaveItsOwnPublishedAcceptanceSource() throws Exception {
        var artifact = publication();
        ((ObjectNode) artifact.path("manifest").path("execution")).put("suite", "interview.MissingTest");
        sign(artifact);
        assertThatThrownBy(() -> reader.describe(version(artifact))).hasMessage("Invalid maintainer publication contract");
    }

    private ProblemVersionEntity version(ObjectNode artifact) {
        return new ProblemVersionEntity("version", "cart", "1.1.0", "a".repeat(64), "sha256:" + "b".repeat(64), "linux/amd64", artifact.toString());
    }

    private ObjectNode publication() throws Exception {
        ObjectNode base = (ObjectNode) json.readTree("""
            {"schemaVersion":1,"identity":{"problem_id":"cart","version":"1.1.0","package_sha256":"%s","execution_image_digest":"sha256:%s","platform":"linux/amd64"},
             "manifest":{"schemaVersion":1,"id":"cart","version":"1.1.0","title":"Cart","durationSeconds":2700,
               "editableRoots":["src/main/java","src/test/java"],"symlinks":"forbidden","targets":["base"],
               "expectedInitial":{"tests":1,"failures":1,"errors":0,"skipped":0},
               "expectedReference":{"tests":1,"failures":0,"errors":0,"skipped":0},"runtime":{},
               "execution":{"suite":"interview.CartTotalTest","tests":["baseTest"]}},
             "requirements":"Public base requirements","initialFiles":{"pom.xml":"same dependencies"},"acceptanceFiles":{"CartTotalTest.java":"base tests"},
             "receipt":{"verdict":"local-contract-validation-passed","packageSha256":"%s","imageId":"sha256:%s","platform":"linux/amd64",
               "limits":{},"environment":{"daemonId":"fixture"},"results":[
                {"name":"initial","testSummary":{"tests":1,"failures":1,"errors":0,"skipped":0}},
                {"name":"reference","testSummary":{"tests":1,"failures":0,"errors":0,"skipped":0}}]}}
            """.formatted("a".repeat(64), "b".repeat(64), "a".repeat(64), "b".repeat(64)));
        ObjectNode child = base.deepCopy();
        child.remove(List.of("identity", "schemaVersion"));
        child.put("requirements", "Hidden discount challenge");
        ((ObjectNode) child.path("acceptanceFiles")).put("CartTotalTest.java", "secret challenge tests");
        ((ObjectNode) child.path("receipt")).put("imageId", "sha256:" + "c".repeat(64));
        sign(child);
        ((ObjectNode) base.path("receipt")).set("challenge", child.remove("receipt"));
        base.set("challenge", child);
        ((ObjectNode) base.path("manifest")).put("challenge", "challenge");
        sign(base);
        return base;
    }

    private void sign(ObjectNode artifact) throws Exception {
        var inputs = new LinkedHashMap<String, Object>();
        for (String field : List.of("manifest", "requirements", "initialFiles", "acceptanceFiles", "challenge")) {
            if (artifact.has(field)) inputs.put(field, json.convertValue(artifact.path(field), Object.class));
        }
        byte[] canonical = json.writer().with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsBytes(inputs);
        ((ObjectNode) artifact.path("receipt")).put("publicationInputsSha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical)));
    }
}
