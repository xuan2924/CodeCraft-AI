package ai.codecraft.modules.workspace.service;

import ai.codecraft.modules.copilot.model.dto.CopilotMessageResponse;
import ai.codecraft.modules.copilot.service.CopilotConversationService;
import ai.codecraft.modules.copilot.service.CopilotTaskService;
import ai.codecraft.modules.workspace.model.dto.ReportEvidenceDTO;
import ai.codecraft.modules.workspace.model.dto.ReportTimelineItemDTO;
import ai.codecraft.modules.workspace.model.entity.ExerciseReportEntity;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Only reads immutable/frozen sources; no file save, tool replay or model invocation. */
@Service
public class ReportEvidenceCollector {
    private final ExerciseService exercises;
    private final TestTaskStore tests;
    private final WorkspaceHistoryService history;
    private final CopilotConversationService conversations;
    private final CopilotTaskService copilot;
    private final InterviewerStore interviewer;
    private final ExerciseReportStore reports;
    private final ExerciseRequirementsService requirements;
    public ReportEvidenceCollector(ExerciseService exercises, TestTaskStore tests, WorkspaceHistoryService history,
        CopilotConversationService conversations, CopilotTaskService copilot, InterviewerStore interviewer, ExerciseReportStore reports, ExerciseRequirementsService requirements) {
        this.exercises = exercises; this.tests = tests; this.history = history; this.conversations = conversations;
        this.copilot = copilot; this.interviewer = interviewer; this.reports = reports; this.requirements = requirements;
    }
    static boolean sourceFile(String path) {
        return path != null && (path.endsWith(".java") && ProblemLanguage.JAVA_MAVEN.editable(path)
            || path.endsWith(".py") && ProblemLanguage.PYTHON_PYTEST.editable(path));
    }

    @Transactional(propagation = Propagation.NEVER)
    public List<ReportEvidenceDTO> collect(ExerciseReportEntity report) {
        if (report.evidence() != null) return reports.evidence(report);
        String session = report.sessionHash();
        String exercise = report.exerciseId();
        var frozen = exercises.find(session, exercise);
        if (!"ENDED".equals(frozen.state()) || !Long.toString(report.finalRevision()).equals(frozen.revision())) throw new IllegalStateException("Frozen evidence identity changed");
        List<ReportEvidenceDTO> evidence = new ArrayList<>();
        evidence.add(item("requirements", "REQUIREMENTS", exercise, "当前阶段公开需求", requirements.published(exercise), 16384));
        if (report.finalTestId() != null) {
            var result = tests.query(session, exercise, report.finalTestId());
            if (!result.revision().equals(frozen.revision()) || !"UNAVAILABLE".equals(report.verification()) && !result.state().equals(report.verification())) throw new IllegalStateException("Final test evidence changed");
            Map<String, Object> facts = new LinkedHashMap<>();
            facts.put("taskId", result.taskId()); facts.put("state", result.state()); facts.put("reason", result.reason()); facts.put("revision", result.revision());
            facts.put("target", result.target()); facts.put("problem", result.problem()); facts.put("evidence", result.evidence());
            evidence.add(new ReportEvidenceDTO("test:" + result.taskId(), "FINAL_TEST", result.taskId(),
                "最终验收 " + result.state() + "，冻结修订 " + result.revision() + "，目标 " + result.target(), reports.encode(facts), false));
        }
        var trace = reports.view(session, exercise);
        evidence.add(item("timeline", "TIMELINE", exercise, "保留的客观演练时间轴（按时间排序）",
            timelineContent(trace.timeline()), 8192));
        evidence.add(item("context-trace", "CONTEXT_TRACE", exercise, "客观上下文使用记录，不代表质量或扣分依据",
            reports.encode(trace.contextTrace()), 8192));
        evidence.add(new ReportEvidenceDTO("trace-policy", "SCOPE", exercise, "上下文证据解释边界",
            "Timeline samples at most 64 retained events; pruned test history may be absent. Legacy reference counts default to zero. "
                + "Use objective evidence for COLLABORATION and VALIDATION. Never mechanically penalize proposal rejection rate, AI usage volume or hint counts. "
                + "Clarify/defense and hint usage count only successful published answers; steer counts applied corrections; skill counts successful loads.", true));
        var paths = frozen.paths().stream().filter(ReportEvidenceCollector::sourceFile).sorted().toList();
        for (String path : sourceSample(paths)) {
            var file = exercises.read(session, exercise, path);
            if (!file.revision().equals(frozen.revision())) throw new IllegalStateException("Frozen file revision changed");
            evidence.add(item("file:" + frozen.revision() + ":" + path, "FILE", path, "冻结修订 " + frozen.revision() + " 的文件 " + path, file.content(), 4096));
        }
        var changes = history.recent(session, exercise, Long.MAX_VALUE).changes();
        for (var change : sample(changes, 12)) {
            evidence.add(item("change:" + change.id(), "CHANGE", change.id(), "代码变更 " + change.baseRevision() + " → " + change.resultRevision() + "，来源 " + change.source(), reports.encode(change), 2048));
        }
        int messageCount = 0;
        var lanes = conversations.list(session, exercise);
        for (var conversation : sample(lanes, 4)) {
            var messages = new TreeMap<Long, CopilotMessageResponse>();
            for (long cursor : List.of(0L, -1L)) {
                for (var message : copilot.messagesInConversation(session, exercise, cursor, conversation.id()).messages()) {
                    if (!"TOOL".equals(message.kind())) messages.put(Long.parseLong(message.sequence()), message);
                }
            }
            var ordered = List.copyOf(messages.values());
            int tail = Math.max(0, ordered.size() - 3);
            var selected = Stream.concat(sample(ordered.subList(0, tail), 3).stream(), ordered.subList(tail, ordered.size()).stream());
            for (var message : selected.toList()) {
                evidence.add(item("message:" + message.id(), "COPILOT_MESSAGE", conversation.id() + "/" + message.id(),
                    "Copilot " + message.kind() + " 消息（序号 " + message.sequence() + "）", message.text(), 1024));
                messageCount++;
            }
        }
        for (var question : sample(interviewer.history(session, exercise, null), 6)) {
            evidence.add(item("interviewer:" + question.id(), "INTERVIEWER_MESSAGE", question.id(), "面试官问答：" + question.state(),
                "用户提问：" + question.question() + "\n面试官：" + (question.answer() == null ? "未完成" : question.answer()), 2048));
        }
        if (!report.reviewSkipped()) evidence.add(item("review", "SELF_REVIEW", report.id(), "用户回顾自述（不可替代工程事实）", report.reviewText(), 4096));
        evidence.add(new ReportEvidenceDTO("scope", "SCOPE", report.id(), "有界取证，不代表全部历史均被模型阅读。",
            "Frozen files " + Math.min(paths.size(), 16) + "/" + paths.size() + "; retained changes sampled " + Math.min(changes.size(), 12)
                + "; conversations sampled " + Math.min(lanes.size(), 4) + "/" + lanes.size() + "; messages sampled " + messageCount
                + ". File/message bodies may be truncated as marked. Skipped review: " + report.reviewSkipped() + ". Do not infer missing behavior or penalize skipped review.", true));
        var after = exercises.find(session, exercise);
        if (!after.workspaceInstanceId().equals(frozen.workspaceInstanceId()) || !after.revision().equals(frozen.revision())) throw new IllegalStateException("Frozen evidence scope changed");
        return List.copyOf(evidence);
    }
    // Preserve the endpoints and spread the remaining slots across the available window.
    private static <T> List<T> sample(List<T> values, int limit) {
        if (values.size() <= limit) return values;
        if (limit == 1) return List.of(values.getLast());
        return IntStream.range(0, limit)
            .mapToObj(i -> values.get((int) ((long) i * (values.size() - 1) / (limit - 1)))).toList();
    }

