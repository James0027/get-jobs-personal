package com.getjobs.application.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.getjobs.application.chat.BossChatStore;
import com.getjobs.application.chat.ChatContracts;
import com.getjobs.application.entity.BossConfigEntity;
import com.getjobs.application.service.BossService;
import com.getjobs.application.service.ConfigService;
import com.getjobs.application.service.Job51Service;
import com.getjobs.application.service.LiepinService;
import com.getjobs.application.service.ZhilianService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.*;

class SearchTemplateApplicationServiceTest {
    @TempDir Path temp;
    SingleConnectionDataSource ds;
    SearchTemplateStore store;
    SearchTemplateApplicationService service;
    BossService boss;
    ConfigService configs;
    BossChatStore profiles;
    String templateId;

    @BeforeEach
    void setup() throws Exception {
        ds = new SingleConnectionDataSource("jdbc:sqlite:" + temp.resolve("template-apply.db"), true);
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        ObjectMapper mapper = new ObjectMapper();
        store = new SearchTemplateStore(jdbc, mapper);
        store.init();

        profiles = mock(BossChatStore.class);
        when(profiles.profile()).thenReturn(ChatContracts.Profile.empty());
        SearchPlatformCatalog catalog = mock(SearchPlatformCatalog.class);
        when(catalog.options("BOSS", "cityCode")).thenReturn(Map.of("sh", "上海"));
        when(catalog.options("BOSS", "jobType")).thenReturn(Map.of("full", "全职"));
        SearchTemplateResolver resolver = new SearchTemplateResolver(store, catalog, profiles, mapper);
        configs = mock(ConfigService.class);
        boss = mock(BossService.class);
        service = new SearchTemplateApplicationService(store, resolver, profiles, configs, boss,
                mock(LiepinService.class), mock(Job51Service.class), mock(ZhilianService.class));

        String body = """
                {"name":"后端实习","isDefault":false,
                 "criteria":{"keywords":["Java"],"targetCities":["上海"],"workType":["全职"]},
                 "sayHiDraft":"您好，期待沟通",
                 "expectedSalary":"200/天","arrivalIntention":"随时到岗",
                 "linkedResumeAssetId":"sha256:fixture","personalIntro":"个人介绍",
                 "communicationStyle":"自然真诚"}
                """;
        templateId = store.save(null, mapper.readTree(body)).id();
    }

    @AfterEach
    void close() {
        ds.destroy();
    }

    @Test
    void effectiveViewShowsTemplateAndProfileSources() {
        Map<String, Object> effective = service.effective(templateId);
        Map<?, ?> fields = (Map<?, ?>) effective.get("fields");
        assertEquals("TEMPLATE", ((Map<?, ?>) fields.get("workType")).get("source"));
        assertEquals("TEMPLATE", ((Map<?, ?>) fields.get("expectedSalary")).get("source"));
        assertEquals("TEMPLATE", ((Map<?, ?>) fields.get("acceptedCities")).get("source"));
        assertEquals("TEMPLATE", ((Map<?, ?>) fields.get("linkedResumeAssetId")).get("source"));
    }

    @Test
    void effectiveFallsBackToProfileIntentionsWhenTemplateFieldIsUnset() throws Exception {
        String body = """
                {"name":"继承模板","isDefault":false,
                 "criteria":{"keywords":["Java"]},
                 "sayHiDraft":"您好"}
                """;
        String id = store.save(null, new ObjectMapper().readTree(body)).id();
        ChatContracts.Profile profile = new ChatContracts.Profile(
                "p1", 2026, List.of("上海"), List.of(), "", false, false, false,
                ChatContracts.Resume.empty(), "", "", "", "", List.of(), false,
                ChatContracts.FollowUp.defaults(), "", "",
                new ChatContracts.JobIntentions("实习", "300/天", "1个月内", "", 5));
        when(profiles.profile()).thenReturn(profile);

        Map<?, ?> fields = (Map<?, ?>) service.effective(id).get("fields");
        assertEquals("PROFILE", ((Map<?, ?>) fields.get("expectedSalary")).get("source"));
        assertEquals("300/天", ((Map<?, ?>) fields.get("expectedSalary")).get("value"));
        assertEquals("PROFILE", ((Map<?, ?>) fields.get("arrivalIntention")).get("source"));
        assertEquals("PROFILE", ((Map<?, ?>) fields.get("workType")).get("source"));
        assertEquals("PROFILE", ((Map<?, ?>) fields.get("acceptedCities")).get("source"));
    }

    @Test
    void applyWritesOnlyPlatformConfigAndMarksPersonalFieldsUnapplied() {
        Map<String, Object> result = service.apply(templateId, "BOSS");

        ArgumentCaptor<BossConfigEntity> captor = ArgumentCaptor.forClass(BossConfigEntity.class);
        verify(boss).saveOrUpdateFirstSelective(captor.capture());
        BossConfigEntity entity = captor.getValue();
        assertEquals("Java", entity.getKeywords());
        assertEquals("sh", entity.getCityCode());
        assertEquals("full", entity.getJobType());
        assertEquals("您好，期待沟通", entity.getSayHi());

        assertEquals(false, result.get("tasksStarted"));
        List<?> unapplied = (List<?>) result.get("unappliedFields");
        assertTrue(unapplied.contains("expectedSalary"));
        assertTrue(unapplied.contains("arrivalIntention"));
        assertTrue(unapplied.contains("linkedResumeAssetId"));
        assertTrue(unapplied.contains("personalIntro"));
        assertTrue(unapplied.contains("communicationStyle"));
        verify(configs).batchUpdateConfigs(anyMap());
    }
}
