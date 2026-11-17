package ai.codecraft.modules.copilot.service;

/** Model instructions cannot grant workspace authority. */
final class CopilotToolPolicy {
    private CopilotToolPolicy() {}
    static boolean allows(String lane, String tool) {
        var entry = CopilotToolCatalog.find(tool);
        return entry != null && entry.lanes().contains(lane);
    }
}
