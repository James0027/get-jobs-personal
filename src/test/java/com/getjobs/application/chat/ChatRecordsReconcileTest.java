package com.getjobs.application.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.util.List;

import static com.getjobs.application.chat.ChatContracts.*;
import static org.junit.jupiter.api.Assertions.*;

class ChatRecordsReconcileTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private BossChatStore store;

    @BeforeEach
    void setup() {
        store = new BossChatStore(new JdbcTemplate(new SingleConnectionDataSource("jdbc:sqlite::memory:", true)), mapper);
        store.init();
    }

    @Test
    void testRecordsFilteringTruncationAndReconcile() {
        String longText = "a".repeat(250);
        Suggestion s1 = new Suggestion("s1", 1, "c1", "m1", "pv1", "blocked", "回复1",
                Intent.OTHER, Action.NO_ACTION, "BLOCKED", List.of(), "短文本", "", List.of(), 1000L);
        Suggestion s2 = new Suggestion("s2", 1, "c2", "m2", "pv1", "unknown", "回复2",
                Intent.OTHER, Action.SUGGEST_REPLY, "UNKNOWN", List.of(), longText, "", List.of(), 2000L);
        Suggestion s3 = new Suggestion("s3", 1, "c3", "m3", "pv1", "failed", "回复3",
                Intent.OTHER, Action.NO_ACTION, "FAILED", List.of(), "短文本3", "", List.of(), 3000L);
        Suggestion s4 = new Suggestion("s4", 1, "c4", "m4", "pv1", "pending_approval", "回复4",
                Intent.OTHER, Action.SUGGEST_REPLY, "WAIT", List.of(), "短文本4", "", List.of(), 4000L);

        store.save(s1);
        store.save(s2);
        store.save(s3);
        store.save(s4);

        // records(List.of("unknown"), 50) 只返回 unknown
        List<Suggestion> unknownRecords = store.records(List.of("unknown"), 50);
        assertEquals(1, unknownRecords.size());
        assertEquals("s2", unknownRecords.get(0).id());
        assertEquals("unknown", unknownRecords.get(0).state());

        // sourceText 超 200 被截断
        assertEquals(200, unknownRecords.get(0).sourceText().length());
        assertEquals("a".repeat(200), unknownRecords.get(0).sourceText());

        // limit=1 只返回 1 条
        List<Suggestion> oneRecord = store.records(List.of("blocked", "unknown", "failed"), 1);
        assertEquals(1, oneRecord.size());

        // reconcileUnknown(id, true) → sent，再次调用 → 409 (IllegalStateException)
        String reconciledState = store.reconcileUnknown("s2", true);
        assertEquals("sent", reconciledState);
        assertThrows(IllegalStateException.class, () -> store.reconcileUnknown("s2", true));

        // 对 blocked 记录调用 → 409 (IllegalStateException)
        assertThrows(IllegalStateException.class, () -> store.reconcileUnknown("s1", true));
    }
}
