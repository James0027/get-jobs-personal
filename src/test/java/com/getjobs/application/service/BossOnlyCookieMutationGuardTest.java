package com.getjobs.application.service;

import com.getjobs.application.entity.CookieEntity;
import com.getjobs.application.mapper.CookieMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class BossOnlyCookieMutationGuardTest {

    private final CookieMapper mapper = mock(CookieMapper.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final CookieService service = new CookieService(mapper, jdbc);

    @Test
    void bossOnlyLockRejectsSaveClearAndDeleteForEveryPlatform() {
        service.lockCookieMutations();

        for (String platform : List.of("boss", "liepin", "51job", "zhilian")) {
            IllegalStateException save = assertThrows(IllegalStateException.class,
                    () -> service.saveOrUpdateCookie(platform, "[]", "manual save"));
            assertEquals("BOSS_ONLY_COOKIE_DB_DISABLED", save.getMessage(), platform);

            IllegalStateException clear = assertThrows(IllegalStateException.class,
                    () -> service.clearCookieByPlatform(platform, "manual logout"));
            assertEquals("BOSS_ONLY_COOKIE_DB_DISABLED", clear.getMessage(), platform);

            IllegalStateException delete = assertThrows(IllegalStateException.class,
                    () -> service.deleteCookie(platform));
            assertEquals("BOSS_ONLY_COOKIE_DB_DISABLED", delete.getMessage(), platform);
        }

        verifyNoInteractions(mapper);
    }

    @Test
    void unlockedServiceStillPersistsCookies() {
        when(mapper.insert(any(CookieEntity.class))).thenReturn(1);
        assertFalse(service.isCookieMutationsLocked());

        service.saveOrUpdateCookie("51job", "[]", "manual save");

        verify(mapper).insert(any(CookieEntity.class));
    }
}
