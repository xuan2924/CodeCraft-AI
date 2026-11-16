package ai.codecraft.modules.workspace.service;

import ai.codecraft.modules.copilot.service.CopilotConversationService;
import ai.codecraft.modules.copilot.service.CopilotTaskService;
import ai.codecraft.modules.workspace.model.dto.ReportEvidenceDTO;
import ai.codecraft.modules.workspace.model.entity.ExerciseReportEntity;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
        var paths = frozen.paths().stream().sorted().toList();
        for (String path : paths.stream().limit(16).toList()) {
            var file = exercises.read(session, exercise, path);
            if (!file.revision().equals(frozen.revision())) throw new IllegalStateException("Frozen file revision changed");
            evidence.add(item("file:" + frozen.revision() + ":" + path, "FILE", path, "冻结修订 " + frozen.revision() + " 的文件 " + path, file.content(), 4096));
        }
        var changes = history.recent(session, exercise, Long.MAX_VALUE).changes();
        for (var change : changes.stream().limit(12).toList()) {
            evidence.add(item("change:" + change.id(), "CHANGE", change.id(), "代码变更 " + change.baseRevision() + " → " + change.resultRevision() + "，来源 " + change.source(), reports.encode(change), 2048));
        }
        int messageCount = 0;
        var lanes = conversations.list(session, exercise);
        for (var conversation : lanes.stream().limit(4).toList()) {
            var messages = copilot.messagesInConversation(session, exercise, -1, conversation.id()).messages();
            for (var message : messages.stream().filter(value -> !"TOOL".equals(value.kind())).limit(6).toList()) {
                evidence.add(item("message:" + message.id(), "COPILOT_MESSAGE", conversation.id() + "/" + message.id(),
                    "Copilot " + message.kind() + " 消息（序号 " + message.sequence() + "）", message.text(), 1024));
                messageCount++;
            }
        }
        for (var question : interviewer.history(session, exercise, null).stream().limit(6).toList()) {
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
