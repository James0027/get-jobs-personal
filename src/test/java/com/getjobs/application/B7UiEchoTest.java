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
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * B7 最小 UI→真实本地 HTTP→临时库→回显 验收测试。
 * Spring 后端端口 18081；静态前端端口 18082；
 * 静态前端服务由 JDK 内置 HttpServer 托管临时目录中的 front/out 产物；
 * Playwright 路由拦截 8888 真实转发至 18081。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
        properties = {"server.port=18081"})
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
            copyFrontOutToTemp(STATIC_FRONT_DIR);
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

            // 2. <path>/index.html
            Path candidateDirIndex = candidate.resolve("index.html");
            if (Files.isRegularFile(candidateDirIndex)) {
                return candidateDirIndex;
            }

            // 3. 回退 index.html
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
                // 路由代理：前端由 next export 静态导出，代码中向 http://localhost:8888 或 127.0.0.1:8888 发请求，
                // 真实转发至 18081（不改响应、不 mock）
                page.route("**/api/**", route -> {
                    String originalUrl = route.request().url();
                    String targetUrl = originalUrl
                            .replace("http://localhost:8888", "http://127.0.0.1:" + PORT)
                            .replace("http://127.0.0.1:8888", "http://127.0.0.1:" + PORT);
                    route.resume(new Route.ResumeOptions().setUrl(targetUrl));
                });

                // 1. 打开 http://127.0.0.1:18082/boss，等待 networkidle；断言「工作经历」可见
                page.navigate("http://127.0.0.1:" + STATIC_PORT + "/boss");
                page.waitForLoadState(LoadState.NETWORKIDLE);
                assertTrue(page.getByText("工作经历").first().isVisible(), "工作经历 卡片应可见");

                // 2. 在「工作经历」卡片点「+ 添加工作经历」，填公司=B7联调公司、职位=产品经理
                page.getByRole(AriaRole.BUTTON,
                        new Page.GetByRoleOptions().setName("+ 添加工作经历")).first().click();
                page.getByPlaceholder("例如：北京某某科技有限公司").fill("B7联调公司");
                page.getByPlaceholder("例如：高级全栈开发工程师").fill("产品经理");

                // 3. 点「保存资料与授权」，等待 PUT /api/boss/chat/profile 返回 200
                Response saveResponse = page.waitForResponse(
                        r -> r.url().contains("/api/boss/chat/profile") && "PUT".equalsIgnoreCase(r.request().method()),
                        () -> page.getByRole(AriaRole.BUTTON,
                                new Page.GetByRoleOptions().setName("保存资料与授权")).click()
                );
                assertEquals(200, saveResponse.status(), "PUT /api/boss/chat/profile 应返回 200");

                // 4. 用 TestRestTemplate GET /api/boss/chat/profile 断言 resume.work[0].company == B7联调公司 且 facts 中存在 work: 开头条目
                String body = rest.getForObject("/api/boss/chat/profile", String.class);
                var node = new ObjectMapper().readTree(body);
                assertEquals("B7联调公司", node.at("/resume/work/0/company").asText(), "work[0].company 应为 B7联调公司");
                boolean hasWorkFact = false;
                if (node.has("facts") && node.get("facts").isArray()) {
                    for (var fact : node.get("facts")) {
                        if (fact.has("id") && fact.get("id").asText().startsWith("work:")) {
                            hasWorkFact = true;
                            break;
                        }
                    }
                }
                assertTrue(hasWorkFact, "facts 中应存在 work: 开头的条目");

                // 5. page.reload() + networkidle，断言页面上能看到 B7联调公司（回显）
                page.reload();
                page.waitForLoadState(LoadState.NETWORKIDLE);
                assertEquals("B7联调公司", page.getByPlaceholder("例如：北京某某科技有限公司").inputValue(), "页面重新加载后公司输入框应回显 B7联调公司");

            } catch (Throwable t) {
                try {
                    dbg.append("----BODY----\n").append(page.locator("body").innerText());
                    Path debugDir = Paths.get("work/boss-upgrade");
                    Files.createDirectories(debugDir);
                    Files.writeString(debugDir.resolve("ui-debug.txt"), dbg.toString(), StandardCharsets.UTF_8);
                } catch (Exception ignored) {
                }
                throw t;
            }
        }
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