    private static List<String> sourceSample(List<String> paths) {
        var groups = paths.stream().collect(Collectors.partitioningBy(path ->
            path.startsWith("src/test/") || path.startsWith("tests/")));
        int testSlots = Math.min(groups.get(true).size(), Math.max(8, 16 - groups.get(false).size()));
        return Stream.concat(sample(groups.get(false), 16 - testSlots).stream(),
            sample(groups.get(true), testSlots).stream()).toList();
    }

    private String timelineContent(List<ReportTimelineItemDTO> events) {
        // Reserve the opening, last saved change, last validation and closing event before filling gaps.
        var anchors = new TreeSet<Integer>();
        if (!events.isEmpty()) { anchors.add(0); anchors.add(events.size() - 1); }
        for (String kind : List.of("WORKSPACE_CHANGED", "TEST_COMPLETED")) {
            for (int i = events.size() - 1; i >= 0; i--) {
                if (kind.equals(events.get(i).kind())) { anchors.add(i); break; }
            }
        }
        int firstFailure = -1, firstPass = -1;
        for (int i = 0; i < events.size(); i++) {
            var event = events.get(i);
            if (!"TEST_COMPLETED".equals(event.kind()) || event.detail() == null) continue;
            if (firstFailure < 0 && event.detail().endsWith(" · FAILED")) anchors.add(firstFailure = i);
            if (event.detail().endsWith(" · PASSED")) {
                if (firstPass < 0) anchors.add(firstPass = i);
                if (firstFailure >= 0) { anchors.add(i); break; }
            }
        }
        for (int budget = 64; ; budget /= 2) {
            var indices = new TreeSet<>(anchors);
            int slots = events.size() <= budget ? events.size() : budget - anchors.size();
            for (int i = 0; i < slots; i++) indices.add((int) ((long) i * (events.size() - 1) / Math.max(1, slots - 1)));
            String content = reports.encode(indices.stream().map(events::get).toList());
            if (content.getBytes(StandardCharsets.UTF_8).length <= 8192 || budget <= 4) return content;
        }
    }

    private ReportEvidenceDTO item(String id, String kind, String source, String summary, String text, int maximum) {
        String content = text == null ? "" : text;
        boolean truncated = content.getBytes(StandardCharsets.UTF_8).length > maximum;
        if (truncated) {
            int end = 0;
            int bytes = 0;
            while (end < content.length()) {
                int point = content.codePointAt(end);
                int size = point <= 0x7f ? 1 : point <= 0x7ff ? 2 : point <= 0xffff ? 3 : 4;
                if (bytes + size > maximum) break;
                bytes += size; end += Character.charCount(point);
            }
            content = content.substring(0, end);
        }
        return new ReportEvidenceDTO(id, kind, source, summary, content, truncated);
    }
}
