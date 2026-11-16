package com.getjobs.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.getjobs.application.chat.BossChatStore;
import com.getjobs.application.chat.ChatContracts.*;
import com.getjobs.application.controller.BossChatController;
import com.getjobs.application.config.StartupRunner;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.LoadState;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * B7 最小 UI→真实本地 HTTP→临时库→回显 验收测试。
 * Spring 后端端口 18081；静态前端端口 18082；
 * 静态前端服务由 JDK 内置 HttpServer 托管临时目录中的 front/out 产物；
 * Playwright 路由将所有 API 请求固定转发至临时后端 18081。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
        properties = {"server.port=18081", "server.address=127.0.0.1", "app.scheduling.enabled=false", "app.browser.auto-start=false", "app.static.enabled=false"})
class B7UiEchoTest {

    private static final int PORT = 18081;
    private static final int STATIC_PORT = 18082;
    private static final Path TEMP_DIR;
    private static final Path STATIC_FRONT_DIR;
    private static final String CHROME = resolveChrome();

    /** 浏览器路径：优先 BOSS_TEST_CHROME，其次在本机 ms-playwright 目录中自动探测；找不到返回空串（测试自行跳过）。 */
    private static String resolveChrome() {
        String env = System.getenv("BOSS_TEST_CHROME");
        if (env != null && !env.isBlank()) {
            return env;
        }
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData == null || localAppData.isBlank()) {
            return "";
        }
        Path root = Path.of(localAppData, "ms-playwright");
        if (!Files.isDirectory(root)) {
            return "";
        }
        for (String exe : List.of("chrome-headless-shell.exe", "chrome.exe")) {
            try (Stream<Path> walk = Files.walk(root, 4)) {
                String found = walk.filter(p -> p.getFileName().toString().equals(exe))
                        .map(Path::toString)
                        .findFirst()
                        .orElse("");
                if (!found.isEmpty()) {
                    return found;
                }
            } catch (IOException e) {
                return "";
            }
        }
        return "";
    }

    private static HttpServer staticServer;
    private static Playwright playwright;
    private static Browser browser;

    static {
        // 固定端口 18081 / 18082：若被外部占用则测试内直接跳过，禁止 kill 外部进程
        if (isPortOccupied(PORT)) {
            System.err.println("端口 " + PORT + " 已被占用，将跳过 B7UiEchoTest");
        }
        if (isPortOccupied(STATIC_PORT)) {
            System.err.println("端口 " + STATIC_PORT + " 已被占用，将跳过 B7UiEchoTest");
        }
        try {
            TEMP_DIR = Files.createTempDirectory("b7ui-");
            STATIC_FRONT_DIR = TEMP_DIR.resolve("front-static");
            Files.createDirectories(STATIC_FRONT_DIR);
            initSchema(TEMP_DIR.resolve("b7.db"));
        } catch (Exception e) {
            throw new IllegalStateException("B7 UI 测试基础设施初始化失败", e);
        }
    }

    private static boolean isPortOccupied(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 300);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        String dbPath = TEMP_DIR.resolve("b7.db").toAbsolutePath().toString().replace('\\', '/');
        String assetsPath = TEMP_DIR.resolve("assets").toAbsolutePath().toString().replace('\\', '/');

        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + dbPath);
        registry.add("boss.resume.document.dir", () -> assetsPath);
        registry.add("boss.read-only-check-mode", () -> "false");
    }

    @MockBean
    private StartupRunner startupRunner;

    @MockBean
    private com.getjobs.application.init.DefaultPageConfigInitializer defaultPageConfigInitializer;

    @MockBean
    private com.getjobs.application.init.CookieSeedInitializer cookieSeedInitializer;

    @MockBean
    private com.getjobs.application.init.ZhilianOptionInitializer zhilianOptionInitializer;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private BossChatStore bossChatStore;

    private static byte[] createMinimalValidPdf(String marker) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        baos.write("%PDF-1.4\n".getBytes(StandardCharsets.US_ASCII));

        int offset1 = baos.size();
        baos.write("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n".getBytes(StandardCharsets.US_ASCII));

        int offset2 = baos.size();
        baos.write("2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n".getBytes(StandardCharsets.US_ASCII));

        int offset3 = baos.size();
        String markerComment = "% marker: " + marker + "\n";
        String paddingComment = "% padding: " + "A".repeat(150) + "\n";
        byte[] obj3 = ("3 0 obj\n<< /Type /Page /Parent 2 0 R >>\n" + markerComment + paddingComment + "endobj\n")
                .getBytes(StandardCharsets.US_ASCII);
        baos.write(obj3);

        int xrefOffset = baos.size();
        String xref = "xref\n" +
                "0 4\n" +
                "0000000000 65535 f \r\n" +
                String.format("%010d 00000 n \r\n", offset1) +
                String.format("%010d 00000 n \r\n", offset2) +
                String.format("%010d 00000 n \r\n", offset3);
        baos.write(xref.getBytes(StandardCharsets.US_ASCII));

        String trailer = "trailer\n" +
                "<< /Size 4 /Root 1 0 R >>\n" +
                "startxref\n" +
                xrefOffset + "\n" +
                "%%EOF\n";
        baos.write(trailer.getBytes(StandardCharsets.US_ASCII));

        byte[] result = baos.toByteArray();
        assertTrue(result.length >= 200, "PDF 必须 >= 200 字节");
        return result;
    }

    @BeforeAll
    static void setup() throws IOException {
        Assumptions.assumeFalse(isPortOccupied(PORT), "端口 " + PORT + " 已被占用，跳过 UI 联调");
        Assumptions.assumeFalse(isPortOccupied(STATIC_PORT), "端口 " + STATIC_PORT + " 已被占用，跳过 UI 联调");
        Assumptions.assumeTrue(Files.isExecutable(Paths.get(CHROME)), "缺少 Chromium 可执行文件 (" + CHROME + ")，跳过 UI 联调");

        copyFrontOutToTemp(STATIC_FRONT_DIR);
        startStaticServer();

        playwright = Playwright.create(new Playwright.CreateOptions()
                .setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")));
        browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                .setHeadless(true)
                .setExecutablePath(Paths.get(CHROME)));
    }

    @AfterAll
    static void tearDown() {
        if (staticServer != null) {
            try { staticServer.stop(0); } catch (Exception ignored) {}
        }
        if (browser != null) {
            try { browser.close(); } catch (Exception ignored) {}
        }
        if (playwright != null) {
            try { playwright.close(); } catch (Exception ignored) {}
        }
        if (TEMP_DIR != null && Files.exists(TEMP_DIR)) {
            try (Stream<Path> walk = Files.walk(TEMP_DIR)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                });
            } catch (Exception ignored) {}
        }
    }

    private static void startStaticServer() throws IOException {
        staticServer = HttpServer.create(new InetSocketAddress("127.0.0.1", STATIC_PORT), 0);
        staticServer.createContext("/", new StaticFileHandler(STATIC_FRONT_DIR));
        staticServer.setExecutor(null);
        staticServer.start();
    }

    private static class StaticFileHandler implements HttpHandler {
        private final Path rootDir;

        StaticFileHandler(Path rootDir) {
            this.rootDir = rootDir;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            if (path == null || path.isEmpty()) {
                path = "/";
            }

            Path resolvedFile = resolveStaticFile(rootDir, path);

            if (resolvedFile == null || !Files.exists(resolvedFile) || Files.isDirectory(resolvedFile)) {
                resolvedFile = rootDir.resolve("index.html");
            }

            if (!Files.exists(resolvedFile)) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }

            byte[] bytes = Files.readAllBytes(resolvedFile);
            String contentType = determineContentType(resolvedFile.getFileName().toString());

            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.sendResponseHeaders(200, bytes.length);

            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }

        private static Path resolveStaticFile(Path root, String rawPath) {
            if ("/".equals(rawPath)) {
                return root.resolve("index.html");
            }
            if ("/boss".equals(rawPath)) {
                return root.resolve("boss.html");
            }

            String relative = rawPath.startsWith("/") ? rawPath.substring(1) : rawPath;

            // 1. 同名文件
            Path candidate = root.resolve(relative);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }

            // 2. Next 静态导出的无扩展名页面，如 /profile -> profile.html。
            Path exportedPage = root.resolve(relative + ".html");
            if (Files.isRegularFile(exportedPage)) {
                return exportedPage;
            }

            // 3. <path>/index.html
            Path candidateDirIndex = candidate.resolve("index.html");
            if (Files.isRegularFile(candidateDirIndex)) {
                return candidateDirIndex;
            }

            // 4. 回退 index.html
            Path fallback = root.resolve("index.html");
            if (Files.isRegularFile(fallback)) {
                return fallback;
            }

            return null;
        }

        private static String determineContentType(String fileName) {
            String lower = fileName.toLowerCase();
            if (lower.endsWith(".html")) return "text/html; charset=UTF-8";
            if (lower.endsWith(".js") || lower.endsWith(".mjs")) return "application/javascript; charset=UTF-8";
            if (lower.endsWith(".css")) return "text/css; charset=UTF-8";
            if (lower.endsWith(".json")) return "application/json; charset=UTF-8";
            if (lower.endsWith(".png")) return "image/png";
            if (lower.endsWith(".svg")) return "image/svg+xml";
            if (lower.endsWith(".woff2")) return "font/woff2";
            if (lower.endsWith(".ico")) return "image/x-icon";
            return "application/octet-stream";
        }
    }

    @Test
    void uiSavesWorkItemAndEchoesAfterReload() throws Exception {
        Assumptions.assumeTrue(browser != null, "浏览器实例未就绪，跳过");

        try (BrowserContext context = browser.newContext(); Page page = context.newPage()) {
            StringBuilder dbg = new StringBuilder();
            page.onPageError(e -> dbg.append("PAGEERROR: ").append(e).append("\n"));
            page.onResponse(r -> {
                if (r.status() >= 400) {
                    dbg.append("HTTP ").append(r.status()).append(" ").append(r.url()).append("\n");
                }
            });

            try {
                // 不依赖静态构建时的 API_BASE，所有 API 都只访问本测试临时后端。
                page.route("**/api/**", route -> {
                    var original = java.net.URI.create(route.request().url());
                    String targetUrl = "http://127.0.0.1:" + PORT + original.getRawPath()
                            + (original.getRawQuery() == null ? "" : "?" + original.getRawQuery());
                    route.resume(new Route.ResumeOptions().setUrl(targetUrl));
                });

                // 1. 资料编辑入口已迁至独立 Profile 页。
                page.navigate("http://127.0.0.1:" + STATIC_PORT + "/profile");
                page.waitForLoadState(LoadState.DOMCONTENTLOADED);
                String extraFact = "B7_SYNTHETIC_EXTRA_FACT";
                Locator extraFactInput = page.getByPlaceholder("补充经历之外的信息，如到岗时间、每周可实习天数");
                extraFactInput.fill(extraFact);
                page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("添加").setExact(true)).click();
                assertTrue(page.getByText(extraFact, new Page.GetByTextOptions().setExact(true)).isVisible(),
                        "添加后补充事实应显示为独立条目");
                assertEquals("", extraFactInput.inputValue(), "添加后草稿输入框应清空");
                page.getByRole(AriaRole.TAB, new Page.GetByRoleOptions().setName("经历").setExact(true)).click();

                // 2. 新增一条工作经历，并填写必填字段。
                page.getByRole(AriaRole.BUTTON,
                        new Page.GetByRoleOptions().setName("新增").setExact(true)).click();
                Locator company = page.locator("label").filter(new Locator.FilterOptions().setHasText("公司"))
                        .first().locator("..").locator("input");
                Locator title = page.locator("label").filter(new Locator.FilterOptions().setHasText("职位"))
                        .first().locator("..").locator("input");
                company.fill("B7联调公司");
                title.fill("产品经理");

                // 3. 保存并等待真实 HTTP 成功响应。
                Response saveResponse = page.waitForResponse(
                        r -> r.url().contains("/api/boss/chat/profile") && "PUT".equalsIgnoreCase(r.request().method()),
                        () -> page.getByRole(AriaRole.BUTTON,
                                new Page.GetByRoleOptions().setName("保存").setExact(true)).click()
                );
                assertEquals(200, saveResponse.status(), "PUT /api/boss/chat/profile 应返回 200");

                // 4. 用 TestRestTemplate GET /api/boss/chat/profile 断言 resume.work[0].company == B7联调公司 且 facts 中存在 work: 开头条目
                String body = rest.getForObject("/api/boss/chat/profile", String.class);
                var node = new ObjectMapper().readTree(body);
                assertEquals("B7联调公司", node.at("/resume/work/0/company").asText(), "work[0].company 应为 B7联调公司");
                boolean hasWorkFact = false;
                boolean hasExtraFact = false;
                if (node.has("facts") && node.get("facts").isArray()) {
                    for (var fact : node.get("facts")) {
                        if (fact.has("id") && fact.get("id").asText().startsWith("work:")) {
                            hasWorkFact = true;
                        }
                        if (extraFact.equals(fact.path("text").asText())) hasExtraFact = true;
                    }
                }
                assertTrue(hasWorkFact, "facts 中应存在 work: 开头的条目");
                assertTrue(hasExtraFact, "HTTP 回读应包含已添加的补充事实");

                // 5. page.reload() + networkidle，断言页面上能看到 B7联调公司（回显）
                page.reload();
                page.waitForLoadState(LoadState.DOMCONTENTLOADED);
                page.getByText(extraFact, new Page.GetByTextOptions().setExact(true)).waitFor();
                page.getByRole(AriaRole.TAB, new Page.GetByRoleOptions().setName("经历").setExact(true)).click();
                assertEquals("B7联调公司", company.inputValue(), "页面重新加载后公司输入框应回显 B7联调公司");

            } catch (Throwable t) {
                try {
                    dbg.append("----BODY----\n").append(page.locator("body").innerText());
                    Path debugDir = TEMP_DIR.resolve("debug");
                    Files.createDirectories(debugDir);
                    Files.writeString(debugDir.resolve("ui-debug.txt"), dbg.toString(), StandardCharsets.UTF_8);
                } catch (Exception ignored) {
                }
                throw t;
            }
        }
    }

    @Test
    void uiTemplateCrudAndApiReadback() throws Exception {
        Assumptions.assumeTrue(browser != null, "浏览器实例未就绪，跳过");
        String name = "B7-UI-TPL-" + System.nanoTime();
        String editedName = name + "-EDIT";
        try (BrowserContext context = browser.newContext(); Page page = newProxiedPage(context)) {
            StringBuilder dbg = new StringBuilder();
            page.onPageError(e -> dbg.append("PAGEERROR: ").append(e).append("\n"));
            page.onResponse(r -> {
                if (r.status() >= 400) {
                    dbg.append("HTTP ").append(r.status()).append(" ").append(r.url()).append("\n");
                }
            });
            try {
                page.navigate("http://127.0.0.1:" + STATIC_PORT + "/search-template");
                page.waitForLoadState(LoadState.DOMCONTENTLOADED);
                page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("新建").setExact(true)).click();
                page.locator("#templateName").fill(name);
                Locator targetTitles = page.locator("label")
                        .filter(new Locator.FilterOptions().setHasText("目标岗位"))
                        .first().locator("..").locator("input");
                targetTitles.fill("AI产品经理");

                Response create = page.waitForResponse(
                        r -> r.url().contains("/api/search-templates") && "POST".equalsIgnoreCase(r.request().method()),
                        () -> page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("保存").setExact(true)).click()
                );
                assertEquals(201, create.status(), "创建模板应返回 201");
                page.getByText("已保存", new Page.GetByTextOptions().setExact(true)).waitFor();

                JsonNode created = findTemplateByName(objectMapper.readTree(
                        rest.getForObject("/api/search-templates", String.class)), name);
                assertNotNull(created, "创建后 API 列表应包含模板 " + name);
                String id = created.path("id").asText();
                assertEquals(1, created.path("version").asLong(), "新模板 version=1");
                assertEquals("AI产品经理", created.at("/criteria/targetTitles/0").asText());

                page.locator("#templateName").fill(editedName);
                Response update = page.waitForResponse(
                        r -> r.url().contains("/api/search-templates/" + id)
                                && "PUT".equalsIgnoreCase(r.request().method()),
                        () -> page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("保存").setExact(true)).click()
                );
                assertEquals(200, update.status(), "修改模板应返回 200");
                JsonNode edited = findTemplateByName(objectMapper.readTree(
                        rest.getForObject("/api/search-templates", String.class)), editedName);
                assertNotNull(edited, "修改后 API 列表应包含新名称");
                assertEquals(id, edited.path("id").asText());
                assertEquals(2, edited.path("version").asLong(), "修改后 version=2");

                page.reload();
                page.waitForLoadState(LoadState.DOMCONTENTLOADED);
                page.getByText(editedName, new Page.GetByTextOptions().setExact(true)).first().waitFor();
                assertEquals(editedName, page.locator("#templateName").inputValue(),
                        "刷新后应选中并回显模板名");

                page.onDialog(dialog -> dialog.accept());
                Response delete = page.waitForResponse(
                        r -> r.url().contains("/api/search-templates/" + id)
                                && "DELETE".equalsIgnoreCase(r.request().method()),
                        () -> page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("删除").setExact(true)).click()
                );
                assertEquals(204, delete.status(), "删除模板应返回 204");
                page.getByText("已删除", new Page.GetByTextOptions().setExact(true)).waitFor();
                JsonNode afterDelete = objectMapper.readTree(
                        rest.getForObject("/api/search-templates", String.class));
                assertNull(findTemplateByName(afterDelete, editedName), "删除后 API 列表不应再有模板");

                ResponseEntity<String> status = rest.getForEntity("/api/login-only/status", String.class);
                assertEquals(HttpStatus.OK, status.getStatusCode());
                assertFalse(objectMapper.readTree(status.getBody()).path("tasksStarted").asBoolean(true),
                        "模板 CRUD 不得启动任务");
            } catch (Throwable t) {
                try {
                    dbg.append("----BODY----\n").append(page.locator("body").innerText());
                    Path debugDir = TEMP_DIR.resolve("debug");
                    Files.createDirectories(debugDir);
                    Files.writeString(debugDir.resolve("template-ui-debug.txt"), dbg.toString(), StandardCharsets.UTF_8);
                } catch (Exception ignored) {
                }
                throw t;
            }
        }
    }

    @Test
    void uiConnectionSaveTestSeparationAndFakeKeyRetention() throws Exception {
        Assumptions.assumeTrue(browser != null, "浏览器实例未就绪，跳过");
        String model = "b7-ui-model-" + System.nanoTime();
        String model2 = model + "-2";
        String fakeKey = "sk-b7-fake-" + System.nanoTime();
        try (BrowserContext context = browser.newContext(); Page page = newProxiedPage(context)) {
            StringBuilder dbg = new StringBuilder();
            page.onPageError(e -> dbg.append("PAGEERROR: ").append(e).append("\n"));
            page.onResponse(r -> {
                if (r.status() >= 400) {
                    dbg.append("HTTP ").append(r.status()).append(" ").append(r.url()).append("\n");
                }
            });
            try {
                page.navigate("http://127.0.0.1:" + STATIC_PORT + "/env-config");
                page.waitForLoadState(LoadState.DOMCONTENTLOADED);

                page.locator("#connection-base-url").waitFor();
                Locator typeButton = page.getByText("接口类型", new Page.GetByTextOptions().setExact(true))
                        .locator("..").locator("button").first();
                typeButton.click();
                Locator dropdown = page.locator(".dropdown-panel").last();
                dropdown.waitFor();
                List<String> optionTexts = dropdown.locator("li").allTextContents();
                assertTrue(optionTexts.stream().noneMatch(t -> t.toLowerCase().contains("stub")),
                        "日常连接入口不得出现 Stub: " + optionTexts);
                assertTrue(optionTexts.stream().noneMatch(t -> t.toLowerCase().contains("mcp")),
                        "日常连接入口不得出现 MCP: " + optionTexts);
                assertEquals(1, page.locator("#connection-base-url").count(), "只应存在一个连接表单");

                dropdown.locator("li").filter(new Locator.FilterOptions()
                        .setHasText("OpenAI Chat Completions")).click();
                page.locator("#connection-base-url").fill("http://127.0.0.1:9");
                page.locator("#connection-model").fill(model);
                page.locator("#connection-api-key").fill(fakeKey);

                Response save = page.waitForResponse(
                        r -> r.url().contains("/api/ai/connection") && "PUT".equalsIgnoreCase(r.request().method()),
                        () -> page.getByRole(AriaRole.BUTTON,
                                new Page.GetByRoleOptions().setName("保存连接").setExact(true)).click()
                );
                assertEquals(200, save.status(), "保存连接应返回 200");
                page.getByText("连接已保存", new Page.GetByTextOptions().setExact(true)).waitFor();

                JsonNode saved = objectMapper.readTree(rest.getForObject("/api/ai/connections", String.class)).get(0);
                assertEquals("OPENAI", saved.path("provider").asText());
                assertEquals("OPENAI_CHAT_COMPLETIONS", saved.path("protocol").asText());
                assertEquals("http://127.0.0.1:9", saved.path("baseUrl").asText());
                assertEquals(model, saved.path("model").asText());
                assertTrue(saved.path("apiKeyConfigured").asBoolean(), "假 key 应已保存");
                assertTrue(saved.path("enabled").asBoolean());
                assertFalse(saved.path("executionVerified").asBoolean(), "保存不等于验证成功");
                assertEquals("", page.locator("#connection-api-key").inputValue(), "保存后不应回显密钥");
                assertTrue(page.locator("#connection-api-key").getAttribute("placeholder").contains("留空保留已有密钥"));
                page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("清除").setExact(true)).waitFor();

                // 测试与保存分离：只连本机 127.0.0.1:9，失败必须可见且不能标记 verified。
                Response test = page.waitForResponse(
                        r -> r.url().contains("/api/ai/connection/test") && "POST".equalsIgnoreCase(r.request().method()),
                        () -> page.getByRole(AriaRole.BUTTON,
                                new Page.GetByRoleOptions().setName("测试连接").setExact(true)).click()
                );
                assertEquals(200, test.status());
                JsonNode testJson = objectMapper.readTree(test.body());
                assertFalse(testJson.path("success").asBoolean(true), "本机 loopback 失败不得假成功");
                assertFalse(testJson.path("verified").asBoolean(true), "失败不得标记 verified");
                page.getByText("连接测试失败").waitFor();
                JsonNode afterTest = objectMapper.readTree(rest.getForObject("/api/ai/connections", String.class)).get(0);
                assertFalse(afterTest.path("executionVerified").asBoolean(), "失败后 executionVerified 必须为 false");

                // 留空 key 再保存：只改模型，已有假 key 必须保留。
                page.locator("#connection-model").fill(model2);
                Response saveAgain = page.waitForResponse(
                        r -> r.url().contains("/api/ai/connection") && "PUT".equalsIgnoreCase(r.request().method()),
                        () -> page.getByRole(AriaRole.BUTTON,
                                new Page.GetByRoleOptions().setName("保存连接").setExact(true)).click()
                );
                assertEquals(200, saveAgain.status());
                JsonNode retained = objectMapper.readTree(rest.getForObject("/api/ai/connections", String.class)).get(0);
                assertEquals(model2, retained.path("model").asText());
                assertTrue(retained.path("apiKeyConfigured").asBoolean(), "留空保存必须保留已有 key");

                // 显式清除：只清除本用例写入的假 key。
                page.onDialog(dialog -> dialog.accept());
                Response clear = page.waitForResponse(
                        r -> r.url().contains("/api/config/API_KEY") && "DELETE".equalsIgnoreCase(r.request().method()),
                        () -> page.getByRole(AriaRole.BUTTON,
                                new Page.GetByRoleOptions().setName("清除").setExact(true)).click()
                );
                assertEquals(200, clear.status());
                page.getByText("密钥已清除", new Page.GetByTextOptions().setExact(true)).waitFor();
                JsonNode cleared = objectMapper.readTree(rest.getForObject("/api/ai/connections", String.class)).get(0);
                assertFalse(cleared.path("apiKeyConfigured").asBoolean(), "显式清除后 key 不应存在");
                assertFalse(cleared.path("enabled").asBoolean(), "无 key 时连接不得为 enabled");
                assertEquals(0, page.getByRole(AriaRole.BUTTON,
                        new Page.GetByRoleOptions().setName("清除").setExact(true)).count());
            } catch (Throwable t) {
                try {
                    dbg.append("----BODY----\n").append(page.locator("body").innerText());
                    Path debugDir = TEMP_DIR.resolve("debug");
                    Files.createDirectories(debugDir);
                    Files.writeString(debugDir.resolve("connection-ui-debug.txt"), dbg.toString(), StandardCharsets.UTF_8);
                } catch (Exception ignored) {
                }
                throw t;
            }
        }
    }

    @Test
    void uiResumeAssetsUploadPreviewSelectUndoDelete() throws Exception {
        Assumptions.assumeTrue(browser != null, "浏览器实例未就绪，跳过");
        Path pdf1 = TEMP_DIR.resolve("b7-ui-pdf1.pdf");
        Path pdf2 = TEMP_DIR.resolve("b7-ui-pdf2.pdf");
        Path png = TEMP_DIR.resolve("b7-ui-test.png");
        Files.write(pdf1, createMinimalValidPdf("b7-ui-pdf-1"));
        Files.write(pdf2, createMinimalValidPdf("b7-ui-pdf-2-different"));
        writeMinimalPng(png);

        try (BrowserContext context = browser.newContext(); Page page = newProxiedPage(context)) {
            StringBuilder dbg = new StringBuilder();
            page.onPageError(e -> dbg.append("PAGEERROR: ").append(e).append("\n"));
            page.onResponse(r -> {
                if (r.status() >= 400) {
                    dbg.append("HTTP ").append(r.status()).append(" ").append(r.url()).append("\n");
                }
            });
            try {
                page.navigate("http://127.0.0.1:" + STATIC_PORT + "/profile");
                page.waitForLoadState(LoadState.DOMCONTENTLOADED);
                waitForProfileLoaded(page);
                page.getByRole(AriaRole.TAB, new Page.GetByRoleOptions().setName("简历").setExact(true)).click();
                page.getByText("PDF 简历", new Page.GetByTextOptions().setExact(true)).waitFor();

                Response uploadPdf1 = page.waitForResponse(
                        r -> r.url().contains("/api/boss/config/resume-document")
                                && "POST".equalsIgnoreCase(r.request().method()),
                        () -> page.setInputFiles("#resume-upload-pdf", pdf1)
                );
                assertEquals(200, uploadPdf1.status(), "PDF 上传应返回 200");
                page.getByText("已上传，选择使用后保存", new Page.GetByTextOptions().setExact(true)).waitFor();
                JsonNode pdf1Asset = findAssetByKind(objectMapper.readTree(
                        rest.getForObject("/api/boss/config/resume-assets", String.class)), "PDF");
                assertTrue(pdf1Asset.path("exists").asBoolean(), "上传后 PDF 资产应存在");
                String pdf1Id = pdf1Asset.path("assetId").asText();
                assertTrue(pdf1Id.startsWith("sha256:"), "PDF assetId 应为 sha256");

                Locator pdfCard = assetCard(page, "PDF 简历");
                pdfCard.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("预览").setExact(true)).click();
                page.getByRole(AriaRole.DIALOG).waitFor();
                page.locator("iframe[title='PDF 预览']").waitFor();
                page.getByLabel("关闭预览").click();

                pdfCard.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("选择使用").setExact(true)).click();
                page.getByText("已选择 PDF 简历，保存后生效", new Page.GetByTextOptions().setExact(true)).waitFor();
                saveProfileThroughUi(page);
                JsonNode approvedPdf = currentProfile();
                assertEquals(pdf1Id, approvedPdf.path("approvedPdfAssetId").asText());
                assertEquals("PDF", approvedPdf.path("preferredResumeAssetKind").asText());

                page.reload();
                page.waitForLoadState(LoadState.DOMCONTENTLOADED);
                waitForProfileLoaded(page);
                page.getByRole(AriaRole.TAB, new Page.GetByRoleOptions().setName("简历").setExact(true)).click();
                pdfCard = assetCard(page, "PDF 简历");
                pdfCard.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("撤销选择").setExact(true)).waitFor();

                Response uploadPdf2 = page.waitForResponse(
                        r -> r.url().contains("/api/boss/config/resume-document")
                                && "POST".equalsIgnoreCase(r.request().method()),
                        () -> page.setInputFiles("#resume-upload-pdf", pdf2)
                );
                assertEquals(200, uploadPdf2.status(), "替换 PDF 应返回 200");
                page.getByText("已上传，选择使用后保存", new Page.GetByTextOptions().setExact(true)).waitFor();
                JsonNode pdf2Asset = findAssetByKind(objectMapper.readTree(
                        rest.getForObject("/api/boss/config/resume-assets", String.class)), "PDF");
                String pdf2Id = pdf2Asset.path("assetId").asText();
                assertNotEquals(pdf1Id, pdf2Id, "替换后 assetId 必须变化");
                saveProfileThroughUi(page);
                assertEquals("", currentProfile().path("approvedPdfAssetId").asText(),
                        "替换后旧 PDF 批准必须失效");

                pdfCard.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("选择使用").setExact(true)).click();
                page.getByText("已选择 PDF 简历，保存后生效", new Page.GetByTextOptions().setExact(true)).waitFor();
                saveProfileThroughUi(page);
                assertEquals(pdf2Id, currentProfile().path("approvedPdfAssetId").asText());

                pdfCard.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("撤销选择").setExact(true)).click();
                page.getByText("已撤销选择，保存后生效", new Page.GetByTextOptions().setExact(true)).waitFor();
                saveProfileThroughUi(page);
                assertEquals("", currentProfile().path("approvedPdfAssetId").asText());

                page.onDialog(dialog -> dialog.accept());
                Locator pdfCardForDelete = pdfCard;
                Response deletePdf = page.waitForResponse(
                        r -> r.url().contains("/api/boss/config/resume-document")
                                && "DELETE".equalsIgnoreCase(r.request().method()),
                        () -> pdfCardForDelete.getByRole(AriaRole.BUTTON,
                                new Locator.GetByRoleOptions().setName("删除").setExact(true)).click()
                );
                assertEquals(200, deletePdf.status(), "删除 PDF 应返回 200");
                page.getByText("已删除", new Page.GetByTextOptions().setExact(true)).waitFor();
                assertFalse(findAssetByKind(objectMapper.readTree(
                        rest.getForObject("/api/boss/config/resume-assets", String.class)), "PDF")
                        .path("exists").asBoolean(), "删除后 PDF 资产不应存在");
                saveProfileThroughUi(page);

                Response uploadImage = page.waitForResponse(
                        r -> r.url().contains("/api/boss/config/resume-image")
                                && "POST".equalsIgnoreCase(r.request().method()),
                        () -> page.setInputFiles("#resume-upload-image", png)
                );
                assertEquals(200, uploadImage.status(), "图片上传应返回 200");
                page.getByText("已上传，选择使用后保存", new Page.GetByTextOptions().setExact(true)).waitFor();
                JsonNode imageAsset = findAssetByKind(objectMapper.readTree(
                        rest.getForObject("/api/boss/config/resume-assets", String.class)), "IMAGE");
                assertTrue(imageAsset.path("exists").asBoolean(), "上传后图片资产应存在");
                assertTrue(imageAsset.path("contentType").asText().startsWith("image/"));
                String imageId = imageAsset.path("assetId").asText();

                Locator imageCard = assetCard(page, "图片简历");
                imageCard.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("预览").setExact(true)).click();
                page.getByRole(AriaRole.DIALOG).waitFor();
                page.locator("img[alt='图片简历预览']").waitFor();
                page.getByLabel("关闭预览").click();

                imageCard.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("选择使用").setExact(true)).click();
                page.getByText("已选择图片简历，保存后生效", new Page.GetByTextOptions().setExact(true)).waitFor();
                saveProfileThroughUi(page);
                JsonNode approvedImage = currentProfile();
                assertEquals(imageId, approvedImage.path("approvedImageAssetId").asText());
                assertEquals("IMAGE", approvedImage.path("preferredResumeAssetKind").asText());

                page.reload();
                page.waitForLoadState(LoadState.DOMCONTENTLOADED);
                waitForProfileLoaded(page);
                page.getByRole(AriaRole.TAB, new Page.GetByRoleOptions().setName("简历").setExact(true)).click();
                imageCard = assetCard(page, "图片简历");
                imageCard.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("撤销选择").setExact(true)).waitFor();
                imageCard.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("撤销选择").setExact(true)).click();
                page.getByText("已撤销选择，保存后生效", new Page.GetByTextOptions().setExact(true)).waitFor();
                saveProfileThroughUi(page);
                assertEquals("", currentProfile().path("approvedImageAssetId").asText());

                Locator imageCardForDelete = imageCard;
                Response deleteImage = page.waitForResponse(
                        r -> r.url().contains("/api/boss/config/resume-image")
                                && "DELETE".equalsIgnoreCase(r.request().method()),
                        () -> imageCardForDelete.getByRole(AriaRole.BUTTON,
                                new Locator.GetByRoleOptions().setName("删除").setExact(true)).click()
                );
                assertEquals(200, deleteImage.status(), "删除图片应返回 200");
                page.getByText("已删除", new Page.GetByTextOptions().setExact(true)).waitFor();
                assertFalse(findAssetByKind(objectMapper.readTree(
                        rest.getForObject("/api/boss/config/resume-assets", String.class)), "IMAGE")
                        .path("exists").asBoolean(), "删除后图片资产不应存在");
                saveProfileThroughUi(page);

                ResponseEntity<String> status = rest.getForEntity("/api/login-only/status", String.class);
                assertEquals(HttpStatus.OK, status.getStatusCode());
                assertFalse(objectMapper.readTree(status.getBody()).path("tasksStarted").asBoolean(true),
                        "资产操作不得启动任务");
            } catch (Throwable t) {
                try {
                    dbg.append("----BODY----\n").append(page.locator("body").innerText());
                    Path debugDir = TEMP_DIR.resolve("debug");
                    Files.createDirectories(debugDir);
                    Files.writeString(debugDir.resolve("asset-ui-debug.txt"), dbg.toString(), StandardCharsets.UTF_8);
                } catch (Exception ignored) {
                }
                throw t;
            }
        }
    }

    @Test
    void uiProfileIntentionsValidationAndPersistence() throws Exception {
        Assumptions.assumeTrue(browser != null, "浏览器实例未就绪，跳过");
        try (BrowserContext context = browser.newContext(); Page page = newProxiedPage(context)) {
            StringBuilder dbg = new StringBuilder();
            page.onPageError(e -> dbg.append("PAGEERROR: ").append(e).append("\n"));
            page.onResponse(r -> {
                if (r.status() >= 400) {
                    dbg.append("HTTP ").append(r.status()).append(" ").append(r.url()).append("\n");
                }
            });
            try {
                page.navigate("http://127.0.0.1:" + STATIC_PORT + "/profile");
                page.waitForLoadState(LoadState.DOMCONTENTLOADED);
                waitForProfileLoaded(page);

                page.locator("#graduationYear").fill("2029");
                page.getByText("工作类型", new Page.GetByTextOptions().setExact(true))
                        .locator("..").locator("input").first().fill("实习");
                page.getByText("期望薪资（个人）", new Page.GetByTextOptions().setExact(true))
                        .locator("..").locator("input").first().fill("QA22-250/天");
                page.getByText("到岗意向", new Page.GetByTextOptions().setExact(true))
                        .locator("..").locator("input").first().fill("QA22-随时到岗");
                page.getByText("可到岗时间", new Page.GetByTextOptions().setExact(true))
                        .locator("..").locator("input").first().fill("2027-03");
                page.getByText("每周可实习天数", new Page.GetByTextOptions().setExact(true))
                        .locator("..").locator("input").first().fill("3");

                Locator cityInput = page.getByPlaceholder("输入城市名");
                cityInput.fill("B7测试城");
                cityInput.press("Enter");
                page.getByText("B7测试城", new Page.GetByTextOptions().setExact(true)).first().waitFor();

                saveProfileThroughUi(page);
                JsonNode profile = currentProfile();
                assertEquals(2029, profile.path("graduationYear").asInt());
                assertEquals("实习", profile.at("/intentions/workType").asText());
                assertEquals("QA22-250/天", profile.at("/intentions/expectedSalary").asText());
                assertEquals("QA22-随时到岗", profile.at("/intentions/arrivalIntention").asText());
                assertEquals("2027-03", profile.at("/intentions/availableFrom").asText());
                assertEquals(3, profile.at("/intentions/weeklyAvailabilityDays").asInt());
                assertTrue(profile.path("acceptedCities").toString().contains("B7测试城"));

                page.reload();
                page.waitForLoadState(LoadState.DOMCONTENTLOADED);
                waitForProfileLoaded(page);
                assertEquals("2029", page.locator("#graduationYear").inputValue());
                assertEquals("实习", page.getByText("工作类型", new Page.GetByTextOptions().setExact(true))
                        .locator("..").locator("input").first().inputValue());
                assertEquals("QA22-250/天", page.getByText("期望薪资（个人）", new Page.GetByTextOptions().setExact(true))
                        .locator("..").locator("input").first().inputValue());
                assertEquals("QA22-随时到岗", page.getByText("到岗意向", new Page.GetByTextOptions().setExact(true))
                        .locator("..").locator("input").first().inputValue());
                assertEquals("2027-03", page.getByText("可到岗时间", new Page.GetByTextOptions().setExact(true))
                        .locator("..").locator("input").first().inputValue());
                assertEquals("3", page.getByText("每周可实习天数", new Page.GetByTextOptions().setExact(true))
                        .locator("..").locator("input").first().inputValue());
                page.getByText("B7测试城", new Page.GetByTextOptions().setExact(true)).first().waitFor();

                Locator weekly = page.getByText("每周可实习天数", new Page.GetByTextOptions().setExact(true))
                        .locator("..").locator("input").first();
                weekly.fill("8");
                page.getByRole(AriaRole.BUTTON,
                        new Page.GetByRoleOptions().setName("保存").setExact(true)).click();
                page.getByText("每周可实习天数需在 1 到 7 之间",
                        new Page.GetByTextOptions().setExact(true)).waitFor();
                assertEquals(3, currentProfile().at("/intentions/weeklyAvailabilityDays").asInt(),
                        "前端校验失败不得写入 8");

                weekly.fill("3");
                saveProfileThroughUi(page);
                assertEquals(3, currentProfile().at("/intentions/weeklyAvailabilityDays").asInt());

                ResponseEntity<String> status = rest.getForEntity("/api/login-only/status", String.class);
                assertEquals(HttpStatus.OK, status.getStatusCode());
                assertFalse(objectMapper.readTree(status.getBody()).path("tasksStarted").asBoolean(true),
                        "资料意向保存不得启动任务");
            } catch (Throwable t) {
                try {
                    dbg.append("----BODY----\n").append(page.locator("body").innerText());
                    Path debugDir = TEMP_DIR.resolve("debug");
                    Files.createDirectories(debugDir);
                    Files.writeString(debugDir.resolve("profile-intentions-debug.txt"), dbg.toString(), StandardCharsets.UTF_8);
                } catch (Exception ignored) {
                }
                throw t;
            }
        }
    }

    @Test
    void uiAiContentStubStylesDraftSaveAndGenerate() throws Exception {
        Assumptions.assumeTrue(browser != null, "浏览器实例未就绪，跳过");
        setStubConnection();
        String marker = "QA22-AI-" + System.nanoTime();
        String skills = marker + "-技能介绍";
        String intro = marker + "-个人介绍";
        String sayHi = marker + "-招呼语";
        try (BrowserContext context = browser.newContext(); Page page = newProxiedPage(context)) {
            StringBuilder dbg = new StringBuilder();
            page.onPageError(e -> dbg.append("PAGEERROR: ").append(e).append("\n"));
            page.onResponse(r -> {
                if (r.status() >= 400) {
                    dbg.append("HTTP ").append(r.status()).append(" ").append(r.url()).append("\n");
                }
            });
            try {
                page.navigate("http://127.0.0.1:" + STATIC_PORT + "/ai-config");
                page.waitForLoadState(LoadState.DOMCONTENTLOADED);
                page.getByRole(AriaRole.TAB, new Page.GetByRoleOptions().setName("内容").setExact(true)).click();
                for (String style : List.of("专业正式", "自然真诚", "简洁直接", "轻松亲和", "活泼俏皮", "自定义")) {
                    page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName(style).setExact(true))
                            .first().waitFor();
                }
                page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("自定义").setExact(true)).click();
                page.getByPlaceholder("自定义风格描述").fill("QA22自定义风格");

                Locator draftCard = page.getByText("可编辑草稿", new Page.GetByTextOptions().setExact(true))
                        .locator("../..");
                draftCard.getByText("技能介绍", new Locator.GetByTextOptions().setExact(true))
                        .locator("..").locator("textarea").first().fill(skills);
                draftCard.getByText("个人介绍", new Locator.GetByTextOptions().setExact(true))
                        .locator("..").locator("textarea").first().fill(intro);
                draftCard.getByText("招呼语", new Locator.GetByTextOptions().setExact(true))
                        .locator("..").locator("textarea").first().fill(sayHi);

                Response save = page.waitForResponse(
                        r -> r.url().contains("/api/ai/content") && "PUT".equalsIgnoreCase(r.request().method()),
                        () -> page.getByRole(AriaRole.BUTTON,
                                new Page.GetByRoleOptions().setName("保存草稿").setExact(true)).first().click()
                );
                assertEquals(200, save.status());
                page.getByText("草稿已保存", new Page.GetByTextOptions().setExact(true)).waitFor();
                JsonNode draft = objectMapper.readTree(rest.getForObject("/api/ai/content", String.class));
                assertEquals(skills, draft.path("skillsIntro").asText());
                assertEquals(intro, draft.path("personalIntro").asText());
                assertEquals(sayHi, draft.path("sayHiDraft").asText());
                assertEquals("custom", draft.path("style").asText());
                assertEquals("QA22自定义风格", draft.path("customStyle").asText());

                page.reload();
                page.waitForLoadState(LoadState.DOMCONTENTLOADED);
                page.getByRole(AriaRole.TAB, new Page.GetByRoleOptions().setName("内容").setExact(true)).click();
                draftCard = page.getByText("可编辑草稿", new Page.GetByTextOptions().setExact(true))
                        .locator("../..");
                assertEquals(skills, draftCard.getByText("技能介绍", new Locator.GetByTextOptions().setExact(true))
                        .locator("..").locator("textarea").first().inputValue());
                page.getByText("模拟草稿", new Page.GetByTextOptions().setExact(true)).first().waitFor();

                Response generate = page.waitForResponse(
                        r -> r.url().contains("/api/ai/content/generate")
                                && "POST".equalsIgnoreCase(r.request().method()),
                        () -> page.getByRole(AriaRole.BUTTON,
                                new Page.GetByRoleOptions().setName("生成预览").setExact(true)).click()
                );
                assertEquals(200, generate.status());
                JsonNode generated = objectMapper.readTree(generate.body());
                assertTrue(generated.path("simulated").asBoolean(), "STUB 必须标记 simulated=true");
                assertFalse(generated.path("verified").asBoolean(), "STUB 不得标记真实 verified");
                assertTrue(generated.path("connectionMessage").asText().contains("Stub"));
                assertFalse(generated.path("skillsIntro").asText().isBlank());
                page.getByText("已生成模拟草稿，未调用真实模型", new Page.GetByTextOptions().setExact(true)).waitFor();
                page.getByText("模拟生成", new Page.GetByTextOptions().setExact(true)).first().waitFor();

                page.getByRole(AriaRole.BUTTON,
                        new Page.GetByRoleOptions().setName("应用全部到草稿").setExact(true)).click();
                page.waitForFunction(
                        "expected => Array.from(document.querySelectorAll('textarea')).some(t => t.value === expected)",
                        generated.path("skillsIntro").asText());
                Response saveGenerated = page.waitForResponse(
                        r -> r.url().contains("/api/ai/content") && "PUT".equalsIgnoreCase(r.request().method()),
                        () -> page.getByRole(AriaRole.BUTTON,
                                new Page.GetByRoleOptions().setName("保存草稿").setExact(true)).first().click()
                );
                assertEquals(200, saveGenerated.status());
                JsonNode afterApply = objectMapper.readTree(rest.getForObject("/api/ai/content", String.class));
                assertEquals(generated.path("skillsIntro").asText(), afterApply.path("skillsIntro").asText(),
                        "应用后草稿应保存 Stub 技能介绍");
            } catch (Throwable t) {
                try {
                    dbg.append("----BODY----\n").append(page.locator("body").innerText());
                    Path debugDir = TEMP_DIR.resolve("debug");
                    Files.createDirectories(debugDir);
                    Files.writeString(debugDir.resolve("ai-content-debug.txt"), dbg.toString(), StandardCharsets.UTF_8);
                } catch (Exception ignored) {
                }
                throw t;
            }
        }
    }

    private void setStubConnection() throws Exception {
        Map<String, Object> body = Map.of(
                "provider", "STUB",
                "protocol", "STUB",
                "baseUrl", "",
                "model", ""
        );
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = rest.exchange(
                "/api/ai/connection",
                HttpMethod.PUT,
                new HttpEntity<>(body, headers),
                String.class
        );
        assertEquals(HttpStatus.OK, response.getStatusCode(), "STUB 连接应由本地后端接受");
        JsonNode active = objectMapper.readTree(
                rest.getForObject("/api/ai/connections", String.class)).get(0);
        assertTrue(active.path("simulated").asBoolean(), "active connection 应为 simulated");
    }

    @Test
    void uiTemplateRepeatSaveNo500() throws Exception {
        Assumptions.assumeTrue(browser != null, "浏览器实例未就绪，跳过");
        String name = "B7-UI-X04TPL-" + System.nanoTime();
        String id = createTemplateViaApi(name, false,
                Map.of("keywords", List.of("B7-UI-X04"), "targetTitles", List.of("产品经理")));
        try (BrowserContext context = browser.newContext(); Page page = newProxiedPage(context)) {
            StringBuilder dbg = new StringBuilder();
            page.onPageError(e -> dbg.append("PAGEERROR: ").append(e).append("\n"));
            page.onResponse(r -> {
                if (r.status() >= 500) {
                    dbg.append("HTTP ").append(r.status()).append(" ").append(r.url()).append("\n");
                }
            });
            try {
                page.navigate("http://127.0.0.1:" + STATIC_PORT + "/search-template");
                page.waitForLoadState(LoadState.DOMCONTENTLOADED);
                page.locator("#templateName").waitFor();
                page.getByText(name, new Page.GetByTextOptions().setExact(true)).first().waitFor();

                Response firstSave = page.waitForResponse(
                        r -> r.url().contains("/api/search-templates/" + id)
                                && "PUT".equalsIgnoreCase(r.request().method()),
                        () -> page.getByRole(AriaRole.BUTTON,
                                new Page.GetByRoleOptions().setName("保存").setExact(true)).click()
                );
                assertEquals(200, firstSave.status(), "模板首次保存应 200，不得 500");
                Response secondSave = page.waitForResponse(
                        r -> r.url().contains("/api/search-templates/" + id)
                                && "PUT".equalsIgnoreCase(r.request().method()),
                        () -> page.getByRole(AriaRole.BUTTON,
                                new Page.GetByRoleOptions().setName("保存").setExact(true)).click()
                );
                assertNotEquals(500, secondSave.status(), "重复保存不得返回 500");
                assertTrue(secondSave.status() == 200 || secondSave.status() == 409,
                        "重复保存应为 200 或显式 409，实际 " + secondSave.status());

                JsonNode list = objectMapper.readTree(rest.getForObject("/api/search-templates", String.class));
                long matches = 0;
                JsonNode saved = null;
                for (JsonNode item : list) {
                    if (name.equals(item.path("name").asText())) {
                        matches++;
                        saved = item;
                    }
                }
                assertEquals(1, matches, "重复保存不得产生重复模板");
                assertNotNull(saved);
                assertTrue(saved.path("version").asLong() >= 2, "保存后 version 应递增");
                ResponseEntity<String> status = rest.getForEntity("/api/login-only/status", String.class);
                assertEquals(HttpStatus.OK, status.getStatusCode());
                assertFalse(objectMapper.readTree(status.getBody()).path("tasksStarted").asBoolean(true));
            } catch (Throwable t) {
                try {
                    dbg.append("----BODY----\n").append(page.locator("body").innerText());
                    Path debugDir = TEMP_DIR.resolve("debug");
                    Files.createDirectories(debugDir);
                    Files.writeString(debugDir.resolve("template-repeat-debug.txt"), dbg.toString(), StandardCharsets.UTF_8);
                } catch (Exception ignored) {
                }
                throw t;
            }
        } finally {
            rest.exchange("/api/search-templates/" + id, HttpMethod.DELETE, null, String.class);
        }
    }

    @Test
    void uiAiConnectionSummaryEditLinkNavigatesToEnvConfig() throws Exception {
        Assumptions.assumeTrue(browser != null, "浏览器实例未就绪，跳过");
        try (BrowserContext context = browser.newContext(); Page page = newProxiedPage(context)) {
            page.navigate("http://127.0.0.1:" + STATIC_PORT + "/ai-config");
            page.waitForLoadState(LoadState.DOMCONTENTLOADED);
            page.getByText("模型连接", new Page.GetByTextOptions().setExact(true)).first().waitFor();
            Locator editLink = page.getByRole(AriaRole.LINK,
                    new Page.GetByRoleOptions().setName("编辑连接").setExact(true));
            editLink.waitFor();
            editLink.click();
            page.waitForURL("**/env-config");
            page.locator("#connection-base-url").waitFor();
            assertEquals(1, page.locator("#connection-base-url").count(), "环境页应只有一个连接表单");
            assertEquals(1, page.locator("#connection-api-key").count());
        }
    }

    @Test
    void uiNetworkFailureNavigationRefreshAndRepeat() throws Exception {
        Assumptions.assumeTrue(browser != null, "浏览器实例未就绪，跳过");

        // X01: 只中止本次测试页面的 API，不改后端，验证可见错误且保存不可用。
        try (BrowserContext context = browser.newContext(); Page page = context.newPage()) {
            page.route("**/api/**", route -> route.abort());
            page.navigate("http://127.0.0.1:" + STATIC_PORT + "/profile");
            page.waitForLoadState(LoadState.DOMCONTENTLOADED);
            page.getByText("无法连接后端，资料未加载", new Page.GetByTextOptions().setExact(true)).waitFor();
            assertTrue(page.getByRole(AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName("保存").setExact(true)).isDisabled(),
                    "加载失败时保存必须禁用");
        }

        // X03: 标准导航无未捕获异常，liepin/zhilian 配置 API 不得再有缺表 500。
        try (BrowserContext context = browser.newContext(); Page page = newProxiedPage(context)) {
            List<String> pageErrors = new ArrayList<>();
            List<String> http500s = new ArrayList<>();
            page.onPageError(e -> pageErrors.add(String.valueOf(e)));
            page.onResponse(r -> {
                if (r.status() >= 500) http500s.add(r.status() + " " + r.url());
            });
            for (String path : List.of("profile", "search-template", "env-config", "ai-config",
                    "boss", "liepin", "51job", "zhilian")) {
                page.navigate("http://127.0.0.1:" + STATIC_PORT + "/" + path);
                page.waitForLoadState(LoadState.DOMCONTENTLOADED);
            }
            assertTrue(pageErrors.isEmpty(), "页面不应有未捕获异常: " + pageErrors);
            assertTrue(http500s.stream().noneMatch(u ->
                            u.contains("/api/liepin/config") || u.contains("/api/zhilian/config")),
                    "liepin/zhilian 配置 API 不得再有缺表 500: " + http500s);
            assertEquals(HttpStatus.OK,
                    rest.getForEntity("/api/liepin/config", String.class).getStatusCode(),
                    "补表后 GET /api/liepin/config 应为 200");
            assertEquals(HttpStatus.OK,
                    rest.getForEntity("/api/zhilian/config", String.class).getStatusCode(),
                    "补表后 GET /api/zhilian/config 应为 200");
        }

        // X04: 资料事实重复点击保存 + 刷新，事实仍唯一，且不启动任务。
        String marker = "B7-X04-FACT-" + System.nanoTime();
        try (BrowserContext context = browser.newContext(); Page page = newProxiedPage(context)) {
            page.navigate("http://127.0.0.1:" + STATIC_PORT + "/profile");
            page.waitForLoadState(LoadState.DOMCONTENTLOADED);
            waitForProfileLoaded(page);
            Locator factInput = page.getByPlaceholder("补充经历之外的信息，如到岗时间、每周可实习天数");
            factInput.fill(marker);
            page.getByRole(AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName("添加").setExact(true)).click();
            page.getByText(marker, new Page.GetByTextOptions().setExact(true)).first().waitFor();

            Response firstSave = page.waitForResponse(
                    r -> r.url().contains("/api/boss/chat/profile") && "PUT".equalsIgnoreCase(r.request().method()),
                    () -> page.getByRole(AriaRole.BUTTON,
                            new Page.GetByRoleOptions().setName("保存").setExact(true)).click()
            );
            assertEquals(200, firstSave.status(), "首次资料保存应成功");
            Response secondSave = page.waitForResponse(
                    r -> r.url().contains("/api/boss/chat/profile") && "PUT".equalsIgnoreCase(r.request().method()),
                    () -> page.getByRole(AriaRole.BUTTON,
                            new Page.GetByRoleOptions().setName("保存").setExact(true)).click()
            );
            assertTrue(secondSave.status() == 200 || secondSave.status() == 409,
                    "重复点击应成功或显式 409");

            JsonNode profile = currentProfile();
            long matches = 0;
            for (JsonNode fact : profile.path("facts")) {
                if (marker.equals(fact.path("text").asText())) matches++;
            }
            assertEquals(1, matches, "重复保存不得产生重复事实");

            page.reload();
            page.waitForLoadState(LoadState.DOMCONTENTLOADED);
            waitForProfileLoaded(page);
            page.getByText(marker, new Page.GetByTextOptions().setExact(true)).first().waitFor();
            profile = currentProfile();
            matches = 0;
            for (JsonNode fact : profile.path("facts")) {
                if (marker.equals(fact.path("text").asText())) matches++;
            }
            assertEquals(1, matches, "刷新后事实仍应唯一");

            ResponseEntity<String> status = rest.getForEntity("/api/login-only/status", String.class);
            assertEquals(HttpStatus.OK, status.getStatusCode());
            assertFalse(objectMapper.readTree(status.getBody()).path("tasksStarted").asBoolean(true));
        }
    }

    private void waitForProfileLoaded(Page page) {
        page.waitForFunction("() => { const b=Array.from(document.querySelectorAll('button')).find(x=>x.textContent.trim()==='保存'); return b && !b.disabled; }");
    }

    private void saveProfileThroughUi(Page page) throws Exception {
        Response save = page.waitForResponse(
                r -> r.url().contains("/api/boss/chat/profile") && "PUT".equalsIgnoreCase(r.request().method()),
                () -> page.getByRole(AriaRole.BUTTON,
                        new Page.GetByRoleOptions().setName("保存").setExact(true)).click()
        );
        assertEquals(200, save.status(), "保存资料应返回 200");
        page.getByText("已保存", new Page.GetByTextOptions().setExact(true)).waitFor();
    }

    private JsonNode currentProfile() throws Exception {
        return objectMapper.readTree(rest.getForObject("/api/boss/chat/profile", String.class));
    }

    private Locator assetCard(Page page, String title) {
        return page.getByText(title, new Page.GetByTextOptions().setExact(true)).locator("../..");
    }

    private static JsonNode findAssetByKind(JsonNode assets, String kind) {
        if (assets == null || !assets.isArray()) return null;
        for (JsonNode item : assets) {
            if (kind.equals(item.path("kind").asText())) return item;
        }
        return null;
    }

    private static void writeMinimalPng(Path path) throws IOException {
        BufferedImage image = new BufferedImage(24, 24, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, 24, 24);
            graphics.setColor(Color.BLUE);
            graphics.drawString("TEST", 2, 14);
        } finally {
            graphics.dispose();
        }
        ImageIO.write(image, "png", path.toFile());
    }

    @Test
    void uiTemplateInheritanceOverrideAndRestore() throws Exception {
        Assumptions.assumeTrue(browser != null, "浏览器实例未就绪，跳过");
        String name = "B7-UI-INHERIT-" + System.nanoTime();
        seedProfileIntentions();
        String id = createTemplateViaApi(name, true,
                Map.of("keywords", List.of("B7-UI-KEYWORD"), "targetTitles", List.of("产品经理")));
        try (BrowserContext context = browser.newContext(); Page page = newProxiedPage(context)) {
            StringBuilder dbg = new StringBuilder();
            page.onPageError(e -> dbg.append("PAGEERROR: ").append(e).append("\n"));
            page.onResponse(r -> {
                if (r.status() >= 400) {
                    dbg.append("HTTP ").append(r.status()).append(" ").append(r.url()).append("\n");
                }
            });
            try {
                page.navigate("http://127.0.0.1:" + STATIC_PORT + "/boss");
                page.waitForLoadState(LoadState.DOMCONTENTLOADED);
                page.getByText("默认模板", new Page.GetByTextOptions().setExact(true)).waitFor();
                page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("预览").setExact(true))
                        .first().click();
                page.getByText("继承资料", new Page.GetByTextOptions().setExact(true)).first().waitFor();
                assertTrue(page.getByText("继承资料", new Page.GetByTextOptions().setExact(true)).count() >= 3,
                        "预览应显示多个 PROFILE 继承来源");
                assertTrue(page.getByText("200/天", new Page.GetByTextOptions().setExact(true)).first().isVisible());
                assertTrue(page.getByText("实习", new Page.GetByTextOptions().setExact(true)).first().isVisible());
                assertTrue(page.getByText("随时到岗", new Page.GetByTextOptions().setExact(true)).first().isVisible());

                page.navigate("http://127.0.0.1:" + STATIC_PORT + "/search-template");
                page.waitForLoadState(LoadState.DOMCONTENTLOADED);
                page.locator("#templateName").waitFor();
                page.getByText(name, new Page.GetByTextOptions().setExact(true)).first().waitFor();

                Locator salaryGroup = page.getByText("个人期望薪资", new Page.GetByTextOptions().setExact(true))
                        .locator("..");
                salaryGroup.locator("..").locator("input").first().fill("300/天");
                saveTemplateThroughUi(page);
                JsonNode effective = effectiveFor(id);
                assertEquals("TEMPLATE", effective.at("/fields/expectedSalary/source").asText());
                assertEquals("300/天", effective.at("/fields/expectedSalary/value").asText());

                salaryGroup = page.getByText("个人期望薪资", new Page.GetByTextOptions().setExact(true))
                        .locator("..");
                salaryGroup.getByRole(AriaRole.BUTTON,
                        new Locator.GetByRoleOptions().setName("继承").setExact(true)).click();
                saveTemplateThroughUi(page);
                effective = effectiveFor(id);
                assertEquals("PROFILE", effective.at("/fields/expectedSalary/source").asText());
                assertEquals("200/天", effective.at("/fields/expectedSalary/value").asText());

                page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("展开").setExact(true)).click();
                Locator bossSection = page.getByText("BOSS直聘", new Page.GetByTextOptions().setExact(true))
                        .locator("../..");
                bossSection.getByText("关键词", new Locator.GetByTextOptions().setExact(true))
                        .locator("..").locator("input").first().fill("B7-UI-OVERRIDE");
                Response patch = page.waitForResponse(
                        r -> r.url().contains("/platform-overrides/BOSS")
                                && "PATCH".equalsIgnoreCase(r.request().method()),
                        () -> bossSection.getByRole(AriaRole.BUTTON,
                                new Locator.GetByRoleOptions().setName("保存覆盖").setExact(true)).click()
                );
                assertEquals(200, patch.status());
                JsonNode patched = objectMapper.readTree(patch.body());
                assertEquals("B7-UI-OVERRIDE", patched.at("/fields/keywords/0").asText());

                page.onDialog(dialog -> dialog.accept());
                Response restored = page.waitForResponse(
                        r -> r.url().contains("/platform-overrides/BOSS")
                                && "PATCH".equalsIgnoreCase(r.request().method()),
                        () -> bossSection.getByRole(AriaRole.BUTTON,
                                new Locator.GetByRoleOptions().setName("恢复继承").setExact(true)).click()
                );
                assertEquals(200, restored.status());
                JsonNode overrides = objectMapper.readTree(
                        rest.getForObject("/api/search-templates/" + id + "/platform-overrides", String.class));
                JsonNode bossOverride = findOverrideByPlatform(overrides, "BOSS");
                assertNotNull(bossOverride);
                assertEquals(0, bossOverride.path("fields").size(), "恢复继承后平台覆盖应为空");
            } catch (Throwable t) {
                try {
                    dbg.append("----BODY----\n").append(page.locator("body").innerText());
                    Path debugDir = TEMP_DIR.resolve("debug");
                    Files.createDirectories(debugDir);
                    Files.writeString(debugDir.resolve("template-inherit-debug.txt"), dbg.toString(), StandardCharsets.UTF_8);
                } catch (Exception ignored) {
                }
                throw t;
            }
        } finally {
            rest.exchange("/api/search-templates/" + id, HttpMethod.DELETE, null, String.class);
        }
    }

    @Test
    void uiTemplateStaleVersionShows409WithoutOverwrite() throws Exception {
        Assumptions.assumeTrue(browser != null, "浏览器实例未就绪，跳过");
        String name = "B7-UI-409-" + System.nanoTime();
        String serverName = name + "-SERVER";
        String staleName = name + "-UI-STALE";
        String id = createTemplateViaApi(name, false,
                Map.of("keywords", List.of("B7-UI-409"), "targetTitles", List.of("产品经理")));
        try (BrowserContext context = browser.newContext(); Page page = newProxiedPage(context)) {
            StringBuilder dbg = new StringBuilder();
            page.onPageError(e -> dbg.append("PAGEERROR: ").append(e).append("\n"));
            page.onResponse(r -> {
                if (r.status() >= 400) {
                    dbg.append("HTTP ").append(r.status()).append(" ").append(r.url()).append("\n");
                }
            });
            try {
                page.navigate("http://127.0.0.1:" + STATIC_PORT + "/search-template");
                page.waitForLoadState(LoadState.DOMCONTENTLOADED);
                page.locator("#templateName").waitFor();
                page.getByText(name, new Page.GetByTextOptions().setExact(true)).first().waitFor();

                JsonNode template = objectMapper.readTree(
                        rest.getForObject("/api/search-templates/" + id, String.class));
                Map<String, Object> update = new LinkedHashMap<>();
                update.put("name", serverName);
                update.put("isDefault", false);
                update.put("version", 1);
                update.put("criteria", objectMapper.convertValue(template.path("criteria"), Map.class));
                update.put("sayHiDraft", "server-side update");
                update.put("expectedSalary", null);
                update.put("arrivalIntention", null);
                update.put("linkedResumeAssetId", null);
                update.put("personalIntro", null);
                update.put("communicationStyle", null);
                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);
                ResponseEntity<String> serverUpdate = rest.exchange(
                        "/api/search-templates/" + id,
                        HttpMethod.PUT,
                        new HttpEntity<>(update, headers),
                        String.class
                );
                assertEquals(HttpStatus.OK, serverUpdate.getStatusCode());
                JsonNode afterServer = objectMapper.readTree(
                        rest.getForObject("/api/search-templates/" + id, String.class));
                assertEquals(2, afterServer.path("version").asLong());

                page.locator("#templateName").fill(staleName);
                Response staleSave = page.waitForResponse(
                        r -> r.url().contains("/api/search-templates/" + id)
                                && "PUT".equalsIgnoreCase(r.request().method()),
                        () -> page.getByRole(AriaRole.BUTTON,
                                new Page.GetByRoleOptions().setName("保存").setExact(true)).click()
                );
                assertEquals(409, staleSave.status(), "过期版本保存应返回 409");
                page.getByText("409 VERSION_CONFLICT").waitFor();
                JsonNode afterStale = objectMapper.readTree(
                        rest.getForObject("/api/search-templates/" + id, String.class));
                assertEquals(serverName, afterStale.path("name").asText(), "旧 UI 不得覆盖新数据");
                assertEquals(2, afterStale.path("version").asLong());
                ResponseEntity<String> status = rest.getForEntity("/api/login-only/status", String.class);
                assertFalse(objectMapper.readTree(status.getBody()).path("tasksStarted").asBoolean(true));
            } catch (Throwable t) {
                try {
                    dbg.append("----BODY----\n").append(page.locator("body").innerText());
                    Path debugDir = TEMP_DIR.resolve("debug");
                    Files.createDirectories(debugDir);
                    Files.writeString(debugDir.resolve("template-409-debug.txt"), dbg.toString(), StandardCharsets.UTF_8);
                } catch (Exception ignored) {
                }
                throw t;
            }
        } finally {
            rest.exchange("/api/search-templates/" + id, HttpMethod.DELETE, null, String.class);
        }
    }

    @Test
    void uiTemplateApplyFourPlatformsDoesNotStartTasks() throws Exception {
        Assumptions.assumeTrue(browser != null, "浏览器实例未就绪，跳过");
        String name = "B7-UI-APPLY-" + System.nanoTime();
        String id = createTemplateViaApi(name, true,
                Map.of("keywords", List.of("B7-UI-APPLY"), "targetTitles", List.of("产品经理")));
        try (BrowserContext context = browser.newContext(); Page page = newProxiedPage(context)) {
            StringBuilder dbg = new StringBuilder();
            page.onPageError(e -> dbg.append("PAGEERROR: ").append(e).append("\n"));
            page.onResponse(r -> {
                if (r.status() >= 400) {
                    dbg.append("HTTP ").append(r.status()).append(" ").append(r.url()).append("\n");
                }
            });
            try {
                for (String[] platform : List.of(
                        new String[]{"boss", "BOSS"},
                        new String[]{"liepin", "LIEPIN"},
                        new String[]{"51job", "JOB51"},
                        new String[]{"zhilian", "ZHILIAN"})) {
                    page.navigate("http://127.0.0.1:" + STATIC_PORT + "/" + platform[0]);
                    page.waitForLoadState(LoadState.DOMCONTENTLOADED);
                    page.getByText("默认模板", new Page.GetByTextOptions().setExact(true)).waitFor();
                    Response apply = page.waitForResponse(
                            r -> r.url().contains("/apply/" + platform[1])
                                    && "POST".equalsIgnoreCase(r.request().method()),
                            () -> page.getByRole(AriaRole.BUTTON,
                                    new Page.GetByRoleOptions().setName("套用到配置").setExact(true)).click()
                    );
                    assertEquals(200, apply.status(), "模板套用应返回 200: " + platform[1]);
                    JsonNode result = objectMapper.readTree(apply.body());
                    assertFalse(result.path("tasksStarted").asBoolean(true), "套用不得启动任务: " + platform[1]);
                    assertEquals(platform[1], result.path("platformId").asText());
                    assertTrue(result.at("/appliedFields/keywords").asText().contains("B7-UI-APPLY"),
                            "关键词应被套用: " + platform[1]);
                    page.getByText("已更新平台配置，未启动任务",
                            new Page.GetByTextOptions().setExact(true)).waitFor();
                    ResponseEntity<String> status = rest.getForEntity("/api/login-only/status", String.class);
                    assertEquals(HttpStatus.OK, status.getStatusCode());
                    assertFalse(objectMapper.readTree(status.getBody()).path("tasksStarted").asBoolean(true),
                            "套用后 tasksStarted 必须为 false");
                }
            } catch (Throwable t) {
                try {
                    dbg.append("----BODY----\n").append(page.locator("body").innerText());
                    Path debugDir = TEMP_DIR.resolve("debug");
                    Files.createDirectories(debugDir);
                    Files.writeString(debugDir.resolve("template-apply-debug.txt"), dbg.toString(), StandardCharsets.UTF_8);
                } catch (Exception ignored) {
                }
                throw t;
            }
        } finally {
            rest.exchange("/api/search-templates/" + id, HttpMethod.DELETE, null, String.class);
        }
    }

    @SuppressWarnings("unchecked")
    private void seedProfileIntentions() throws Exception {
        Map<String, Object> profile = objectMapper.convertValue(currentProfile(), Map.class);
        profile.put("acceptedCities", List.of("上海", "杭州"));
        profile.put("intentions", Map.of(
                "workType", "实习",
                "expectedSalary", "200/天",
                "arrivalIntention", "随时到岗",
                "availableFrom", "2027-01",
                "weeklyAvailabilityDays", 3));
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = rest.exchange(
                "/api/boss/chat/profile",
                HttpMethod.PUT,
                new HttpEntity<>(profile, headers),
                String.class
        );
        assertEquals(HttpStatus.OK, response.getStatusCode(), "合成意向保存应返回 200");
    }

    private String createTemplateViaApi(String name, boolean isDefault, Map<String, Object> criteria) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("isDefault", isDefault);
        body.put("criteria", criteria);
        body.put("sayHiDraft", "B7 UI 模板招呼");
        body.put("expectedSalary", null);
        body.put("arrivalIntention", null);
        body.put("linkedResumeAssetId", null);
        body.put("personalIntro", null);
        body.put("communicationStyle", null);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = rest.postForEntity(
                "/api/search-templates",
                new HttpEntity<>(body, headers),
                String.class
        );
        assertEquals(HttpStatus.CREATED, response.getStatusCode(), "模板 fixture 创建应返回 201");
        return objectMapper.readTree(response.getBody()).path("id").asText();
    }

    private void saveTemplateThroughUi(Page page) throws Exception {
        Response save = page.waitForResponse(
                r -> r.url().contains("/api/search-templates") && "PUT".equalsIgnoreCase(r.request().method()),
                () -> page.getByRole(AriaRole.BUTTON,
                        new Page.GetByRoleOptions().setName("保存").setExact(true)).click()
        );
        assertEquals(200, save.status(), "模板保存应返回 200");
        page.getByText("已保存", new Page.GetByTextOptions().setExact(true)).waitFor();
    }

    private JsonNode effectiveFor(String id) throws Exception {
        return objectMapper.readTree(rest.getForObject(
                "/api/search-templates/" + id + "/effective", String.class));
    }

    private static JsonNode findOverrideByPlatform(JsonNode overrides, String platform) {
        if (overrides == null || !overrides.isArray()) return null;
        for (JsonNode item : overrides) {
            if (platform.equals(item.path("platformId").asText())) return item;
        }
        return null;
    }

    private Page newProxiedPage(BrowserContext context) {
        Page page = context.newPage();
        page.route("**/api/**", route -> {
            var original = java.net.URI.create(route.request().url());
            String targetUrl = "http://127.0.0.1:" + PORT + original.getRawPath()
                    + (original.getRawQuery() == null ? "" : "?" + original.getRawQuery());
            route.resume(new Route.ResumeOptions().setUrl(targetUrl));
        });
        return page;
    }

    private static JsonNode findTemplateByName(JsonNode list, String name) {
        if (list == null || !list.isArray()) {
            return null;
        }
        for (JsonNode item : list) {
            if (name.equals(item.path("name").asText())) {
                return item;
            }
        }
        return null;
    }

    @Test
    void pdfUploadApproveAndRevoke() throws Exception {
        byte[] pdf1 = createMinimalValidPdf("pdf-1");
        ByteArrayResource res1 = new ByteArrayResource(pdf1) {
            @Override
            public String getFilename() {
                return "resume.pdf";
            }
        };

        MultiValueMap<String, Object> body1 = new LinkedMultiValueMap<>();
        body1.add("file", res1);
        HttpHeaders headers1 = new HttpHeaders();
        headers1.setContentType(MediaType.MULTIPART_FORM_DATA);

        ResponseEntity<String> uploadResp1 = rest.postForEntity(
                "/api/boss/config/resume-document",
                new HttpEntity<>(body1, headers1),
                String.class
        );
        assertEquals(HttpStatus.OK, uploadResp1.getStatusCode());

        JsonNode uploadJson1 = objectMapper.readTree(uploadResp1.getBody());
        String assetId1 = uploadJson1.path("resumeDocument").path("assetId").asText();
        assertNotNull(assetId1);
        assertTrue(assetId1.startsWith("sha256:"), "assetId 必须以 sha256: 开头");

        ResponseEntity<String> configResp = rest.getForEntity("/api/boss/config", String.class);
        assertEquals(HttpStatus.OK, configResp.getStatusCode());
        JsonNode configJson = objectMapper.readTree(configResp.getBody());
        assertTrue(configJson.has("resumeDocument"), "GET /api/boss/config 应含 resumeDocument");
        assertEquals(assetId1, configJson.path("resumeDocument").path("assetId").asText());

        ResponseEntity<String> getProfileResp = rest.getForEntity("/api/boss/chat/profile", String.class);
        assertEquals(HttpStatus.OK, getProfileResp.getStatusCode());
        Profile currentProfile = objectMapper.readValue(getProfileResp.getBody(), Profile.class);

        Profile updatedProfile = new Profile(
                currentProfile.version(),
                currentProfile.graduationYear(),
                currentProfile.acceptedCities(),
                currentProfile.facts(),
                currentProfile.approvedResumeAssetId(),
                currentProfile.autoResume(),
                currentProfile.autoLocation(),
                currentProfile.enabled(),
                currentProfile.resume(),
                assetId1,
                currentProfile.resumeText(),
                currentProfile.resumeTextHash(),
                currentProfile.approvedResumeTextHash(),
                currentProfile.derivedFactIds(),
                currentProfile.autoReply(),
                currentProfile.followUp()
        );

        HttpHeaders jsonHeaders = new HttpHeaders();
        jsonHeaders.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<String> putProfileResp = rest.exchange(
                "/api/boss/chat/profile",
                HttpMethod.PUT,
                new HttpEntity<>(updatedProfile, jsonHeaders),
                String.class
        );
        assertEquals(HttpStatus.OK, putProfileResp.getStatusCode());

        // 上传另一份不同内容的 PDF
        byte[] pdf2 = createMinimalValidPdf("pdf-2-different-content");
        ByteArrayResource res2 = new ByteArrayResource(pdf2) {
            @Override
            public String getFilename() {
                return "resume.pdf";
            }
        };
        MultiValueMap<String, Object> body2 = new LinkedMultiValueMap<>();
        body2.add("file", res2);

        ResponseEntity<String> uploadResp2 = rest.postForEntity(
                "/api/boss/config/resume-document",
                new HttpEntity<>(body2, headers1),
                String.class
        );
        assertEquals(HttpStatus.OK, uploadResp2.getStatusCode());

        JsonNode uploadJson2 = objectMapper.readTree(uploadResp2.getBody());
        String assetId2 = uploadJson2.path("resumeDocument").path("assetId").asText();
        assertNotEquals(assetId1, assetId2, "新 assetId 应该与旧 assetId 不同");

        // 再次获取最新 profile 后保留旧 assetId1 尝试 PUT，预期 400 且提示包含「PDF」
        ResponseEntity<String> latestProfileResp = rest.getForEntity("/api/boss/chat/profile", String.class);
        Profile latestProfile = objectMapper.readValue(latestProfileResp.getBody(), Profile.class);
        Profile putOldPdfProfile = new Profile(
                latestProfile.version(),
                latestProfile.graduationYear(),
                latestProfile.acceptedCities(),
                latestProfile.facts(),
                latestProfile.approvedResumeAssetId(),
                latestProfile.autoResume(),
                latestProfile.autoLocation(),
                latestProfile.enabled(),
                latestProfile.resume(),
                assetId1,
                latestProfile.resumeText(),
                latestProfile.resumeTextHash(),
                latestProfile.approvedResumeTextHash(),
                latestProfile.derivedFactIds(),
                latestProfile.autoReply(),
                latestProfile.followUp()
        );

        ResponseEntity<String> rejectResp = rest.exchange(
                "/api/boss/chat/profile",
                HttpMethod.PUT,
                new HttpEntity<>(putOldPdfProfile, jsonHeaders),
                String.class
        );
        assertEquals(HttpStatus.BAD_REQUEST, rejectResp.getStatusCode());
        assertTrue(rejectResp.getBody() != null && rejectResp.getBody().contains("PDF"), "提示应含「PDF」");
    }

    @Test
    void resumeTextApproveThenInvalidate() throws Exception {
        ResponseEntity<String> initialProfileResp = rest.getForEntity("/api/boss/chat/profile", String.class);
        Profile initialProfile = objectMapper.readValue(initialProfileResp.getBody(), Profile.class);

        String textA = "这是B7UI文本A内容，展示核心技能与经验。";
        Profile putA = new Profile(
                initialProfile.version(),
                initialProfile.graduationYear(),
                initialProfile.acceptedCities(),
                initialProfile.facts(),
                initialProfile.approvedResumeAssetId(),
                false,
                initialProfile.autoLocation(),
                initialProfile.enabled(),
                initialProfile.resume(),
                "",
                textA,
                "",
                "",
                initialProfile.derivedFactIds(),
                initialProfile.autoReply(),
                initialProfile.followUp()
        );

        HttpHeaders jsonHeaders = new HttpHeaders();
        jsonHeaders.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<String> respA = rest.exchange(
                "/api/boss/chat/profile",
                HttpMethod.PUT,
                new HttpEntity<>(putA, jsonHeaders),
                String.class
        );
        assertEquals(HttpStatus.OK, respA.getStatusCode());

        Profile profileA = objectMapper.readValue(respA.getBody(), Profile.class);
        assertEquals("", profileA.approvedResumeTextHash());
        assertTrue(profileA.facts().stream().noneMatch(f -> f.id().startsWith("resume:")));

        BossChatController.ApprovalRequest approvalReq = new BossChatController.ApprovalRequest(
                profileA.version(),
                profileA.resumeTextHash()
        );
        ResponseEntity<String> approveResp = rest.postForEntity(
                "/api/boss/chat/profile/resume-text/approve",
                new HttpEntity<>(approvalReq, jsonHeaders),
                String.class
        );
        assertEquals(HttpStatus.OK, approveResp.getStatusCode());

        Profile approvedProfile = objectMapper.readValue(approveResp.getBody(), Profile.class);
        assertEquals(profileA.resumeTextHash(), approvedProfile.approvedResumeTextHash());
        assertTrue(approvedProfile.facts().stream().anyMatch(f -> f.id().startsWith("resume:")));

        String textB = "这是B7UI修改后的文本B内容。";
        Profile putB = new Profile(
                approvedProfile.version(),
                approvedProfile.graduationYear(),
                approvedProfile.acceptedCities(),
                approvedProfile.facts(),
                approvedProfile.approvedResumeAssetId(),
                approvedProfile.autoResume(),
                approvedProfile.autoLocation(),
                approvedProfile.enabled(),
                approvedProfile.resume(),
                approvedProfile.approvedPdfAssetId(),
                textB,
                "",
                approvedProfile.approvedResumeTextHash(),
                approvedProfile.derivedFactIds(),
                approvedProfile.autoReply(),
                approvedProfile.followUp()
        );

        ResponseEntity<String> respB = rest.exchange(
                "/api/boss/chat/profile",
                HttpMethod.PUT,
                new HttpEntity<>(putB, jsonHeaders),
                String.class
        );
        assertEquals(HttpStatus.OK, respB.getStatusCode());

        Profile profileB = objectMapper.readValue(respB.getBody(), Profile.class);
        assertEquals("", profileB.approvedResumeTextHash());
        assertTrue(profileB.facts().stream().noneMatch(f -> f.id().startsWith("resume:")));
    }

    @Test
    void switchesDefaultsAndRecordReconcile() throws Exception {
        ResponseEntity<String> profileResp = rest.getForEntity("/api/boss/chat/profile", String.class);
        assertEquals(HttpStatus.OK, profileResp.getStatusCode());
        Profile profile = objectMapper.readValue(profileResp.getBody(), Profile.class);

        assertFalse(profile.autoReply(), "autoReply 应默认为 false");
        assertNotNull(profile.followUp());
        assertFalse(profile.followUp().enabled(), "followUp.enabled 应默认为 false");
        assertEquals(168, profile.followUp().cooldownHours(), "followUp.cooldownHours 应默认为 168");
        assertEquals(1, profile.followUp().maxPerConversation(), "followUp.maxPerConversation 应默认为 1");

        Suggestion unknownRec = new Suggestion(
                "s-rec-1", 1, "c-rec-1", "m-rec-1", "v1", "unknown", "回复内容",
                Intent.OTHER, Action.SUGGEST_REPLY, "UNKNOWN_INTENT", List.of(), "候选人消息", "", List.of(), System.currentTimeMillis()
        );
        bossChatStore.save(unknownRec);

        ResponseEntity<String> recordsResp = rest.getForEntity("/api/boss/chat/records?states=unknown", String.class);
        assertEquals(HttpStatus.OK, recordsResp.getStatusCode());
        JsonNode recordsJson = objectMapper.readTree(recordsResp.getBody());
        JsonNode recordsArray = recordsJson.path("records");
        assertTrue(recordsArray.isArray());
        assertTrue(recordsArray.size() >= 1, "GET /api/boss/chat/records?states=unknown 至少应有 1 条记录");

        HttpHeaders jsonHeaders = new HttpHeaders();
        jsonHeaders.setContentType(MediaType.APPLICATION_JSON);
        String reconcileBody = "{\"confirmed\":true}";

        ResponseEntity<String> reconcileResp1 = rest.postForEntity(
                "/api/boss/chat/records/" + unknownRec.id() + "/reconcile",
                new HttpEntity<>(reconcileBody, jsonHeaders),
                String.class
        );
        assertEquals(HttpStatus.OK, reconcileResp1.getStatusCode());
        JsonNode reconcileJson = objectMapper.readTree(reconcileResp1.getBody());
        assertEquals("sent", reconcileJson.path("state").asText(), "reconcile 后 state 应为 sent");

        ResponseEntity<String> reconcileResp2 = rest.postForEntity(
                "/api/boss/chat/records/" + unknownRec.id() + "/reconcile",
                new HttpEntity<>(reconcileBody, jsonHeaders),
                String.class
        );
        assertEquals(HttpStatus.CONFLICT, reconcileResp2.getStatusCode(), "重复调用 reconcile 应返回 409 CONFLICT");
    }

    @Test
    void legacySuggestionsConfirmReturns410() throws Exception {
        HttpHeaders jsonHeaders = new HttpHeaders();
        jsonHeaders.setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"version\":1,\"lastInboundMessageId\":\"m\",\"replyText\":\"x\"}";

        ResponseEntity<String> resp = rest.postForEntity(
                "/api/boss/chat/suggestions/anything/confirm",
                new HttpEntity<>(body, jsonHeaders),
                String.class
        );
        assertEquals(HttpStatus.GONE, resp.getStatusCode(), "旧建议确认接口应返回 410 GONE");
        JsonNode respJson = objectMapper.readTree(resp.getBody());
        assertEquals("LEGACY_SUGGESTIONS_RETIRED", respJson.path("code").asText(), "code 应为 LEGACY_SUGGESTIONS_RETIRED");
    }

    private static void initSchema(Path db) throws Exception {
        String sql = new String(new ClassPathResource("b7-schema.sql").getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath().toString().replace('\\', '/'));
             Statement st = c.createStatement()) {
            for (String part : sql.split(";")) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) {
                    st.execute(trimmed);
                }
            }
        }
    }

    private static void copyFrontOutToTemp(Path targetDir) throws IOException {
        Path out = Paths.get("front/out");
        Assumptions.assumeTrue(Files.isDirectory(out), "front/out 不存在，跳过 UI 联调");

        try (Stream<Path> walk = Files.walk(out)) {
            walk.forEach(src -> {
                try {
                    Path target = targetDir.resolve(out.relativize(src).toString());
                    if (Files.isDirectory(src)) {
                        Files.createDirectories(target);
                    } else {
                        Files.createDirectories(target.getParent());
                        Files.copy(src, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            });
        }
    }
}
