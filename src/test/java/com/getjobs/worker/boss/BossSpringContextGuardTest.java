package com.getjobs.worker.boss;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.getjobs.application.chat.*;
import com.getjobs.application.service.AiService;
import com.getjobs.application.service.BossResumeImageService;
import com.getjobs.application.service.ConfigService;
import com.getjobs.application.service.CookieService;
import com.getjobs.worker.boss.delivery.DeliveryTaskStore;
import com.getjobs.worker.manager.PlaywrightManager;
import com.getjobs.worker.service.BossJobService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * P1 验证：实际生产类在真实 Spring 容器中的依赖注入测试。
 * 1. 验证四个核心 Bean（BossJobService / BossChatBrowserGateway / BossAiWorkflowService / BossChatMonitor）
 *    均通过 @Autowired 正确注入容器内的单一共享 BossExecutionGuard Bean，杜绝 NoSuchMethodException 与 new(false) 旁路；
 * 2. 验证普通模式与只读检查模式在真实容器中均能正常装配实例化；
 * 3. 验证只读检查模式下外发动作（投递预留、聊天确认、手工扫描、网关外发）均被硬阻断；
 * 4. 仅装配目标核心 Bean 并 mock 浏览器/网络依赖，绝对排除 StartupRunner 与真实浏览器启动。
 */
class BossSpringContextGuardTest {

    @Configuration
    static class MockInfrastructureConfig {
        @Bean
        PlaywrightManager playwrightManager() {
            return mock(PlaywrightManager.class);
        }

        @Bean
        CookieService cookieService() {
            return mock(CookieService.class);
        }

        @Bean
        ConfigService configService() {
            ConfigService cs = mock(ConfigService.class);
            when(cs.getBossConfig()).thenReturn(new BossConfig());
            return cs;
        }

        @Bean
        Boss boss() {
            return mock(Boss.class);
        }

        @Bean
        DeliveryTaskStore deliveryTaskStore() {
            DeliveryTaskStore store = mock(DeliveryTaskStore.class);
            when(store.latest()).thenReturn(Map.of());
            return store;
        }

        @Bean
        BossResumeImageService bossResumeImageService() {
            BossResumeImageService service = mock(BossResumeImageService.class);
            when(service.getCurrentImagePath()).thenReturn(Optional.empty());
            return service;
        }

        @Bean
        BossChatStore bossChatStore() {
            BossChatStore store = mock(BossChatStore.class);
            when(store.profile()).thenReturn(new ChatContracts.Profile("v1", 2024, List.of(), List.of(), "", true, true, true));
            when(store.suggestions()).thenReturn(List.of());
            return store;
        }

        @Bean
        AiService aiService() {
            return mock(AiService.class);
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }

    @AfterEach
    void cleanup() {
        System.clearProperty("boss.readOnlyCheckMode");
        System.clearProperty("boss.read-only-check-mode");
    }

