package com.getjobs.application.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.getjobs.worker.boss.BossExecutionGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static com.getjobs.application.chat.ChatContracts.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FollowUpIdentityGateTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private BossChatStore store;
    private BossAiWorkflowService workflow;
    private BossChatBrowserGateway browser;
    private final AtomicLong mockNow = new AtomicLong(System.currentTimeMillis() + 200 * 3600_000L);

    @BeforeEach
    void setup() {
        store = new BossChatStore(new JdbcTemplate(new SingleConnectionDataSource("jdbc:sqlite::memory:", true)), mapper);
        store.init();
        workflow = mock(BossAiWorkflowService.class);
        browser = mock(BossChatBrowserGateway.class);

        Profile profile = new Profile(
                "v1", 2026, List.of("北京"), List.of(new Fact("f1", "经历真实")),
                "asset", false, false, true,
                Resume.empty(), "", "", "", "", List.of(), true, new FollowUp(true, 24, 3)
        );
        store.saveProfile(profile);
    }

    @Test
    void readOnlyGuardThrowsAndNeverSends() {
        BossExecutionGuard readOnlyGuard = new BossExecutionGuard(true);
        BossFollowUpService service = new BossFollowUpService(store, workflow, browser, readOnlyGuard, mockNow::get);

        long sentTime = mockNow.get() - (30 * 3600_000L);
        store.save(new Suggestion("s1", 1, "c1", "m1", "v1", "sent", "初次沟通", Intent.OTHER, Action.SUGGEST_REPLY, "", List.of(), "初次沟通", "", List.of("f1"), sentTime));

        assertThrows(IllegalStateException.class, () -> service.send("c1"));
        verify(browser, never()).send(any(), any(), anyString());
    }

    @Test
    void browserStaleBlocksWithoutIncrementingCount() throws Exception {
        when(browser.send(any(), any(), anyString())).thenReturn(SendResult.STALE);
        BossFollowUpService service = new BossFollowUpService(store, workflow, browser, null, mockNow::get);

        long sentTime = mockNow.get() - (30 * 3600_000L);
        store.save(new Suggestion("s1", 1, "c1", "m1", "v1", "sent", "初次沟通", Intent.OTHER, Action.SUGGEST_REPLY, "", List.of(), "初次沟通", "", List.of("f1"), sentTime));

        when(workflow.generateFollowUp(any(), eq("c1"), any())).thenReturn(
                new Decision("2", "req-1", Intent.OTHER, Action.AUTO_REPLY, "跟进内容", "", List.of("f1"), List.of(), "FOLLOW_UP", "")
        );

        Map<String, Object> resp = service.send("c1");
        assertEquals("blocked", resp.get("state"));
        assertTrue(((String) resp.get("reasonCode")).contains("IDENTITY_NOT_BOUND"));
        assertEquals(0, resp.get("followUpCount"));
        assertEquals(0, store.followUpCount("c1"));
    }

    @Test
    void unknownSendBlocksCandidateWithUnverifiedSend() {
        BossFollowUpService service = new BossFollowUpService(store, workflow, browser, null, mockNow::get);

        long sentTime = mockNow.get() - (30 * 3600_000L);
        store.save(new Suggestion("s1", 1, "c1", "m1", "v1", "unknown", "初次沟通", Intent.OTHER, Action.SUGGEST_REPLY, "", List.of(), "初次沟通", "", List.of("f1"), sentTime));

        List<Map<String, Object>> candidates = service.candidates();
        assertEquals("UNVERIFIED_SEND", candidates.getFirst().get("reasonCode"));
        assertThrows(IllegalStateException.class, () -> service.send("c1"));
    }

    @Test
    void newInboundUnhandledAndRefusedSemantics() {
        BossFollowUpService service = new BossFollowUpService(store, workflow, browser, null, mockNow::get);

        // 1. Inbound after outbound -> NEW_INBOUND_UNHANDLED
        long sentTime1 = System.currentTimeMillis() - 100_000L;
        store.save(new Suggestion("s1", 1, "c1", "m1", "v1", "sent", "初次沟通", Intent.OTHER, Action.SUGGEST_REPLY, "", List.of(), "初次沟通", "", List.of("f1"), sentTime1));

        Inbound newInbound = new Inbound("c1", "m2", "您好，我们已经收到", "职位", "JD", List.of(new Message("m2", "HR", "您好，我们已经收到")));
        store.ingest(newInbound);

        Map<String, Object> c1Cand = service.candidates().stream().filter(c -> "c1".equals(c.get("conversationId"))).findFirst().orElseThrow();
        assertEquals("NEW_INBOUND_UNHANDLED", c1Cand.get("reasonCode"));

        // 2. Refused semantics: "感谢您的投递，但很遗憾岗位暂不匹配"
        Inbound refusedInbound = new Inbound("c2", "m3", "感谢您的关注，很遗憾目前不匹配", "职位", "JD", List.of(new Message("m3", "HR", "感谢您的关注，很遗憾目前不匹配")));
        store.ingest(refusedInbound);

        // Make outbound later than inbound so it's not NEW_INBOUND_UNHANDLED, but text is refused
        long outboundLater = System.currentTimeMillis() + 50_000L;
        store.save(new Suggestion("s2", 1, "c2", "m4", "v1", "sent", "打扰了", Intent.OTHER, Action.SUGGEST_REPLY, "", List.of(), "打扰了", "", List.of("f1"), outboundLater));
        mockNow.set(outboundLater + 30 * 3600_000L);

        Map<String, Object> c2Cand = service.candidates().stream().filter(c -> "c2".equals(c.get("conversationId"))).findFirst().orElseThrow();
        assertEquals("REFUSED", c2Cand.get("reasonCode"));
    }
}
