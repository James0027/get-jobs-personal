package com.getjobs.worker.boss.delivery;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DeliveryRunTest {
    @Test void allocatesRemainderInSelectionOrderAndDeduplicatesCities() {
        var run = new DeliveryRun(50, List.of("上海", "杭州", "深圳", "上海"), null);
        assertEquals(Map.of("上海",17,"杭州",17,"深圳",16),run.snapshot().get("cityQuotas"));
        run.enterCity("上海");
        for (int i=0;i<17;i++) { assertTrue(run.claim("sh"+i)); run.sent("sh"+i,false); }
        assertTrue(run.cityQuotaReached("上海"));
        assertFalse(run.claim("sh0"));
        assertEquals(17,run.successCount());
    }
    @Test void shortagesCanBeFilledElsewhereWithoutExceedingGlobalLimit() {
        var run = new DeliveryRun(5,List.of("A","B"),null);
        run.enterCity("A"); run.claim("A1");run.sent("A1",false);
        run.enterCity("B");
        for(int i=0;i<4;i++){ assertTrue(run.claim("B"+i));run.sent("B"+i,false); }
        assertFalse(run.maySend()); assertFalse(run.claim("overflow")); assertEquals(5,run.successCount());
    }
    @Test void repeatsFailuresAndSecondMessageNeverConsumeFirstGreetingQuota() {
        var run = new DeliveryRun(2,List.of("A"),null);
        run.enterCity("A");run.sent("repeat",true);run.failed();run.explanationFailed();
        assertEquals(0,run.successCount());assertTrue(run.maySend());
        run.sent("job",false);run.sent("job",false);
        assertEquals(1,run.successCount()); assertEquals(1,run.snapshot().get("repeatCount"));
    }
    @Test void unknownBlocksSendingAndSurvivesFinish() {
        List<Map<String,Object>> saved=new ArrayList<>();
        var run=new DeliveryRun(50,List.of("A"),saved::add);
        run.unknown();run.finish(false);
        assertFalse(run.maySend());assertFalse(run.claim("another"));
        assertEquals("awaiting_reconciliation",saved.getLast().get("state"));
        assertEquals(0,run.successCount());
    }
    @Test void graduationRequiresExplicitEvidenceAndDoesNotTreatUnlimitedAsCampus() {
        assertEquals(GraduationEligibility.Decision.INELIGIBLE,GraduationEligibility.evaluate(2027,"面向2028届毕业生"));
        assertEquals(GraduationEligibility.Decision.ELIGIBLE,GraduationEligibility.evaluate(2027,"面向27届、28届毕业生"));
        assertEquals(GraduationEligibility.Decision.NEEDS_REVIEW,GraduationEligibility.evaluate(null,"面向2027届"));
        assertEquals(GraduationEligibility.Decision.NEEDS_REVIEW,GraduationEligibility.evaluate(2027,"校招应届生"));
        assertEquals(GraduationEligibility.Decision.ELIGIBLE,GraduationEligibility.evaluate(2027,"经验不限，全职产品经理"));
    }
    @Test void platformCampusTagRequiresReviewEvenIfTitleAndJdOmitIt() {
        assertEquals(GraduationEligibility.Decision.NEEDS_REVIEW,
                GraduationEligibility.evaluate(2027,"产品经理",List.of("上海","在校/应届"),"负责用户体验与需求分析"));
    }
    @Test void sharedCohortSuffixIncludesEveryEnumeratedYear() {
        assertEquals(GraduationEligibility.Decision.ELIGIBLE,GraduationEligibility.evaluate(2027,"招聘2027、2028届毕业生"));
        assertEquals(GraduationEligibility.Decision.ELIGIBLE,GraduationEligibility.evaluate(2027,"面向27、28届应届生"));
        assertEquals(GraduationEligibility.Decision.INELIGIBLE,GraduationEligibility.evaluate(2026,"招聘2027、2028届毕业生"));
    }
    @Test void eventEditionIsNotGraduationEvidence() {
        assertEquals(GraduationEligibility.Decision.ELIGIBLE,GraduationEligibility.evaluate(2027,"负责第24届活动的产品方案，经验不限"));
        assertEquals(GraduationEligibility.Decision.ELIGIBLE,GraduationEligibility.evaluate(2027,"负责2024届活动的产品方案，经验不限"));
        assertEquals(GraduationEligibility.Decision.ELIGIBLE,GraduationEligibility.evaluate(2027,"校招2027届毕业生，参与第24届活动策划"));
    }
    @Test void cohortRangesStayPendingReview() {
        assertEquals(GraduationEligibility.Decision.NEEDS_REVIEW,GraduationEligibility.evaluate(2027,"面向2027-2028届毕业生"));
        assertEquals(GraduationEligibility.Decision.NEEDS_REVIEW,GraduationEligibility.evaluate(2027,"招聘27届至28届毕业生"));
    }
}
