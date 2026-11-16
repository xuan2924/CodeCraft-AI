package ai.codecraft.execution;

import com.sun.tools.javac.file.JavacFileManager;
import com.sun.tools.javac.main.Main;
import com.sun.tools.javac.util.Context;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashSet;
import java.util.Set;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticListener;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;

/** Fixed JDK 21 CLI adapter: javac parses Maven's arguments and compiles normally.
 * Only structured source diagnostics can produce code-failure evidence.
 * The three exported javac internals require revalidation when the image changes.
 */
public final class JavacEvidence implements DiagnosticListener<JavaFileObject> {
    private static final Set<String> ENVIRONMENT_ERRORS = Set.of(
            "compiler.err.class.cant.write", "compiler.err.error.writing.file",
            "compiler.err.error.reading.file", "compiler.err.io.exception",
            "compiler.err.cant.read.file", "compiler.err.cant.access",
            "compiler.err.unsupported.encoding", "compiler.err.no.java.lang");
    private final Set<String> codes = new LinkedHashSet<>();
    private boolean environmentError;

    @Override
    public void report(Diagnostic<? extends JavaFileObject> diagnostic) {
        // User-facing text is only a log; classification below uses codes/source.
        System.err.println(diagnostic);
        if (diagnostic.getKind() != Diagnostic.Kind.ERROR) {
            return;
        }
        String code = diagnostic.getCode();
        JavaFileObject source = diagnostic.getSource();
        String name = source == null ? "" : source.toUri().getPath();
        boolean sourceLocation = name != null && (name.startsWith("/work/src/main/java/")
                || name.startsWith("/opt/acceptance/")) && diagnostic.getPosition() != Diagnostic.NOPOS;
        if (!sourceLocation || code == null || !code.matches("compiler\\.err\\.[a-zA-Z0-9_.]{1,100}")
                || ENVIRONMENT_ERRORS.contains(code) || code.startsWith("compiler.err.locn.")
                || code.startsWith("compiler.err.proc.")) {
            environmentError = true;
            return;
        }
        if (codes.size() >= 128) {
            environmentError = true;
        } else {
            codes.add(code);
        }
    }

    public static void main(String[] args) throws IOException {
        Context context = new Context();
        JavacEvidence evidence = new JavacEvidence();
        context.put(DiagnosticListener.class, evidence);
        JavacFileManager.preRegister(context);
        Main.Result result;
        try (JavaFileManager files = context.get(JavaFileManager.class)) {
            result = new Main("javac", new PrintWriter(System.err, true)).compile(args, context);
        }
        if (result == Main.Result.ERROR && !evidence.environmentError && !evidence.codes.isEmpty()) {
            String report = "compiler-source-errors-v2\n" + String.join("\n", evidence.codes) + "\n";
            Path staging = Path.of("/work/.compilation-result.tmp");
            Files.writeString(staging, report, StandardCharsets.US_ASCII);
            Files.move(staging, Path.of("/work/compilation-result"),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }
        // SYSERR (resource exhaustion), ABNORMAL, bad arguments and evidence I/O
        // never publish source-failure evidence; the runner fails closed.
        System.exit(result.exitCode);
    }
}
