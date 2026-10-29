package com.getjobs.worker.boss.delivery;

import com.getjobs.application.service.ConfigService;
import com.getjobs.worker.boss.*;
import com.getjobs.worker.manager.PlaywrightManager;
import com.getjobs.worker.service.BossJobService;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class BossDeliveryLifecycleTest {

    @Test
    void changedConfigurationCannotResumeRunWithOldQuota() {
        var manager = mock(PlaywrightManager.class);
        var configs = mock(ConfigService.class);
        @SuppressWarnings("unchecked") ObjectProvider<Boss> provider = mock(ObjectProvider.class);
        var store = mock(DeliveryTaskStore.class);
        var original = new BossConfig();
        original.setCityCode(List.of("A"));
        original.setKeywords(List.of("产品"));
        original.setTargetCount(50);
        var run = new DeliveryRun(50, original.getCityCode(), null, DeliveryConfiguration.fingerprint(original));
        run.finish(true);
        when(store.latest()).thenReturn(run.snapshot());
        var changed = new BossConfig();
        changed.setCityCode(List.of("B"));
        changed.setKeywords(List.of("研发"));
        changed.setTargetCount(10);
        when(configs.getBossConfig()).thenReturn(changed);
        when(manager.getBossPage()).thenReturn(mock(Page.class));
        when(manager.isLoggedIn("boss")).thenReturn(true);

        var jobs = new BossJobService(manager, configs, provider, store);
        List<String> messages = new ArrayList<>();
        jobs.resumeDelivery(message -> messages.add(message.getMessage()));
        assertTrue(messages.stream().anyMatch(text -> text.contains("配置已变更")));
        verifyNoInteractions(provider);
        verify(store, never()).save(any());
        assertFalse(jobs.isRunning());
    }

    @Test
    void stopDoesNotUnlockWhileBrowserTaskIsStillAlive() throws Exception {
        var manager = mock(PlaywrightManager.class);
        var configs = mock(ConfigService.class);
        @SuppressWarnings("unchecked") ObjectProvider<Boss> provider = mock(ObjectProvider.class);
        var store = mock(DeliveryTaskStore.class);
        var boss = mock(Boss.class);
        var config = new BossConfig();
        config.setCityCode(List.of("A"));
        config.setTargetCount(50);
        when(configs.getBossConfig()).thenReturn(config);
        when(store.latest()).thenReturn(Map.of());
        when(provider.getObject()).thenReturn(boss);
        when(manager.getBossPage()).thenReturn(mock(Page.class));
        when(manager.isLoggedIn("boss")).thenReturn(true);
        when(manager.callOnPlaywright(any())).thenAnswer(i -> ((Callable<?>) i.getArgument(0)).call());
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(boss.execute()).thenAnswer(i -> {
            entered.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return 0;
        });
        var jobs = new BossJobService(manager, configs, provider, store);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var running = executor.submit(() -> jobs.startNewDelivery(message -> {}));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            jobs.stopDelivery();
            assertTrue(jobs.isRunning());
            jobs.startNewDelivery(message -> {});
            verify(provider, times(1)).getObject();
            release.countDown();
            running.get(5, TimeUnit.SECONDS);
            assertFalse(jobs.isRunning());
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void startNewDeliveryCreatesIndependentTaskIdAndAcceptsChangedConfig() throws Exception {
        var manager = mock(PlaywrightManager.class);
        var configs = mock(ConfigService.class);
        @SuppressWarnings("unchecked") ObjectProvider<Boss> provider = mock(ObjectProvider.class);
        var store = mock(DeliveryTaskStore.class);
        var boss = mock(Boss.class);
        var original = new BossConfig();
        original.setCityCode(List.of("A"));
        original.setKeywords(List.of("产品"));
        original.setTargetCount(50);
        var run = new DeliveryRun(50, original.getCityCode(), null, DeliveryConfiguration.fingerprint(original));
        run.finish(true);
        when(store.latest()).thenReturn(run.snapshot());
        var changed = new BossConfig();
        changed.setCityCode(List.of("B"));
        changed.setKeywords(List.of("研发"));
        changed.setTargetCount(20);
        when(configs.getBossConfig()).thenReturn(changed);
        when(provider.getObject()).thenReturn(boss);
        when(manager.getBossPage()).thenReturn(mock(Page.class));
        when(manager.isLoggedIn("boss")).thenReturn(true);
        when(manager.callOnPlaywright(any())).thenAnswer(i -> ((Callable<?>) i.getArgument(0)).call());

        var jobs = new BossJobService(manager, configs, provider, store);
        List<String> messages = new ArrayList<>();
        jobs.startNewDelivery(message -> messages.add(message.getMessage()));

        verify(boss).prepare();
        verify(boss).execute();
        var captor = org.mockito.ArgumentCaptor.forClass(DeliveryRun.class);
        verify(boss).setDeliveryRun(captor.capture());
        assertNotEquals(run.snapshot().get("taskId"), captor.getValue().snapshot().get("taskId"));
        assertEquals(20, captor.getValue().snapshot().get("targetCount"));
        assertEquals(0, captor.getValue().successCount());
        assertFalse(jobs.isRunning());
    }

    @Test
    void cannotStartNewWhenUnknownCountIsPositive() {
        var manager = mock(PlaywrightManager.class);
        var configs = mock(ConfigService.class);
        @SuppressWarnings("unchecked") ObjectProvider<Boss> provider = mock(ObjectProvider.class);
        var store = mock(DeliveryTaskStore.class);
        when(store.latest()).thenReturn(Map.of("taskId", "t1", "unknownCount", 1, "state", "awaiting_reconciliation"));
        var jobs = new BossJobService(manager, configs, provider, store);
        List<String> messages = new ArrayList<>();
        jobs.startNewDelivery(message -> messages.add(message.getMessage()));
        assertTrue(messages.stream().anyMatch(text -> text.contains("待核实")));
        verifyNoInteractions(provider);
        assertFalse(jobs.isRunning());
    }

    @Test
    void resumeDeliveryPreservesOriginalTaskIdAndRemainingCount() throws Exception {
        var manager = mock(PlaywrightManager.class);
        var configs = mock(ConfigService.class);
        @SuppressWarnings("unchecked") ObjectProvider<Boss> provider = mock(ObjectProvider.class);
        var store = mock(DeliveryTaskStore.class);
        var boss = mock(Boss.class);
        var config = new BossConfig();
        config.setCityCode(List.of("A"));
        config.setKeywords(List.of("产品"));
        config.setTargetCount(50);
        var run = new DeliveryRun(50, config.getCityCode(), null, DeliveryConfiguration.fingerprint(config));
        run.enterCity("A");
        run.claim("j1");
        run.sent("j1", false);
        run.finish(true);
        when(store.latest()).thenReturn(run.snapshot());
        when(configs.getBossConfig()).thenReturn(config);
        when(provider.getObject()).thenReturn(boss);
        when(manager.getBossPage()).thenReturn(mock(Page.class));
        when(manager.isLoggedIn("boss")).thenReturn(true);
        when(manager.callOnPlaywright(any())).thenAnswer(i -> ((Callable<?>) i.getArgument(0)).call());

        var jobs = new BossJobService(manager, configs, provider, store);
        List<String> messages = new ArrayList<>();
        jobs.resumeDelivery(message -> messages.add(message.getMessage()));

        verify(boss).prepare();
        verify(boss).execute();
        var captor = org.mockito.ArgumentCaptor.forClass(DeliveryRun.class);
        verify(boss).setDeliveryRun(captor.capture());
        assertEquals(run.snapshot().get("taskId"), captor.getValue().snapshot().get("taskId"));
        assertEquals(1, captor.getValue().successCount());
        assertEquals(50, captor.getValue().snapshot().get("targetCount"));
        assertFalse(jobs.isRunning());
    }

    @Test
    void resumeDeliveryRejectsChangedConfig() {
        var manager = mock(PlaywrightManager.class);
        var configs = mock(ConfigService.class);
        @SuppressWarnings("unchecked") ObjectProvider<Boss> provider = mock(ObjectProvider.class);
        var store = mock(DeliveryTaskStore.class);
        var original = new BossConfig();
        original.setCityCode(List.of("A"));
        original.setKeywords(List.of("产品"));
        original.setTargetCount(50);
        var run = new DeliveryRun(50, original.getCityCode(), null, DeliveryConfiguration.fingerprint(original));
        run.finish(true);
        when(store.latest()).thenReturn(run.snapshot());
        var changed = new BossConfig();
        changed.setCityCode(List.of("B"));
        changed.setKeywords(List.of("研发"));
        changed.setTargetCount(10);
        when(configs.getBossConfig()).thenReturn(changed);
        when(manager.getBossPage()).thenReturn(mock(Page.class));
        when(manager.isLoggedIn("boss")).thenReturn(true);

        var jobs = new BossJobService(manager, configs, provider, store);
        List<String> messages = new ArrayList<>();
        jobs.resumeDelivery(message -> messages.add(message.getMessage()));
        assertTrue(messages.stream().anyMatch(text -> text.contains("配置已变更")));
        verifyNoInteractions(provider);
        assertFalse(jobs.isRunning());
    }

    // ========== B5 补齐测试项 ==========

    @Test
    void resumeDeliveryRejectsWhenStoreIsEmpty() {
        var manager = mock(PlaywrightManager.class);
        var configs = mock(ConfigService.class);
        @SuppressWarnings("unchecked") ObjectProvider<Boss> provider = mock(ObjectProvider.class);
        var store = mock(DeliveryTaskStore.class);
        when(store.latest()).thenReturn(Map.of());

        var jobs = new BossJobService(manager, configs, provider, store);
        assertThrows(IllegalArgumentException.class, () -> jobs.reserveDelivery(true));
        assertFalse(jobs.isRunning());
    }

    @Test
    void newDeliverySucceedsWhenStoreIsEmpty() {
        var manager = mock(PlaywrightManager.class);
        var configs = mock(ConfigService.class);
        @SuppressWarnings("unchecked") ObjectProvider<Boss> provider = mock(ObjectProvider.class);
        var store = mock(DeliveryTaskStore.class);
        when(store.latest()).thenReturn(Map.of());
        var config = new BossConfig();
        config.setTargetCount(30);
        config.setCityCode(List.of("101010100"));
        when(configs.getBossConfig()).thenReturn(config);

        var jobs = new BossJobService(manager, configs, provider, store);
        var reservation = jobs.reserveDelivery(false);

        assertNotNull(reservation.taskId());
        assertFalse(reservation.resume());
        assertTrue(jobs.isRunning());
        verify(store, times(1)).save(any());
    }

    @Test
    void legacyExecuteDeliverySemanticsIsAlwaysNewNotResume() throws Exception {
        var manager = mock(PlaywrightManager.class);
        var configs = mock(ConfigService.class);
        @SuppressWarnings("unchecked") ObjectProvider<Boss> provider = mock(ObjectProvider.class);
        var store = mock(DeliveryTaskStore.class);
        var boss = mock(Boss.class);

        var oldConfig = new BossConfig();
        oldConfig.setCityCode(List.of("A"));
        oldConfig.setTargetCount(100);
        var oldRun = new DeliveryRun(100, oldConfig.getCityCode(), null, DeliveryConfiguration.fingerprint(oldConfig));
        oldRun.finish(true);
        when(store.latest()).thenReturn(oldRun.snapshot());

        var newConfig = new BossConfig();
        newConfig.setCityCode(List.of("B"));
        newConfig.setTargetCount(20);
        when(configs.getBossConfig()).thenReturn(newConfig);
        when(provider.getObject()).thenReturn(boss);
        when(manager.getBossPage()).thenReturn(mock(Page.class));
        when(manager.isLoggedIn("boss")).thenReturn(true);
        when(manager.callOnPlaywright(any())).thenAnswer(i -> ((Callable<?>) i.getArgument(0)).call());

        var jobs = new BossJobService(manager, configs, provider, store);
        // 调用旧兼容接口 executeDelivery，验证其不会隐式续跑旧任务，而是新建并分配新 ID
        jobs.executeDelivery(msg -> {});

        var captor = org.mockito.ArgumentCaptor.forClass(DeliveryRun.class);
        verify(boss).setDeliveryRun(captor.capture());
        assertNotEquals(oldRun.snapshot().get("taskId"), captor.getValue().snapshot().get("taskId"));
        assertEquals(20, captor.getValue().snapshot().get("targetCount"));
    }

    @Test
    void concurrentReservationOnlyOneWins() throws Exception {
        var manager = mock(PlaywrightManager.class);
        var configs = mock(ConfigService.class);
        @SuppressWarnings("unchecked") ObjectProvider<Boss> provider = mock(ObjectProvider.class);
        var store = mock(DeliveryTaskStore.class);
        when(store.latest()).thenReturn(Map.of());
        var config = new BossConfig();
        config.setTargetCount(50);
        config.setCityCode(List.of("101010100"));
        when(configs.getBossConfig()).thenReturn(config);

        var jobs = new BossJobService(manager, configs, provider, store);
        int threads = 4;
        var startLatch = new CountDownLatch(1);
        var doneLatch = new CountDownLatch(threads);
        var successCount = new AtomicInteger(0);
        var rejectCount = new AtomicInteger(0);

        var pool = Executors.newFixedThreadPool(threads);
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        startLatch.await();
                        jobs.reserveDelivery(false);
                        successCount.incrementAndGet();
                    } catch (IllegalStateException e) {
                        rejectCount.incrementAndGet();
                    } catch (Exception ignored) {
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }
            startLatch.countDown();
            assertTrue(doneLatch.await(5, TimeUnit.SECONDS));

            assertEquals(1, successCount.get(), "并发预留只允许一个成功");
            assertEquals(threads - 1, rejectCount.get(), "并发预留其余必须被拒绝");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void abortReservationReleasesLockAndMarksTaskFailed() {
        var manager = mock(PlaywrightManager.class);
        var configs = mock(ConfigService.class);
        @SuppressWarnings("unchecked") ObjectProvider<Boss> provider = mock(ObjectProvider.class);
        var store = mock(DeliveryTaskStore.class);
        when(store.latest()).thenReturn(Map.of());
        var config = new BossConfig();
        config.setTargetCount(50);
        config.setCityCode(List.of("101010100"));
        when(configs.getBossConfig()).thenReturn(config);

        var jobs = new BossJobService(manager, configs, provider, store);
        var reservation = jobs.reserveDelivery(false);
        assertTrue(jobs.isRunning());

        // 模拟调度异常，调用 abortReservation 释放预留
        jobs.abortReservation(reservation, "调度线程池满");

        assertFalse(jobs.isRunning(), "预留释放后任务状态应为未运行");
        // 释放后应允许再次发起新的预留
        var secondReservation = jobs.reserveDelivery(false);
        assertNotNull(secondReservation);
        assertNotEquals(reservation.taskId(), secondReservation.taskId());
    }

    @Test
    void readOnlyCheckModeBlocksDeliveryReservation() {
        var manager = mock(PlaywrightManager.class);
        var configs = mock(ConfigService.class);
        @SuppressWarnings("unchecked") ObjectProvider<Boss> provider = mock(ObjectProvider.class);
        var store = mock(DeliveryTaskStore.class);
        var guard = new BossExecutionGuard(true); // 开启只读检查模式

        var jobs = new BossJobService(manager, configs, provider, store, guard);
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> jobs.reserveDelivery(false));
        assertTrue(e.getMessage().contains("只读检查模式"));
        assertFalse(jobs.isRunning());
    }
}
