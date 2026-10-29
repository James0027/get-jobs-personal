package com.getjobs.application.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.getjobs.application.controller.BossController;
import com.getjobs.application.service.AiService;
import com.getjobs.application.service.BossResumeImageService;
import com.getjobs.application.service.CookieService;
import com.getjobs.worker.boss.Boss;
import com.getjobs.worker.boss.BossExecutionGuard;
import com.getjobs.worker.manager.PlaywrightManager;
import com.getjobs.worker.service.BossJobService;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BossChatReadOnlyCheckModeTest {

    @Test
    void readOnlyCheckModeBlocksChatSending() {
        var guard = new BossExecutionGuard(true); // 开启检查模式
        assertTrue(guard.isReadOnlyCheckMode());

        var store = new BossChatStore(new org.springframework.jdbc.core.JdbcTemplate(new org.springframework.jdbc.datasource.SingleConnectionDataSource("jdbc:sqlite::memory:", true)), new ObjectMapper());
        store.init();
        var profile = new ChatContracts.Profile("v1", 2027, List.of("上海"), List.of(new ChatContracts.Fact("f1", "经验")), "asset", true, true, true);
        store.saveProfile(profile);

        var ai = mock(AiService.class);
        var validator = mock(ChatDecisionValidator.class);
        var browser = mock(BossChatBrowserGateway.class);
        var mapper = new ObjectMapper();

        when(ai.sendRequest(anyString())).thenAnswer(call -> {
            String prompt = call.getArgument(0);
            String data = prompt.substring(prompt.indexOf("输入数据：") + "输入数据：".length()).trim();
            String requestId = mapper.readTree(data).get("requestId").asText();
            return mapper.writeValueAsString(new ChatContracts.Decision("2", requestId, ChatContracts.Intent.OTHER, ChatContracts.Action.SUGGEST_REPLY, "回复内容", "", List.of("f1"), List.of(), "ANSWER", ""));
        });

        var workflow = new BossAiWorkflowService(store, ai, new ChatDecisionValidator(mapper), browser, mapper, guard);
        var inbound = new ChatContracts.Inbound("c1", "m1", "方便沟通吗？", "产品", "JD", List.of(new ChatContracts.Message("m1", "RECRUITER", "方便沟通吗？")));

        var s = workflow.observe(inbound);
        assertNotNull(s);
        assertEquals("blocked", s.state());
        assertTrue(s.reasonCode().contains("READ_ONLY"));
        verify(browser, never()).send(any(), any(), anyString());

        var monitor = new BossChatMonitor(store, browser, workflow, guard);
        var scanResult = monitor.scan();
        assertEquals(true, scanResult.get("readOnlyCheckMode"));
        assertEquals(0, scanResult.get("scanned"));
    }

    @Test
    void readOnlyCheckModeBlocksGatewayDirectSend() {
        var guard = new BossExecutionGuard(true);
        var manager = mock(PlaywrightManager.class);
        @SuppressWarnings("unchecked") ObjectProvider<BossJobService> jobs = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked") ObjectProvider<Boss> bosses = mock(ObjectProvider.class);
        var resumes = mock(BossResumeImageService.class);
        var store = mock(BossChatStore.class);
        var mapper = new ObjectMapper();

        var gateway = new BossChatBrowserGateway(manager, jobs, bosses, resumes, store, mapper, guard);
        var suggestion = new ChatContracts.Suggestion(
                "s1", 1, "u1", "m1", "v1", "pending_approval",
                "回复", ChatContracts.Intent.CONFIRM_LOCATION, ChatContracts.Action.CONFIRM_LOCATION,
                "OK", List.of(), "在北京吗", "", List.of(), System.currentTimeMillis()
        );
        var profile = new ChatContracts.Profile("v1", 2024, List.of(), List.of(), "", true, true, true);

        // 网关直接拦截并返回 FAILED，不调用底层 Playwright 页面
        var result = gateway.send(suggestion, profile, "可以接受在某地工作");
        assertEquals(ChatContracts.SendResult.FAILED, result);
        verify(manager, never()).callOnPlaywright(any());
    }

    @Test
    void statusEndpointExposesReadOnlyCheckModeState() {
        var guard = new BossExecutionGuard(true);
        var store = mock(BossChatStore.class);
        var browser = mock(BossChatBrowserGateway.class);
        var workflow = mock(BossAiWorkflowService.class);
        when(browser.lastScanNote()).thenReturn("就绪");
        when(workflow.metrics()).thenReturn(Map.of());

        var monitor = new BossChatMonitor(store, browser, workflow, guard);
        var status = monitor.status();

        assertTrue((Boolean) status.get("readOnlyCheckMode"), "状态接口须能核实检查模式有效");
    }

    @Test
    void readOnlyDiagnosticsExecutesSafelyWithoutSending() {
        var guard = new BossExecutionGuard(true);
        var manager = mock(PlaywrightManager.class);
        @SuppressWarnings("unchecked") ObjectProvider<BossJobService> jobsProvider = mock(ObjectProvider.class);
        var jobs = mock(BossJobService.class);
        when(jobsProvider.getObject()).thenReturn(jobs);
        when(jobs.isRunning()).thenReturn(false);

        @SuppressWarnings("unchecked") ObjectProvider<Boss> bosses = mock(ObjectProvider.class);
        var resumes = mock(BossResumeImageService.class);
        var store = mock(BossChatStore.class);
        var mapper = new ObjectMapper();

        when(manager.isLoggedIn("boss")).thenReturn(false);
        Page mainPage = mock(Page.class);
        when(manager.getBossPage()).thenReturn(mainPage);
        when(mainPage.isClosed()).thenReturn(false);
        when(manager.callOnPlaywright(any())).thenAnswer(i -> ((Callable<?>) i.getArgument(0)).call());

        var gateway = new BossChatBrowserGateway(manager, jobsProvider, bosses, resumes, store, mapper, guard);
        var diagnosis = gateway.diagnose();

        assertNotNull(diagnosis);
        assertEquals("NOT_LOGGED_IN", diagnosis.get("stage"));
        assertFalse((Boolean) diagnosis.get("success"));
        assertTrue((Boolean) diagnosis.get("readOnlyCheckMode"));
        // 绝无外发与 AI 交互
        verifyNoInteractions(bosses);
        verify(resumes, never()).getCurrentImagePath();
    }

    /**
     * P2 验证：诊断端点只检查已存在且已加载的聊天页面，未打开聊天页时绝不创建新标签页、绝不 navigate
     */
    @Test
    void readOnlyDiagnosticsRefusesToCreateTabOrNavigateWhenNotOnChatPage() {
        var guard = new BossExecutionGuard(true);
        var manager = mock(PlaywrightManager.class);
        @SuppressWarnings("unchecked") ObjectProvider<BossJobService> jobsProvider = mock(ObjectProvider.class);
        var jobs = mock(BossJobService.class);
        when(jobsProvider.getObject()).thenReturn(jobs);
        when(jobs.isRunning()).thenReturn(false);

        @SuppressWarnings("unchecked") ObjectProvider<Boss> bosses = mock(ObjectProvider.class);
        var resumes = mock(BossResumeImageService.class);
        var store = mock(BossChatStore.class);
        var mapper = new ObjectMapper();

        when(manager.isLoggedIn("boss")).thenReturn(true);
        Page mainPage = mock(Page.class);
        when(manager.getBossPage()).thenReturn(mainPage);
        when(mainPage.isClosed()).thenReturn(false);
        // 当前主页面停留在职位列表页，未进入聊天页
        when(mainPage.url()).thenReturn("https://www.zhipin.com/web/geek/job");

        BrowserContext context = mock(BrowserContext.class);
        when(mainPage.context()).thenReturn(context);
        when(context.pages()).thenReturn(List.of(mainPage));

        when(manager.callOnPlaywright(any())).thenAnswer(i -> ((Callable<?>) i.getArgument(0)).call());

        var gateway = new BossChatBrowserGateway(manager, jobsProvider, bosses, resumes, store, mapper, guard);
        var diagnosis = gateway.diagnose();

        assertNotNull(diagnosis);
        assertEquals("CHAT_PAGE_NOT_READY", diagnosis.get("stage"));
        assertFalse((Boolean) diagnosis.get("success"));
        assertEquals("当前页不是聊天页，未新建标签页", diagnosis.get("note"));

        // 核心证据：严禁调用 newPage() 与 navigate()
        verify(context, never()).newPage();
        verify(mainPage, never()).navigate(anyString());
        verify(mainPage, never()).navigate(anyString(), any());
    }

    /**
     * P2 验证：已存在聊天页面时只读检查 DOM，脱敏 URL、无文本与敏感 ID 泄露、无页面导航
     */
    @Test
    void readOnlyDiagnosticsReadsExistingChatPageSafelyWithoutNavigation() {
        var guard = new BossExecutionGuard(true);
        var manager = mock(PlaywrightManager.class);
        @SuppressWarnings("unchecked") ObjectProvider<BossJobService> jobsProvider = mock(ObjectProvider.class);
        var jobs = mock(BossJobService.class);
        when(jobsProvider.getObject()).thenReturn(jobs);
        when(jobs.isRunning()).thenReturn(false);

        @SuppressWarnings("unchecked") ObjectProvider<Boss> bosses = mock(ObjectProvider.class);
        var resumes = mock(BossResumeImageService.class);
        var store = mock(BossChatStore.class);
        var mapper = new ObjectMapper();

        when(manager.isLoggedIn("boss")).thenReturn(true);
        Page chatPage = mock(Page.class);
        when(manager.getBossPage()).thenReturn(chatPage);
        when(chatPage.isClosed()).thenReturn(false);
        when(chatPage.url()).thenReturn("https://www.zhipin.com/web/geek/chat?city=101010100&secret_token=sensitive123");

        BrowserContext context = mock(BrowserContext.class);
        when(chatPage.context()).thenReturn(context);
        when(context.pages()).thenReturn(List.of(chatPage));

        Locator mockLocator = mock(Locator.class);
        when(chatPage.locator(anyString())).thenReturn(mockLocator);
        when(mockLocator.count()).thenReturn(0);

        when(manager.callOnPlaywright(any())).thenAnswer(i -> ((Callable<?>) i.getArgument(0)).call());

        var gateway = new BossChatBrowserGateway(manager, jobsProvider, bosses, resumes, store, mapper, guard);
        var diagnosis = gateway.diagnose();

        assertNotNull(diagnosis);
        // 验证 URL 脱敏：查询参数被剔除
        @SuppressWarnings("unchecked")
        Map<String, Object> landing = (Map<String, Object>) diagnosis.get("landing");
        assertNotNull(landing);
        assertEquals("https://www.zhipin.com/web/geek/chat", landing.get("url"), "URL中的敏感查询参数须被脱敏");

        // 验证零导航、零新建页面
        verify(context, never()).newPage();
        verify(chatPage, never()).navigate(anyString());
        verify(chatPage, never()).navigate(anyString(), any());
    }

    /**
     * P3 验证：只读模式下手工 scan 入口立即拒绝，不触发浏览器 scan 与 AI
     */
    @Test
    void readOnlyModeBlocksManualScanAtEntry() {
        var guard = new BossExecutionGuard(true);
        var store = mock(BossChatStore.class);
        var browser = mock(BossChatBrowserGateway.class);
        var workflow = mock(BossAiWorkflowService.class);

        var monitor = new BossChatMonitor(store, browser, workflow, guard);
        Map<String, Object> result = monitor.scan();

        assertEquals(0, result.get("scanned"));
        assertEquals(true, result.get("readOnlyCheckMode"));
        assertEquals("只读检查模式，已禁用手工扫描", result.get("note"));

        // 绝对不调用底层浏览器与 AI 观察
        verifyNoInteractions(browser);
        verifyNoInteractions(workflow);
    }

    /**
     * P6 验证：兼容接口 /api/boss/start 在只读拒绝时返回明确原因与独立状态，不能误报为任务运行中
     */
    @Test
    void startEndpointInReadOnlyModeReturnsExplicitReason() {
        var jobService = mock(BossJobService.class);
        var playwrightManager = mock(PlaywrightManager.class);
        var cookieService = mock(CookieService.class);

        when(playwrightManager.isLoggedIn("boss")).thenReturn(true);
        when(jobService.reserveDelivery(false)).thenThrow(new IllegalStateException("只读检查模式已生效，禁止启动投递外发任务"));

        var controller = new BossController(jobService, playwrightManager, cookieService);
        var response = controller.startBoss();

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        assertEquals(false, body.get("success"));
        assertEquals("read_only_mode", body.get("status"), "状态须为独立 read_only_mode 而非 running");
        assertTrue(body.get("message").toString().contains("只读检查模式已生效"));
        assertFalse(body.get("message").toString().contains("已在运行中"), "严禁错误提示任务已在运行中");
    }

    /**
     * P7 验证：索要附件/PDF/文档简历时，autoAllowed 必须返回 false，禁止自动发图片
     */
    @Test
    void autoAllowedRejectsAttachmentOrPdfResumeRequest() {
        var guard = new BossExecutionGuard(false);
        var store = mock(BossChatStore.class);
        var ai = mock(AiService.class);
        var validator = mock(ChatDecisionValidator.class);
        var browser = mock(BossChatBrowserGateway.class);
        when(browser.currentResumeAssetId()).thenReturn("approved-hash");
        var mapper = new ObjectMapper();

        var workflow = new BossAiWorkflowService(store, ai, validator, browser, mapper, guard);
        var profile = new ChatContracts.Profile("v1", 2024, List.of("北京"), List.of(), "approved-hash", true, true, true);

        // 正例：普通明确索取简历（应自动允许）
        var normal1 = new ChatContracts.Suggestion(
                "s1", 1, "c1", "m1", "v1", "pending_approval",
                "", ChatContracts.Intent.REQUEST_RESUME, ChatContracts.Action.SEND_RESUME,
                "RESUME", List.of(), "发一份简历", "approved-hash", List.of(), System.currentTimeMillis()
        );
        assertTrue(workflow.autoAllowed(normal1, profile), "普通发简历应自动允许");

        var normal2 = new ChatContracts.Suggestion(
                "s2", 1, "c1", "m2", "v1", "pending_approval",
                "", ChatContracts.Intent.REQUEST_RESUME, ChatContracts.Action.SEND_RESUME,
                "RESUME", List.of(), "麻烦发一份简历给我", "approved-hash", List.of(), System.currentTimeMillis()
        );
        assertTrue(workflow.autoAllowed(normal2, profile), "普通发简历应自动允许");

        // 反例：明确索要附件/PDF/文件/文档简历（严禁自动发图片，必须返回 false）
        List<String> negativeTexts = List.of(
                "发一份附件简历",
                "麻烦发一份附件简历给我",
                "请发一份附件简历",
                "请发一份PDF简历",
                "发我一份PDF简历",
                "可以把PDF简历发我吗",
                "请发一份pdf简历",
                "发一份文件简历",
                "请发文档简历"
        );

        for (String negativeText : negativeTexts) {
            var attachmentReq = new ChatContracts.Suggestion(
                    "s_neg", 1, "c1", "m_neg", "v1", "pending_approval",
                    "", ChatContracts.Intent.REQUEST_RESUME, ChatContracts.Action.SEND_RESUME,
                    "RESUME", List.of(), negativeText, "approved-hash", List.of(), System.currentTimeMillis()
            );
            assertFalse(workflow.autoAllowed(attachmentReq, profile),
                    "索要附件/PDF/文档时必须禁止自动发图片: " + negativeText);
        }
    }

    /**
     * P7 验证：入站消息索要附件简历时，observe 生成 pending_approval 待人工建议并提示当前仅支持图片
     */
    @Test
    void observeAttachmentResumeGeneratesPendingApprovalWithPrompt() throws Exception {
        var guard = new BossExecutionGuard(false);
        var store = mock(BossChatStore.class);
        var ai = mock(AiService.class);
        var validator = mock(ChatDecisionValidator.class);
        var browser = mock(BossChatBrowserGateway.class);
        when(browser.currentResumeAssetId()).thenReturn("approved-hash");
        var mapper = new ObjectMapper();

        var profile = new ChatContracts.Profile("v1", 2024, List.of("北京"), List.of(), "approved-hash", true, true, true);
        when(store.profile()).thenReturn(profile);
        when(store.ingest(any())).thenReturn(true);
        when(store.isLatest(any())).thenReturn(true);

        // 模拟 AI 输出原始建议为发简历
        when(ai.sendRequest(anyString())).thenReturn("""
                {
                    "schemaVersion": "1",
                    "requestId": "r1",
                    "intent": "REQUEST_RESUME",
                    "action": "SEND_RESUME",
                    "replyText": "",
                    "resumeAssetId": "approved-hash",
                    "evidenceFactIds": [],
                    "missingFacts": [],
                    "reasonCode": "HR_REQUEST_RESUME"
                }
                """);
        when(validator.parse(anyString(), anyString(), any(), anyInt(), anyString())).thenReturn(
                new ChatContracts.Decision("1", "r1", ChatContracts.Intent.REQUEST_RESUME, ChatContracts.Action.SEND_RESUME,
                        "", "approved-hash", List.of(), List.of(), "HR_REQUEST_RESUME")
        );

        var workflow = new BossAiWorkflowService(store, ai, validator, browser, mapper, guard);
        var inbound = new ChatContracts.Inbound("c1", "m1", "麻烦发一份附件简历", "Java工程师", "JD",
                List.of(new ChatContracts.Message("m1", "RECRUITER", "麻烦发一份附件简历")));

        var captured = new java.util.concurrent.atomic.AtomicReference<ChatContracts.Suggestion>();
        doAnswer(inv -> {
            captured.set(inv.getArgument(0));
            return null;
        }).when(store).save(any());
        when(store.get(any())).thenAnswer(inv -> captured.get());

        var result = workflow.observe(inbound);
        assertNotNull(result);
        assertTrue("blocked".equals(result.state()) || "pending_approval".equals(result.state()), "必须保留为待处理/已阻断");
        assertEquals(ChatContracts.Action.NEEDS_INFORMATION, result.action(), "动作必须降级为缺资料/人工介入");
        assertTrue(result.missingFacts().stream().anyMatch(f -> f.contains("当前仅支持图片简历")),
                "必须明确提示当前仅支持图片简历: " + result.missingFacts());
        // 严禁外发图片简历
        verify(browser, never()).send(any(), any(), any());
    }
}