    private AnnotationConfigApplicationContext createContainer(Map<String, Object> properties) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        if (properties != null && !properties.isEmpty()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("testProps", properties));
        }
        // 注册真实生产组件与依赖 Mock 配置（完全排除 StartupRunner 与全应用启动）
        context.register(
                MockInfrastructureConfig.class,
                BossExecutionGuard.class,
                ChatDecisionValidator.class,
                BossJobService.class,
                BossChatBrowserGateway.class,
                BossAiWorkflowService.class,
                BossChatMonitor.class
        );
        context.refresh();
        return context;
    }

    @Test
    void testNormalModeSpringContainerWiring() {
        try (var context = createContainer(Map.of("boss.read-only-check-mode", "false"))) {
            BossExecutionGuard guard = context.getBean(BossExecutionGuard.class);
            BossJobService jobService = context.getBean(BossJobService.class);
            BossChatBrowserGateway gateway = context.getBean(BossChatBrowserGateway.class);
            BossAiWorkflowService workflow = context.getBean(BossAiWorkflowService.class);
            BossChatMonitor monitor = context.getBean(BossChatMonitor.class);

            assertNotNull(guard, "容器须成功提供 BossExecutionGuard");
            assertNotNull(jobService, "容器须成功提供 BossJobService");
            assertNotNull(gateway, "容器须成功提供 BossChatBrowserGateway");
            assertNotNull(workflow, "容器须成功提供 BossAiWorkflowService");
            assertNotNull(monitor, "容器须成功提供 BossChatMonitor");

            assertFalse(guard.isReadOnlyCheckMode(), "默认模式应为普通模式");

            // 核心验证：四个 Bean 必须共享容器中同一个 Guard 实例，绝非内部 new(false) 的孤立对象
            assertSame(guard, jobService.getGuard(), "BossJobService 必须注入容器内的共享 Guard");
            assertSame(guard, gateway.getGuard(), "BossChatBrowserGateway 必须注入容器内的共享 Guard");
            assertSame(guard, workflow.getGuard(), "BossAiWorkflowService 必须注入容器内的共享 Guard");
            assertSame(guard, monitor.getGuard(), "BossChatMonitor 必须注入容器内的共享 Guard");
        }
    }

    @Test
    void testReadOnlyModeSpringContainerWiringAndEnforcement() {
        try (var context = createContainer(Map.of("boss.read-only-check-mode", "true"))) {
            BossExecutionGuard guard = context.getBean(BossExecutionGuard.class);
            BossJobService jobService = context.getBean(BossJobService.class);
            BossChatBrowserGateway gateway = context.getBean(BossChatBrowserGateway.class);
            BossAiWorkflowService workflow = context.getBean(BossAiWorkflowService.class);
            BossChatMonitor monitor = context.getBean(BossChatMonitor.class);

            assertTrue(guard.isReadOnlyCheckMode(), "配置 boss.read-only-check-mode=true 时 Guard 须为只读模式");

            // 四个消费者共享容器内的只读守卫
            assertSame(guard, jobService.getGuard());
            assertSame(guard, gateway.getGuard());
            assertSame(guard, workflow.getGuard());
            assertSame(guard, monitor.getGuard());

            // 1. 验证投递预留入口被阻断
            IllegalStateException deliveryEx = assertThrows(IllegalStateException.class, () -> jobService.reserveDelivery(false));
            assertTrue(deliveryEx.getMessage().contains("只读检查模式"), "投递预留应被只读模式阻断");

            // 2. 旧建议确认入口已退役：任何模式下都不得发送（只读模式由 observe/网关层保证）
            var confirmation = new ChatContracts.Confirmation(1, "m1", "你好");
            IllegalStateException chatEx = assertThrows(IllegalStateException.class, () -> workflow.confirm("s1", confirmation));
            assertTrue(chatEx.getMessage().contains("LEGACY_SUGGESTIONS_RETIRED"), "旧建议确认入口须退役且不发送");

            // 3. 验证手工 scan 入口直接拒绝，不触发任何浏览器操作与 AI
            Map<String, Object> scanResult = monitor.scan();
            assertEquals(0, scanResult.get("scanned"));
            assertEquals(true, scanResult.get("readOnlyCheckMode"));
            assertTrue(scanResult.get("note").toString().contains("只读检查模式"));

            // 4. 验证网关底层外发被拦截
            var suggestion = new ChatContracts.Suggestion(
                    "s1", 1, "u1", "m1", "v1", "pending_approval",
                    "回复", ChatContracts.Intent.CONFIRM_LOCATION, ChatContracts.Action.CONFIRM_LOCATION,
                    "OK", List.of(), "在北京吗", "", List.of(), System.currentTimeMillis()
            );
            var profile = new ChatContracts.Profile("v1", 2024, List.of(), List.of(), "", true, true, true);
            ChatContracts.SendResult sendResult = gateway.send(suggestion, profile, "可以接受在某地工作");
            assertEquals(ChatContracts.SendResult.FAILED, sendResult, "网关层必须阻断外发");
        }
    }

    @Test
    void testSystemPropertyActivatesReadOnlyCheckModeInContainer() {
        System.setProperty("boss.readOnlyCheckMode", "true");
        try (var context = createContainer(Map.of())) {
            BossExecutionGuard guard = context.getBean(BossExecutionGuard.class);
            assertTrue(guard.isReadOnlyCheckMode(), "-Dboss.readOnlyCheckMode=true 必须使 Guard 激活只读检查模式");

            BossJobService jobService = context.getBean(BossJobService.class);
            assertSame(guard, jobService.getGuard());
            assertTrue(jobService.getGuard().isReadOnlyCheckMode());
        }
    }
}
