package com.getjobs.application.service;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LoginOnlyBrowserServiceLifecycleTest {

    @TempDir
    Path tempDir;

    private LoginOnlyBrowserService service;

    @BeforeEach
    void setUp() {
        service = new LoginOnlyBrowserService();
        service.setConfiguredLoginDir(tempDir.toString());
        service.setHeadless(true);
        service.setLoginUrlOverrides(Map.of("boss", "http://127.0.0.1:1/login",
                "liepin", "http://127.0.0.1:1/login"));
    }

    @AfterEach
    void tearDown() {
        if (service != null) {
            service.destroy();
        }
    }

    @Test
    @DisplayName("初始状态规范：not_started，tasksStarted与schedulerEnabled均为false")
    void initialStateIsCleanAndNotStarted() {
        Map<String, Object> status = service.status();
        assertEquals("", status.get("platform"));
        assertEquals("not_started", status.get("state"));
        assertEquals(false, status.get("initialized"));
        assertEquals(false, status.get("loggedIn"));
        assertEquals(false, status.get("tasksStarted"));
        assertEquals(false, status.get("schedulerEnabled"));
        assertFalse(service.isAlive());
    }

    @Test
    @DisplayName("不支持的平台抛出IllegalArgumentException")
    void invalidPlatformThrowsException() {
        assertThrows(IllegalArgumentException.class, () -> service.start("unknown_platform"));
    }

    @Test
    @DisplayName("平台冲突保护：当前已有activePlatform且状态为starting时切换平台应抛出ActiveLoginException")
    void activePlatformConflictThrowsException() throws Exception {
        Field stateField = LoginOnlyBrowserService.class.getDeclaredField("state");
        stateField.setAccessible(true);
        stateField.set(service, "starting");

        Field platformField = LoginOnlyBrowserService.class.getDeclaredField("activePlatform");
        platformField.setAccessible(true);
        platformField.set(service, "boss");

        assertThrows(LoginOnlyBrowserService.ActiveLoginException.class, () -> service.start("liepin"));
    }

    @Test
    @DisplayName("假死检测：若state为running但实际并未存活（!isAlive），重复start不应短路，应重置并重新启动")
    void staleRunningStateDoesNotBlockRestart() throws Exception {
        Field stateField = LoginOnlyBrowserService.class.getDeclaredField("state");
        stateField.setAccessible(true);
        stateField.set(service, "running");

        Field platformField = LoginOnlyBrowserService.class.getDeclaredField("activePlatform");
        platformField.setAccessible(true);
        platformField.set(service, "boss");

        assertFalse(service.isAlive(), "由于context为空，isAlive必须为false");

        // start 同一平台
        service.start("boss");

        // 验证状态已被更新为 starting，且 generation 已递增
        Map<String, Object> status = service.status();
        assertEquals("boss", status.get("platform"));
        assertEquals("starting", status.get("state"));
    }

    @Test
    @DisplayName("代际隔离防旧回调覆盖：旧代际的handleContextClosed不影响新代际状态")
    void generationTokenProtectsAgainstStaleCallbacks() throws Exception {
        Field genField = LoginOnlyBrowserService.class.getDeclaredField("generation");
        genField.setAccessible(true);
        AtomicLong gen = (AtomicLong) genField.get(service);
        gen.set(2L);

        Field stateField = LoginOnlyBrowserService.class.getDeclaredField("state");
        stateField.setAccessible(true);
        stateField.set(service, "running");

        Field platformField = LoginOnlyBrowserService.class.getDeclaredField("activePlatform");
        platformField.setAccessible(true);
        platformField.set(service, "boss");

        // 触发旧代际 (generation = 1) 的 handleContextClosed
        Method handleClosedMethod = LoginOnlyBrowserService.class.getDeclaredMethod("handleContextClosed", long.class, com.microsoft.playwright.BrowserContext.class);
        handleClosedMethod.setAccessible(true);
        handleClosedMethod.invoke(service, 1L, null);

        // 验证当前状态未被旧代际回调重置，仍然为 running
        assertEquals("running", service.status().get("state"));
        assertEquals("boss", service.status().get("platform"));

        // 触发当前代际 (generation = 2) 的 handleContextClosed
        BrowserContext owned = mock(BrowserContext.class);
        setField("context", owned);
        ownerCall(() -> handleClosedMethod.invoke(service, 2L, owned));

        // 验证当前代际回调成功重置状态
        assertEquals("not_started", service.status().get("state"));
        assertEquals("", service.status().get("platform"));
    }

    @Test
    @DisplayName("stop操作即时重置状态并递增代际，保证并发安全")
    void stopResetsStateImmediately() throws Exception {
        Field stateField = LoginOnlyBrowserService.class.getDeclaredField("state");
        stateField.setAccessible(true);
        stateField.set(service, "running");

        Field platformField = LoginOnlyBrowserService.class.getDeclaredField("activePlatform");
        platformField.setAccessible(true);
        platformField.set(service, "boss");

        service.stop();

        Map<String, Object> status = service.status();
        assertEquals("not_started", status.get("state"));
        assertEquals("", status.get("platform"));
        assertEquals(false, status.get("initialized"));
        assertEquals(false, status.get("loggedIn"));
        assertFalse(service.isAlive());
    }

    @Test
    @DisplayName("并发真实交错：多线程交替调用start与stop无死锁且状态最终收敛为not_started")
    void concurrentStartAndStopInterleavingSafety() throws Exception {
        ExecutorService testPool = Executors.newFixedThreadPool(4);
        try {
            List<Callable<Void>> tasks = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                final int index = i;
                tasks.add(() -> {
                    try {
                        if (index % 2 == 0) {
                            service.start("boss");
                        } else {
                            service.stop();
                        }
                    } catch (LoginOnlyBrowserService.ActiveLoginException ignored) {
                        // 并发下若有冲突抛出此受检异常属于正常平台保护行为
                    }
                    return null;
                });
            }

            List<Future<Void>> futures = testPool.invokeAll(tasks, 10, TimeUnit.SECONDS);
            for (Future<Void> f : futures) {
                assertFalse(f.isCancelled(), "所有并发任务应在10秒内完成，不发生死锁超时");
                f.get();
            }

            // 最终调用 stop，验证能够干净重置且 isAlive 为 false
            service.stop();
            assertEquals("not_started", service.status().get("state"));
            assertFalse(service.isAlive());
        } finally {
            testPool.shutdownNow();
        }
    }

    @Test
    @DisplayName("真实非反射交错：start后立即stop再start验证代际递增与无死锁")
    void realStartStopInterleavingSequential() {
        // 第一次 start
        service.start("boss");
        assertEquals("boss", service.status().get("platform"));
        assertEquals("starting", service.status().get("state"));

        // 立即 stop
        service.stop();
        assertEquals("not_started", service.status().get("state"));
        assertEquals("", service.status().get("platform"));
        assertFalse(service.isAlive());

        // 再次 start
        service.start("boss");
        assertEquals("boss", service.status().get("platform"));
        assertEquals("starting", service.status().get("state"));

        // 再次 stop
        service.stop();
        assertEquals("not_started", service.status().get("state"));
        assertFalse(service.isAlive());
    }
    @Test
    void closeBeforeListenerRegistrationIsDetected() throws Exception {
        assertCloseBoundary(0);
    }

    @Test
    void closeBetweenListenerRegistrationsIsDetected() throws Exception {
        assertCloseBoundary(1);
    }

    @Test
    void closeAfterListenerRegistrationsUsesCallback() throws Exception {
        assertCloseBoundary(2);
    }

    @Test
    void queuedCloseEventIsDispatchedByProtocolCheck() throws Exception {
        assertCloseBoundary(3);
    }

    private void assertCloseBoundary(int boundary) throws Exception {
        BrowserContext ctx = mock(BrowserContext.class);
        Page target = mock(Page.class);
        Playwright pw = mock(Playwright.class);
        AtomicReference<Consumer<Page>> pageListener = new AtomicReference<>();
        java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean(boundary == 0);
        when(target.isClosed()).thenAnswer(call -> {
            assertEquals("login-only-browser", Thread.currentThread().getName());
            return closed.get();
        });
        when(ctx.pages()).thenAnswer(call -> closed.get() ? List.of() : List.of(target));
        doAnswer(call -> {
            assertEquals("login-only-browser", Thread.currentThread().getName());
            if (boundary == 1) closed.set(true); // Context event lost before page listener exists.
            return null;
        }).when(ctx).onClose(any());
        doAnswer(call -> {
            pageListener.set(call.getArgument(0));
            return null;
        }).when(target).onClose(any());
        when(target.title()).thenAnswer(call -> {
            assertEquals("login-only-browser", Thread.currentThread().getName());
            if (boundary == 3) {
                closed.set(true);
                pageListener.get().accept(target);
                throw new IllegalStateException("Page closed during protocol query");
            }
            return "local page";
        });
        setField("context", ctx);
        setField("page", target);
        setField("playwright", pw);
        setField("state", "running");
        setField("alive", true);
        setField("initialized", true);
        setField("activePlatform", "boss");
        Method register = LoginOnlyBrowserService.class.getDeclaredMethod("registerCloseListeners",
                long.class, BrowserContext.class, Page.class);
        register.setAccessible(true);
        ownerCall(() -> {
            register.invoke(service, 0L, ctx, target);
            if (boundary == 2) {
                closed.set(true);
                pageListener.get().accept(target);
            }
            return null;
        });
        assertEquals("not_started", service.status().get("state"));
        assertEquals("", service.status().get("platform"));
        assertFalse(service.isAlive());
        verify(pw, times(1)).close();
    }

    @Test
    void cleanupCallbacksPreserveFailedAndIgnoreOtherContext() throws Exception {
        BrowserContext old = mock(BrowserContext.class);
        BrowserContext current = mock(BrowserContext.class);
        Playwright pw = mock(Playwright.class);
        setField("context", current);
        setField("playwright", pw);
        setField("state", "running");
        Method closed = LoginOnlyBrowserService.class.getDeclaredMethod("handleContextClosed", long.class, BrowserContext.class);
        closed.setAccessible(true);
        ownerCall(() -> closed.invoke(service, 0L, old));
        assertEquals("running", service.status().get("state"));
        verifyNoInteractions(pw);
        setField("context", null); // Failure cleanup detached before callback dispatch.
        setField("playwright", null);
        setField("state", "failed");
        setField("message", "controlled failure");
        ownerCall(() -> closed.invoke(service, 0L, current));
        assertEquals("failed", service.status().get("state"));
        assertEquals("controlled failure", service.status().get("message"));
        verifyNoInteractions(pw);
    }

    private void setField(String name, Object value) throws Exception {
        Field field = LoginOnlyBrowserService.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(service, value);
    }

    private void ownerCall(Callable<Object> call) throws Exception {
        Field executor = LoginOnlyBrowserService.class.getDeclaredField("loginExecutor");
        executor.setAccessible(true);
        ((ExecutorService) executor.get(service)).submit(call).get(5, TimeUnit.SECONDS);
    }

    @Test
    void handoffRequiresExplicitConfigurationAndDoesNotUseCwd() {
        service.setConfiguredLoginDir(null);
        assertThrows(java.util.concurrent.ExecutionException.class,
                () -> service.claimBossProfileForExecution().toCompletableFuture().get(5, TimeUnit.SECONDS));
    }

    @Test
    void idleHandoffUsesConfiguredBossPathAndFencesStartUntilRelease() throws Exception {
        var ticket = service.claimBossProfileForExecution().toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertEquals(tempDir.toAbsolutePath().normalize().resolve("boss"), ticket.profilePath());
        assertFalse(java.nio.file.Files.exists(ticket.profilePath()), "claim must not create a fresh profile");
        assertTrue(service.isCurrentBossHandoff(ticket));
        assertThrows(LoginOnlyBrowserService.ActiveLoginException.class, () -> service.start("boss"));
        assertThrows(LoginOnlyBrowserService.ActiveLoginException.class, () -> service.start("liepin"));
        service.stop();
        assertTrue(service.isCurrentBossHandoff(ticket), "stop cannot release execution ownership");
        service.releaseBossHandoff(ticket);
        assertFalse(service.isCurrentBossHandoff(ticket));
    }

    @Test
    void handoffWaitsForOwnerAndCallerTimeoutOrCancellationDoesNotUnfence() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        ownerExecutor().submit(() -> {
            entered.countDown();
            try { release.await(5, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        BrowserContext ctx = mock(BrowserContext.class);
        Playwright pw = mock(Playwright.class);
        setField("context", ctx);
        setField("playwright", pw);
        doAnswer(call -> { assertEquals("login-only-browser", Thread.currentThread().getName()); return null; })
                .when(ctx).close();
        doAnswer(call -> { assertEquals("login-only-browser", Thread.currentThread().getName()); return null; })
                .when(pw).close();
        try {
            var stage = service.claimBossProfileForExecution();
            assertSame(stage, service.claimBossProfileForExecution());
            assertThrows(java.util.concurrent.TimeoutException.class,
                    () -> stage.toCompletableFuture().get(20, TimeUnit.MILLISECONDS));
            assertTrue(stage.toCompletableFuture().cancel(true));
            assertFalse(stage.toCompletableFuture().isDone(), "derived cancellation must not cancel owner work");
            assertThrows(LoginOnlyBrowserService.ActiveLoginException.class, () -> service.start("boss"));
            verifyNoInteractions(ctx, pw);
            release.countDown();
            var ticket = stage.toCompletableFuture().get(5, TimeUnit.SECONDS);
            var order = inOrder(ctx, pw);
            order.verify(ctx).close();
            order.verify(pw).close();
            assertTrue(service.isCurrentBossHandoff(ticket));
            service.releaseBossHandoff(ticket);
        } finally {
            release.countDown();
        }
    }

    @Test
    void contextCloseFailureStillClosesDriverAndBlocksHandoff() throws Exception {
        assertHandoffCloseFailure(true);
    }

    @Test
    void driverCloseFailureBlocksHandoff() throws Exception {
        assertHandoffCloseFailure(false);
    }

    private void assertHandoffCloseFailure(boolean contextFails) throws Exception {
        BrowserContext ctx = mock(BrowserContext.class);
        Playwright pw = mock(Playwright.class);
        setField("context", ctx);
        setField("playwright", pw);
        setField("state", "failed");
        setField("message", "original startup failure");
        if (contextFails) doThrow(new IllegalStateException("controlled ctx close failure")).when(ctx).close();
        else doThrow(new IllegalStateException("controlled driver close failure")).when(pw).close();
        assertThrows(java.util.concurrent.ExecutionException.class,
                () -> service.claimBossProfileForExecution().toCompletableFuture().get(5, TimeUnit.SECONDS));
        verify(ctx).close();
        verify(pw).close();
        assertThrows(LoginOnlyBrowserService.ActiveLoginException.class, () -> service.start("boss"));
        assertEquals("failed", service.status().get("state"));
        assertEquals("original startup failure", service.status().get("message"));
        assertThrows(IllegalStateException.class, () -> service.releaseBossHandoff(null));
    }

    @Test
    void earlierUnpublishedCleanupFailureCannotBeAcknowledgedByEmptyFields() throws Exception {
        BrowserContext unpublished = mock(BrowserContext.class);
        doThrow(new IllegalStateException("unpublished cleanup failed")).when(unpublished).close();
        Method close = LoginOnlyBrowserService.class.getDeclaredMethod("safeCloseContext", BrowserContext.class);
        close.setAccessible(true);
        ownerCall(() -> close.invoke(service, unpublished));
        assertThrows(java.util.concurrent.ExecutionException.class,
                () -> service.claimBossProfileForExecution().toCompletableFuture().get(5, TimeUnit.SECONDS));
        assertThrows(LoginOnlyBrowserService.ActiveLoginException.class, () -> service.start("boss"));
    }

    @Test
    void staleOrForeignTicketCannotReleaseCurrentHandoff() throws Exception {
        var first = service.claimBossProfileForExecution().toCompletableFuture().get(5, TimeUnit.SECONDS);
        service.releaseBossHandoff(first);
        var second = service.claimBossProfileForExecution().toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertThrows(IllegalStateException.class, () -> service.releaseBossHandoff(first));
        assertFalse(service.isCurrentBossHandoff(first));
        assertTrue(service.isCurrentBossHandoff(second));
        LoginOnlyBrowserService other = new LoginOnlyBrowserService();
        other.setConfiguredLoginDir(tempDir.resolve("other").toString());
        try {
            var foreign = other.claimBossProfileForExecution().toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertThrows(IllegalStateException.class, () -> service.releaseBossHandoff(foreign));
            assertTrue(service.isCurrentBossHandoff(second));
            other.releaseBossHandoff(foreign);
        } finally { other.destroy(); }
        service.releaseBossHandoff(second);
    }

    @Test
    void otherActivePlatformIsNotClosedForBossClaim() throws Exception {
        setField("activePlatform", "liepin");
        setField("state", "starting");
        BrowserContext ctx = mock(BrowserContext.class);
        setField("context", ctx);
        assertThrows(java.util.concurrent.ExecutionException.class,
                () -> service.claimBossProfileForExecution().toCompletableFuture().get(5, TimeUnit.SECONDS));
        assertEquals("liepin", service.status().get("platform"));
        verifyNoInteractions(ctx);
    }

    @Test
    void destroyedServiceRejectsClaimAndInvalidatesReadyTicket() throws Exception {
        var ticket = service.claimBossProfileForExecution().toCompletableFuture().get(5, TimeUnit.SECONDS);
        service.destroy();
        assertFalse(service.isCurrentBossHandoff(ticket));
        assertThrows(java.util.concurrent.ExecutionException.class,
                () -> service.claimBossProfileForExecution().toCompletableFuture().get(5, TimeUnit.SECONDS));
        assertThrows(IllegalStateException.class, () -> service.start("boss"));
    }

    @Test
    void destroyCompletesPendingHandoffExceptionallyWithoutReleasingFence() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        ownerExecutor().submit(() -> {
            entered.countDown();
            try { release.await(10, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        var pending = service.claimBossProfileForExecution();
        var pool = Executors.newSingleThreadExecutor();
        try {
            var destroyed = pool.submit(service::destroy);
            assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> pending.toCompletableFuture().get(5, TimeUnit.SECONDS));
            assertThrows(IllegalStateException.class, () -> service.start("boss"));
            release.countDown();
            destroyed.get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void rejectedOwnerSubmissionFailsClaimAndKeepsStartFenced() throws Exception {
        var rejected = mock(ExecutorService.class);
        doThrow(new java.util.concurrent.RejectedExecutionException("controlled rejection"))
                .when(rejected).execute(any(Runnable.class));
        setField("loginExecutor", rejected);
        assertThrows(java.util.concurrent.ExecutionException.class,
                () -> service.claimBossProfileForExecution().toCompletableFuture().get(5, TimeUnit.SECONDS));
        assertThrows(LoginOnlyBrowserService.ActiveLoginException.class, () -> service.start("boss"));
    }

    private ExecutorService ownerExecutor() throws Exception {
        Field executor = LoginOnlyBrowserService.class.getDeclaredField("loginExecutor");
        executor.setAccessible(true);
        return (ExecutorService) executor.get(service);
    }

}
