package com.getjobs.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.getjobs.application.chat.BossChatStore;
import com.getjobs.application.chat.ChatContracts;
import com.getjobs.application.chat.ChatContracts.Fact;
import com.getjobs.application.chat.ChatContracts.JobIntentions;
import com.getjobs.application.chat.ChatContracts.Resume;
import com.getjobs.application.search.SearchTemplateStore;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AiContentServiceTest {
    @Test
    void stubUsesOnlyProfileFactsAndIsClearlySimulated() {
        ConfigService configs = mock(ConfigService.class);
        AiConnectionService connections = mock(AiConnectionService.class);
        when(connections.getActive()).thenReturn(new AiConnectionService.Connection(
                "active", "隔离验证 Stub", AiConnectionService.ConnectionKind.MODEL_API,
                "STUB", "STUB", "", "stub-deterministic", false, false, false, true, "", "", List.of()));
        BossChatStore profiles = mock(BossChatStore.class);
        ChatContracts.Profile profile = new ChatContracts.Profile(
                "1", null, List.of("上海"), List.of(new Fact("f1", "五年后端经验")), "", false, false, true,
                Resume.empty(), "", "", "", "", List.of(), false, ChatContracts.FollowUp.defaults(),
                "", "", new JobIntentions("实习", "200/天", "随时到岗", "", 5));
        when(profiles.profile()).thenReturn(profile);
        AiContentService service = new AiContentService(
                configs, connections, mock(AiService.class), profiles, mock(SearchTemplateStore.class), new ObjectMapper());

        AiContentService.Generated generated = service.generate(
                new AiContentService.GenerateRequest(null, "professional", "medium", "job_search", "", ""));

        assertTrue(generated.simulated());
        assertFalse(generated.verified());
        assertTrue(generated.connectionMessage().contains("Stub"));
        assertTrue(generated.skillsIntro().contains("五年后端经验"));
        assertTrue(generated.personalIntro().contains("五年后端经验"));
        assertFalse(generated.sayHiDraft().contains("薪资"));
        assertFalse(generated.toString().contains("200/天"));
    }

    @Test
    void emptyProfileReturnsMissingFactsInsteadOfInventing() {
        ConfigService configs = mock(ConfigService.class);
        AiConnectionService connections = mock(AiConnectionService.class);
        when(connections.getActive()).thenReturn(new AiConnectionService.Connection(
                "active", "隔离验证 Stub", AiConnectionService.ConnectionKind.MODEL_API,
                "STUB", "STUB", "", "stub-deterministic", false, false, false, true, "", "", List.of()));
        BossChatStore profiles = mock(BossChatStore.class);
        when(profiles.profile()).thenReturn(ChatContracts.Profile.empty());
        AiContentService service = new AiContentService(
                configs, connections, mock(AiService.class), profiles, mock(SearchTemplateStore.class), new ObjectMapper());

        AiContentService.Generated generated = service.generate(
                new AiContentService.GenerateRequest(null, "sincere", "short", "personal_intro", "", ""));

        assertTrue(generated.missingFacts().stream().anyMatch(item -> item.contains("个人事实")));
        assertTrue(generated.skillsIntro().contains("资料不足"));
        assertTrue(generated.personalIntro().contains("资料不足"));
    }
}
