package com.getjobs.application.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.util.List;

import static com.getjobs.application.chat.ChatContracts.*;
import static org.junit.jupiter.api.Assertions.*;

class ProfileContractTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private BossChatStore store;

    @BeforeEach
    void setup() {
        store = new BossChatStore(new JdbcTemplate(new SingleConnectionDataSource("jdbc:sqlite::memory:", true)), mapper);
        store.init();
    }

    @Test
    void testFourCategoriesSaveReadBackAndDerivedTemplate() {
        WorkItem w1 = new WorkItem("w1", "某某科技", "产品经理", "2021-2024", "负责核心业务");
        ProjectItem p1 = new ProjectItem("p1", "企业中台", "负责人", "2023", "项目描述");
        EducationItem e1 = new EducationItem("e1", "某某大学", "本科", "工商管理", "2017-2021");
        SkillItem s1 = new SkillItem("s1", "需求分析", "熟练", "精通PRD");
        Resume resume = new Resume(List.of(w1), List.of(p1), List.of(e1), List.of(s1));

        Fact oldFact = new Fact("work:old", "旧资料原文");
        Profile p = new Profile("", 2026, List.of("北京"), List.of(oldFact), "", false, false, true,
                resume, "", "", "", "", List.of(), false, FollowUp.defaults());

        store.saveProfile(p);
        Profile reloaded = store.profile();

        // 读回字段一致
        assertEquals(1, reloaded.resume().work().size());
        assertEquals(w1, reloaded.resume().work().get(0));
        assertEquals(1, reloaded.resume().project().size());
        assertEquals(p1, reloaded.resume().project().get(0));
        assertEquals(1, reloaded.resume().education().size());
        assertEquals(e1, reloaded.resume().education().get(0));
        assertEquals(1, reloaded.resume().skills().size());
        assertEquals(s1, reloaded.resume().skills().get(0));

        // 派生文本与 §2.3 模板逐字相等
        List<Fact> facts = reloaded.facts();
        assertEquals(5, facts.size());
        // facts 顺序 = 历史事实在前、派生在后
        assertEquals("work:old", facts.get(0).id());
        assertEquals("旧资料原文", facts.get(0).text());

        assertEquals("work:w1", facts.get(1).id());
        assertEquals("工作经历：某某科技｜产品经理｜2021-2024｜负责核心业务", facts.get(1).text());

        assertEquals("project:p1", facts.get(2).id());
        assertEquals("项目经历：企业中台｜负责人｜2023｜项目描述", facts.get(2).text());

        assertEquals("education:e1", facts.get(3).id());
        assertEquals("教育背景：某某大学｜本科｜工商管理｜2017-2021", facts.get(3).text());

        assertEquals("skill:s1", facts.get(4).id());
        assertEquals("专业技能：需求分析｜熟练｜精通PRD", facts.get(4).text());

        // 删掉一个工作条目再保存 → 其 work: 事实消失、其它历史事实保留
        Resume resumeNoWork = new Resume(List.of(), List.of(p1), List.of(e1), List.of(s1));
        Profile update = new Profile(reloaded.version(), 2026, List.of("北京"), reloaded.facts(), "", false, false, true,
                resumeNoWork, "", "", "", "", reloaded.derivedFactIds(), false, FollowUp.defaults());
        store.saveProfile(update);
        Profile afterDelete = store.profile();

        assertTrue(afterDelete.facts().stream().noneMatch(f -> f.id().equals("work:w1")));
        assertTrue(afterDelete.facts().stream().anyMatch(f -> f.id().equals("work:old")));
        assertTrue(afterDelete.facts().stream().anyMatch(f -> f.id().equals("project:p1")));
        assertTrue(afterDelete.facts().stream().anyMatch(f -> f.id().equals("education:e1")));
        assertTrue(afterDelete.facts().stream().anyMatch(f -> f.id().equals("skill:s1")));
    }

    @Test
    void testValidationMissingCompanyAndDuplicateId() {
        // 缺 company → 400
        WorkItem missingCompany = new WorkItem("w1", "  ", "产品经理", "2021", "说明");
        Resume r1 = new Resume(List.of(missingCompany), List.of(), List.of(), List.of());
        Profile p1 = new Profile("", 2026, List.of(), List.of(), "", false, false, true,
                r1, "", "", "", "", List.of(), false, FollowUp.defaults());
        assertThrows(IllegalArgumentException.class, () -> store.saveProfile(p1));

        // 重复 id → 400
        WorkItem dup1 = new WorkItem("same-id", "公司A", "职位A", "", "");
        WorkItem dup2 = new WorkItem("same-id", "公司B", "职位B", "", "");
        Resume r2 = new Resume(List.of(dup1, dup2), List.of(), List.of(), List.of());
        Profile p2 = new Profile("", 2026, List.of(), List.of(), "", false, false, true,
                r2, "", "", "", "", List.of(), false, FollowUp.defaults());
        assertThrows(IllegalArgumentException.class, () -> store.saveProfile(p2));

        // 历史条目 ID 与派生条目 ID 冲突 → 400
        Fact conflictFact = new Fact("work:conflict1", "历史事实");
        WorkItem conflictWork = new WorkItem("conflict1", "公司", "职位", "", "");
        Resume r3 = new Resume(List.of(conflictWork), List.of(), List.of(), List.of());
        Profile p3 = new Profile("", 2026, List.of(), List.of(conflictFact), "", false, false, true,
                r3, "", "", "", "", List.of(), false, FollowUp.defaults());
        assertThrows(IllegalArgumentException.class, () -> store.saveProfile(p3));
    }
}
