package com.getjobs.worker.boss;

import com.getjobs.application.service.BossService;
import com.getjobs.worker.boss.delivery.DeliveryRun;
import com.getjobs.worker.utils.PlaywrightUtil;
import com.microsoft.playwright.*;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Exercises search-result filtering through first-greeting confirmation without a real browser. */
class BossSearchResultTest {
    @ParameterizedTest
    @CsvSource({"产品经理,负责用户体验和需求分析", "产品实习生,面向在校学生",
            "应届产品经理,欢迎应届毕业生", "产品经理,招聘2028届毕业生"})
    void searchResultsDoNotRequireAiWordsOrCohortEvidence(String title, String description) {
        var result = deliver(title, description, false, false);
        assertEquals(1, result.successCount());
        assertEquals(0, result.snapshot().get("skippedCount"));
        assertEquals(0, result.snapshot().get("unknownCount"));
    }

    @Test void jobBlacklistStillBlocksSearchResults() {
        var result = deliver("产品经理", "负责需求分析", true, false);
        assertEquals(0, result.successCount());
        assertTrue(((Number) result.snapshot().get("skippedCount")).intValue() > 0);
    }

    @Test void unknownGreetingStillStopsTheRunWithoutCountingSuccess() {
        var result = deliver("产品实习生", "面向在校学生", false, true);
        assertEquals(0, result.successCount());
        assertEquals(1, result.snapshot().get("unknownCount"));
        assertEquals("awaiting_reconciliation", result.snapshot().get("state"));
        assertFalse(result.maySend());
    }

    @Test void unrestrictedCompanySizeAddsNoSearchConstraint() {
        BossConfig config = new BossConfig();
        config.setScale(List.of("0"));
        assertFalse(Boss.buildSearchUrl(config, "0").contains("scale="));
    }

    private DeliveryRun deliver(String title, String description, boolean blacklisted, boolean unknown) {
        BossService service = mock(BossService.class);
        when(service.getBlackJobs()).thenReturn(blacklisted ? Set.of("产品") : Set.of());
        when(service.getBlackCompanies()).thenReturn(Set.of());
        when(service.getBlackRecruiters()).thenReturn(Set.of());
        Boss boss = new Boss(service, null, null);
        BossConfig config = new BossConfig();
        config.setKeywords(List.of("产品经理"));
        config.setCityCode(List.of("0"));
        config.setTargetCount(1);
        config.setAiOnly(true); // A saved legacy value must not restore the retired AI gate.
        config.setSayHi("您好，希望进一步沟通。");
        config.setAllowRepeatChat(false);
        boss.setConfig(config);
        boss.setProgressCallback((message, current, total) -> {});
        var run = new DeliveryRun(1, List.of("0"), null);
        boss.setDeliveryRun(run);

        Page listing = mock(Page.class);
        Locator card = mock(Locator.class);
        when(listing.locator(anyString())).thenReturn(card);
        when(card.first()).thenReturn(card);
        when(card.nth(anyInt())).thenReturn(card);
        when(card.count()).thenReturn(1);
        when(card.isVisible()).thenReturn(true);
        when(card.getAttribute("href")).thenReturn("/job_detail/test-job.html");
        Response response = mock(Response.class);
        String json = new JSONObject().put("zpData", new JSONObject()
                .put("jobInfo", new JSONObject().put("jobName", title).put("postDescription", description)
                        .put("encryptId", "test-job").put("encryptUserId", "test-user")
                        .put("experienceName", "在校/应届"))
                .put("brandComInfo", new JSONObject().put("brandName", "TEST company"))
                .put("bossInfo", new JSONObject().put("title", "招聘经理"))).toString();
        when(response.text()).thenReturn(json);
        when(listing.waitForResponse(org.mockito.ArgumentMatchers.<Predicate<Response>>any(), any(Runnable.class)))
                .thenReturn(response);

        Page detail = mock(Page.class);
        BrowserContext context = mock(BrowserContext.class);
        when(listing.context()).thenReturn(context);
        when(context.newPage()).thenReturn(detail);
        Locator chat = mock(Locator.class);
        Locator input = mock(Locator.class);
        Locator send = mock(Locator.class);
        when(detail.locator("a.btn-startchat, a.op-btn-chat")).thenReturn(chat);
        when(chat.first()).thenReturn(chat);
        when(chat.count()).thenReturn(1);
        when(chat.textContent()).thenReturn("立即沟通");
        when(input.first()).thenReturn(input);
        when(input.count()).thenReturn(1);
        when(input.evaluate(anyString())).thenReturn("textarea");
        when(detail.locator("div.send-message, button[type='send'].btn-send, button.btn-send")).thenReturn(send);
        when(send.first()).thenReturn(send);
        when(send.count()).thenReturn(1);
        Frame frame = mock(Frame.class);
        when(detail.frames()).thenReturn(List.of(frame));
        when(frame.evaluate(anyString(), any())).thenReturn("{\"rootFound\":true,\"count\":0}",
                unknown ? "{\"rootFound\":true,\"count\":0}" : "{\"rootFound\":true,\"count\":1}");
        boss.setPage(listing);
        boss.prepare();
        try (var pauses = mockStatic(PlaywrightUtil.class);
             var navigators = mockConstruction(BossChatNavigator.class, (navigator, construction) ->
                     when(navigator.open(any(), anyBoolean(), anyBoolean(), any(), anyLong()))
                             .thenReturn(new BossChatNavigator.Chat(detail, input, false)))) {
            boss.execute();
        }
        verify(send, times(blacklisted ? 0 : 1)).click();
        return run;
    }
}
