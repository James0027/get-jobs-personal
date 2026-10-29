package com.getjobs.application.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.getjobs.application.chat.ChatContracts.*;
import com.getjobs.application.service.AiService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.util.List;

import static com.getjobs.application.chat.ChatContracts.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class AutoReplyHardGateTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private BossChatStore store;
    private AiService ai;
    private BossChatBrowserGateway browser;
    private BossAiWorkflowService workflow;

    @BeforeEach
    void setUp() {
        store = new BossChatStore(new JdbcTemplate(new SingleConnectionDataSource("jdbc:sqlite::memory:", true)), mapper);
        store.init();
        ai = mock(AiService.class);
        browser = mock(BossChatBrowserGateway.class);
        workflow = new BossAiWorkflowService(store, ai, new ChatDecisionValidator(mapper), browser, mapper);
    }

    private Inbound inbound(String convId, String msgId, String text) {
        return new Inbound(convId, msgId, text, "产品经理", "JD", List.of(new Message(msgId, "HR", text)));
    }

    @Test
    void enabledTrueAutoReplyFalseBlocksWithAutoReplyOffAndNeverSends() {
        Profile profile = new Profile(
                "v1", 2026, List.of("北京"), List.of(new Fact("f1", "熟悉需求管理")),
                "asset", false, true, true,
                Resume.empty(), "", "", "", "", List.of(), false, FollowUp.defaults()
        );
        store.saveProfile(profile);

        when(ai.sendRequest(anyString())).thenAnswer(call -> {
            String prompt = call.getArgument(0);
            String data = prompt.substring(prompt.indexOf("输入数据：") + "输入数据：".length()).trim();
            String reqId = mapper.readTree(data).get("requestId").asText();
            return mapper.writeValueAsString(new Decision("2", reqId, Intent.OTHER, Action.AUTO_REPLY, "熟悉需求管理。", "", List.of("f1"), List.of(), "ANSWER", ""));
        });

        // First message to register watermark
        workflow.observe(inbound("c1", "m0", "你好"));

        // Second message
        Suggestion s = workflow.observe(inbound("c1", "m1", "请问你的背景？"));
        assertEquals("blocked", s.state());
        assertEquals("AUTO_REPLY_OFF", s.reasonCode());
        verify(browser, never()).send(any(), any(), anyString());
    }

    @Test
    void raceConditionRereadsProfileBeforeSendAndBlocksIfAutoReplyTurnedOff() {
        Profile initial = new Profile(
                "v1", 2026, List.of("北京"), List.of(new Fact("f1", "熟悉需求管理")),
                "asset", false, true, true,
                Resume.empty(), "", "", "", "", List.of(), true, FollowUp.defaults()
        );
        store.saveProfile(initial);

        // First inbound to set watermark
        workflow.observe(inbound("c2", "m0", "你好"));

        // During AI call, another thread/user toggles autoReply off
        when(ai.sendRequest(anyString())).thenAnswer(call -> {
            Profile turnedOff = new Profile(
                    "v1", 2026, List.of("北京"), List.of(new Fact("f1", "熟悉需求管理")),
                    "asset", false, true, true,
                    Resume.empty(), "", "", "", "", List.of(), false, FollowUp.defaults()
            );
            store.saveProfile(turnedOff);

            String prompt = call.getArgument(0);
            String data = prompt.substring(prompt.indexOf("输入数据：") + "输入数据：".length()).trim();
            String reqId = mapper.readTree(data).get("requestId").asText();
            return mapper.writeValueAsString(new Decision("2", reqId, Intent.OTHER, Action.AUTO_REPLY, "熟悉需求管理。", "", List.of("f1"), List.of(), "ANSWER", ""));
        });

        Suggestion s = workflow.observe(inbound("c2", "m1", "请问你的背景？"));
        assertTrue(java.util.Set.of("blocked", "stale").contains(s.state()), s.state());
        assertTrue("AUTO_REPLY_OFF".equals(s.reasonCode()) || "stale".equals(s.state()));
        verify(browser, never()).send(any(), any(), anyString());
    }

    @Test
    void legacyPendingApprovalRowConfirmThrowsAndNeverSends() {
        Profile profile = new Profile(
                "v1", 2026, List.of("北京"), List.of(new Fact("f1", "熟悉需求管理")),
                "asset", false, true, true,
                Resume.empty(), "", "", "", "", List.of(), true, FollowUp.defaults()
        );
        store.saveProfile(profile);

        Suggestion legacy = new Suggestion(
                "legacy-1", 1, "c3", "m1", "v1", "pending_approval", "回复内容",
                Intent.OTHER, Action.SUGGEST_REPLY, "ANSWER", List.of(),
                "请问何时到岗？", "", List.of("f1"), System.currentTimeMillis()
        );
        store.save(legacy);

        assertThrows(IllegalStateException.class, () -> workflow.confirm(legacy.id(), new Confirmation(1, "m1", "明天")));
        verify(browser, never()).send(any(), any(), anyString());
    }
}
