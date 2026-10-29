package com.getjobs.application.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.getjobs.application.service.AiService;
import com.getjobs.worker.boss.BossConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.util.List;

import static com.getjobs.application.chat.ChatContracts.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class MatchExplanationTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private BossChatStore store;
    private AiService ai;
    private BossChatBrowserGateway browser;
    private BossAiWorkflowService workflow;
    private BossConfig config;

    @BeforeEach
    void setup() {
        store = new BossChatStore(new JdbcTemplate(new SingleConnectionDataSource("jdbc:sqlite::memory:", true)), mapper);
        store.init();
        ai = mock(AiService.class);
        browser = mock(BossChatBrowserGateway.class);
        workflow = new BossAiWorkflowService(store, ai, new ChatDecisionValidator(mapper), browser, mapper);

        config = new BossConfig();
        config.setSayHi("您好，我对该岗位很感兴趣，期待沟通！");

        Profile profile = new Profile(
                "v1", 2026, List.of("北京"), List.of(new Fact("f1", "具备5年敏捷与产品设计经验")),
                "asset", true, true, true,
                Resume.empty(), "", "", "", "", List.of(), true, FollowUp.defaults()
        );
        store.saveProfile(profile);
    }

    @Test
    void validUnder60WithEvidenceReturnsText() throws Exception {
        when(ai.sendRequest(anyString())).thenAnswer(call -> {
            String prompt = call.getArgument(0);
            String data = prompt.substring(prompt.indexOf("输入数据：") + "输入数据：".length()).trim();
            String reqId = mapper.readTree(data).get("requestId").asText();
            return mapper.writeValueAsString(new Decision("2", reqId, Intent.OTHER, Action.SUGGEST_REPLY, "我具备敏捷与产品设计经验，与岗位要求相匹配。", "", List.of("f1"), List.of(), "MATCH", ""));
        });

        String result = workflow.generateMatchExplanation(config, "产品", "高级产品经理", "负责业务规划");
        assertNotNull(result);
        assertEquals("我具备敏捷与产品设计经验，与岗位要求相匹配。", result);
    }

    @Test
    void over60CharsReturnsNull() throws Exception {
        String longText = "一二三四五六七八九十一二三四五六七八九十一二三四五六七八九十一二三四五六七八九十一二三四五六七八九十一二三四五六七八九十一"; // 61 chars
        when(ai.sendRequest(anyString())).thenAnswer(call -> {
            String prompt = call.getArgument(0);
            String data = prompt.substring(prompt.indexOf("输入数据：") + "输入数据：".length()).trim();
            String reqId = mapper.readTree(data).get("requestId").asText();
            return mapper.writeValueAsString(new Decision("2", reqId, Intent.OTHER, Action.SUGGEST_REPLY, longText, "", List.of("f1"), List.of(), "MATCH", ""));
        });

        String result = workflow.generateMatchExplanation(config, "产品", "高级产品经理", "负责业务规划");
        assertNull(result);
    }

    @Test
    void noEvidenceReturnsNull() throws Exception {
        when(ai.sendRequest(anyString())).thenAnswer(call -> {
            String prompt = call.getArgument(0);
            String data = prompt.substring(prompt.indexOf("输入数据：") + "输入数据：".length()).trim();
            String reqId = mapper.readTree(data).get("requestId").asText();
            // action is NO_ACTION
            return mapper.writeValueAsString(new Decision("2", reqId, Intent.OTHER, Action.NO_ACTION, "", "", List.of(), List.of(), "NO_MATCH", ""));
        });

        String result = workflow.generateMatchExplanation(config, "产品", "高级产品经理", "负责业务规划");
        assertNull(result);
    }

    @Test
    void sameAsSayHiReturnsNull() throws Exception {
        when(ai.sendRequest(anyString())).thenAnswer(call -> {
            String prompt = call.getArgument(0);
            String data = prompt.substring(prompt.indexOf("输入数据：") + "输入数据：".length()).trim();
            String reqId = mapper.readTree(data).get("requestId").asText();
            return mapper.writeValueAsString(new Decision("2", reqId, Intent.OTHER, Action.SUGGEST_REPLY, "您好，我对该岗位很感兴趣，期待沟通！", "", List.of("f1"), List.of(), "MATCH", ""));
        });

        String result = workflow.generateMatchExplanation(config, "产品", "高级产品经理", "负责业务规划");
        assertNull(result);
    }
}
