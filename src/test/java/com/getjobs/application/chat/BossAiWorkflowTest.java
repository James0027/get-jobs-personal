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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BossAiWorkflowTest {
    ObjectMapper mapper = new ObjectMapper();
    BossChatStore store;
    AiService ai;
    BossChatBrowserGateway browser;
    BossAiWorkflowService workflow;

    @BeforeEach
    void setup() {
        store = new BossChatStore(new JdbcTemplate(new SingleConnectionDataSource("jdbc:sqlite::memory:", true)), mapper);
        store.init();
        ai = mock(AiService.class);
        browser = mock(BossChatBrowserGateway.class);
        when(browser.currentResumeAssetId()).thenReturn("asset");
        when(browser.send(any(), any(), anyString())).thenReturn(SendResult.SENT);
        workflow = new BossAiWorkflowService(store, ai, new ChatDecisionValidator(mapper), browser, mapper);
        store.saveProfile(new Profile("", 2027, List.of("上海"), List.of(new Fact("f1", "做过产品需求分析")), "asset", true, true, true));
        when(ai.sendRequest(anyString())).thenAnswer(call -> {
            String prompt = call.getArgument(0);
            String data = prompt.substring(prompt.indexOf("输入数据：") + "输入数据：".length()).trim();
            String requestId = mapper.readTree(data).get("requestId").asText();
            return mapper.writeValueAsString(new Decision("2", requestId, Intent.OTHER, Action.SUGGEST_REPLY, "我可以进一步沟通。", "", List.of("f1"), List.of(), "ANSWER", ""));
        });
    }

    Inbound inbound(String id, String text) {
        return new Inbound("c1", id, text, "产品经理", "岗位描述", List.of(new Message(id, "RECRUITER", text)));
    }

    @Test
    void duplicateInboundAndDoubleConfirmationSendOnlyOnce() {
        store.saveProfile(new Profile("", 2027, List.of("上海"), List.of(new Fact("f1", "做过产品需求分析")), "asset", true, true, true,
                Resume.empty(), "", "", "", "", List.of(), true, FollowUp.defaults()));
        workflow.observe(inbound("m0", "先建立对话"));
        Suggestion s = workflow.observe(inbound("m1", "您什么时候方便沟通？"));
        assertNotNull(s);
        assertNull(workflow.observe(inbound("m1", "您什么时候方便沟通？")));
        verify(browser, times(1)).send(any(), any(), anyString());
        assertEquals("sent", store.get(s.id()).state());
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> workflow.confirm(s.id(), new Confirmation(1, "m1", "x")));
        assertTrue(ex.getMessage().contains("LEGACY_SUGGESTIONS_RETIRED"));
    }

    @Test
    void newMessageInvalidatesPriorSuggestion() {
        Suggestion pending = new Suggestion("s1", 1, "c1", "m1", store.profile().version(), "pending_approval", "今天下午", Intent.OTHER, Action.SUGGEST_REPLY, "ANSWER", List.of(), "何时沟通？", "", List.of("f1"), System.currentTimeMillis());
        store.save(pending);
        store.ingest(inbound("m1", "何时沟通？"));
        workflow.observe(inbound("m2", "下午有空吗？"));
        assertEquals("stale", store.get(pending.id()).state());
        assertThrows(IllegalStateException.class, () -> workflow.confirm(pending.id(), new Confirmation(1, "m1", "可以")));
        verify(browser, never()).send(any(), any(), anyString());
    }

    @Test
    void changedProfileInvalidatesPriorSuggestion() {
        Suggestion pending = new Suggestion("s1", 1, "c1", "m1", store.profile().version(), "pending_approval", "今天下午", Intent.OTHER, Action.SUGGEST_REPLY, "ANSWER", List.of(), "何时到岗？", "", List.of("f1"), System.currentTimeMillis());
        store.save(pending);
        store.saveProfile(Profile.empty());
        assertThrows(IllegalStateException.class, () -> workflow.confirm(pending.id(), new Confirmation(1, "m1", "马上")));
    }

    @Test
    void invalidModelJsonNeverAutomaticallySends() {
        doReturn("```json\n{}\n```").when(ai).sendRequest(anyString());
        Suggestion s = workflow.observe(inbound("m1", "请发简历"));
        assertEquals("blocked", s.state());
        assertEquals(Action.NEEDS_INFORMATION, s.action());
        verify(browser, never()).send(any(), any(), anyString());
    }

    @Test
    void mixedOrMissingFactsDoNotQualifyForAutomaticActions() {
        Profile p = store.profile();
        Suggestion mixed = new Suggestion("s", 1, "c", "m", p.version(), "pending_approval", "", Intent.MIXED, Action.SEND_RESUME, "MIXED", List.of(), "请发简历，期望薪资多少？", "asset", List.of(), 0);
        assertFalse(workflow.autoAllowed(mixed, p));
        Suggestion request = new Suggestion("s", 1, "c", "m", p.version(), "pending_approval", "", Intent.REQUEST_RESUME, Action.SEND_RESUME, "REQUEST", List.of(), "请发简历", "asset", List.of(), 0);
        assertTrue(workflow.autoAllowed(request, p));
        assertFalse(workflow.autoAllowed(new Suggestion("s", 1, "c", "m", p.version(), "pending_approval", "", Intent.REQUEST_RESUME, Action.SEND_RESUME, "REQUEST", List.of("resume"), "请发简历", "asset", List.of(), 0), p));
    }

    @Test
    void locationNegationTravelAndMixedQuestionsNeverAutoConfirm() {
        Profile p = store.profile();
        for (String source : List.of("不能在上海工作吗？", "能在上海工作并接受出差吗？", "你现在在上海吗？", "可以在北京工作吗？", "能去上海出差吗？", "可以在上海工作吗？期望薪资多少？")) {
            Suggestion s = new Suggestion("s", 1, "c", "m", p.version(), "pending_approval", "", Intent.CONFIRM_LOCATION, Action.CONFIRM_LOCATION, "LOCATION", List.of(), source, "", List.of("location:上海"), 0);
            assertFalse(workflow.autoAllowed(s, p), source);
        }
        Suggestion s = new Suggestion("s", 1, "c", "m", p.version(), "pending_approval", "", Intent.CONFIRM_LOCATION, Action.CONFIRM_LOCATION, "LOCATION", List.of(), "可以在上海工作吗？", "", List.of("location:上海"), 0);
        assertTrue(workflow.autoAllowed(s, p));
    }

    @Test
    void initialHistoricalResumeRequestRequiresApproval() {
        doAnswer(call -> {
            String prompt = call.getArgument(0);
            String data = prompt.substring(prompt.indexOf("输入数据：") + "输入数据：".length()).trim();
            String requestId = mapper.readTree(data).get("requestId").asText();
            return mapper.writeValueAsString(new Decision("2", requestId, Intent.REQUEST_RESUME, Action.SEND_RESUME, "", "asset", List.of(), List.of(), "REQUEST", "IMAGE"));
        }).when(ai).sendRequest(anyString());
        Suggestion historical = workflow.observe(inbound("m1", "请发简历"));
        assertEquals("blocked", historical.state());
        assertEquals("AUTO_REPLY_OFF", historical.reasonCode());
        verify(browser, never()).send(any(), any(), anyString());
        Suggestion fresh = workflow.observe(inbound("m2", "请发简历"));
        assertEquals("blocked", fresh.state());
        assertEquals("AUTO_REPLY_OFF", fresh.reasonCode());
        verify(browser, never()).send(any(), any(), anyString());
    }

    @Test
    void unknownSendIsNotRetried() {
        store.saveProfile(new Profile("", 2027, List.of("上海"), List.of(new Fact("f1", "做过产品需求分析")), "asset", true, true, true,
                Resume.empty(), "", "", "", "", List.of(), true, FollowUp.defaults()));
        when(browser.send(any(), any(), anyString())).thenReturn(SendResult.UNKNOWN);
        workflow.observe(inbound("m0", "先建立对话"));
        Suggestion s = workflow.observe(inbound("m1", "方便沟通吗？"));
        assertNotNull(s);
        assertEquals("unknown", store.get(s.id()).state());
        assertNull(workflow.observe(inbound("m1", "方便沟通吗？")));
        verify(browser, times(1)).send(any(), any(), anyString());
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> workflow.confirm(s.id(), new Confirmation(1, "m1", "x")));
        assertTrue(ex.getMessage().contains("LEGACY_SUGGESTIONS_RETIRED"));
    }

    @Test
    void persistedConversationIsNotAnAutomaticWatermarkAfterRestartOrProfileReenable() {
        workflow.observe(inbound("old", "历史问题"));
        store.saveProfile(new Profile("", 2027, List.of("上海"), List.of(new Fact("f1", "产品经验")), "asset", false, false, false));
        store.saveProfile(new Profile("", 2027, List.of("上海"), List.of(new Fact("f1", "产品经验")), "asset", true, true, true));
        BossAiWorkflowService restarted = new BossAiWorkflowService(store, ai, new ChatDecisionValidator(mapper), browser, mapper);
        doAnswer(call -> {
            String prompt = call.getArgument(0);
            String data = prompt.substring(prompt.indexOf("输入数据：") + "输入数据：".length()).trim();
            String requestId = mapper.readTree(data).get("requestId").asText();
            return mapper.writeValueAsString(new Decision("2", requestId, Intent.REQUEST_RESUME, Action.SEND_RESUME, "", "asset", List.of(), List.of(), "REQUEST", "IMAGE"));
        }).when(ai).sendRequest(anyString());
        assertEquals("blocked", restarted.observe(inbound("historical-resume", "请发简历")).state());
        verify(browser, never()).send(any(), any(), anyString());
        assertEquals("blocked", restarted.observe(inbound("new-resume", "请发简历")).state());
        verify(browser, never()).send(any(), any(), anyString());
    }
}
