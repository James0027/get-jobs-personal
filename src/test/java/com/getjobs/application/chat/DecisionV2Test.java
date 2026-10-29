package com.getjobs.application.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.getjobs.application.chat.ChatContracts.*;
import static org.junit.jupiter.api.Assertions.*;

class DecisionV2Test {
    private final ObjectMapper mapper = new ObjectMapper();
    private ChatDecisionValidator validator;
    private Profile profile;

    @BeforeEach
    void setup() {
        validator = new ChatDecisionValidator(mapper);
        profile = new Profile(
                "v1", 2026, List.of("北京"), List.of(new Fact("f1", "本科毕业于测试大学")),
                "asset-img", true, true, true,
                Resume.empty(), "asset-pdf", "", "", "", List.of(), true, FollowUp.defaults()
        );
    }

    @Test
    void v1NineFieldsParsesAndInfersKind() {
        String json = """
                {
                  "schemaVersion": "1",
                  "requestId": "req-1",
                  "intent": "REQUEST_RESUME",
                  "action": "SEND_RESUME",
                  "replyText": "",
                  "resumeAssetId": "asset-img",
                  "evidenceFactIds": [],
                  "missingFacts": [],
                  "reasonCode": "OK"
                }
                """;
        Decision d = validator.parse(json, "req-1", profile, 300);
        assertEquals("1", d.schemaVersion());
        assertEquals("IMAGE", d.resumeAssetKind());
    }

    @Test
    void v2TenFieldsParsesCorrectly() {
        String json = """
                {
                  "schemaVersion": "2",
                  "requestId": "req-2",
                  "intent": "REQUEST_RESUME",
                  "action": "SEND_RESUME",
                  "replyText": "",
                  "resumeAssetId": "asset-pdf",
                  "evidenceFactIds": [],
                  "missingFacts": [],
                  "reasonCode": "OK",
                  "resumeAssetKind": "PDF"
                }
                """;
        Decision d = validator.parse(json, "req-2", profile, 300);
        assertEquals("2", d.schemaVersion());
        assertEquals("PDF", d.resumeAssetKind());
    }

    @Test
    void missingOrExtraFieldsThrow() {
        // v1 missing reasonCode
        String missingField = """
                {
                  "schemaVersion": "1",
                  "requestId": "req-3",
                  "intent": "OTHER",
                  "action": "NO_ACTION",
                  "replyText": "",
                  "resumeAssetId": "",
                  "evidenceFactIds": [],
                  "missingFacts": []
                }
                """;
        assertThrows(IllegalArgumentException.class, () -> validator.parse(missingField, "req-3", profile, 300));

        // v2 extra field
        String extraField = """
                {
                  "schemaVersion": "2",
                  "requestId": "req-4",
                  "intent": "OTHER",
                  "action": "NO_ACTION",
                  "replyText": "",
                  "resumeAssetId": "",
                  "evidenceFactIds": [],
                  "missingFacts": [],
                  "reasonCode": "OK",
                  "resumeAssetKind": "",
                  "extraKey": "value"
                }
                """;
        assertThrows(IllegalArgumentException.class, () -> validator.parse(extraField, "req-4", profile, 300));
    }

    @Test
    void illegalKindOrMismatchThrows() {
        // illegal kind "WORD"
        String illegalKind = """
                {
                  "schemaVersion": "2",
                  "requestId": "req-5",
                  "intent": "REQUEST_RESUME",
                  "action": "SEND_RESUME",
                  "replyText": "",
                  "resumeAssetId": "asset-img",
                  "evidenceFactIds": [],
                  "missingFacts": [],
                  "reasonCode": "OK",
                  "resumeAssetKind": "WORD"
                }
                """;
        assertThrows(IllegalArgumentException.class, () -> validator.parse(illegalKind, "req-5", profile, 300));

        // SEND_RESUME with empty kind
        String emptyKindForResume = """
                {
                  "schemaVersion": "2",
                  "requestId": "req-6",
                  "intent": "REQUEST_RESUME",
                  "action": "SEND_RESUME",
                  "replyText": "",
                  "resumeAssetId": "asset-img",
                  "evidenceFactIds": [],
                  "missingFacts": [],
                  "reasonCode": "OK",
                  "resumeAssetKind": ""
                }
                """;
        assertThrows(IllegalArgumentException.class, () -> validator.parse(emptyKindForResume, "req-6", profile, 300));

        // SEND_RESUME with asset mismatch
        String assetMismatch = """
                {
                  "schemaVersion": "2",
                  "requestId": "req-7",
                  "intent": "REQUEST_RESUME",
                  "action": "SEND_RESUME",
                  "replyText": "",
                  "resumeAssetId": "unknown-asset-id",
                  "evidenceFactIds": [],
                  "missingFacts": [],
                  "reasonCode": "OK",
                  "resumeAssetKind": "PDF"
                }
                """;
        assertThrows(IllegalArgumentException.class, () -> validator.parse(assetMismatch, "req-7", profile, 300));
    }
}
