package ai.codecraft.modules.copilot.service;

import java.util.Set;

/** One policy for advertised schemas and actual dispatch; model instructions cannot grant workspace authority. */
final class CopilotToolPolicy {
    private static final Set<String> READ_ONLY = Set.of("view_file", "glob_files", "grep_code", "read_evidence", "read_source", "load_skill", "web_search", "web_read");
    private CopilotToolPolicy() {}
    static boolean allows(String lane, String tool) {
        return "MAIN".equals(lane) || "CHILD".equals(lane) && !"delegate_task".equals(tool)
            || "BTW".equals(lane) && READ_ONLY.contains(tool);
    }
}
