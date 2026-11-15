package ai.codecraft.modules.skills.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.codecraft.modules.skills.config.SkillProperties;
import ai.codecraft.modules.skills.model.dto.SkillFileDTO;
import ai.codecraft.modules.skills.model.dto.SkillPackageRequest;
import java.util.List;
import java.util.ArrayList;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class SkillPackageParserTest {
    private final SkillPackageParser parser = new SkillPackageParser(new SkillProperties(50, 262144, 65536, 5242880, 32, 256, 4));

    @Test
    void preservesOriginalUtf8AndExtractsMetadataFromTheSingleMainDocument() {
        String main = "---\r\nname: review-java\r\ndescription: 审查 Java\r\n---\r\nFollow references/rules.md\r\n";
        var result = parser.parse(new SkillPackageRequest(1, List.of(new SkillFileDTO("references/rules.md", "规则"), new SkillFileDTO("SKILL.md", main))));
        assertThat(result.name()).isEqualTo("review-java");
        assertThat(result.description()).isEqualTo("审查 Java");
        assertThat(result.files().getFirst().content()).isEqualTo(main);
        assertThat(result.contentBytes()).isEqualTo(main.getBytes(StandardCharsets.UTF_8).length + 6);
    }

    @Test
    void enforcesRawByteLimitsAndRejectsEncodingAndCaseCollisions() {
        String prefix = "---\nname: a\ndescription: d\n---\n";
        String exactMain = prefix + "x".repeat(65536 - prefix.length());
        assertThat(parser.parse(new SkillPackageRequest(1, List.of(new SkillFileDTO("SKILL.md", exactMain)))).contentBytes()).isEqualTo(65536);
        assertThatThrownBy(() -> parser.parse(new SkillPackageRequest(1, List.of(new SkillFileDTO("SKILL.md", exactMain + "x"))))).hasMessageContaining("SKILL_LIMIT_EXCEEDED");
        var files = List.of(new SkillFileDTO("SKILL.md", exactMain), new SkillFileDTO("references/rules.txt", "x".repeat(196608)));
        assertThat(parser.parse(new SkillPackageRequest(1, files)).contentBytes()).isEqualTo(262144);
        assertThatThrownBy(() -> parser.parse(new SkillPackageRequest(1, List.of(files.getFirst(), new SkillFileDTO("references/rules.txt", "x".repeat(196609)))))).hasMessageContaining("SKILL_LIMIT_EXCEEDED");
        for (String invalid : List.of(prefix + "\u0000", prefix + "\uD800", "\uFEFF" + prefix + "Body")) {
            assertThatThrownBy(() -> parser.parse(new SkillPackageRequest(1, List.of(new SkillFileDTO("SKILL.md", invalid))))).hasMessageContaining("SKILL_INVALID_PACKAGE");
        }
        assertThatThrownBy(() -> parser.parse(new SkillPackageRequest(1, List.of(files.getFirst(), new SkillFileDTO("references/A.md", "a"), new SkillFileDTO("references/a.md", "b"))))).hasMessageContaining("SKILL_INVALID_PACKAGE");
        var many = new ArrayList<SkillFileDTO>(); many.add(new SkillFileDTO("SKILL.md", prefix + "Body"));
        for (int index = 0; index < 31; index++) many.add(new SkillFileDTO("references/" + index + ".txt", "a"));
        assertThat(parser.parse(new SkillPackageRequest(1, many)).files()).hasSize(32);
        many.add(new SkillFileDTO("references/last.txt", "a"));
        assertThatThrownBy(() -> parser.parse(new SkillPackageRequest(1, many))).hasMessageContaining("SKILL_LIMIT_EXCEEDED");
    }

    @Test
    void rejectsDuplicateMetadataAliasesAndUnsafePathsWithoutNormalizingThem() {
        for (String metadata : List.of("name: a\nname: b\ndescription: d", "name: a\ndescription: &a d\nlicense: *a", "name: a\ndescription: d\nallowed-tools: bash")) {
            assertThatThrownBy(() -> parser.parse(new SkillPackageRequest(1, List.of(new SkillFileDTO("SKILL.md", "---\n" + metadata + "\n---\nBody"))))).isInstanceOf(RuntimeException.class);
        }
        for (String path : List.of("references/../secret.txt", "references\\secret.txt", "/references/a.md", "references/a.sh", "references//a.md")) {
            assertThatThrownBy(() -> parser.parse(new SkillPackageRequest(1, List.of(new SkillFileDTO("SKILL.md", "---\nname: a\ndescription: d\n---\nBody"), new SkillFileDTO(path, "text"))))).isInstanceOf(RuntimeException.class);
        }
    }
}
