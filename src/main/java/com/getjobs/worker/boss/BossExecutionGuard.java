package com.getjobs.worker.boss;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * BOSS 平台执行守卫与总闸。
 * 支持启动期只读检查模式，强制关闭投递与聊天的一切外发入口，保证只读取证安全。
 */
@Component
public class BossExecutionGuard {
    private final boolean readOnlyCheckMode;

    public BossExecutionGuard(@Value("${boss.read-only-check-mode:false}") boolean configValue) {
        String env = System.getenv("BOSS_READ_ONLY_CHECK_MODE");
        String sysProp1 = System.getProperty("boss.readOnlyCheckMode");
        String sysProp2 = System.getProperty("boss.read-only-check-mode");
        this.readOnlyCheckMode = "true".equalsIgnoreCase(env)
                || "true".equalsIgnoreCase(sysProp1)
                || "true".equalsIgnoreCase(sysProp2)
                || configValue;
    }

    public boolean isReadOnlyCheckMode() {
        return readOnlyCheckMode;
    }

    public void assertCanDeliver() {
        if (readOnlyCheckMode) {
            throw new IllegalStateException("只读检查模式已生效，禁止启动投递外发任务");
        }
    }

    public void assertCanSendChat() {
        if (readOnlyCheckMode) {
            throw new IllegalStateException("只读检查模式已生效，禁止外发消息或简历附件");
        }
    }
}
