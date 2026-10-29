package com.getjobs.application.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.util.List;

import static com.getjobs.application.chat.ChatContracts.*;
import static org.junit.jupiter.api.Assertions.*;

class ProfileCompatTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private BossChatStore store;

    @BeforeEach
    void setup() {
        store = new BossChatStore(new JdbcTemplate(new SingleConnectionDataSource("jdbc:sqlite::memory:", true)), mapper);
        store.init();
    }

    @Test
    void testLegacyJsonDeserializationAndDefaultValues() throws Exception {
        String legacyJson = """
                {
                  "version": "v1",
                  "graduationYear": 2026,
                  "acceptedCities": ["北京", "上海"],
                  "facts": [{"id": "work:old", "text": "旧资料原文"}],
                  "approvedResumeAssetId": "asset-old",
                  "autoResume": false,
                  "autoLocation": true,
                  "enabled": true
                }
                """;

        Profile p = mapper.readValue(legacyJson, Profile.class);
        assertEquals("v1", p.version());
        assertEquals(2026, p.graduationYear());
        assertEquals(List.of("北京", "上海"), p.acceptedCities());
        assertEquals(1, p.facts().size());
        assertEquals("work:old", p.facts().get(0).id());
        assertEquals("旧资料原文", p.facts().get(0).text());
        assertEquals("asset-old", p.approvedResumeAssetId());
        assertFalse(p.autoResume());
        assertTrue(p.autoLocation());
        assertTrue(p.enabled());

        // Default values for new fields
        assertNotNull(p.resume());
        assertTrue(p.resume().work().isEmpty());
        assertTrue(p.resume().project().isEmpty());
        assertTrue(p.resume().education().isEmpty());
        assertTrue(p.resume().skills().isEmpty());
        assertEquals("", p.approvedPdfAssetId());
        assertEquals("", p.resumeText());
        assertEquals("", p.resumeTextHash());
        assertEquals("", p.approvedResumeTextHash());
        assertTrue(p.derivedFactIds().isEmpty());
        assertFalse(p.autoReply());
        assertNotNull(p.followUp());
        assertFalse(p.followUp().enabled());
        assertEquals(168, p.followUp().cooldownHours());
        assertEquals(1, p.followUp().maxPerConversation());

        // 8-param constructor equivalence
        Profile p8 = new Profile("v1", 2026, List.of("北京", "上海"), List.of(new Fact("work:old", "旧资料原文")), "asset-old", false, true, true);
        assertEquals(p.version(), p8.version());
        assertEquals(p.graduationYear(), p8.graduationYear());
        assertEquals(p.acceptedCities(), p8.acceptedCities());
        assertEquals(p.facts(), p8.facts());
        assertEquals(p.approvedResumeAssetId(), p8.approvedResumeAssetId());
        assertEquals(p.autoResume(), p8.autoResume());
        assertEquals(p.autoLocation(), p8.autoLocation());
        assertEquals(p.enabled(), p8.enabled());
        assertEquals(p.resume(), p8.resume());
        assertEquals(p.approvedPdfAssetId(), p8.approvedPdfAssetId());
        assertEquals(p.resumeText(), p8.resumeText());
        assertEquals(p.resumeTextHash(), p8.resumeTextHash());
        assertEquals(p.approvedResumeTextHash(), p8.approvedResumeTextHash());
        assertEquals(p.derivedFactIds(), p8.derivedFactIds());
        assertEquals(p.autoReply(), p8.autoReply());
        assertEquals(p.followUp(), p8.followUp());

        // store.saveProfile saves and reads back, old facts remain verbatim
        Profile saved = store.saveProfile(p);
        Profile reloaded = store.profile();
        assertEquals(1, reloaded.facts().size());
        assertEquals("work:old", reloaded.facts().get(0).id());
        assertEquals("旧资料原文", reloaded.facts().get(0).text());
    }
}
