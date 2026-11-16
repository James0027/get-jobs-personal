package com.getjobs.application.controller;

import com.getjobs.application.entity.ConfigEntity;
import com.getjobs.application.service.ConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.Map;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ConfigControllerSecretTest {
    ConfigService service;
    MockMvc mvc;
    @BeforeEach void setup() {
        service = mock(ConfigService.class);
        var controller = new ConfigController();
        ReflectionTestUtils.setField(controller, "configService", service);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }
    @Test void bothReadPathsExposeOnlyStatus() throws Exception {
        when(service.getAllConfigsAsMap()).thenReturn(Map.of("API_KEY", "fixture-secret", "MODEL", "fixture-model"));
        var entity = new ConfigEntity(); entity.setConfigValue("fixture-secret");
        when(service.getConfigByKey("API_KEY")).thenReturn(entity);
        mvc.perform(get("/api/config")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.API_KEY").doesNotExist())
                .andExpect(jsonPath("$.data.API_KEY_CONFIGURED").value(true))
                .andExpect(jsonPath("$.data.MODEL").value("fixture-model"));
        mvc.perform(get("/api/config/API_KEY")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.configured").value(true))
                .andExpect(jsonPath("$.data.configValue").doesNotExist());
    }
    @Test void blankSavesPreserveAndOnlyExplicitDeleteClears() throws Exception {
        mvc.perform(post("/api/config").contentType(MediaType.APPLICATION_JSON)
                .content("{\"API_KEY\":\"  \"}")).andExpect(status().isOk());
        mvc.perform(put("/api/config/API_KEY").contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":\"\"}")).andExpect(status().isOk());
        verifyNoInteractions(service);
        when(service.updateConfig("API_KEY", "")).thenReturn(true);
        mvc.perform(delete("/api/config/API_KEY")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.configured").value(false));
        verify(service).updateConfig("API_KEY", "");
    }
    @Test void replacementIsSavedWithoutReturningIt() throws Exception {
        when(service.batchUpdateConfigs(Map.of("API_KEY", "replacement"))).thenReturn(1);
        mvc.perform(post("/api/config").contentType(MediaType.APPLICATION_JSON)
                .content("{\"API_KEY\":\"replacement\"}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data").doesNotExist());
        verify(service).batchUpdateConfigs(Map.of("API_KEY", "replacement"));
    }
    @Test void serviceErrorsDoNotEchoSecretText() throws Exception {
        when(service.getAllConfigsAsMap()).thenThrow(new IllegalStateException("fixture-secret"));
        mvc.perform(get("/api/config")).andExpect(status().isInternalServerError())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("fixture-secret"))));
    }
}
