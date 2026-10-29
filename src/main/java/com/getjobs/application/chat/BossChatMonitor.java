package com.getjobs.application.chat;

import com.getjobs.worker.boss.BossExecutionGuard;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.getjobs.application.chat.ChatContracts.*;

/** Only enabled, logged-in sessions are observed. No offline/24-hour service promise. */
@Component
public class BossChatMonitor {
    private final BossChatStore store;
    private final BossChatBrowserGateway browser;
    private final BossAiWorkflowService workflow;
    private final BossExecutionGuard guard;
    private final BossFollowUpService followUpService;
    private final AtomicBoolean scanning = new AtomicBoolean();
    private volatile String error = "";

    public BossChatMonitor(BossChatStore store, BossChatBrowserGateway browser, BossAiWorkflowService workflow) {
        this(store, browser, workflow, new BossExecutionGuard(false), null);
    }

    public BossChatMonitor(BossChatStore store, BossChatBrowserGateway browser, BossAiWorkflowService workflow, BossExecutionGuard guard) {
        this(store, browser, workflow, guard, null);
    }

    public BossChatMonitor(BossChatStore store, BossChatBrowserGateway browser, BossAiWorkflowService workflow, BossFollowUpService followUpService) {
        this(store, browser, workflow, new BossExecutionGuard(false), followUpService);
    }

    @Autowired
    public BossChatMonitor(BossChatStore store, BossChatBrowserGateway browser, BossAiWorkflowService workflow, BossExecutionGuard guard, @Autowired(required = false) BossFollowUpService followUpService) {
        this.store = store;
        this.browser = browser;
        this.workflow = workflow;
        this.guard = guard != null ? guard : new BossExecutionGuard(false);
        this.followUpService = followUpService;
    }

    public BossExecutionGuard getGuard() {
        return guard;
    }

    @Scheduled(fixedDelay = 30000, initialDelay = 30000)
    public void tick() {
        if (guard.isReadOnlyCheckMode()) return;
        Profile profile = store.profile();
        if (profile.enabled() || profile.autoReply()) {
            try {
                scan();
            } catch (Exception e) {
                error = e.getMessage() == null ? "扫描失败" : e.getMessage();
            }
            if (profile.autoReply() && followUpService != null) {
                try {
                    followUpService.autoRun();
                } catch (Exception e) {
                    error = e.getMessage() == null ? "自动跟进失败" : e.getMessage();
                }
            }
        }
    }

    public Map<String, Object> scan() {
        if (guard.isReadOnlyCheckMode()) {
            return Map.of("scanned", 0, "note", "只读检查模式，已禁用手工扫描", "readOnlyCheckMode", true);
        }
        if (!store.profile().enabled() && !store.profile().autoReply()) return Map.of("scanned", 0, "note", "请先启用后续沟通");
        if (!scanning.compareAndSet(false, true)) return Map.of("scanned", 0, "note", "扫描进行中");
        try {
            int processed = 0;
            for (var in : browser.scan()) {
                if (workflow.observe(in) != null) processed++;
            }
            error = "";
            return Map.of("scanned", processed, "note", browser.lastScanNote(), "readOnlyCheckMode", guard.isReadOnlyCheckMode());
        } finally {
            scanning.set(false);
        }
    }

    public Map<String, Object> status() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("scanning", scanning.get());
        status.put("readOnlyCheckMode", guard.isReadOnlyCheckMode());
        status.put("note", browser.lastScanNote());
        status.put("error", error);
        status.put("metrics", workflow.metrics());
        return status;
    }
}
