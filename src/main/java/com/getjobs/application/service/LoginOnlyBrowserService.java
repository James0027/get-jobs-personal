package com.getjobs.application.service;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Single-platform login browser. It never starts delivery, chat, or scheduling,
 * and never reads/writes cookies in the application database.
 */
@Slf4j
@Service
public class LoginOnlyBrowserService {

    private static final Set<String> PLATFORMS = Set.of("boss", "liepin", "51job", "zhilian");
    private static final Map<String, String> LOGIN_URLS = Map.of(
            "boss", "https://www.zhipin.com/web/user/?ka=header-login",
            "liepin", "https://www.liepin.com/login",
            "51job", "https://login.51job.com/login.php",
            "zhilian", "https://www.zhaopin.com"
    );

    private final ExecutorService loginExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "login-only-browser");
        thread.setDaemon(true);
        return thread;
    });

    private final AtomicLong generation = new AtomicLong(0);

    @Value("${browser.data.dir:}")
    private String configuredBaseDir;

    @Value("${browser.login-only.dir:}")
    private String configuredLoginDir;

    @Value("${browser.channel:chrome}")
    private String browserChannel;

    @Value("${browser.login-only.headless:false}")
    private boolean headless = false;

    private Map<String, String> loginUrlOverrides;

    private volatile String activePlatform;
    private volatile String state = "not_started";
    private volatile String message;
    private volatile String currentUrl = "";
    private volatile boolean initialized;
    private volatile boolean loggedIn;
    private volatile boolean alive;

    private volatile Playwright playwright;
    private volatile BrowserContext context;
    private volatile Page page;

    // A claim fences start until the execution owner acknowledges its own cleanup.
    private BossProfileHandoff bossHandoff;
    private CompletableFuture<BossProfileHandoff> handoffCompletion;
    private CompletionStage<BossProfileHandoff> handoffView;
    private boolean handoffReady;
    private boolean destroying;
    private Exception cleanupFailure;

    /** Internal capability, never constructed from an HTTP path or serialized as a response. */
    public static final class BossProfileHandoff {
        private final Path profilePath;

        private BossProfileHandoff(Path profilePath) {
            this.profilePath = profilePath;
        }

        public Path profilePath() {
            return profilePath;
        }
    }

    /**
     * Fence new starts, drain earlier owner work and acknowledge service-side cleanup.
     * This does not prove that an external Chrome released the profile or that it is logged in.
     * Caller timeouts/cancellation of a derived future never release the fence.
     */
    public CompletionStage<BossProfileHandoff> claimBossProfileForExecution() {
        CompletableFuture<BossProfileHandoff> completion;
        CompletionStage<BossProfileHandoff> view;
        Exception submissionFailure = null;
        synchronized (this) {
            if (destroying || loginExecutor.isShutdown()) {
                return CompletableFuture.failedStage(new IllegalStateException("登录服务已关闭，不能交接"));
            }
            if (bossHandoff != null) return handoffView;
            if ((configuredLoginDir == null || configuredLoginDir.isBlank())
                    && (configuredBaseDir == null || configuredBaseDir.isBlank())) {
                return CompletableFuture.failedStage(new IllegalStateException("未显式配置登录 profile，不能交接"));
            }
            if (activePlatform != null && !"boss".equals(activePlatform)) {
                return CompletableFuture.failedStage(new ActiveLoginException(activePlatform));
            }
            BossProfileHandoff ticket = new BossProfileHandoff(profileFor("boss"));
            completion = new CompletableFuture<>();
            view = completion.minimalCompletionStage();
            bossHandoff = ticket;
            handoffCompletion = completion;
            handoffView = view;
            handoffReady = false;
            generation.incrementAndGet();
            activePlatform = null;
            initialized = false;
            loggedIn = false;
            alive = false;
            currentUrl = "";
            if (!"failed".equals(state)) {
                state = "not_started";
                message = null;
            }
            // Enqueue while establishing the fence, but never wait or call PW under the monitor.
            try {
                loginExecutor.execute(() -> completeBossHandoff(ticket, completion));
            } catch (Exception error) {
                submissionFailure = error;
            }
        }
        if (submissionFailure != null) completion.completeExceptionally(submissionFailure);
        return view;
    }

    private void completeBossHandoff(BossProfileHandoff ticket,
                                     CompletableFuture<BossProfileHandoff> completion) {
        closeBrowser();
        Exception failure;
        synchronized (this) {
            failure = cleanupFailure;
            if (failure == null && (destroying || bossHandoff != ticket || completion.isDone())) {
                failure = new IllegalStateException("交接已失效或登录服务已关闭");
            }
            if (failure == null) {
                handoffReady = true;
            } else if (!"failed".equals(state)) {
                state = "failed";
                message = "登录资源未确认释放，禁止交接";
            }
        }
        // Completing outside the monitor prevents dependent manager work from inheriting our lock.
        if (failure == null) completion.complete(ticket);
        else completion.completeExceptionally(failure);
    }

    public synchronized boolean isCurrentBossHandoff(BossProfileHandoff ticket) {
        return !destroying && ticket != null && bossHandoff == ticket && handoffReady;
    }

    /** Execution owner must finish closing its context/driver before releasing this ticket. */
    public synchronized void releaseBossHandoff(BossProfileHandoff ticket) {
        if (ticket == null || bossHandoff != ticket || !handoffReady) {
            throw new IllegalStateException("交接凭证无效或关闭尚未确认，保留 profile 阻挡");
        }
        bossHandoff = null;
        handoffReady = false;
        handoffCompletion = null;
        handoffView = null;
    }

    void setConfiguredLoginDir(String dir) {
        this.configuredLoginDir = dir;
    }

    void setBrowserChannel(String channel) {
        this.browserChannel = channel;
    }

    public void setHeadless(boolean headless) {
        this.headless = headless;
    }

    public boolean isHeadless() {
        return headless;
    }

    void setLoginUrlOverrides(Map<String, String> overrides) {
        this.loginUrlOverrides = overrides;
    }

    private String loginUrlFor(String platform) {
        if (loginUrlOverrides != null && loginUrlOverrides.containsKey(platform)) {
            return loginUrlOverrides.get(platform);
        }
        return LOGIN_URLS.get(platform);
    }

    public synchronized boolean isAlive() {
        return alive && context != null && page != null;
    }

    public synchronized void start(String platform) {
        String normalized = normalize(platform);
        if (destroying) throw new IllegalStateException("登录服务已关闭");
        if (bossHandoff != null) {
            throw new ActiveLoginException("boss", "BOSS profile 正在交接或由执行服务持有，禁止启动登录浏览器");
        }
        if (activePlatform != null && !activePlatform.equals(normalized)
                && ("starting".equals(state) || ("running".equals(state) && isAlive()))) {
            throw new ActiveLoginException(activePlatform);
        }
        if (normalized.equals(activePlatform) && "running".equals(state)) {
            if (isAlive()) {
                return;
            }
            log.warn("检测到平台 {} 的登录浏览器已关闭或未存活，重置旧状态并重新启动", normalized);
        }

        long currentGen = generation.incrementAndGet();
        activePlatform = normalized;
        state = "starting";
        message = null;
        currentUrl = "";
        loggedIn = false;
        initialized = false;
        alive = false;
        loginExecutor.submit(() -> launch(normalized, currentGen));
    }

    private void launch(String platform, long myGen) {
        if (generation.get() != myGen) {
            return;
        }
        Playwright pw = null;
        BrowserContext ctx = null;
        boolean published = false;
        try {
            closeBrowser();
            if (generation.get() != myGen) {
                return;
            }

            Path profile = profileFor(platform);
            Files.createDirectories(profile);

            if (generation.get() != myGen) {
                return;
            }

            pw = Playwright.create();
            if (generation.get() != myGen) {
                return;
            }

            BrowserType.LaunchPersistentContextOptions options =
                    new BrowserType.LaunchPersistentContextOptions()
                            .setHeadless(headless)
                            .setViewportSize(null)
                            .setChromiumSandbox(true)
                            .setArgs(List.of(
                                    "--start-maximized",
                                    "--no-first-run",
                                    "--no-default-browser-check",
                                    "--disable-session-crashed-bubble",
                                    "--hide-crash-restore-bubble"
                            ));
            if (browserChannel != null && !browserChannel.isBlank()) {
                options.setChannel(browserChannel.trim());
            }

            ctx = pw.chromium().launchPersistentContext(profile, options);
            if (generation.get() != myGen) {
                return;
            }

            Page targetPage = ctx.pages().isEmpty() ? ctx.newPage() : ctx.pages().get(0);
            BrowserContext launchedCtx = ctx;

            // Session restore and popup tabs can appear after launch and steal the foreground,
            // leaving the tracked page at the login URL while the visible window stays blank.
            // Reconcile on the owner thread (never call Playwright from the event dispatch thread).
            ctx.onPage(newPage -> {
                if (newPage != targetPage) {
                    scheduleForegroundReconcile(myGen, launchedCtx, targetPage);
                }
            });

            targetPage.onFrameNavigated(frame -> {
                if (generation.get() == myGen && context == launchedCtx && page == targetPage
                        && frame == targetPage.mainFrame()) {
                    String url = null;
                    try {
                        url = targetPage.url();
                    } catch (Exception ignored) {
                    }
                    if (url != null) {
                        boolean logged = isLikelyLoggedIn(platform, url);
                        synchronized (this) {
                            if (generation.get() == myGen && context == launchedCtx && page == targetPage) {
                                currentUrl = url;
                                loggedIn = logged;
                            }
                        }
                    }
                }
            });

            targetPage.navigate(loginUrlFor(platform), new Page.NavigateOptions()
                    .setWaitUntil(com.microsoft.playwright.options.WaitUntilState.DOMCONTENTLOADED)
                    .setTimeout(60000));

            if (generation.get() != myGen) {
                return;
            }

            // Accept only a committed, non-blank page that is actually the visible foreground
            // tab. Window existence or a 202 response is not evidence of a usable login page.
            String finalUrl = awaitVisibleTarget(myGen, ctx, targetPage);
            boolean isLogged = isLikelyLoggedIn(platform, finalUrl);

            synchronized (this) {
                if (generation.get() == myGen) {
                    this.playwright = pw;
                    this.context = ctx;
                    this.page = targetPage;
                    this.currentUrl = finalUrl;
                    this.loggedIn = isLogged;
                    this.initialized = true;
                    this.alive = true;
                    this.state = "running";
                    this.message = "登录浏览器已打开，请手动完成登录";
                    published = true;
                    log.info("Login-only browser opened for {} at {}", platform, currentUrl);
                }
            }

            if (published) {
                registerCloseListeners(myGen, ctx, targetPage);
            }
        } catch (Exception error) {
            log.error("Login-only browser launch failed for {}", platform, error);
            closeBrowser();
            synchronized (this) {
                if (generation.get() == myGen) {
                    initialized = false;
                    alive = false;
                    state = "failed";
                    message = error.getMessage() == null ? "登录浏览器启动失败" : error.getMessage();
                    activePlatform = null;
                    currentUrl = "";
                    loggedIn = false;
                }
            }
        } finally {
            if (!published) {
                safeCloseContext(ctx);
                safeClosePlaywright(pw);
            }
        }
    }

    void registerCloseListeners(long myGen, BrowserContext ctx, Page targetPage) {
        ctx.onClose(closedCtx -> handleContextClosed(myGen, closedCtx));
        targetPage.onClose(closedPage -> handlePageClosed(myGen, ctx, closedPage));
        // Close events are not replayed to late listeners. Check on the owner thread
        // after BOTH registrations to cover a close during publication/registration.
        if (targetPage.isClosed() || !ctx.pages().contains(targetPage)) {
            handleContextClosed(myGen, ctx);
            return;
        }
        try {
            // isClosed/pages are cached; a protocol call also dispatches queued events.
            targetPage.title();
        } catch (Exception error) {
            if (!targetPage.isClosed() && ctx.pages().contains(targetPage)) {
                throw error;
            }
        }
        if (targetPage.isClosed() || !ctx.pages().contains(targetPage)) {
            handleContextClosed(myGen, ctx);
        }
    }

    private void handlePageClosed(long myGen, BrowserContext ownerCtx, Page closedPage) {
        synchronized (this) {
            if (generation.get() != myGen || context != ownerCtx || page != closedPage) {
                return;
            }
        }
        boolean noPages = false;
        try {
            noPages = ownerCtx.pages().isEmpty();
        } catch (Exception ignored) {
            noPages = true;
        }
        if (noPages) {
            handleContextClosed(myGen, ownerCtx);
        }
    }

    private void handleContextClosed(long myGen, BrowserContext closedCtx) {
        Playwright pwToClose;
        synchronized (this) {
            if (generation.get() == myGen && closedCtx != null && this.context == closedCtx) {
                pwToClose = playwright;
                log.info("Login-only browser context closed for platform {}", activePlatform);
                state = "not_started";
                initialized = false;
                loggedIn = false;
                alive = false;
                activePlatform = null;
                currentUrl = "";
                context = null;
                page = null;
                playwright = null;
                message = null;
            } else {
                return;
            }
        }
        // Detach before closing: reentrant cleanup callbacks cannot overwrite failed
        // state, close a replacement instance, or recursively close this driver.
        safeClosePlaywright(pwToClose);
    }

    /**
     * The tracked page must be a committed non-blank document and the visible foreground tab.
     * A restored/companion blank tab can otherwise sit in front while status still reports the
     * login URL as running.
     */
    private String awaitVisibleTarget(long myGen, BrowserContext ctx, Page target) {
        long deadline = System.currentTimeMillis() + 3000;
        String lastIssue = "登录页面未成为可见前台标签页";
        while (System.currentTimeMillis() < deadline) {
            if (generation.get() != myGen) throw new IllegalStateException("登录启动已取消");
            if (target.isClosed()) throw new IllegalStateException("登录页面已关闭");
            closeBlankTabs(ctx, target);
            try {
                target.bringToFront();
            } catch (Exception ignored) {
            }
            try {
                Object raw = target.evaluate(
                        "() => ({href: String(location.href), visibility: String(document.visibilityState)})");
                if (raw instanceof Map<?, ?> values) {
                    String href = String.valueOf(values.get("href"));
                    String visibility = String.valueOf(values.get("visibility"));
                    if (href.isBlank() || href.startsWith("about:")) {
                        lastIssue = "登录页面仍在空白页: " + href;
                    } else if (!"visible".equals(visibility) && hasOtherContentPage(ctx, target)) {
                        lastIssue = "登录页面不是可见前台标签页";
                    } else {
                        return href;
                    }
                }
            } catch (Exception error) {
                lastIssue = "无法确认登录页面可见性: " + error.getMessage();
            }
            try {
                Thread.sleep(150);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("登录启动被中断", error);
            }
        }
        throw new IllegalStateException(lastIssue);
    }

    private void scheduleForegroundReconcile(long myGen, BrowserContext ctx, Page target) {
        try {
            loginExecutor.execute(() -> {
                if (generation.get() != myGen) return;
                try {
                    if (target.isClosed()) return;
                    // Only reclaim the foreground when a blank tab actually stole it; a
                    // content tab opened by the login flow must keep the user's focus.
                    if (closeBlankTabs(ctx, target)) target.bringToFront();
                } catch (Exception ignored) {
                }
            });
        } catch (Exception ignored) {
        }
    }

    private boolean closeBlankTabs(BrowserContext ctx, Page target) {
        boolean closed = false;
        for (Page other : ctx.pages()) {
            if (other != target && isBlankPage(other)) {
                try {
                    other.close();
                    closed = true;
                } catch (Exception ignored) {
                }
            }
        }
        return closed;
    }

    private boolean hasOtherContentPage(BrowserContext ctx, Page target) {
        for (Page other : ctx.pages()) {
            if (other != target && !isBlankPage(other)) return true;
        }
        return false;
    }

    private boolean isBlankPage(Page candidate) {
        try {
            String url = candidate.url();
            return url == null || url.isBlank()
                    || url.startsWith("about:")
                    || url.startsWith("chrome://newtab")
                    || url.startsWith("chrome://new-tab-page");
        } catch (Exception error) {
            return true;
        }
    }

    private boolean isLikelyLoggedIn(String platform, String url) {
        if (url == null || url.isBlank()) return false;
        String normalized = url.toLowerCase(Locale.ROOT);
        return switch (platform) {
            case "boss" -> !normalized.contains("/web/user/") && normalized.contains("zhipin.com");
            case "liepin" -> !normalized.contains("/login") && normalized.contains("liepin.com");
            case "51job" -> !normalized.contains("login.51job.com") && normalized.contains("51job.com");
            case "zhilian" -> !normalized.contains("passport") && normalized.contains("zhaopin.com");
            default -> false;
        };
    }

    private Path profileFor(String platform) {
        Path base;
        if (configuredLoginDir != null && !configuredLoginDir.isBlank()) {
            base = Paths.get(configuredLoginDir);
        } else if (configuredBaseDir != null && !configuredBaseDir.isBlank()) {
            base = Paths.get(configuredBaseDir).resolve("login-only");
        } else {
            base = Paths.get(System.getProperty("user.dir"), "browser-data", "login-only");
        }
        return base.toAbsolutePath().normalize().resolve(platform);
    }

    private String normalize(String platform) {
        String normalized = platform == null ? "" : platform.trim().toLowerCase(Locale.ROOT);
        if (!PLATFORMS.contains(normalized)) {
            throw new IllegalArgumentException("不支持的平台: " + platform);
        }
        return normalized;
    }

    public synchronized Map<String, Object> status() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("platform", activePlatform == null ? "" : activePlatform);
        status.put("state", state);
        status.put("initialized", initialized);
        status.put("loggedIn", loggedIn);
        status.put("url", currentUrl);
        status.put("tasksStarted", false);
        status.put("schedulerEnabled", false);
        if (message != null) {
            status.put("message", message);
        }
        return status;
    }

    public void stop() {
        long stoppedGeneration;
        synchronized (this) {
            stoppedGeneration = generation.incrementAndGet();
            activePlatform = null;
            state = "not_started";
            initialized = false;
            loggedIn = false;
            alive = false;
            currentUrl = "";
            message = null;
        }
        runOnLoginThread(() -> {
            if (generation.get() == stoppedGeneration) closeBrowser();
        });
    }

    private void runOnLoginThread(Runnable action) {
        if ("login-only-browser".equals(Thread.currentThread().getName())) {
            action.run();
            return;
        }
        if (loginExecutor.isShutdown()) {
            return;
        }
        try {
            loginExecutor.submit(action).get(10, TimeUnit.SECONDS);
        } catch (Exception error) {
            log.warn("执行登录浏览器任务失败: {}", error.getMessage());
        }
    }

    private void closeBrowser() {
        BrowserContext ctxToClose;
        Playwright pwToClose;
        synchronized (this) {
            ctxToClose = this.context;
            pwToClose = this.playwright;
            this.context = null;
            this.page = null;
            this.playwright = null;
            this.alive = false;
        }
        safeCloseContext(ctxToClose);
        safeClosePlaywright(pwToClose);
    }

    private void safeCloseContext(BrowserContext ctx) {
        if (ctx != null) {
            try {
                ctx.close();
            } catch (Exception error) {
                recordCleanupFailure(error);
            }
        }
    }

    private void safeClosePlaywright(Playwright pw) {
        if (pw != null) {
            try {
                pw.close();
            } catch (Exception error) {
                recordCleanupFailure(error);
            }
        }
    }

    private synchronized void recordCleanupFailure(Exception error) {
        // Sticky: even an unpublished old launch may have failed cleanup before the claim.
        if (cleanupFailure == null) cleanupFailure = error;
        else if (cleanupFailure != error) cleanupFailure.addSuppressed(error);
    }

    void closeActivePage() {
        runOnLoginThread(() -> {
            try {
                if (page != null && !page.isClosed()) {
                    page.close();
                }
            } catch (Exception ignored) {
            }
        });
    }

    Map<String, Object> queryWindowMetrics() {
        if (loginExecutor.isShutdown()) {
            return Map.of();
        }
        try {
            return loginExecutor.submit(() -> {
                Map<String, Object> metrics = new LinkedHashMap<>();
                if (page == null || page.isClosed()) {
                    metrics.put("open", false);
                    return metrics;
                }
                metrics.put("open", true);
                metrics.put("title", page.title());
                metrics.put("pages", context.pages().size());
                metrics.put("blankPages", context.pages().stream().filter(LoginOnlyBrowserService.this::isBlankPage).count());
                try {
                    Object dims = page.evaluate("() => ({\n" +
                            "  outerWidth: window.outerWidth,\n" +
                            "  outerHeight: window.outerHeight,\n" +
                            "  innerWidth: window.innerWidth,\n" +
                            "  innerHeight: window.innerHeight,\n" +
                            "  visibilityState: document.visibilityState\n" +
                            "})");
                    metrics.put("metrics", dims);
                } catch (Exception ignored) {
                }
                return metrics;
            }).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            return Map.of("error", e.getMessage());
        }
    }

    @PreDestroy
    public void destroy() {
        CompletableFuture<BossProfileHandoff> pending;
        synchronized (this) {
            destroying = true;
            handoffReady = false;
            pending = handoffCompletion;
        }
        if (pending != null) {
            pending.completeExceptionally(new IllegalStateException("登录服务正在关闭，交接失效"));
        }
        stop();
        loginExecutor.shutdown();
        try {
            if (!loginExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                loginExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            loginExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public static class ActiveLoginException extends RuntimeException {
        private final String activePlatform;

        public ActiveLoginException(String activePlatform) {
            this(activePlatform, "已有登录浏览器: " + activePlatform + "，请先关闭再切换平台");
        }

        private ActiveLoginException(String activePlatform, String message) {
            super(message);
            this.activePlatform = activePlatform;
        }

        public String activePlatform() {
            return activePlatform;
        }
    }
}
