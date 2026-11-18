package ai.codecraft.modules.workspace.service;

import java.util.List;

/** Static trusted profiles; manifests may select but never expand a profile's write authority. */
public enum ProblemLanguage {
    JAVA_MAVEN(List.of("src/main/java", "src/test/java"), List.of("pom.xml"), true),
    PYTHON_PYTEST(List.of("src", "tests"), List.of("pyproject.toml", "requirements.txt"), false);

    final List<String> roots;
    final List<String> buildFiles;
    final boolean executionSupported;

    ProblemLanguage(List<String> roots, List<String> buildFiles, boolean supported) {
        this.roots = roots;
        this.buildFiles = buildFiles;
        this.executionSupported = supported;
    }

    public boolean editable(String path) {
        if (path == null || path.length() > 4096 || !path.matches("[A-Za-z0-9_.-]+(/[A-Za-z0-9_.-]+)*")) return false;
        for (String part : path.split("/")) {
            if (part.equals(".") || part.equals("..")) return false;
        }
        return !path.endsWith("/AGENTS.md") && roots.stream().anyMatch(root -> path.startsWith(root + "/"));
    }
}
