package com.getjobs.application.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.getjobs.application.service.AiService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.util.List;

import static com.getjobs.application.chat.ChatContracts.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class AutoReplySwitchTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private BossChatStore store;
    private AiService ai;
    private BossChatBrowserGateway browser;
    private BossAiWorkflowService workflow;

    @BeforeEach
    void setup() {
        store = new BossChatStore(new JdbcTemplate(new SingleConnectionDataSource("jdbc:sqlite::memory:", true)), mapper);
        store.init();
        ai = mock(AiService.class);
        browser = mock(BossChatBrowserGateway.class);
        when(browser.currentResumeAssetId()).thenReturn("asset");
        when(browser.send(any(), any(), anyString())).thenReturn(SendResult.SENT);
        workflow = new BossAiWorkflowService(store, ai, new ChatDecisionValidator(mapper), browser, mapper);
    }

    private Inbound inbound(String convId, String msgId, String text) {
        return new Inbound(convId, msgId, text, "产品经理", "JD", List.of(new Message(msgId, "HR", text)));
    }

    @Test
    void switchControlsAutoSendAndNeverProducesPendingApproval() {
        // 1. autoReply=false, enabled=true, autoLocation=true
        Profile p1 = new Profile(
                "v1", 2026, List.of("北京"), List.of(new Fact("f1", "熟悉需求管理")),
                "asset", false, true, true,
                Resume.empty(), "", "", "", "", List.of(), false, FollowUp.defaults()
        );
        store.saveProfile(p1);

        when(ai.sendRequest(anyString())).thenAnswer(call -> {
            String prompt = call.getArgument(0);
            String data = prompt.substring(prompt.indexOf("输入数据：") + "输入数据：".length()).trim();
            String reqId = mapper.readTree(data).get("requestId").asText();
            return mapper.writeValueAsString(new Decision("2", reqId, Intent.OTHER, Action.AUTO_REPLY, "我熟悉敏捷与需求管理。", "", List.of("f1"), List.of(), "ANSWER", ""));
        });

        // First message
        Suggestion s1 = workflow.observe(inbound("c1", "m1", "请问你的专业背景？"));
        assertNotNull(s1);
        assertEquals("blocked", s1.state());
        assertEquals("AUTO_REPLY_OFF", s1.reasonCode());
        verify(browser, never()).send(any(), any(), anyString());

        // 2. autoReply=true
        Profile p2 = new Profile(
                "v2", 2026, List.of("北京"), List.of(new Fact("f1", "熟悉需求管理")),
                "asset", false, true, true,
                Resume.empty(), "", "", "", "", List.of(), true, FollowUp.defaults()
        );
        store.saveProfile(p2);

        // Under new profile: establish observation then send
        workflow.observe(inbound("c2", "m2_1", "你好"));
        Suggestion s2 = workflow.observe(inbound("c2", "m2_2", "请问你的专业背景？"));
        assertNotNull(s2);
        assertEquals("sent", s2.state());
        verify(browser, times(1)).send(any(), any(), eq("我熟悉敏捷与需求管理。"));

        // Verify that neither suggestion ever produced pending_approval in DB
        for (Suggestion item : store.suggestions()) {
            assertNotEquals("pending_approval", item.state());
        }
    }
}
