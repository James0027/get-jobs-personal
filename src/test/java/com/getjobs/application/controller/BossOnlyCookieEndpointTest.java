package com.getjobs.application.controller;

import com.getjobs.application.mapper.CookieMapper;
import com.getjobs.application.service.CookieService;
import com.getjobs.application.service.Job51Service;
import com.getjobs.application.service.LiepinService;
import com.getjobs.application.service.ZhilianService;
import com.getjobs.worker.manager.PlaywrightManager;
import com.getjobs.worker.service.BossJobService;
import com.getjobs.worker.service.Job51JobService;
import com.getjobs.worker.service.LiepinJobService;
import com.getjobs.worker.service.ZhilianJobService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Every logout endpoint reaches CookieService.clearCookieByPlatform directly (not only the
 * manager), so the BOSS-only mutation fence must be enforced at that shared boundary.
 */
class BossOnlyCookieEndpointTest {

    private final CookieMapper mapper = mock(CookieMapper.class);
    private final CookieService cookieService = new CookieService(mapper, mock(JdbcTemplate.class));
    private final PlaywrightManager playwrightManager = mock(PlaywrightManager.class);

    @Test
    void allFourLogoutEndpointsFailClosedWithoutTouchingCookieRows() {
        cookieService.lockCookieMutations();

        BossController boss = new BossController(mock(BossJobService.class), playwrightManager, cookieService);
        JobController job51 = new JobController(mock(Job51Service.class), mock(Job51JobService.class),
                playwrightManager, cookieService);

        LiepinController liepin = new LiepinController();
        ReflectionTestUtils.setField(liepin, "playwrightManager", playwrightManager);
        ReflectionTestUtils.setField(liepin, "cookieService", cookieService);
        ReflectionTestUtils.setField(liepin, "liepinService", mock(LiepinService.class));
        ReflectionTestUtils.setField(liepin, "liepinJobService", mock(LiepinJobService.class));

        ZhilianController zhilian = new ZhilianController();
        ReflectionTestUtils.setField(zhilian, "playwrightManager", playwrightManager);
        ReflectionTestUtils.setField(zhilian, "cookieService", cookieService);
        ReflectionTestUtils.setField(zhilian, "zhilianService", mock(ZhilianService.class));
        ReflectionTestUtils.setField(zhilian, "zhilianJobService", mock(ZhilianJobService.class));

        for (var response : List.of(
                boss.logoutBoss(),
                job51.logout51job(),
                liepin.logoutLiepin(),
                zhilian.logoutZhilian())) {
            assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        }

        verifyNoInteractions(mapper);
    }
}
