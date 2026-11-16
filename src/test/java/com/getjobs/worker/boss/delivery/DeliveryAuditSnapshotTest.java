package com.getjobs.worker.boss.delivery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.getjobs.application.chat.BossChatStore;
import com.getjobs.application.search.*;
import com.getjobs.application.service.ConfigService;
import com.getjobs.worker.boss.*;
import com.getjobs.worker.manager.PlaywrightManager;
import com.getjobs.worker.service.BossJobService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DeliveryAuditSnapshotTest {
    @TempDir Path temp;
    SingleConnectionDataSource ds;JdbcTemplate jdbc;SearchTemplateStore templates;DeliveryTaskStore tasks;
    BossJobService jobs;BossConfig actual;ObjectProvider<Boss> provider;
    ObjectMapper mapper=new ObjectMapper();
    String draft="{\"name\":\"draft\",\"isDefault\":true,\"criteria\":{\"keywords\":[\"DRAFT_NOT_APPLIED\"]},\"sayHiDraft\":\"not sent\"}";
    @BeforeEach void setup(){
        ds=new SingleConnectionDataSource("jdbc:sqlite:"+temp.resolve("audit.db"),true);jdbc=new JdbcTemplate(ds);
        var profiles=new BossChatStore(jdbc,mapper);profiles.init();
        jdbc.update("INSERT INTO boss_chat_profile VALUES(1,?)","{\"version\":\"profile-v1\",\"approvedPdfAssetId\":\"sha256:fixture-old\"}");
        templates=new SearchTemplateStore(jdbc,mapper);templates.init();tasks=new DeliveryTaskStore(jdbc);
        actual=new BossConfig();actual.setCityCode(List.of("actual-city"));actual.setKeywords(new ArrayList<>(List.of("ACTUAL")));actual.setTargetCount(3);
        var configs=mock(ConfigService.class);when(configs.getBossConfig()).thenReturn(actual);
        provider=mock(ObjectProvider.class);
        jobs=new BossJobService(mock(PlaywrightManager.class),configs,provider,tasks,new BossExecutionGuard(false),new DeliveryAuditSnapshot(profiles,templates));
    }
    @AfterEach void close(){ds.destroy();}
    @Test void reservationPersistsActualConfigAndResumeKeepsFrozenAuditAfterEdits() throws Exception {
        String id=templates.save(null,mapper.readTree(draft)).id();
        var reservation=jobs.reserveDelivery(false,id);
        var before=tasks.findById(reservation.taskId()).orElseThrow();
        assertEquals("profile-v1",before.get("profileVersion"));assertEquals(id,before.get("templateId"));
        assertEquals(false,before.get("templateApplicationSupported"));assertEquals(false,before.get("assetExecutionFrozen"));
        assertEquals("NOT_CHECKED",before.get("assetAvailability"));
        assertEquals(List.of("sha256:fixture-old"),before.get("selectedAssetIds"));
        assertEquals(List.of("ACTUAL"),((Map<?,?>)before.get("resolvedCriteria")).get("keywords"));
        assertFalse(before.toString().contains("DRAFT_NOT_APPLIED"));
        jdbc.update("UPDATE boss_chat_profile SET payload=? WHERE id=1","{\"version\":\"profile-v2\",\"approvedPdfAssetId\":\"sha256:fixture-new\"}");
        templates.save(id,mapper.readTree(draft.replace("\"name\"","\"version\":1,\"name\"")));
        actual.getKeywords().add("changed-in-memory");
        assertEquals(List.of("ACTUAL"),((Map<?,?>)tasks.findById(reservation.taskId()).orElseThrow().get("resolvedCriteria")).get("keywords"));
        actual.setKeywords(List.of("ACTUAL"));jobs.abortReservation(reservation,"fixture stop");
        var resumed=jobs.reserveDelivery(true);
        var after=tasks.findById(resumed.taskId()).orElseThrow();
        assertEquals(reservation.taskId(),resumed.taskId());
        for(String field:List.of("profileVersion","templateId","templateVersion","resolvedCriteria","selectedAssetIds","createdAt","configFingerprint","cityQuotas"))assertEquals(before.get(field),after.get(field),field);
        verifyNoInteractions(provider);
    }
    @Test void auditMarksOnlyExplicitlyAppliedTemplateAsApplied() throws Exception {
        String id=templates.save(null,mapper.readTree(draft)).id();
        ConfigService appliedConfigs=mock(ConfigService.class);
        when(appliedConfigs.getConfigValue("TEMPLATE_APPLIED_BOSS_ID")).thenReturn(id);
        when(appliedConfigs.getConfigValue("TEMPLATE_APPLIED_BOSS_VERSION")).thenReturn("1");
        when(appliedConfigs.getConfigValue("TEMPLATE_APPLIED_BOSS_FIELDS")).thenReturn("keywords,cityCode");
        var audit=new DeliveryAuditSnapshot(new BossChatStore(jdbc,mapper),templates,appliedConfigs).capture(actual,id);
        assertEquals(true,audit.get("templateApplied"));
        assertEquals(true,audit.get("templateApplicationSupported"));
        assertEquals("PLATFORM_CONFIG_APPLY",audit.get("templateApplicationSource"));
        assertEquals(id,audit.get("appliedTemplateId"));
        assertEquals(1L,((Number)audit.get("appliedTemplateVersion")).longValue());
        assertFalse(((List<?>)audit.get("unappliedTemplateFields")).contains("keywords"));
    }

    @Test void noTemplatePathStillCreatesAuditAndDoesNotReadOrSelectDefaultTemplate() throws Exception {
        templates.save(null,mapper.readTree(draft));
        var reserved=jobs.reserveDelivery(false);
        var audit=tasks.findById(reserved.taskId()).orElseThrow();
        assertEquals("",audit.get("templateId"));assertEquals("BOSS",audit.get("platformId"));
        assertEquals(3,((Number)audit.get("targetCount")).intValue());
        verifyNoInteractions(provider);
    }
    @Test void nestedSnapshotCannotBeMutatedAndLegacyRestoreRemainsSupported(){
        Map<String,Object> mutable=new HashMap<>();mutable.put("resolvedCriteria",new HashMap<>(Map.of("keywords",new ArrayList<>(List.of("initial")))));
        var run=new DeliveryRun(2,List.of("city"),null,"fingerprint",mutable);
        Map<String,Object> view=run.snapshot();
        Map nested=(Map)view.get("resolvedCriteria");
        assertThrows(UnsupportedOperationException.class,()->nested.put("keywords",List.of("forged")));
        Map<String,Object> old=new DeliveryRun(2,List.of("city"),null,"fingerprint").snapshot();
        old.remove("auditSnapshotStatus");var restored=DeliveryRun.restore(old,null).snapshot();
        assertEquals("LEGACY_MISSING",restored.get("auditSnapshotStatus"));assertEquals(old.get("cityQuotas"),restored.get("cityQuotas"));
    }
}
