package com.getjobs.application.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.getjobs.application.chat.ChatContracts.*;
import com.getjobs.worker.boss.BossExecutionGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FollowUpAutoRunTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private BossChatStore store;
    private BossAiWorkflowService workflow;
    private BossChatBrowserGateway browser;
    private final AtomicLong mockNow = new AtomicLong(System.currentTimeMillis() + 200 * 3600_000L);

    @BeforeEach
    void setUp() {
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
    void autoRunSendsEligibleCandidateOnceAndRespectsCooldownAndAutoReplySwitch() throws Exception {
        when(browser.send(any(), any(), anyString())).thenReturn(SendResult.SENT);
        when(workflow.generateFollowUp(any(), eq("c1"), any())).thenReturn(
                new Decision("2", "req1", Intent.OTHER, Action.AUTO_REPLY, "您好，想跟进一下沟通进展。", "", List.of("f1"), List.of(), "FOLLOW_UP", "")
        );

        BossFollowUpService service = new BossFollowUpService(store, workflow, browser, new BossExecutionGuard(false), mockNow::get);

        // 构造一个 eligible 候选（我方 sent 记录时间早于冷却：30 小时前）
        long sentTime = mockNow.get() - (30 * 3600_000L);
        store.save(new Suggestion("s-prev", 1, "c1", "m1", "v1", "sent", "初次沟通", Intent.OTHER, Action.SUGGEST_REPLY, "", List.of(), "初次沟通", "", List.of("f1"), sentTime));

        // 第 1 次 autoRun() 发送 1 条，followUpCount 变为 1，状态 sent
        int count1 = service.autoRun();
        assertEquals(1, count1);
        assertEquals(1, store.followUpCount("c1"));
        verify(browser, times(1)).send(any(), any(), eq("您好，想跟进一下沟通进展。"));

        // 再调 autoRun() → 0 条（触发冷却门闸）
        int count2 = service.autoRun();
        assertEquals(0, count2);
        verify(browser, times(1)).send(any(), any(), anyString());

        // autoReply=false 时返回 0 条
        Profile turnedOff = new Profile(
                "v2", 2026, List.of("北京"), List.of(new Fact("f1", "经历真实")),
                "asset", false, false, true,
                Resume.empty(), "", "", "", "", List.of(), false, new FollowUp(true, 24, 3)
        );
        store.saveProfile(turnedOff);
        int count3 = service.autoRun();
        assertEquals(0, count3);
    }

    @Test
    void monitorTickTriggersAutoRunOnlyWhenAutoReplyIsTrue() {
        BossFollowUpService mockFollowUp = mock(BossFollowUpService.class);
        when(mockFollowUp.autoRun()).thenReturn(1);

        BossChatMonitor monitor = new BossChatMonitor(store, browser, workflow, new BossExecutionGuard(false), mockFollowUp);

        // autoReply=true 触发一次 autoRun
        monitor.tick();
        verify(mockFollowUp, times(1)).autoRun();

        // autoReply=false 不触发 autoRun
        Profile turnedOff = new Profile(
                "v2", 2026, List.of("北京"), List.of(new Fact("f1", "经历真实")),
                "asset", false, false, true,
                Resume.empty(), "", "", "", "", List.of(), false, new FollowUp(true, 24, 3)
        );
        store.saveProfile(turnedOff);

        monitor.tick();
        verify(mockFollowUp, times(1)).autoRun(); // 仍为 1 次，本次未触发
    }
}
