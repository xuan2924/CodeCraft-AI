package ai.codecraft.execution;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.maven.eventspy.AbstractEventSpy;
import org.apache.maven.execution.ExecutionEvent;
import org.apache.maven.lifecycle.LifecycleExecutionException;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.MojoFailureException;

/** Fixed-image evidence collector; it never reads candidate output or log text. */
public final class CompilationSpy extends AbstractEventSpy {
    private static final Pattern LOCATION = Pattern.compile(
            "^/(?:work/src/main/java|opt/acceptance)/[^\\r\\n]+\\.java:\\[\\d+,\\d+\\] (.+)$");
    private static final Set<String> SOURCE_ERRORS = Set.of(
            "';' expected", "')' expected", "'(' expected", "'}' expected", "'{' expected",
            "<identifier> expected", "not a statement", "illegal start of expression",
            "illegal start of type", "reached end of file while parsing",
            "class, interface, enum, or record expected", "cannot find symbol");

    // Maven's exception collapses source and output-I/O errors into one type.
    // Only recognize these pinned JDK 21 source diagnostics; unknown/localized
    // diagnostics fail closed. This reads the exception, never stdout/stderr.
    private static boolean sourceErrorsOnly(String diagnostics) {
        if (diagnostics == null || diagnostics.length() > 65536) {
            return false;
        }
        String[] messages = diagnostics.split("(?m)(?=^/(?:work/src/main/java|opt/acceptance)/)");
        boolean found = false;
        for (String message : messages) {
            if (message.isBlank()) {
                continue;
            }
            var lines = message.strip().lines().toList();
            Matcher location = LOCATION.matcher(lines.getFirst());
            if (!location.matches() || !SOURCE_ERRORS.contains(location.group(1))) {
                return false;
            }
            if ("cannot find symbol".equals(location.group(1))) {
                if (lines.size() != 3 || !lines.get(1).stripLeading().startsWith("symbol:")
                        || !lines.get(2).stripLeading().startsWith("location:")) {
                    return false;
                }
            } else if (lines.size() != 1) {
                return false;
            }
            found = true;
        }
        return found;
    }

    @Override
    public void onEvent(Object value) throws Exception {
        if (!(value instanceof ExecutionEvent event)
                || event.getType() != ExecutionEvent.Type.MojoFailed) {
            return;
        }
        MojoExecution mojo = event.getMojoExecution();
        if (mojo == null || !"org.apache.maven.plugins".equals(mojo.getGroupId())
                || !"maven-compiler-plugin".equals(mojo.getArtifactId())
                || !"3.14.1".equals(mojo.getVersion())
                || !("compile".equals(mojo.getGoal()) || "testCompile".equals(mojo.getGoal()))) {
            return;
        }
        // The plugin has its own classloader. Match the fixed plugin's exception
        // identity, not localized messages, Maven's exit code or stdout/stderr.
        Throwable failure = event.getException();
        if (failure instanceof LifecycleExecutionException) {
            failure = failure.getCause();
        }
        if (!(failure instanceof MojoFailureException compilation)
                || !"org.apache.maven.plugin.compiler.CompilationFailureException".equals(failure.getClass().getName())
                || !sourceErrorsOnly(compilation.getLongMessage())) {
            return;
        }
        // Only the controlled runtime opts in. Image warm-up produces no record.
        if (!"/work/compilation-result".equals(event.getSession().getUserProperties()
                .getProperty("codecraft.compilationResult"))) {
            return;
        }
        Path staging = Path.of("/work/.compilation-result.tmp");
        Files.writeString(staging, "compiler-failure-v1\n" + mojo.getGoal() + "\n", StandardCharsets.UTF_8);
        Files.move(staging, Path.of("/work/compilation-result"),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
}
