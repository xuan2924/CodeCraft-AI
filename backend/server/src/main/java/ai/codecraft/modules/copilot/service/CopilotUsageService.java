package ai.codecraft.modules.copilot.service;

import ai.codecraft.modules.copilot.model.dto.CopilotUsageContextResponse;
import ai.codecraft.modules.copilot.model.dto.CopilotUsageResponse;
import ai.codecraft.modules.copilot.repository.CopilotCompactionRepository;
import ai.codecraft.modules.copilot.repository.CopilotUsageRepository;
import ai.codecraft.modules.identity.model.exception.IdentityErrorCode;
import ai.codecraft.modules.identity.model.exception.IdentityFailure;
import ai.codecraft.modules.identity.service.IdentityTransactionService;
import ai.codecraft.modules.workspace.service.ExerciseTransactionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CopilotUsageService {
    private final CopilotUsageRepository usage;
    private final CopilotCompactionRepository contexts;
    private final IdentityTransactionService identity;
    private final ExerciseTransactionService exercises;
    private final CopilotConversationStore conversations;
    private final ai.codecraft.modules.modelaccess.service.ProxyAuditService proxy;

    public CopilotUsageService(CopilotUsageRepository usage, CopilotCompactionRepository contexts,
        IdentityTransactionService identity, ExerciseTransactionService exercises, CopilotConversationStore conversations,
        ai.codecraft.modules.modelaccess.service.ProxyAuditService proxy) {
        this.proxy = proxy;
        this.conversations = conversations;
        this.usage = usage; this.contexts = contexts; this.identity = identity; this.exercises = exercises;
    }

    /** Private delivery serializes against revocation using the owner row lock; no model dispatch or bookkeeping writes. */
    @Transactional
    public CopilotUsageResponse currentInConversation(String session, String exercise, String conversation) {
        var grant = identity.authorizePrivateDelivery(session);
        if (!"USER".equals(grant.role())) throw new IdentityFailure(IdentityErrorCode.FORBIDDEN);
        exercises.find(session, exercise);
        String scope = conversations.resolve(session, exercise, conversation);
        var head = contexts.head(scope);
        var latest = usage.latest(scope);
        long version = head == null ? 0 : head.version();
        boolean waiting = version > 0 && (latest == null || latest.contextVersion() == null || version > latest.contextVersion());
        String input = waiting || latest == null ? null : latest.inputTokens();
        var context = new CopilotUsageContextResponse(waiting ? "WAITING_UPDATE" : input == null ? "UNKNOWN" : "REPORTED",
            latest == null ? null : latest.turnId(), latest == null ? null : latest.modelId(), latest == null ? null : latest.contextWindow(),
            latest == null ? null : "USER_CONFIGURATION", input, latest == null ? null : latest.dispatchedAt());
        var totals = new java.util.ArrayList<>(usage.totals(exercise));
        for (var cli : proxy.totals(exercise)) {
            var chat = totals.stream().filter(row -> row.purpose().equals("CHAT")).findFirst().orElse(null);
            totals.remove(chat);
            totals.add(combine(chat, cli));
        }
        var costs = new java.util.ArrayList<>(usage.costs(exercise));
        for (var cli : proxy.costs(exercise)) {
            var chat = costs.stream().filter(row -> row.purpose().equals("CHAT") && row.currency().equals(cli.currency())).findFirst().orElse(null);
            costs.remove(chat);
            costs.add(new ai.codecraft.modules.modelaccess.model.dto.ModelCostSubtotalResponse("CHAT", cli.currency(),
                chat == null ? cli.amount() : new java.math.BigDecimal(chat.amount()).add(new java.math.BigDecimal(cli.amount())).toPlainString(),
                sum(chat == null ? null : chat.pricedCalls(), cli.pricedCalls())));
        }
        return new CopilotUsageResponse(exercise, context, totals, costs);
    }
    private static ai.codecraft.modules.modelaccess.model.dto.ModelUsageTotalsResponse combine(
        ai.codecraft.modules.modelaccess.model.dto.ModelUsageTotalsResponse a, ai.codecraft.modules.modelaccess.model.dto.ModelUsageTotalsResponse b) {
        if (a == null) return new ai.codecraft.modules.modelaccess.model.dto.ModelUsageTotalsResponse("CHAT", b.calls(), b.completeCalls(), b.inputTokens(),
            b.ordinaryInputTokens(), b.cacheReadTokens(), b.cacheWriteTokens(), b.outputTokens(), b.totalTokens(), b.pricedCalls());
        return new ai.codecraft.modules.modelaccess.model.dto.ModelUsageTotalsResponse("CHAT", sum(a.calls(), b.calls()), sum(a.completeCalls(), b.completeCalls()),
            sum(a.inputTokens(), b.inputTokens()), sum(a.ordinaryInputTokens(), b.ordinaryInputTokens()), sum(a.cacheReadTokens(), b.cacheReadTokens()),
            sum(a.cacheWriteTokens(), b.cacheWriteTokens()), sum(a.outputTokens(), b.outputTokens()), sum(a.totalTokens(), b.totalTokens()), sum(a.pricedCalls(), b.pricedCalls()));
    }
    private static String sum(String a, String b) {
        return a == null ? b : b == null ? a : new java.math.BigInteger(a).add(new java.math.BigInteger(b)).toString();
    }
}
