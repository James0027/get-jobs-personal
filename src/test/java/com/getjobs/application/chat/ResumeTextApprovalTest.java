package com.getjobs.application.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.util.List;

import static com.getjobs.application.chat.ChatContracts.*;
import static org.junit.jupiter.api.Assertions.*;

class ResumeTextApprovalTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private BossChatStore store;

    @BeforeEach
    void setup() {
        store = new BossChatStore(new JdbcTemplate(new SingleConnectionDataSource("jdbc:sqlite::memory:", true)), mapper);
        store.init();
    }

    @Test
    void testResumeTextApprovalLifecycleAndChunking() {
        // 构造超长段落和多段文本 A（覆盖分块逻辑）
        StringBuilder textABuilder = new StringBuilder();
        for (int i = 1; i <= 15; i++) {
            textABuilder.append("这是第").append(i).append("段个人经历内容，包含更多详细项目与成果描述。".repeat(25)).append("\n\n");
        }
        String textA = textABuilder.toString().trim();

        // 1. 保存文本 A → resumeTextHash 非空、approvedResumeTextHash 为空、无 resume: 事实
        Profile p1 = new Profile("", 2026, List.of(), List.of(), "", false, false, true,
                Resume.empty(), "", textA, "", "", List.of(), false, FollowUp.defaults());
        Profile saved1 = store.saveProfile(p1);

        assertFalse(saved1.resumeTextHash().isEmpty());
        assertEquals("", saved1.approvedResumeTextHash());
        assertTrue(saved1.facts().stream().noneMatch(f -> f.id().startsWith("resume:")));

        // 2. 调 approve（正确 version+hash）→ 出现分块事实且每块 ≤2000、未超过 11 块
        Profile approved1 = store.approveResumeText(saved1.version(), saved1.resumeTextHash());
        assertEquals(saved1.resumeTextHash(), approved1.approvedResumeTextHash());

        List<Fact> resumeFacts = approved1.facts().stream().filter(f -> f.id().startsWith("resume:")).toList();
        assertFalse(resumeFacts.isEmpty());
        assertTrue(resumeFacts.size() <= 11);
        for (Fact rf : resumeFacts) {
            assertTrue(rf.text().length() <= 2000);
            assertTrue(rf.text().startsWith("简历文本第"));
        }

        // 3. 再调 approve（旧 version）→ 409 (IllegalStateException)
        assertThrows(IllegalStateException.class, () -> store.approveResumeText(saved1.version(), saved1.resumeTextHash()));

        // 4. 改文本 B 后保存 → approvedResumeTextHash 为空、resume: 事实消失
        String textB = "这是修改后的简短文本B";
        Profile p2 = new Profile(approved1.version(), 2026, List.of(), approved1.facts(), "", false, false, true,
                Resume.empty(), "", textB, "", approved1.approvedResumeTextHash(), approved1.derivedFactIds(), false, FollowUp.defaults());
        Profile saved2 = store.saveProfile(p2);

        assertEquals("", saved2.approvedResumeTextHash());
        assertTrue(saved2.facts().stream().noneMatch(f -> f.id().startsWith("resume:")));

        // 5. 客户端在 PUT 里伪造 approvedResumeTextHash 不生效
        String fakeHash = BossChatStore.sha256("fake");
        Profile forged = new Profile(saved2.version(), 2026, List.of(), saved2.facts(), "", false, false, true,
                Resume.empty(), "", textB, fakeHash, fakeHash, List.of(), false, FollowUp.defaults());
        Profile savedForged = store.saveProfile(forged);

        assertEquals("", savedForged.approvedResumeTextHash());
        assertTrue(savedForged.facts().stream().noneMatch(f -> f.id().startsWith("resume:")));
    }
}
