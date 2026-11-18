package ai.codecraft.modules.copilot.service;

import static org.assertj.core.api.Assertions.assertThat;

import ai.codecraft.modules.modelaccess.model.ModelProtocol;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Uses the persistence fixture's isolated PostgreSQL database and scripted ModelCallDriver, never a network model. */
class CopilotReplayEvalTest {
    @Test
    @EnabledIfEnvironmentVariable(named = "COPILOT_IT_JDBC_URL", matches = ".+")
    void replay() throws Exception {
        var f = new CopilotPersistenceIT();
        var results = new LinkedHashMap<String, Object>();
        results.put("source", "DETERMINISTIC_SCRIPTED_BASELINE");
        try {
            f.migrate();
            f.seed(); results.put("A_serial", f.evalReads(false, 4));
            f.seed(); results.put("A_parallel", f.evalReads(true, 4));
            f.seed(); var main = f.evalReads(false, 3); results.put("B_main", main);
            f.seed(); var explore = f.evalExplore(); results.put("B_explore", explore);
            long saved = main.get("parentRequestBytes") - explore.get("parentRequestBytes");
            assertThat(saved).isPositive(); results.put("B_parentBytesSaved", saved);
            results.put("C_micro", new CopilotContextPlannerTest().compareMicro());
            f.seed(); f.planningBaselinePersistsConflictsAndRefreshesOnlyAfterSuccessfulRead();
            var conflict = f.evalTotals(); assertThat(conflict.recoveredRevisionConflicts()).isZero(); // The stale test was not retried.
            f.seed(); f.usageReadsDoNotRecountCallsAndPublishedSummaryWaitsForNewReportedContext();
            f.seed(); f.steerInvalidatesOldApprovalAndPairsUnstartedToolsBeforeReplanning(ModelProtocol.OPENAI_CHAT, false);
            var steer = f.evalTotals(); assertThat(steer.steerInterruptions()).isOne();
            f.seed(); int writes = f.evalAskRetry();
            results.put("D_recovery", Map.of("recoveredRevisionConflicts", conflict.recoveredRevisionConflicts(),
                "steerInterruptions", steer.steerInterruptions(), "askRounds", f.evalTotals().rounds(), "revisedWritesApplied", writes));
        } finally { f.eraseOwnedDatabase(); }
        var json = new ObjectMapper().writerWithDefaultPrettyPrinter();
        Files.writeString(Path.of("target/copilot-eval-report.json"), json.writeValueAsString(results));
        var md = new StringBuilder("# DETERMINISTIC_SCRIPTED_BASELINE\n\nScripted driver; real runner, tool executor and PostgreSQL. A includes database and scheduling time, not provider latency. B compares only final parent message bytes. C compares planner projections.\n\n");
        results.forEach((key, value) -> md.append("- ").append(key).append(": ").append(value).append('\n'));
        Files.writeString(Path.of("target/copilot-eval-report.md"), md);
    }
}
