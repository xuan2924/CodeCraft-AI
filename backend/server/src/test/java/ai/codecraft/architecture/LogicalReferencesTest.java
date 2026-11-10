package ai.codecraft.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class LogicalReferencesTest {
    @Test
    void newMigrationsCannotIntroducePhysicalForeignKeys() throws Exception {
        Pattern version = Pattern.compile("V(\\d+)__.*\\.sql");
        Pattern foreignKey = Pattern.compile("\\bREFERENCES\\b|\\bFOREIGN\\s+KEY\\b");
        try (var files = Files.list(Path.of("src/main/resources/db/migration"))) {
            for (Path file : files.toList()) {
                var match = version.matcher(file.getFileName().toString());
                // V1-V12 are immutable history; V13 removes their foreign keys.
                if (!match.matches() || Integer.parseInt(match.group(1)) < 13) continue;
                String sql = Files.readString(file).replaceAll("(?m)--[^\\n]*", "").toUpperCase(Locale.ROOT);
                assertThat(foreignKey.matcher(sql).find()).as("Logical references only: %s", file).isFalse();
            }
        }
    }
}
