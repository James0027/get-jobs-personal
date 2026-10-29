package com.getjobs.application.chat;

import com.getjobs.worker.boss.BossExecutionGuard;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.function.LongSupplier;

import static com.getjobs.application.chat.ChatContracts.*;

@Service
public class BossFollowUpService {
    private final BossChatStore store;
    private final BossAiWorkflowService workflow;
    private final BossChatBrowserGateway browser;
    private final BossExecutionGuard guard;
    private final LongSupplier nowSupplier;

    public BossFollowUpService(BossChatStore store, BossAiWorkflowService workflow, BossChatBrowserGateway browser) {
        this(store, workflow, browser, new BossExecutionGuard(false), System::currentTimeMillis);
    }

    @Autowired
    public BossFollowUpService(BossChatStore store, BossAiWorkflowService workflow, BossChatBrowserGateway browser, BossExecutionGuard guard) {
        this(store, workflow, browser, guard, System::currentTimeMillis);
    }

    public BossFollowUpService(BossChatStore store, BossAiWorkflowService workflow, BossChatBrowserGateway browser, BossExecutionGuard guard, LongSupplier nowSupplier) {
        this.store = store;
        this.workflow = workflow;
        this.browser = browser;
        this.guard = guard != null ? guard : new BossExecutionGuard(false);
        this.nowSupplier = nowSupplier != null ? nowSupplier : System::currentTimeMillis;
    }

    public List<Map<String, Object>> candidates() {
        Profile profile = store.profile();
        long now = nowSupplier.getAsLong();
        Set<String> convIds = store.allFollowUpCandidateConversationIds();
        List<Map<String, Object>> list = new ArrayList<>();
        for (String convId : convIds) {
            Map<String, Object> candidate = evaluateCandidate(convId, profile, now);
            list.add(candidate);
        }
        return list;
    }

    private Map<String, Object> evaluateCandidate(String conversationId, Profile profile, long now) {
        Long lastInboundAt = store.latestInboundAt(conversationId);
        Long lastOutboundAt = store.latestOutboundAt(conversationId);
        int followUpCount = store.followUpCount(conversationId);
        int followUpClaimCount = store.followUpClaimCount(conversationId);
        int used = Math.max(followUpCount, followUpClaimCount);
        boolean eligible;
        String reasonCode;

        if (lastInboundAt == null && lastOutboundAt == null) {
            eligible = false;
            reasonCode = "NO_TRUSTED_TIME";
        } else if (lastOutboundAt == null) {
            eligible = false;
            reasonCode = "NO_OUTBOUND";
        } else if (lastInboundAt != null && lastInboundAt > lastOutboundAt) {
            eligible = false;
            reasonCode = "NEW_INBOUND_UNHANDLED";
        } else if (store.hasUnsettledSend(conversationId)) {
            eligible = false;
            reasonCode = "UNVERIFIED_SEND";
        } else if (isRefused(store.latestInboundText(conversationId))) {
            eligible = false;
            reasonCode = "REFUSED";
        } else if (profile.followUp() != null && used >= profile.followUp().maxPerConversation()) {
            eligible = false;
            reasonCode = "LIMIT_REACHED";
        } else {
            long cooldownHours = profile.followUp() != null && profile.followUp().cooldownHours() != null ? profile.followUp().cooldownHours() : 168;
            if (now - lastOutboundAt < cooldownHours * 3600_000L) {
                eligible = false;
                reasonCode = "COOLDOWN";
            } else {
                eligible = true;
                reasonCode = "";
            }
        }

        Map<String, Object> map = new LinkedHashMap<>();
        map.put("conversationId", conversationId);
        map.put("lastInboundAt", lastInboundAt);
        map.put("lastOutboundAt", lastOutboundAt);
        map.put("followUpCount", followUpCount);
        map.put("eligible", eligible);
        map.put("reasonCode", reasonCode);
        return map;
    }

    public int autoRun() {
        Profile profile = store.profile();
        if (!profile.autoReply() || profile.followUp() == null || !profile.followUp().enabled()) {
            return 0;
        }
        int sent = 0;
        for (Map<String, Object> candidate : candidates()) {
            if (!Boolean.TRUE.equals(candidate.get("eligible"))) {
                continue;
            }
            String convId = (String) candidate.get("conversationId");
            try {
                Map<String, Object> res = sendInternal(convId, profile);
                if ("sent".equals(res.get("state"))) {
                    sent++;
                }
            } catch (Exception ignored) {
                // 任何单个会话异常不得中断循环（catch 后继续），不得重试
            }
        }
        return sent;
    }

