package ai.codecraft.modules.skills.service;

import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.skills.config.SkillProperties;
import ai.codecraft.modules.skills.model.dto.SkillFileDTO;
import ai.codecraft.modules.skills.model.dto.SkillPackageDTO;
import ai.codecraft.modules.skills.model.dto.SkillPackageRequest;
import ai.codecraft.modules.skills.model.exception.SkillErrorCode;
import java.io.StringReader;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.events.AliasEvent;
import org.yaml.snakeyaml.error.YAMLException;

/** Parses bounded text only; never resolves Markdown references or touches the filesystem. */
@Component
public class SkillPackageParser {
    private static final Set<String> FIELDS = Set.of("name", "description", "license", "compatibility", "metadata");
    private final SkillProperties limits;
    public SkillPackageParser(SkillProperties limits) { this.limits = limits; }

    public SkillPackageDTO parse(SkillPackageRequest request) {
        if (request == null || request.formatVersion() != 1 || request.files() == null || request.files().isEmpty()) invalid();
        if (request.files().size() > limits.maxFiles()) exceeded();
        var paths = new HashSet<String>();
        String main = null;
        long bytes = 0;
        for (var file : request.files()) {
            if (file == null) invalid();
            path(file.path());
            if (!paths.add(file.path().toLowerCase(Locale.ROOT))) invalid();
            int size = utf8Bytes(file.content());
            bytes += size;
            if (bytes > limits.maxPackageBytes()) exceeded();
            if ("SKILL.md".equals(file.path())) {
                if (size > limits.maxMainBytes()) exceeded();
                main = file.content();
            }
        }
        if (main == null) invalid();
        Map<?, ?> metadata = frontmatter(main);
        if (!FIELDS.containsAll(metadata.keySet())) invalid();
        String name = text(metadata.get("name"), 64);
        if (!name.matches("[a-z0-9]+(?:-[a-z0-9]+)*")) invalid();
        String description = text(metadata.get("description"), 1024);
        for (String optional : List.of("license", "compatibility")) {
            if (metadata.containsKey(optional)) text(metadata.get(optional), 500);
        }
        if (metadata.containsKey("metadata")) {
            if (!(metadata.get("metadata") instanceof Map<?, ?> fields) || fields.size() > 16) invalid();
            var fields = (Map<?, ?>) metadata.get("metadata");
            fields.forEach((key, value) -> { text(key, 64); text(value, 256); });
        }
        return new SkillPackageDTO(name, description, request.files().stream().sorted(Comparator.comparing(SkillFileDTO::path)).toList(), bytes);
    }

    private void path(String path) {
        if (path == null || utf8Bytes(path) > limits.maxPathBytes()) invalid();
        if ("SKILL.md".equals(path)) return;
        if (!path.startsWith("references/") || !(path.endsWith(".md") || path.endsWith(".txt"))) invalid();
        String[] parts = path.split("/", -1);
        if (parts.length > limits.maxPathDepth()) invalid();
        for (String part : parts) {
            if (!part.matches("[A-Za-z0-9._-]+") || part.equals(".") || part.equals("..")) invalid();
        }
    }

    private Map<?, ?> frontmatter(String main) {
        String[] lines = main.split("\n", -1);
        if (!lines[0].equals("---") && !lines[0].equals("---\r")) invalid();
        int end = 1;
        while (end < lines.length && !lines[end].equals("---") && !lines[end].equals("---\r")) end++;
        if (end >= lines.length) invalid();
        String body = String.join("\n", List.of(lines).subList(end + 1, lines.length));
        if (body.isBlank()) invalid();
        String header = String.join("\n", List.of(lines).subList(1, end));
        var options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setAllowRecursiveKeys(false);
        options.setMaxAliasesForCollections(0);
        options.setNestingDepthLimit(8);
        options.setCodePointLimit(limits.maxMainBytes());
        var yaml = new Yaml(new SafeConstructor(options));
        try {
            // SnakeYAML's alias quota covers collection aliases only; scalar aliases are forbidden too.
            for (var event : yaml.parse(new StringReader(header))) if (event instanceof AliasEvent) invalid();
            Object value = yaml.load(header);
            if (!(value instanceof Map<?, ?>)) invalid();
            return (Map<?, ?>) value;
        } catch (YAMLException failure) {
            throw new BusinessException(SkillErrorCode.SKILL_INVALID_PACKAGE);
        }
    }

    private String text(Object value, int max) {
        if (!(value instanceof String)) invalid();
        String text = (String) value;
        if (text.isBlank() || text.codePointCount(0, text.length()) > max) invalid();
        return text;
    }

    private int utf8Bytes(String text) {
        if (text == null || text.indexOf('\u0000') >= 0 || text.indexOf('\uFEFF') >= 0) invalid();
        try { return StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(text)).remaining(); }
        catch (CharacterCodingException failure) { throw new BusinessException(SkillErrorCode.SKILL_INVALID_PACKAGE); }
    }
    private static void invalid() { throw new BusinessException(SkillErrorCode.SKILL_INVALID_PACKAGE); }
    private static void exceeded() { throw new BusinessException(SkillErrorCode.SKILL_LIMIT_EXCEEDED); }
}
