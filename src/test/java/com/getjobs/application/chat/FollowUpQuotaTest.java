package com.getjobs.application.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
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

class FollowUpQuotaTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private BossChatStore store;
    private BossAiWorkflowService workflow;
    private BossChatBrowserGateway browser;
    private final AtomicLong mockNow = new AtomicLong(1_000_000_000_000L);

    @BeforeEach
    void setup() {
        store = new BossChatStore(new JdbcTemplate(new SingleConnectionDataSource("jdbc:sqlite::memory:", true)), mapper);
        store.init();
        workflow = mock(BossAiWorkflowService.class);
        browser = mock(BossChatBrowserGateway.class);
        when(browser.send(any(), any(), anyString())).thenReturn(SendResult.SENT);
    }

    private void saveProfile(boolean autoReply, boolean followUpEnabled, int cooldownHours, int maxPerConv) {
        Profile p = new Profile(
                "v1", 2026, List.of("北京"), List.of(new Fact("f1", "经历真实")),
                "asset", false, false, true,
                Resume.empty(), "", "", "", "", List.of(), autoReply, new FollowUp(followUpEnabled, cooldownHours, maxPerConv)
        );
        store.saveProfile(p);
    }

    @Test
    void outboundOnlyCooldownAndEligible() {
        saveProfile(true, true, 24, 2);
        BossFollowUpService service = new BossFollowUpService(store, workflow, browser, null, mockNow::get);

        long sentTime = mockNow.get() - (10 * 3600_000L); // 10h ago
        Suggestion s = new Suggestion("s1", 1, "c1", "m1", "v1", "sent", "你好", Intent.OTHER, Action.SUGGEST_REPLY, "", List.of(), "你好", "", List.of("f1"), sentTime);
        store.save(s);

        // 10h ago < 24h -> COOLDOWN
        List<Map<String, Object>> candidates = service.candidates();
        assertEquals(1, candidates.size());
        assertEquals("COOLDOWN", candidates.getFirst().get("reasonCode"));
        assertFalse((Boolean) candidates.getFirst().get("eligible"));

        // Advance clock to 25h later
        mockNow.addAndGet(15 * 3600_000L);
        candidates = service.candidates();
        assertTrue((Boolean) candidates.getFirst().get("eligible"));
        assertEquals("", candidates.getFirst().get("reasonCode"));
    }

    @Test
    void disabledSwitchesThrowIllegalStateAndNeverSend() {
        // autoReply=false
        saveProfile(false, true, 24, 2);
        BossFollowUpService service = new BossFollowUpService(store, workflow, browser, null, mockNow::get);
        assertThrows(IllegalStateException.class, () -> service.send("c1"));
        verify(browser, never()).send(any(), any(), anyString());

        // followUp.enabled=false
        saveProfile(true, false, 24, 2);
        assertThrows(IllegalStateException.class, () -> service.send("c1"));
        verify(browser, never()).send(any(), any(), anyString());
    }

    @Test
    void limitReachedRejectsAndSuccessfulSendIncrements() throws Exception {
        saveProfile(true, true, 24, 1);
        BossFollowUpService service = new BossFollowUpService(store, workflow, browser, null, mockNow::get);

        long sentTime = mockNow.get() - (30 * 3600_000L); // 30h ago (past cooldown)
        Suggestion prior = new Suggestion("s1", 1, "c1", "m1", "v1", "sent", "初次沟通", Intent.OTHER, Action.SUGGEST_REPLY, "", List.of(), "初次沟通", "", List.of("f1"), sentTime);
        store.save(prior);

        when(workflow.generateFollowUp(any(), eq("c1"), any())).thenReturn(
                new Decision("2", "req-1", Intent.OTHER, Action.AUTO_REPLY, "请问关于该职位是否有进一步反馈？", "", List.of("f1"), List.of(), "FOLLOW_UP", "")
        );

        // 1st follow-up: successful
        Map<String, Object> resp = service.send("c1");
        assertEquals("sent", resp.get("state"));
        assertEquals(1, resp.get("followUpCount"));
        assertEquals(1, store.followUpCount("c1"));
        assertEquals(1, store.followUpClaimCount("c1"));
        assertEquals("followup:1", store.records(List.of("sent"), 1).getFirst().lastInboundMessageId());
        verify(browser, times(1)).send(any(), any(), anyString());

        // 2nd follow-up immediately: in cooldown and limit reached (max=1)
        assertThrows(IllegalStateException.class, () -> service.send("c1"));

        // Even advance 100h: limit reached (max(count, claims) 1 >= max 1)
        mockNow.addAndGet(100 * 3600_000L);
        List<Map<String, Object>> candidates = service.candidates();
        assertEquals("LIMIT_REACHED", candidates.getFirst().get("reasonCode"));
        assertThrows(IllegalStateException.class, () -> service.send("c1"));
    }

    @Test
    void limitReachedAccountsForClaimsWhenCountIsLower() {
        saveProfile(true, true, 24, 1);
        BossFollowUpService service = new BossFollowUpService(store, workflow, browser, null, mockNow::get);

        long sentTime = mockNow.get() - (30 * 3600_000L);
        Suggestion prior = new Suggestion("s0", 1, "c1", "m0", "v1", "sent", "初次沟通", Intent.OTHER, Action.SUGGEST_REPLY, "", List.of(), "初次沟通", "", List.of("f1"), sentTime);
        store.save(prior);

        // Pre-existing claim that failed (claims=1, count=0)
        Suggestion claim = new Suggestion("s-claim", 1, "c1", "", "v1", "sending", "跟进", Intent.OTHER, Action.AUTO_REPLY, "", List.of(), "", "", List.of("f1"), sentTime);
        store.claimFollowUp("c1", claim);
        store.transition("s-claim", "sending", "failed");

        assertEquals(0, store.followUpCount("c1"));
        assertEquals(1, store.followUpClaimCount("c1"));

        List<Map<String, Object>> candidates = service.candidates();
        assertEquals("LIMIT_REACHED", candidates.getFirst().get("reasonCode"));
        assertThrows(IllegalStateException.class, () -> service.send("c1"));
    }
}
