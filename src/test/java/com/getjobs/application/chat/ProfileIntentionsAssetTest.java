package com.getjobs.application.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.getjobs.application.chat.ChatContracts.Profile;
import com.getjobs.application.service.BossResumeImageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ProfileIntentionsAssetTest {
    @TempDir Path temp;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void oldProfileJsonKeepsCompatibilityAndNewFieldsRoundTrip() throws Exception {
        Profile old = mapper.readValue("{\"version\":\"v0\",\"graduationYear\":2025}", Profile.class);
        assertEquals("", old.approvedImageAssetId());
        assertEquals("", old.preferredResumeAssetKind());
        assertEquals("", old.intentions().workType());

        String json = """
                {"version":"v1","graduationYear":2026,"acceptedCities":["上海"],
                 "facts":[{"id":"f1","text":"补充事实"}],
                 "approvedImageAssetId":"sha256:image","preferredResumeAssetKind":"image",
                 "intentions":{"workType":"实习","expectedSalary":"200/天",
                 "arrivalIntention":"随时到岗","availableFrom":"2026-12","weeklyAvailabilityDays":5}}
                """;
        Profile profile = mapper.readValue(json, Profile.class);
        assertEquals("IMAGE", profile.preferredResumeAssetKind());
        assertEquals("sha256:image", profile.preferredResumeAssetId());
        assertEquals("实习", profile.intentions().workType());
        assertEquals(5, profile.intentions().weeklyAvailabilityDays());
        assertTrue(mapper.writeValueAsString(profile).contains("weeklyAvailabilityDays"));
    }

    @Test
    void imageAssetExposesStableIdAndCanBeDeleted() throws Exception {
        BossResumeImageService service = new BossResumeImageService(temp.toString());
        byte[] png = Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Y9Zl1sAAAAASUVORK5CYII=");
        service.save(new MockMultipartFile("file", "resume.png", "image/png", png));

        Map<String, Object> metadata = service.getMetadata();
        assertEquals(true, metadata.get("exists"));
        assertEquals(true, metadata.get("available"));
        assertEquals("IMAGE", metadata.get("kind"));
        assertTrue(String.valueOf(metadata.get("assetId")).startsWith("sha256:"));
        assertFalse(metadata.containsKey("path"));

        assertTrue(service.deleteCurrentImage());
        assertEquals(false, service.getMetadata().get("exists"));
        assertEquals("", service.currentAssetId());
    }
}
