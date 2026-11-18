package ai.codecraft.modules.workspace.service;

import ai.codecraft.modules.workspace.model.dto.ProblemResponse;
import ai.codecraft.modules.workspace.model.entity.ProblemVersionEntity;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class ProblemPublicationReader {
    private static final int ARTIFACT_LIMIT = 1024 * 1024;
    private final ObjectMapper json;

    public ProblemPublicationReader(ObjectMapper json) {
        // Maintainer artifacts have a separate bounded parser; HTTP limits remain unchanged.
        this.json = json.copy().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY);
        this.json.getFactory().setStreamReadConstraints(StreamReadConstraints.builder()
            .maxStringLength(ARTIFACT_LIMIT).maxDocumentLength(ARTIFACT_LIMIT).maxNestingDepth(16).build());
    }

    public ProblemVersionEntity read(Path path) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Publication must be a regular file");
        byte[] bytes;
        try (InputStream input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            bytes = input.readNBytes(ARTIFACT_LIMIT + 1);
        }
        return read(bytes);
    }

    public ProblemVersionEntity read(byte[] bytes) throws IOException {
        if (bytes.length > ARTIFACT_LIMIT) throw new IOException("Publication artifact exceeds limit");
        String text = StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        require(!text.startsWith("\ufeff"));
        JsonNode artifact = json.readTree(text);
        require(artifact != null && artifact.isObject());
        validateStrings(artifact);
        validate(artifact);
        JsonNode identity = artifact.path("identity");
        return new ProblemVersionEntity(UUID.randomUUID().toString(), identity.path("problem_id").asText(),
            identity.path("version").asText(), identity.path("package_sha256").asText(),
            identity.path("execution_image_digest").asText(null), identity.path("platform").asText(null),
            json.writeValueAsString(artifact));
    }

    public ProblemResponse describe(ProblemVersionEntity version) {
        try {
            JsonNode artifact = json.readTree(version.artifact());
            validate(artifact);
            JsonNode manifest = artifact.path("manifest");
            List<String> roots = new ArrayList<>();
            manifest.path("editableRoots").forEach(root -> roots.add(root.asText()));
            Map<String, String> acceptance = new LinkedHashMap<>();
            artifact.path("acceptanceFiles").properties().forEach(entry -> acceptance.put(entry.getKey(), entry.getValue().asText()));
            var language = language(manifest);
            return new ProblemResponse(version.id(), version.problemId(), version.version(), manifest.path("title").asText(),
                artifact.path("requirements").asText(), manifest.path("durationSeconds").asInt(), List.copyOf(roots),
                Map.copyOf(acceptance), version.packageSha256(), version.executionImageDigest(), version.platform(), language.executionSupported ? "LOCAL_DOCKER_IMAGE_ID" : "NONE", language.executionSupported && manifest.path("execution").isObject(), manifest.has("challenge"), language.name(), language.executionSupported);
        } catch (IOException error) {
            throw new IllegalStateException("Stored publication is invalid", error);
        }
    }

    /** Hidden companion projection. The caller must establish that this exercise entered its challenge. */
    public ProblemResponse challenge(ProblemVersionEntity version) {
        try {
            JsonNode artifact = json.readTree(version.artifact());
            validate(artifact);
            if (!artifact.has("challenge")) return null;
            JsonNode companion = challengeArtifact(artifact);
            JsonNode identity = companion.path("identity");
            return describe(new ProblemVersionEntity(version.id(), identity.path("problem_id").asText(), identity.path("version").asText(),
                identity.path("package_sha256").asText(), identity.path("execution_image_digest").asText(), identity.path("platform").asText(), companion.toString()));
        } catch (IOException error) { throw new IllegalStateException("Stored challenge is invalid", error); }
    }

    private ObjectNode challengeArtifact(JsonNode artifact) {
        ObjectNode companion = artifact.path("challenge").deepCopy();
        companion.put("schemaVersion", 1);
        companion.set("receipt", artifact.path("receipt").path("challenge"));
        ObjectNode identity = json.createObjectNode();
        identity.set("problem_id", companion.path("manifest").path("id"));
        identity.set("version", companion.path("manifest").path("version"));
        identity.set("package_sha256", companion.path("receipt").path("packageSha256"));
        identity.set("execution_image_digest", companion.path("receipt").path("imageId"));
        identity.set("platform", companion.path("receipt").path("platform"));
        companion.set("identity", identity);
        return companion;
    }

    private void validate(JsonNode artifact) {
        JsonNode identity = artifact.path("identity");
        JsonNode manifest = artifact.path("manifest");
        JsonNode receipt = artifact.path("receipt");
        try {
            Map<String, Object> inputs = new LinkedHashMap<>();
            for (String field : List.of("manifest", "requirements", "initialFiles", "acceptanceFiles")) {
                inputs.put(field, json.convertValue(artifact.path(field), Object.class));
            }
            if (artifact.has("challenge")) inputs.put("challenge", json.convertValue(artifact.path("challenge"), Object.class));
            byte[] canonical = json.writer().with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsBytes(inputs);
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
            require(digest.equals(receipt.path("publicationInputsSha256").asText()));
        } catch (IOException | NoSuchAlgorithmException error) {
            throw new IllegalStateException("Cannot verify publication inputs", error);
        }
        require(artifact.path("schemaVersion").asInt(-1) == 1 && manifest.path("schemaVersion").asInt(-1) == 1);
        require(identity.path("problem_id").asText().matches("[a-z][a-z0-9-]{0,63}"));
        require(identity.path("version").asText().matches("[0-9]+\\.[0-9]+\\.[0-9]+") && identity.path("version").asText().length() <= 32);
        require(identity.path("package_sha256").asText().matches("[0-9a-f]{64}"));

        require(manifest.path("id").equals(identity.path("problem_id")) && manifest.path("version").equals(identity.path("version")));
        require(!manifest.path("title").asText().isBlank() && !artifact.path("requirements").asText().isBlank());
        require(manifest.path("durationSeconds").canConvertToInt() && manifest.path("durationSeconds").asInt() > 0);
        var language = language(manifest);
        require(manifest.path("editableRoots").equals(json.valueToTree(language.roots)));
        require("forbidden".equals(manifest.path("symlinks").asText()) && manifest.path("targets").toString().equals("[\"base\"]"));
        if (language.executionSupported) validateExecutionReceipt(manifest, identity, receipt);
        else {
            require(!identity.has("execution_image_digest") && !identity.has("platform"));
            require(receipt.size() == 3 && "static-contract-only".equals(receipt.path("verdict").asText())
                && receipt.path("packageSha256").equals(identity.path("package_sha256")));
        }
        validateContents(artifact, manifest, receipt, language);
    }

    private void validateExecutionReceipt(JsonNode manifest, JsonNode identity, JsonNode receipt) {
        require(identity.path("execution_image_digest").asText().matches("sha256:[0-9a-f]{64}"));
        require("linux/amd64".equals(identity.path("platform").asText()));
        require("local-contract-validation-passed".equals(receipt.path("verdict").asText()));
        require(receipt.path("packageSha256").equals(identity.path("package_sha256")) && receipt.path("imageId").equals(identity.path("execution_image_digest")));
        require(receipt.path("platform").equals(identity.path("platform")) && receipt.path("limits").equals(manifest.path("runtime")));
        require(!receipt.path("environment").path("daemonId").asText().isBlank());
        for (String variant : List.of("initial", "reference")) {
            JsonNode expected = manifest.path(variant.equals("initial") ? "expectedInitial" : "expectedReference");
            boolean found = false;
            for (JsonNode result : receipt.path("results")) {
                if (variant.equals(result.path("name").asText()) && expected.equals(result.path("testSummary"))) found = true;
            }
            require(found && expected.path("tests").asInt() > 0);
        }
        require(manifest.path("expectedReference").path("failures").asInt(-1) == 0
            && manifest.path("expectedReference").path("errors").asInt(-1) == 0
            && manifest.path("expectedReference").path("skipped").asInt(-1) == 0);
    }

    private void validateContents(JsonNode artifact, JsonNode manifest, JsonNode receipt, ProblemLanguage language) {
        if (manifest.has("execution")) {
            JsonNode execution = manifest.path("execution");
            require(execution.isObject() && execution.path("suite").asText().matches("[A-Za-z_][A-Za-z_0-9.]{0,254}"));
            require(execution.path("tests").isArray() && execution.path("tests").size() > 0 && execution.path("tests").size() <= 200
                && execution.path("tests").size() == manifest.path("expectedReference").path("tests").asInt());
            List<String> names = new ArrayList<>();
            for (JsonNode name : execution.path("tests")) {
                require(name.isTextual() && name.asText().matches("[A-Za-z_][A-Za-z_0-9.]{0,254}") && !names.contains(name.asText()));
                names.add(name.asText());
            }
        }
        if (manifest.has("challenge")) {
            require("challenge".equals(manifest.path("challenge").asText()) && artifact.path("challenge").isObject());
            require(!artifact.path("challenge").path("manifest").has("challenge"));
            require(language == language(artifact.path("challenge").path("manifest")));
            require(manifest.path("execution").isObject() && artifact.path("challenge").path("manifest").path("execution").isObject());
            require(artifact.path("initialFiles").equals(artifact.path("challenge").path("initialFiles")));
            require(receipt.path("challenge").path("environment").path("daemonId").equals(receipt.path("environment").path("daemonId")));
            require(!receipt.path("challenge").path("imageId").equals(receipt.path("imageId")));
            validate(challengeArtifact(artifact));
        } else require(!artifact.has("challenge") && !receipt.has("challenge"));
        String suite = manifest.path("execution").path("suite").asText();
        String suiteFile = suite.substring(suite.lastIndexOf('.') + 1) + ".java";
        require(language.buildFiles.stream().anyMatch(artifact.path("initialFiles")::has));
        if (language == ProblemLanguage.PYTHON_PYTEST) {
            require(!manifest.has("execution") && artifact.path("acceptanceFiles").properties().stream().anyMatch(entry -> entry.getKey().matches("(?:.*/)?test_[A-Za-z0-9_]+\\.py")));
        } else require((suite.isEmpty()
            ? artifact.path("acceptanceFiles").properties().stream().anyMatch(entry -> entry.getKey().endsWith("Test.java"))
            : artifact.path("acceptanceFiles").has(suiteFile)));
        for (String section : List.of("initialFiles", "acceptanceFiles")) {
            require(artifact.path(section).isObject());
            artifact.path(section).properties().forEach(entry -> {
                require(validPath(entry.getKey()) && entry.getValue().isTextual() && entry.getValue().asText().indexOf(0) < 0);
                if (section.equals("initialFiles")) require(language.buildFiles.contains(entry.getKey()) || entry.getKey().equals("AGENTS.md") || entry.getKey().endsWith("/AGENTS.md")
                    || language.editable(entry.getKey()));
            });
        }
    }

    private static ProblemLanguage language(JsonNode manifest) {
        return ProblemLanguage.valueOf(manifest.has("language") ? manifest.path("language").asText() : "JAVA_MAVEN");
    }

    private static void validateStrings(JsonNode node) {
        if (node.isTextual()) require(storable(node.textValue()));
        else if (node.isObject()) node.properties().forEach(entry -> { require(storable(entry.getKey())); validateStrings(entry.getValue()); });
        else if (node.isArray()) node.forEach(ProblemPublicationReader::validateStrings);
    }
    private static boolean storable(String value) {
        return value.indexOf(0) < 0 && StandardCharsets.UTF_8.newEncoder().canEncode(value);
    }

    private static boolean validPath(String path) {
        if (path.isBlank() || path.length() > 4096) return false;
        for (String part : path.split("/", -1)) {
            if (!part.matches("[a-zA-Z0-9_.-]+") || part.equals(".") || part.equals("..")) return false;
        }
        return true;
    }
    private static void require(boolean valid) {
        if (!valid) throw new IllegalStateException("Invalid maintainer publication contract");
    }
}
