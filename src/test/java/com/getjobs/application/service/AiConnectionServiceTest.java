package com.getjobs.application.service;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class AiConnectionServiceTest {
    @Test
    void stubIsPersistedAndExplicitlyNotVerified() {
        ConfigService configs = mock(ConfigService.class);
        Map<String, String> store = new HashMap<>();
        when(configs.getConfigValue(anyString())).thenAnswer(inv -> store.get(inv.getArgument(0)));
        doAnswer(inv -> {
            Map<String, String> updates = inv.getArgument(0);
            store.putAll(updates);
            return updates.size();
        }).when(configs).batchUpdateConfigs(anyMap());
        AiService ai = mock(AiService.class);
        AiConnectionService service = new AiConnectionService(configs, ai);

        AiConnectionService.Connection saved = service.save(
                new AiConnectionService.SaveRequest("STUB", "STUB", "", ""));
        assertTrue(saved.simulated());
        assertTrue(saved.enabled());
        assertEquals("stub-deterministic", saved.model());
        assertFalse(saved.executionVerified());

        AiConnectionService.TestResult result = service.test();
        assertTrue(result.success());
        assertTrue(result.simulated());
        assertFalse(result.verified());
        verifyNoInteractions(ai);
    }

    @Test
    void mcpCannotBePresentedAsModelApi() {
        ConfigService configs = mock(ConfigService.class);
        AiConnectionService service = new AiConnectionService(configs, mock(AiService.class));
        assertThrows(IllegalArgumentException.class,
                () -> service.save(new AiConnectionService.SaveRequest("MCP", "MCP", "", "")));
    }

    @Test
    void explicitRealTestOnlyVerifiesOnSuccessAndNeverReturnsSecret() {
        ConfigService configs = mock(ConfigService.class);
        Map<String, String> store = new HashMap<>();
        store.put("API_KEY", "fixture-secret");
        store.put("BASE_URL", "https://fixture.invalid");
        store.put("MODEL", "fixture-model");
        store.put("AI_PROVIDER", "OPENAI");
        store.put("AI_PROTOCOL", "OPENAI_CHAT_COMPLETIONS");
        when(configs.getConfigValue(anyString())).thenAnswer(inv -> store.get(inv.getArgument(0)));
        doAnswer(inv -> {
            Map<String, String> updates = inv.getArgument(0);
            store.putAll(updates);
            return updates.size();
        }).when(configs).batchUpdateConfigs(anyMap());
        AiService ai = mock(AiService.class);
        when(ai.sendRequest(anyString())).thenReturn("OK");
        AiConnectionService service = new AiConnectionService(configs, ai);

        AiConnectionService.TestResult result = service.test();
        assertTrue(result.success());
        assertTrue(result.verified());
        assertFalse(result.simulated());
        assertFalse(result.toString().contains("fixture-secret"));
        assertEquals("1", store.get("AI_CONNECTION_VERIFIED"));
    }
}
