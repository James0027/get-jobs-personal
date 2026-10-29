package com.getjobs.application.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.getjobs.application.service.AiService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.util.List;

import static com.getjobs.application.chat.ChatContracts.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class PdfResumeDecisionTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private BossChatStore store;
    private AiService ai;
    private BossChatBrowserGateway browser;
    private BossAiWorkflowService workflow;

    @BeforeEach
    void setup() {
        store = new BossChatStore(new JdbcTemplate(new SingleConnectionDataSource("jdbc:sqlite::memory:", true)), mapper);
        store.init();
        ai = mock(AiService.class);
        browser = mock(BossChatBrowserGateway.class);
        when(browser.currentResumeAssetId()).thenReturn("img-asset");
        workflow = new BossAiWorkflowService(store, ai, new ChatDecisionValidator(mapper), browser, mapper);

        Profile profile = new Profile(
                "v1", 2026, List.of("北京"), List.of(new Fact("f1", "学历真实")),
                "img-asset", true, true, true,
                Resume.empty(), "pdf-asset-123", "", "", "", List.of(), true, FollowUp.defaults()
        );
        store.saveProfile(profile);
    }

    private Inbound inbound(String convId, String msgId, String text) {
        return new Inbound(convId, msgId, text, "职位", "描述", List.of(new Message(msgId, "HR", text)));
    }

    @Test
    void pdfSendBlockedWithNotImplemented() {
        when(ai.sendRequest(anyString())).thenAnswer(call -> {
            String prompt = call.getArgument(0);
            String data = prompt.substring(prompt.indexOf("输入数据：") + "输入数据：".length()).trim();
            String reqId = mapper.readTree(data).get("requestId").asText();
            return mapper.writeValueAsString(new Decision("2", reqId, Intent.REQUEST_RESUME, Action.SEND_RESUME, "", "pdf-asset-123", List.of(), List.of(), "RESUME", "PDF"));
        });

        workflow.observe(inbound("c1", "m0", "你好"));
        Suggestion s = workflow.observe(inbound("c1", "m1", "请发一份PDF简历"));

        assertNotNull(s);
        assertEquals("blocked", s.state());
        assertTrue(s.reasonCode().contains("PDF_SEND_NOT_IMPLEMENTED"));
        verify(browser, never()).send(any(), any(), anyString());
    }

    @Test
    void legacyImageResumeSendBlockedWithLegacyRejected() {
        when(ai.sendRequest(anyString())).thenAnswer(call -> {
            String prompt = call.getArgument(0);
            String data = prompt.substring(prompt.indexOf("输入数据：") + "输入数据：".length()).trim();
            String reqId = mapper.readTree(data).get("requestId").asText();
            // v1 output (schemaVersion 1, no resumeAssetKind)
            return mapper.writeValueAsString(new Decision("1", reqId, Intent.REQUEST_RESUME, Action.SEND_RESUME, "", "img-asset", List.of(), List.of(), "REQUEST"));
        });

        workflow.observe(inbound("c2", "m0", "你好"));
        Suggestion s = workflow.observe(inbound("c2", "m1", "请发简历"));

        assertNotNull(s);
        assertEquals("blocked", s.state());
        assertTrue(s.reasonCode().contains("LEGACY_IMAGE_RESUME_REJECTED"));
        verify(browser, never()).send(any(), any(), anyString());
    }
}
