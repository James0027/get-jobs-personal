package com.getjobs.application.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.getjobs.application.chat.ChatContracts.*;
import com.getjobs.application.controller.BossChatController;
import com.getjobs.application.service.AiService;
import com.getjobs.application.service.BossResumeDocumentService;
import com.getjobs.worker.boss.BossExecutionGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class LegacySuggestionsRetiredTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private BossChatStore store;
    private BossChatBrowserGateway browser;
    private BossAiWorkflowService workflow;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        SingleConnectionDataSource dataSource = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        store = new BossChatStore(new JdbcTemplate(dataSource), mapper);
        store.init();

        AiService ai = mock(AiService.class);
        ChatDecisionValidator validator = new ChatDecisionValidator(mapper);
        browser = mock(BossChatBrowserGateway.class);
        BossExecutionGuard guard = new BossExecutionGuard(false);
        workflow = new BossAiWorkflowService(store, ai, validator, browser, mapper, guard);

        BossChatMonitor monitor = mock(BossChatMonitor.class);
        BossResumeDocumentService documents = mock(BossResumeDocumentService.class);

        BossChatController controller = new BossChatController(store, workflow, monitor, browser, documents);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void postConfirmReturns410AndRetiredCodeWithoutBrowserSend() throws Exception {
        mockMvc.perform(post("/api/boss/chat/suggestions/s1/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1,\"lastInboundMessageId\":\"m1\",\"replyText\":\"好的\"}"))
                .andExpect(status().is(410))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("LEGACY_SUGGESTIONS_RETIRED"))
                .andExpect(jsonPath("$.message").value("旧建议队列已退役，请使用 AI 工作台的自动回复与执行记录"));

        verify(browser, never()).send(any(), any(), anyString());
    }

    @Test
    void directWorkflowConfirmThrowsIllegalStateExceptionWithoutSending() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> workflow.confirm("s1", new Confirmation(1, "m1", "好的")));
        assertEquals("LEGACY_SUGGESTIONS_RETIRED", ex.getMessage());
        verify(browser, never()).send(any(), any(), anyString());
    }

    @Test
    void dismissReturns200AndMarksDismissed() throws Exception {
        Suggestion s = new Suggestion(
                "s1", 1, "c1", "m1", "v1", "blocked", "",
                Intent.OTHER, Action.NO_ACTION, "OUT_OF_POLICY", List.of(),
                "你好", "", List.of(), System.currentTimeMillis()
        );
        store.save(s);

        mockMvc.perform(post("/api/boss/chat/suggestions/s1/dismiss"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        assertEquals("dismissed", store.get("s1").state());
    }

    @Test
    void getSuggestionsReturns200WithHistoricalRows() throws Exception {
        Suggestion s = new Suggestion(
                "s1", 1, "c1", "m1", "v1", "blocked", "",
                Intent.OTHER, Action.NO_ACTION, "OUT_OF_POLICY", List.of(),
                "你好", "", List.of(), System.currentTimeMillis()
        );
        store.save(s);

        mockMvc.perform(get("/api/boss/chat/suggestions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value("s1"));
    }
}
