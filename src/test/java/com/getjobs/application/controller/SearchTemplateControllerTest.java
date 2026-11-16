package com.getjobs.application.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.getjobs.application.chat.BossChatStore;
import com.getjobs.application.chat.ChatContracts;
import com.getjobs.application.search.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.http.MediaType.APPLICATION_JSON;

class SearchTemplateControllerTest {
    @TempDir Path temp;
    SingleConnectionDataSource ds;JdbcTemplate jdbc;SearchTemplateStore store;MockMvc mvc;SearchPlatformCatalog catalog;
    ObjectMapper mapper=new ObjectMapper();
    String body="{\"name\":\"AI\",\"isDefault\":true,\"criteria\":{\"keywords\":[\"Agent\"],\"targetTitles\":[\"PM\"]},\"sayHiDraft\":\"draft\"}";
    @BeforeEach void setup(){
        ds=new SingleConnectionDataSource("jdbc:sqlite:"+temp.resolve("templates.db"),true);jdbc=new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE legacy_fixture(payload TEXT)");jdbc.update("INSERT INTO legacy_fixture VALUES('preserved')");
        store=new SearchTemplateStore(jdbc,mapper);store.init();
        catalog=mock(SearchPlatformCatalog.class);var profiles=mock(BossChatStore.class);
        when(profiles.profile()).thenReturn(ChatContracts.Profile.empty());
        var resolver=new SearchTemplateResolver(store,catalog,profiles,mapper);
        mvc=MockMvcBuilders.standaloneSetup(new SearchTemplateController(store,catalog,resolver)).build();
    }
    @AfterEach void close(){ds.destroy();}
    String create() throws Exception {return mapper.readTree(mvc.perform(post("/api/search-templates").contentType(APPLICATION_JSON).content(body))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asText();}
    @Test void crudDefaultSwitchAndStaleUpdateRollBack() throws Exception {
        String first=create(),second=create();
        assertFalse(store.get(first).isDefault());assertTrue(store.get(second).isDefault());assertEquals(2,store.get(first).version());
        mvc.perform(put("/api/search-templates/"+first).contentType(APPLICATION_JSON).content(body.replace("\"name\"","\"version\":1,\"name\"")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("VERSION_CONFLICT"));
        assertTrue(store.get(second).isDefault());
        mvc.perform(delete("/api/search-templates/"+first)).andExpect(status().isNoContent());
        mvc.perform(get("/api/search-templates/"+first)).andExpect(status().isNotFound());
    }
    @Test void patchMissingNullAndEmptyAreDistinctAndScalarTypesAreEnforced() throws Exception {
        String id=create();String uri="/api/search-templates/"+id+"/platform-overrides/BOSS";
        mvc.perform(patch(uri).contentType(APPLICATION_JSON).content("{\"version\":0,\"fields\":{\"keywords\":[\"x\"],\"cityCode\":[]}}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1));
        mvc.perform(patch(uri).contentType(APPLICATION_JSON).content("{\"version\":1,\"fields\":{\"keywords\":null}}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.fields.keywords").doesNotExist()).andExpect(jsonPath("$.fields.cityCode").isEmpty());
        mvc.perform(patch(uri).contentType(APPLICATION_JSON).content("{\"version\":1,\"fields\":{}}"))
                .andExpect(status().isConflict());
        mvc.perform(patch(uri.replace("BOSS","LIEPIN")).contentType(APPLICATION_JSON).content("{\"version\":0,\"fields\":{\"cityCode\":[]}}"))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(get("/api/search-templates/"+id+"/platform-overrides")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].executionSupported").value(false));
        mvc.perform(delete("/api/search-templates/"+id)).andExpect(status().isNoContent());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM search_template_override",Integer.class));
    }
    @Test void rollbackPreservesLegacyAndMigrationCanBeAppliedAgain() throws Exception {
        create();new ResourceDatabasePopulator(new ClassPathResource("migrations/search-templates-v1-down.sql")).execute(ds);
        assertEquals("preserved",jdbc.queryForObject("SELECT payload FROM legacy_fixture",String.class));
        store.init();store.init();assertTrue(store.list().isEmpty());
    }
    @Test void closedDatabaseBackupCanRestoreTemplatesAfterDownMigration() throws Exception {
        String id=create();ds.destroy();
        Path database=temp.resolve("templates.db"),backup=temp.resolve("backup.db");Files.copy(database,backup);
        ds=new SingleConnectionDataSource("jdbc:sqlite:"+database,true);
        new ResourceDatabasePopulator(new ClassPathResource("migrations/search-templates-v1-down.sql")).execute(ds);ds.destroy();
        Files.copy(backup,database,StandardCopyOption.REPLACE_EXISTING);
        ds=new SingleConnectionDataSource("jdbc:sqlite:"+database,true);jdbc=new JdbcTemplate(ds);
        store=new SearchTemplateStore(jdbc,mapper);store.init();assertEquals(id,store.get(id).id());
        assertEquals("preserved",jdbc.queryForObject("SELECT payload FROM legacy_fixture",String.class));
    }
    @Test void invalidInputAndMalformedJsonHaveStructuredErrors() throws Exception {
        mvc.perform(post("/api/search-templates").contentType(APPLICATION_JSON).content(body.replace("targetTitles","unexpected")))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.error.field").value("criteria.unexpected"));
        mvc.perform(post("/api/search-templates").contentType(APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }
    @Test void previewMapsCityNamesAndKeepsUnmappedConditionsAsDrafts() throws Exception {
        when(catalog.options("BOSS","cityCode")).thenReturn(Map.of("fixture-sh","上海"));
        String id=mapper.readTree(mvc.perform(post("/api/search-templates").contentType(APPLICATION_JSON)
                .content(body.replace("\"keywords\":[\"Agent\"]","\"targetCities\":[\"上海\"],\"keywords\":[\"Agent\"]")))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asText();
        mvc.perform(get("/api/search-templates/"+id+"/platform-overrides"))
                .andExpect(jsonPath("$[0].effectiveCriteria.cityCode[0]").value("fixture-sh"))
                .andExpect(jsonPath("$[0].sources.cityCode").value("TEMPLATE"))
                .andExpect(jsonPath("$[0].draftOnlyFields[0]").value("targetTitles"));
        mvc.perform(patch("/api/search-templates/"+id+"/platform-overrides/BOSS").contentType(APPLICATION_JSON)
                .content("{\"version\":0,\"fields\":{\"cityCode\":[]}}" )).andExpect(status().isOk());
        mvc.perform(get("/api/search-templates/"+id+"/platform-overrides"))
                .andExpect(jsonPath("$[0].effectiveCriteria.cityCode").isEmpty())
                .andExpect(jsonPath("$[0].sources.cityCode").value("PLATFORM_OVERRIDE"));
    }
}
