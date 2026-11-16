package com.getjobs.application.controller;
import com.getjobs.application.service.AiConnectionService;
import com.getjobs.application.service.AiService;
import com.getjobs.application.service.ConfigService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.*;
class AiConnectionControllerTest {
    @Test void viewNeverExposesKeyAndDoesNotClaimVerifiedExecution() throws Exception {
        var service=mock(ConfigService.class);
        when(service.getConfigValue("API_KEY")).thenReturn("fixture-secret");
        when(service.getConfigValue("BASE_URL")).thenReturn("https://fixture.invalid");
        when(service.getConfigValue("MODEL")).thenReturn("fixture-model");
        var connections=new AiConnectionService(service, mock(AiService.class));
        var mvc=MockMvcBuilders.standaloneSetup(new AiConnectionController(connections)).build();
        mvc.perform(get("/api/ai/connections")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].apiKeyConfigured").value(true))
                .andExpect(jsonPath("$[0].executionVerified").value(false))
                .andExpect(content().string(not(containsString("fixture-secret"))));
        mvc.perform(get("/api/ai/provider-presets")).andExpect(status().isOk())
                .andExpect(jsonPath("$[3].connectionKind").value("AGENT_OR_MCP"))
                .andExpect(jsonPath("$[3].legacyProtocolSupported").value(false))
                .andExpect(jsonPath("$[3].agentExecutionSupported").value(false));
        verify(service,never()).getAiConfigs();
    }
}
