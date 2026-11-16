package com.getjobs.application.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LoginOnlyBrowserServiceRealChromeTest {

    private static HttpServer localServer;
    private static String localLoginUrl;

    @BeforeAll
    static void startHttpServer() throws Exception {
        localServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        localServer.createContext("/login", exchange -> {
            byte[] response = "<!DOCTYPE html><html><head><title>Local Login Loopback</title></head><body><div id='login-box'>Login Form Ready</div></body></html>".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response);
            }
        });
        localServer.createContext("/blank-login", exchange -> {
            byte[] response = "<!DOCTYPE html><html><head><title>Will Become Blank</title></head><body><script>location.replace('about:blank')</script></body></html>".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response);
            }
        });
        localServer.start();
        int port = localServer.getAddress().getPort();
        localLoginUrl = "http://127.0.0.1:" + port + "/login";
    }

    @AfterAll
    static void stopHttpServer() {
        if (localServer != null) {
            localServer.stop(0);
        }
    }

    private boolean waitForStatus(LoginOnlyBrowserService service, String targetState, int maxSeconds) throws InterruptedException {
        long end = System.currentTimeMillis() + maxSeconds * 1000L;
        while (System.currentTimeMillis() < end) {
            if (targetState.equals(service.status().get("state"))) {
                return true;
            }
            Thread.sleep(200);
        }
        return false;
    }

    @Test
    @DisplayName("真实Windows Chrome headed启动、sandbox开启、本地页面导航可见、窗口可见度、真实关闭状态复位与重启复验")
    void realChromeSandboxStartupNavigationExitAndRestart(@TempDir Path tempDir) throws Exception {
        LoginOnlyBrowserService service = new LoginOnlyBrowserService();
        service.setConfiguredLoginDir(tempDir.toString());
        service.setBrowserChannel("chrome");
        service.setHeadless(false); // headed=true，不能使用 headless 当作可见证据
        service.setLoginUrlOverrides(Map.of("boss", localLoginUrl));

        try {
            // 1. 验证真实 headed 启动与基本状态
            assertFalse(service.isHeadless(), "测试必须为 headed 模式，不能使用 headless");
            service.start("boss");
            boolean reachedRunning = waitForStatus(service, "running", 25);
            assertTrue(reachedRunning, "服务应在25秒内进入 running 状态，实际为: " + service.status().get("state"));
            assertTrue(service.isAlive(), "服务实际关联的 Chrome context 与 page 应处于存活状态");

            Map<String, Object> status = service.status();
            assertEquals("boss", status.get("platform"));
            assertTrue(String.valueOf(status.get("url")).contains("/login"), "URL 应导航到本地测试登录页: " + status.get("url"));
            assertEquals(true, status.get("initialized"));
            assertEquals(false, status.get("tasksStarted"), "tasksStarted 必须恒为 false");
            assertEquals(false, status.get("schedulerEnabled"), "schedulerEnabled 必须恒为 false");

            // 2. 验证窗口可见度与真实页面渲染指标（非 headless）
            Map<String, Object> metrics = service.queryWindowMetrics();
            assertEquals(true, metrics.get("open"), "页面在启动后应处于打开状态");
            assertEquals("Local Login Loopback", metrics.get("title"), "页面标题应与本地页面一致");
            assertEquals(1, ((Number) metrics.get("pages")).intValue(), "登录浏览器只应保留一个前台登录标签页");
            assertEquals(0, ((Number) metrics.get("blankPages")).intValue(), "不得遗留 about:blank 伴生标签页");

            @SuppressWarnings("unchecked")
            Map<String, Object> winMetrics = (Map<String, Object>) metrics.get("metrics");
            assertNotNull(winMetrics, "headed 模式下必须能获取窗口指标");
            Number outerWidth = (Number) winMetrics.get("outerWidth");
            Number outerHeight = (Number) winMetrics.get("outerHeight");
            assertNotNull(outerWidth, "outerWidth 不应为空");
            assertNotNull(outerHeight, "outerHeight 不应为空");
            assertTrue(outerWidth.intValue() > 0, "真实桌面窗口 outerWidth 必须大于0，当前为: " + outerWidth);
            assertTrue(outerHeight.intValue() > 0, "真实桌面窗口 outerHeight 必须大于0，当前为: " + outerHeight);
            assertEquals("visible", winMetrics.get("visibilityState"), "页面可见状态必须为 visible");

            // 3. 验证通过浏览器端关闭页面触发真实 onClose 回调并复位状态
            service.closeActivePage();
            boolean reachedNotStarted = waitForStatus(service, "not_started", 10);
            assertTrue(reachedNotStarted, "窗口关闭后应通过 onClose 回调在10秒内自动复位为 not_started，实际为: " + service.status().get("state"));
            assertFalse(service.isAlive(), "窗口关闭后 isAlive() 必须返回 false");
            assertEquals("", service.status().get("platform"), "窗口关闭后 activePlatform 必须被清空");

            // 4. 验证同 profile 目录重启复验（确保关闭后锁已释放、可正常二次拉起并依然可见）
            service.start("boss");
            boolean reachedRestartRunning = waitForStatus(service, "running", 25);
            assertTrue(reachedRestartRunning, "同 profile 重启应在25秒内重新进入 running 状态，实际为: " + service.status().get("state"));
            assertTrue(service.isAlive(), "重启后服务实际关联的 Chrome context 应存活");

            Map<String, Object> restartStatus = service.status();
            assertEquals("boss", restartStatus.get("platform"));
            assertTrue(String.valueOf(restartStatus.get("url")).contains("/login"));

            Map<String, Object> restartMetrics = service.queryWindowMetrics();
            assertEquals(true, restartMetrics.get("open"));
            @SuppressWarnings("unchecked")
            Map<String, Object> restartWinMetrics = (Map<String, Object>) restartMetrics.get("metrics");
            assertNotNull(restartWinMetrics);
            assertTrue(((Number) restartWinMetrics.get("outerWidth")).intValue() > 0);

            // 5. 验证主动 stop() 退出
            service.stop();
            assertEquals("not_started", service.status().get("state"));
            assertFalse(service.isAlive(), "关闭后 isAlive() 必须返回 false");

        } finally {
            service.stop();
            service.destroy();
        }
    }

    @Test
    @DisplayName("启动失败资源清理受控回归：导航失败时释放profile锁且状态置为failed，同profile后续成功启动")
    void startupNavigationFailureCleansUpResourcesAndAllowsSameProfileRestart(@TempDir Path tempDir) throws Exception {
        LoginOnlyBrowserService service = new LoginOnlyBrowserService();
        service.setConfiguredLoginDir(tempDir.toString());
        service.setBrowserChannel("chrome");
        service.setHeadless(true);

        // 1. 获取一个无监听的本地空闲端口，构造受控导航失败目标
        int unreachablePort;
        try (java.net.ServerSocket ss = new java.net.ServerSocket(0)) {
            unreachablePort = ss.getLocalPort();
        }
        String unreachableUrl = "http://127.0.0.1:" + unreachablePort + "/unreachable_login";
        service.setLoginUrlOverrides(Map.of("boss", unreachableUrl));

        try {
            // 2. 启动服务，验证导航失败并进入 failed 状态，且资源已释放、isAlive 为 false
            service.start("boss");
            boolean reachedFailed = waitForStatus(service, "failed", 15);
            assertTrue(reachedFailed, "导航失败应在15秒内将服务状态置为 failed，实际为: " + service.status().get("state"));
            assertFalse(service.isAlive(), "导航失败后 isAlive() 必须为 false");

            Map<String, Object> failedStatus = service.status();
            assertEquals("failed", failedStatus.get("state"), "状态必须为 failed 可见");
            assertEquals("", failedStatus.get("platform"), "失败后 activePlatform 必须被清空");
            assertEquals(false, failedStatus.get("initialized"));
            assertNotNull(failedStatus.get("message"), "失败信息 message 不应为空");

            // 3. 切换回本地有效 loopback URL，使用完全相同的 profile 目录重新启动
            service.setLoginUrlOverrides(Map.of("boss", localLoginUrl));
            service.start("boss");

            // 4. 验证同 profile 成功启动进入 running 状态（证明先前失败的本地进程与锁已被完全释放）
            boolean reachedRunning = waitForStatus(service, "running", 25);
            assertTrue(reachedRunning, "同 profile 目录重新启动应成功进入 running 状态，实际为: " + service.status().get("state"));
            assertTrue(service.isAlive(), "重启后服务实际关联的 Chrome 必须处于存活状态");

            Map<String, Object> runningStatus = service.status();
            assertEquals("boss", runningStatus.get("platform"));
            assertEquals("running", runningStatus.get("state"));
            assertTrue(String.valueOf(runningStatus.get("url")).contains("/login"));

            // 5. 验证主动 stop() 退出
            service.stop();
            assertEquals("not_started", service.status().get("state"));
            assertFalse(service.isAlive());
        } finally {
            service.stop();
            service.destroy();
        }
    }
    @Test
    void realCloseAfterPublishBeforeRegistrationResetsAndCanRestart(@TempDir Path tempDir) throws Exception {
        java.util.concurrent.CompletableFuture<Void> boundary = new java.util.concurrent.CompletableFuture<>();
        LoginOnlyBrowserService service = new LoginOnlyBrowserService() {
            @Override
            void registerCloseListeners(long gen, com.microsoft.playwright.BrowserContext ctx,
                                        com.microsoft.playwright.Page target) {
                assertEquals("login-only-browser", Thread.currentThread().getName());
                if (!boundary.isDone()) {
                    assertEquals("running", status().get("state"));
                    target.close(); // Real close event occurs with neither listener installed.
                    super.registerCloseListeners(gen, ctx, target);
                    boundary.complete(null);
                } else {
                    super.registerCloseListeners(gen, ctx, target);
                }
            }
        };
        service.setConfiguredLoginDir(tempDir.toString());
        service.setBrowserChannel("chrome");
        service.setHeadless(false);
        service.setLoginUrlOverrides(Map.of("boss", localLoginUrl));
        try {
            service.start("boss");
            boundary.get(25, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals("not_started", service.status().get("state"));
            assertEquals("", service.status().get("platform"));
            assertFalse(service.isAlive());
            service.start("boss");
            assertTrue(waitForStatus(service, "running", 25));
            assertEquals(true, service.queryWindowMetrics().get("open"));
        } finally {
            service.destroy();
        }
    }

    @Test
    void concurrentApisDuringInflightLocalNavigationConverge(@TempDir Path tempDir) throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        localServer.createContext("/controlled-slow", exchange -> {
            entered.countDown();
            try {
                release.await(15, java.util.concurrent.TimeUnit.SECONDS);
                byte[] body = "<title>Controlled local navigation</title>".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) { out.write(body); }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        LoginOnlyBrowserService service = new LoginOnlyBrowserService();
        service.setConfiguredLoginDir(tempDir.toString());
        service.setBrowserChannel("chrome");
        service.setHeadless(true);
        service.setLoginUrlOverrides(Map.of("boss", localLoginUrl.replace("/login", "/controlled-slow")));
        var pool = java.util.concurrent.Executors.newFixedThreadPool(3);
        try {
            service.start("boss");
            assertTrue(entered.await(25, java.util.concurrent.TimeUnit.SECONDS));
            var restart = pool.submit(() -> service.start("boss"));
            restart.get(5, java.util.concurrent.TimeUnit.SECONDS);
            var stop = pool.submit(service::stop);
            // Observe immediate generation invalidation while owner navigation is blocked.
            assertTrue(waitForStatus(service, "not_started", 5));
            release.countDown();
            stop.get(15, java.util.concurrent.TimeUnit.SECONDS);
            service.stop();
            assertFalse(service.isAlive());
            assertEquals("not_started", service.status().get("state"));
            service.setLoginUrlOverrides(Map.of("boss", localLoginUrl));
            service.start("boss");
            assertTrue(waitForStatus(service, "running", 25));
        } finally {
            release.countDown();
            pool.shutdownNow();
            service.destroy();
            localServer.removeContext("/controlled-slow");
        }
    }

    @Test
    @DisplayName("导航落到 about:blank 时不得报告 running/initialized，必须失败关闭并允许同 profile 重试")
    void blankCommittedPageIsNeverReportedAsRunning(@TempDir Path tempDir) throws Exception {
        LoginOnlyBrowserService service = new LoginOnlyBrowserService();
        service.setConfiguredLoginDir(tempDir.toString());
        service.setBrowserChannel("chrome");
        service.setHeadless(true);
        service.setLoginUrlOverrides(Map.of("boss", localLoginUrl.replace("/login", "/blank-login")));
        try {
            service.start("boss");
            assertTrue(waitForStatus(service, "failed", 25),
                    "空白页不得被接受为 running，实际状态: " + service.status().get("state"));
            assertFalse(service.isAlive(), "失败关闭后不得保留存活 context/page");
            Map<String, Object> failed = service.status();
            assertEquals(false, failed.get("initialized"));
            assertEquals("", failed.get("platform"));
            assertTrue(String.valueOf(failed.get("message")).contains("空白"),
                    "失败原因应说明页面仍为空白: " + failed.get("message"));

            service.setLoginUrlOverrides(Map.of("boss", localLoginUrl));
            service.start("boss");
            assertTrue(waitForStatus(service, "running", 25), "同一 profile 应能在失败关闭后重新打开有效登录页");
            assertEquals(true, service.queryWindowMetrics().get("open"));
        } finally {
            service.destroy();
        }
    }

    @Test
    void handoffClosesFirstOwnerBeforeSecondOwnerUsesSameProfile(@TempDir Path tempDir) throws Exception {
        LoginOnlyBrowserService first = new LoginOnlyBrowserService();
        LoginOnlyBrowserService second = new LoginOnlyBrowserService();
        for (var owner : java.util.List.of(first, second)) {
            owner.setConfiguredLoginDir(tempDir.toString());
            owner.setBrowserChannel("chrome");
            owner.setHeadless(false);
            owner.setLoginUrlOverrides(Map.of("boss", localLoginUrl));
        }
        try {
            first.start("boss");
            assertTrue(waitForStatus(first, "running", 25));
            var ticket = first.claimBossProfileForExecution().toCompletableFuture()
                    .get(15, java.util.concurrent.TimeUnit.SECONDS);
            assertFalse(first.isAlive());
            assertTrue(first.isCurrentBossHandoff(ticket));
            assertThrows(LoginOnlyBrowserService.ActiveLoginException.class, () -> first.start("boss"));
            // Independent second owner opens exactly the same TempDir profile, never copies cookies.
            second.start("boss");
            assertTrue(waitForStatus(second, "running", 25));
            assertEquals(true, second.queryWindowMetrics().get("open"));
            second.destroy(); // Owner cleanup + executor termination before returning the ticket.
            first.releaseBossHandoff(ticket);
            first.start("boss");
            assertTrue(waitForStatus(first, "running", 25));
        } finally {
            second.destroy();
            first.destroy();
        }
    }

}
