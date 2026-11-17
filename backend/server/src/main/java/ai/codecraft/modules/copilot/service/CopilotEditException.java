package ai.codecraft.modules.copilot.service;

/** A safe diagnostic for a model-correctable exact-edit mismatch, without echoing source contents. */
final class CopilotEditException extends RuntimeException {
    private final String code;
    private final String path;
    private final int lineCount;
    CopilotEditException(String code, String path, int lineCount) {
        super(code);
        this.code = code; this.path = path; this.lineCount = lineCount;
    }
    String code() { return code; }
    String path() { return path; }
    int lineCount() { return lineCount; }
}