    public Map<String, Object> send(String conversationId) {
        Profile profile = store.profile();
        return sendInternal(conversationId, profile);
    }

    private Map<String, Object> sendInternal(String conversationId, Profile profile) {
        if (!profile.autoReply() || profile.followUp() == null || !profile.followUp().enabled()) {
            throw new IllegalStateException("未开启自动回复或跟进");
        }
        guard.assertCanSendChat();

        long now = nowSupplier.getAsLong();
        Map<String, Object> evaluation = evaluateCandidate(conversationId, profile, now);
        if (!Boolean.TRUE.equals(evaluation.get("eligible"))) {
            throw new IllegalStateException((String) evaluation.get("reasonCode"));
        }

        Inbound lastInbound = store.latestInbound(conversationId);
        Decision decision;
        try {
            decision = workflow.generateFollowUp(profile, conversationId, lastInbound);
        } catch (Exception e) {
            throw new IllegalStateException("生成跟进建议失败: " + e.getMessage(), e);
        }

        int count = store.followUpCount(conversationId);
        if (decision == null || (decision.action() != Action.AUTO_REPLY && decision.action() != Action.SUGGEST_REPLY)
                || !decision.missingFacts().isEmpty() || decision.evidenceFactIds().isEmpty()
                || decision.replyText() == null || decision.replyText().isBlank()
                || decision.replyText().codePointCount(0, decision.replyText().length()) > 300) {
            String blockedReason = (decision != null && !decision.missingFacts().isEmpty()) ? "NEEDS_INFORMATION" : "OUT_OF_POLICY";
            Suggestion blocked = new Suggestion(
                    UUID.randomUUID().toString(), 1, conversationId,
                    lastInbound != null ? lastInbound.lastInboundMessageId() : "inbound:none",
                    profile.version(), "blocked", "",
                    decision != null ? decision.intent() : Intent.UNKNOWN,
                    decision != null ? decision.action() : Action.NO_ACTION,
                    blockedReason,
                    decision != null ? decision.missingFacts() : List.of(),
                    lastInbound != null ? lastInbound.sourceText() : "",
                    decision != null ? decision.resumeAssetId() : "",
                    decision != null ? decision.evidenceFactIds() : List.of(),
                    now
            );
            store.save(blocked);
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("state", "blocked");
            resp.put("reasonCode", blockedReason);
            resp.put("followUpCount", count);
            return resp;
        }

        String approvedText = decision.replyText().trim();
        String sid = UUID.randomUUID().toString();
        Suggestion claim = new Suggestion(
                sid, 1, conversationId,
                "",
                profile.version(), "sending", approvedText, decision.intent(), decision.action(),
                "", decision.missingFacts(),
                lastInbound != null ? lastInbound.sourceText() : "",
                decision.resumeAssetId(), decision.evidenceFactIds(), now
        );

        Suggestion claimed = store.claimFollowUp(conversationId, claim);
        if (claimed == null) {
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("state", "blocked");
            resp.put("reasonCode", "DUPLICATE_CLAIM");
            resp.put("followUpCount", count);
            return resp;
        }

        sid = claimed.id();
        SendResult result;
        try {
            result = browser.send(claimed, profile, approvedText);
        } catch (Exception e) {
            result = SendResult.UNKNOWN;
        }

        int newCount = count;
        String finalState;
        String finalReason = "";
        switch (result) {
            case SENT -> {
                store.recordFollowUpSend(conversationId, now);
                newCount = count + 1;
                finalState = "sent";
                store.transition(sid, "sending", "sent");
            }
            case FAILED -> {
                finalState = "failed";
                store.transition(sid, "sending", "failed");
            }
            case STALE -> {
                finalState = "blocked";
                finalReason = "IDENTITY_NOT_BOUND";
                store.transition(sid, "sending", "failed");
            }
            case UNKNOWN -> {
                finalState = "unknown";
                store.transition(sid, "sending", "unknown");
            }
            default -> {
                finalState = "unknown";
                store.transition(sid, "sending", "unknown");
            }
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("state", finalState);
        resp.put("reasonCode", finalReason);
        resp.put("followUpCount", newCount);
        return resp;
    }

    private boolean isRefused(String text) {
        if (text == null) return false;
        boolean hasRefusal = text.contains("不") || text.contains("感谢") || text.contains("但") ||
                text.contains("遗憾") || text.contains("需要本") || text.contains("对不");
        boolean hasException = text.contains("不是") || text.contains("不生");
        return hasRefusal && !hasException;
    }
}
