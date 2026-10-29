package com.getjobs.application.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

import static com.getjobs.application.chat.ChatContracts.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FollowUpClaimConcurrencyTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private SingleConnectionDataSource dataSource;
    private JdbcTemplate jdbcTemplate;
    private BossChatStore store;
    private BossAiWorkflowService workflow;
    private BossChatBrowserGateway browser;
    private final AtomicLong mockNow = new AtomicLong(1_000_000_000_000L);

    @BeforeEach
    void setup() {
        dataSource = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        jdbcTemplate = new JdbcTemplate(dataSource);
        store = new BossChatStore(jdbcTemplate, mapper);
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
    void concurrentAutoRunAndSendClaimsOnceAndSendsOnlyOnce() throws Exception {
        saveProfile(true, true, 24, 2);
        long sentTime = mockNow.get() - (30 * 3600_000L);
        Suggestion prior = new Suggestion("s0", 1, "c1", "m0", "v1", "sent", "初次沟通", Intent.OTHER, Action.SUGGEST_REPLY, "", List.of(), "初次沟通", "", List.of("f1"), sentTime);
        store.save(prior);

        CountDownLatch workflowEntered = new CountDownLatch(2);
        CountDownLatch releaseWorkflow = new CountDownLatch(1);

        when(workflow.generateFollowUp(any(), eq("c1"), any())).thenAnswer(inv -> {
            workflowEntered.countDown();
            releaseWorkflow.await(5, TimeUnit.SECONDS);
            return new Decision("2", "req-1", Intent.OTHER, Action.AUTO_REPLY, "跟进内容", "", List.of("f1"), List.of(), "FOLLOW_UP", "");
        });

        BossFollowUpService service = new BossFollowUpService(store, workflow, browser, null, mockNow::get);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<Integer> autoRunFuture = executor.submit(service::autoRun);
        Future<Map<String, Object>> sendFuture = executor.submit(() -> service.send("c1"));

        assertTrue(workflowEntered.await(5, TimeUnit.SECONDS), "Both threads must enter workflow");
        releaseWorkflow.countDown();

        int autoRunSent = autoRunFuture.get(5, TimeUnit.SECONDS);
        Map<String, Object> sendResult = sendFuture.get(5, TimeUnit.SECONDS);
        executor.shutdown();

        // Total sends is exactly 1
        verify(browser, times(1)).send(any(), any(), anyString());
        assertEquals(1, store.followUpCount("c1"));
        assertEquals(1, store.followUpClaimCount("c1"));

        boolean autoRunWon = (autoRunSent == 1) && "blocked".equals(sendResult.get("state")) && "DUPLICATE_CLAIM".equals(sendResult.get("reasonCode"));
        boolean sendWon = (autoRunSent == 0) && "sent".equals(sendResult.get("state"));
        assertTrue(autoRunWon || sendWon, "Exactly one thread must win the claim; the other must be blocked by DUPLICATE_CLAIM or return 0");
    }

    @Test
    void claimMessageIdIsFollowUpRoundWithoutTimestamp() throws Exception {
        saveProfile(true, true, 24, 2);
        long sentTime = mockNow.get() - (30 * 3600_000L);
        Suggestion prior = new Suggestion("s0", 1, "c1", "m0", "v1", "sent", "初次沟通", Intent.OTHER, Action.SUGGEST_REPLY, "", List.of(), "初次沟通", "", List.of("f1"), sentTime);
        store.save(prior);

        when(workflow.generateFollowUp(any(), eq("c1"), any())).thenReturn(
                new Decision("2", "req-1", Intent.OTHER, Action.AUTO_REPLY, "跟进内容", "", List.of("f1"), List.of(), "FOLLOW_UP", "")
        );

        BossFollowUpService service = new BossFollowUpService(store, workflow, browser, null, mockNow::get);
        Map<String, Object> res = service.send("c1");
        assertEquals("sent", res.get("state"));

        var messageIds = jdbcTemplate.queryForList(
                "SELECT message_id FROM boss_chat_suggestion WHERE conversation_id='c1' AND message_id LIKE 'followup:%'",
                String.class);
        assertEquals(1, messageIds.size());
        assertEquals("followup:1", messageIds.getFirst());
        assertFalse(messageIds.getFirst().contains(String.valueOf(mockNow.get())));
        assertFalse(messageIds.getFirst().contains("-"));
    }

    @Test
    void restartingWithSendingClaimRecoversToUnknownAndBlocksSending() {
        saveProfile(true, true, 24, 2);
        long sentTime = mockNow.get() - (30 * 3600_000L);
        Suggestion prior = new Suggestion("s0", 1, "c1", "m0", "v1", "sent", "初次沟通", Intent.OTHER, Action.SUGGEST_REPLY, "", List.of(), "初次沟通", "", List.of("f1"), sentTime);
        store.save(prior);

        // 手工写入一条 state='sending' 的 followup:1 行
        jdbcTemplate.update(
                "INSERT INTO boss_chat_suggestion(id,conversation_id,message_id,state,payload) VALUES('s-crash','c1','followup:1','sending','{}')");

        // 新建 BossChatStore 并 init()（会把 sending 置 unknown）
        BossChatStore restartedStore = new BossChatStore(jdbcTemplate, mapper);
        restartedStore.init();

        BossFollowUpService service = new BossFollowUpService(restartedStore, workflow, browser, null, mockNow::get);

        int autoRunSent = service.autoRun();
        assertEquals(0, autoRunSent);

        assertThrows(IllegalStateException.class, () -> service.send("c1"));
        verify(browser, never()).send(any(), any(), anyString());
    }

    @Test
    void subsequentRoundsFollowUpUpToMaxPerConversation() throws Exception {
        saveProfile(true, true, 24, 2);
        long sentTime = mockNow.get() - (30 * 3600_000L);
        Suggestion prior = new Suggestion("s0", 1, "c1", "m0", "v1", "sent", "初次沟通", Intent.OTHER, Action.SUGGEST_REPLY, "", List.of(), "初次沟通", "", List.of("f1"), sentTime);
        store.save(prior);

        when(workflow.generateFollowUp(any(), eq("c1"), any())).thenReturn(
                new Decision("2", "req-1", Intent.OTHER, Action.AUTO_REPLY, "跟进内容", "", List.of("f1"), List.of(), "FOLLOW_UP", "")
        );

        BossFollowUpService service = new BossFollowUpService(store, workflow, browser, null, mockNow::get);

        // 第一轮成功（count=1）
        Map<String, Object> resp1 = service.send("c1");
        assertEquals("sent", resp1.get("state"));
        assertEquals(1, store.followUpCount("c1"));
        assertEquals(1, store.followUpClaimCount("c1"));

        var roundsAfter1 = jdbcTemplate.queryForList(
                "SELECT message_id FROM boss_chat_suggestion WHERE conversation_id='c1' AND message_id LIKE 'followup:%' ORDER BY message_id",
                String.class);
        assertEquals(List.of("followup:1"), roundsAfter1);

        // 把时钟推进到冷却之后
        mockNow.addAndGet(25 * 3600_000L);

        // 第二轮成功（claim followup:2、count=2）
        Map<String, Object> resp2 = service.send("c1");
        assertEquals("sent", resp2.get("state"));
        assertEquals(2, store.followUpCount("c1"));
        assertEquals(2, store.followUpClaimCount("c1"));

        var roundsAfter2 = jdbcTemplate.queryForList(
                "SELECT message_id FROM boss_chat_suggestion WHERE conversation_id='c1' AND message_id LIKE 'followup:%' ORDER BY message_id",
                String.class);
        assertEquals(List.of("followup:1", "followup:2"), roundsAfter2);

        // 把时钟推进到冷却之后
        mockNow.addAndGet(25 * 3600_000L);

        // 第三轮 LIMIT_REACHED 且 0 次发送
        List<Map<String, Object>> candidates = service.candidates();
        assertEquals("LIMIT_REACHED", candidates.getFirst().get("reasonCode"));
        assertThrows(IllegalStateException.class, () -> service.send("c1"));

        // 总 browser.send 次数恰为 2（第三轮未发）
        verify(browser, times(2)).send(any(), any(), anyString());
    }
}
