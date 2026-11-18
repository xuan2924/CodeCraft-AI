package ai.codecraft.testfixture;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Real crash children share immutable classes on the disposable Linux container's native filesystem. */
public final class RecoveryChildRuntime {
    private static String staged;
    private RecoveryChildRuntime() { }

    public static synchronized String classPath() throws IOException {
        if (staged != null) return staged;
        var root = Files.createTempDirectory("recovery-child-classpath-");
        var entries = new ArrayList<String>();
        for (String item : System.getProperty("surefire.test.class.path").split(Pattern.quote(File.pathSeparator))) {
            Path original = Path.of(item), local = root.resolve(Integer.toString(entries.size())).resolve(original.getFileName());
            Files.createDirectories(local.getParent());
            if (Files.isDirectory(original)) {
                try (var paths = Files.walk(original)) {
                    for (var source : paths.toList()) Files.copy(source, local.resolve(original.relativize(source)));
                }
            } else Files.copy(original, local);
            entries.add(local.toString());
        }
        staged = String.join(File.pathSeparator, entries);
        return staged;
    }

    /** Liveness alone doesn't establish the scheduled, authenticated placement lease. Never seed it here. */
    public static boolean hasReadyNode() {
        var db = new JdbcTemplate(new DriverManagerDataSource(System.getenv("SPRING_DATASOURCE_URL"),
            System.getenv("SPRING_DATASOURCE_USERNAME"), System.getenv("SPRING_DATASOURCE_PASSWORD")));
        return Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM sandbox_node WHERE id='default' AND ready "
            + "AND storage_id IS NOT NULL AND healthy_until>clock_timestamp())", Boolean.class));
    }
}
