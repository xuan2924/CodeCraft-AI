package ai.codecraft.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

/** Enforces the source layout and dependency rules, separately from behavioral acceptance tests. */
class BackendLayeringTest {
    private static final Path SOURCE_ROOT = Path.of("src/main/java");
    private static final Pattern PACKAGE = Pattern.compile("(?m)^package ([\\w.]+);");
    private static final Pattern IMPORT = Pattern.compile("(?m)^import (?:static )?([\\w.*]+);");

    @Test
    void packageDeclarationsMatchPathsAndCommonDoesNotDependOnBusiness() throws IOException {
        for (Path path : sources()) {
            String source = Files.readString(path);
            String name = packageName(source);
            String expected = SOURCE_ROOT.relativize(path.getParent()).toString().replace('\\', '.').replace('/', '.');
            assertThat(name).as("package for %s", path).isEqualTo(expected);
            assertThat(name.equals("ai.codecraft") || name.startsWith("ai.codecraft.common.")
                || name.startsWith("ai.codecraft.infrastructure.") || name.startsWith("ai.codecraft.modules."))
                .as("top-level ownership for %s", path).isTrue();
            for (String dependency : imports(source)) {
                assertThat(dependency).as("explicit import in %s", path).doesNotEndWith(".*");
                if (name.startsWith("ai.codecraft.infrastructure.") && dependency.startsWith("ai.codecraft.")) {
                    assertThat(dependency).as("infrastructure dependency in %s", path)
                        .doesNotStartWith("ai.codecraft.modules.");
                }
                if (name.startsWith("ai.codecraft.common") && dependency.startsWith("ai.codecraft.")) {
                    assertThat(dependency).as("common dependency in %s", path).startsWith("ai.codecraft.common.");
                }
            }
        }
    }

    @Test
    void layersKeepTransportAndPersistenceResponsibilitiesSeparate() throws IOException {
        for (Path path : sources()) {
            String source = Files.readString(path);
            String name = packageName(source);
            List<String> dependencies = imports(source);
            if (name.endsWith(".controller")) {
                assertThat(dependencies).as("controller dependencies in %s", path)
                    .noneMatch(value -> value.contains(".repository.") || value.contains(".mapper.") || value.contains(".model.entity."));
            }
            if (name.endsWith(".service")) {
                assertThat(dependencies).as("service dependencies in %s", path)
                    .noneMatch(value -> value.startsWith("jakarta.servlet.") || value.contains(".controller.")
                        || value.startsWith("org.springframework.web."));
            }
            if (name.endsWith(".repository") || name.endsWith(".mapper")) {
                assertThat(dependencies).as("persistence dependencies in %s", path)
                    .noneMatch(value -> value.contains(".service.") || value.contains(".controller.")
                        || value.startsWith("jakarta.servlet.") || value.contains(".security."));
            }
        }
    }

    @Test
    void transactionsAreDeclaredOnlyOnPublicServiceMethods() throws Exception {
        int transactionalMethods = 0;
        for (Path path : sources()) {
            String source = Files.readString(path);
            String name = packageName(source);
            String className = path.getFileName().toString().replaceFirst("\\.java$", "");
            Class<?> type = Class.forName(name + "." + className, false, getClass().getClassLoader());
            assertThat(type.isAnnotationPresent(Transactional.class)).as("method-scoped transactions in %s", type.getName()).isFalse();
            for (Method method : type.getDeclaredMethods()) {
                if (!method.isAnnotationPresent(Transactional.class)) continue;
                transactionalMethods++;
                assertThat(name).as("transaction layer for %s", method).endsWith(".service");
                assertThat(Modifier.isPublic(method.getModifiers())).as("transaction visibility for %s", method).isTrue();
            }
        }
        assertThat(transactionalMethods).as("transaction rule must inspect actual transaction methods").isPositive();
    }

    private static List<Path> sources() throws IOException {
        try (Stream<Path> paths = Files.walk(SOURCE_ROOT)) {
            List<Path> result = paths.filter(path -> path.toString().endsWith(".java")).sorted().toList();
            assertThat(result).isNotEmpty();
            return result;
        }
    }

    private static String packageName(String source) {
        return PACKAGE.matcher(source).results().map(result -> result.group(1)).findFirst().orElseThrow();
    }

    private static List<String> imports(String source) {
        return IMPORT.matcher(source).results().map(result -> result.group(1)).toList();
    }
}
