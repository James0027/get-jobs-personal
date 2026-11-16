package com.getjobs.worker.manager;

import com.getjobs.application.service.CookieService;
import com.getjobs.application.service.LoginOnlyBrowserService;
import com.microsoft.playwright.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import org.mockito.ArgumentCaptor;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class BossOnlyManagerTest {
    @TempDir Path temp;
    LoginOnlyBrowserService login;
    Playwright pw;BrowserType type;BrowserContext ctx;Page page;
    PlaywrightManager manager;
    CookieService cookies;
    @BeforeEach void setup(){
        login=new LoginOnlyBrowserService();ReflectionTestUtils.setField(login,"configuredLoginDir",temp.toString());
        pw=mock(Playwright.class);type=mock(BrowserType.class);ctx=mock(BrowserContext.class);page=mock(Page.class);cookies=mock(CookieService.class);
        when(pw.chromium()).thenReturn(type);when(type.launchPersistentContext(any(Path.class),any())).thenReturn(ctx);
        when(ctx.pages()).thenReturn(List.of());when(ctx.newPage()).thenReturn(page);
        manager=new PlaywrightManager(){@Override protected Playwright createPlaywright(){assertTrue(Thread.currentThread().getName().equals("playwright-thread"));return pw;}};
        ReflectionTestUtils.setField(manager,"cookieService",cookies);
    }
    LoginOnlyBrowserService.BossProfileHandoff ticket()throws Exception{
        var ticket=login.claimBossProfileForExecution().toCompletableFuture().get(5,TimeUnit.SECONDS);Files.createDirectories(ticket.profilePath());return ticket;
    }
    @AfterEach void cleanup(){
        try{manager.destroy();}catch(Exception expected){}
        ((ExecutorService)ReflectionTestUtils.getField(manager,"playwrightExecutor")).shutdownNow();login.destroy();
    }
    @Test void onlyBossPageNoCookieImportExportOrBackgroundAndOwnerCloseThenRelease()throws Exception{
        var ticket=ticket();manager.ensureBossOnlyReady(login,ticket);
        assertNull(manager.getLiepinPage());assertNull(manager.getJob51Page());assertNull(manager.getZhilianPage());
        verify(ctx,times(1)).newPage();verify(ctx,never()).cookies();verify(ctx,never()).addCookies(anyList());
        verify(cookies).lockCookieMutations();verifyNoMoreInteractions(cookies);
        assertFalse(manager.tryRunOnPlaywright(()->fail("background ran")));
        assertThrows(IllegalStateException.class,()->manager.saveBossCookiesToDb("blocked"));
        assertThrows(IllegalStateException.class,()->manager.save51jobCookiesToDb("blocked"));
        assertThrows(IllegalStateException.class,()->manager.saveCookiesToDb("51job","blocked"));
        assertThrows(IllegalStateException.class,()->manager.saveCookiesToDb("liepin","blocked"));
        assertThrows(IllegalStateException.class,()->manager.saveCookiesToDb("zhilian","blocked"));
        assertThrows(IllegalStateException.class,manager::clearBossCookies);
        var capture=ArgumentCaptor.forClass(BrowserType.LaunchPersistentContextOptions.class);
        verify(type).launchPersistentContext(eq(ticket.profilePath()),capture.capture());
        assertTrue(capture.getValue().args.stream().noneMatch(a->a.contains("remote-debugging-port")||a.contains("28888")||a.contains("6866")));
        doAnswer(call->{assertTrue(login.isCurrentBossHandoff(ticket));assertEquals("playwright-thread",Thread.currentThread().getName());return null;}).when(ctx).close();
        doAnswer(call->{assertTrue(login.isCurrentBossHandoff(ticket));assertEquals("playwright-thread",Thread.currentThread().getName());return null;}).when(pw).close();
        manager.closeBossOnly();assertFalse(login.isCurrentBossHandoff(ticket));
        var ordered=inOrder(ctx,pw);ordered.verify(ctx).close();ordered.verify(pw).close();
    }
    @Test void invalidForeignOrUnacknowledgedTicketNeverOpens()throws Exception{
        assertThrows(IllegalStateException.class,()->manager.ensureBossOnlyReady(login,null));verifyNoInteractions(pw);
        var ticket=ticket();var foreign=new LoginOnlyBrowserService();
        try{assertThrows(IllegalStateException.class,()->manager.ensureBossOnlyReady(foreign,ticket));verifyNoInteractions(pw);}finally{foreign.destroy();}
    }
    @Test void cleanupFailureStillClosesDriverRetainsFenceAndCannotFallback()throws Exception{
        var ticket=ticket();manager.ensureBossOnlyReady(login,ticket);doThrow(new RuntimeException("close failed")).when(ctx).close();
        assertThrows(IllegalStateException.class,manager::closeBossOnly);verify(pw).close();assertTrue(login.isCurrentBossHandoff(ticket));
        assertThrows(IllegalStateException.class,manager::ensureReady);assertThrows(LoginOnlyBrowserService.ActiveLoginException.class,()->login.start("boss"));
        verify(type,times(1)).launchPersistentContext(any(Path.class),any());
    }
    @Test void launchFailureRetainsFenceAndDoesNotAutomaticallyRetryOrReadCookies()throws Exception{
        var ticket=ticket();when(type.launchPersistentContext(any(Path.class),any())).thenThrow(new RuntimeException("profile locked"));
        assertThrows(IllegalStateException.class,()->manager.ensureBossOnlyReady(login,ticket));assertTrue(login.isCurrentBossHandoff(ticket));
        assertThrows(IllegalStateException.class,manager::ensureReady);verify(type,times(1)).launchPersistentContext(any(Path.class),any());
        verify(cookies).lockCookieMutations();verifyNoMoreInteractions(cookies);
    }
    @Test void ensureReadyRecreatesOnlyBossOnSameTicket()throws Exception{
        var ticket=ticket();manager.ensureBossOnlyReady(login,ticket);ReflectionTestUtils.setField(manager,"browserClosed",true);manager.ensureReady();
        verify(type,times(2)).launchPersistentContext(eq(ticket.profilePath()),any());verify(ctx,times(2)).newPage();
        verify(cookies).lockCookieMutations();verifyNoMoreInteractions(cookies);
        assertNull(manager.getLiepinPage());assertTrue(login.isCurrentBossHandoff(ticket));
    }
    @Test void missingConfiguredProfileFailsClosedWithoutCreatingDirectory()throws Exception{
        var ticket=login.claimBossProfileForExecution().toCompletableFuture().get(5,TimeUnit.SECONDS);
        assertThrows(IllegalStateException.class,()->manager.ensureBossOnlyReady(login,ticket));assertFalse(Files.exists(ticket.profilePath()));verifyNoInteractions(pw);
        assertTrue(login.isCurrentBossHandoff(ticket));
    }
    @Test void failedCloseAcknowledgementNeverCreatesManagerResources(){
        var blocked=mock(LoginOnlyBrowserService.class);
        when(blocked.claimBossProfileForExecution()).thenReturn(CompletableFuture.failedStage(new TimeoutException("no ACK")));
        assertThrows(IllegalStateException.class,()->manager.handoffBossOnlyReady(blocked));verifyNoInteractions(pw);verify(blocked,never()).releaseBossHandoff(any());
    }
    @Test void handoffClaimRunsOnPlaywrightOwnerThread(){
        var owner=mock(LoginOnlyBrowserService.class);
        when(owner.claimBossProfileForExecution()).thenAnswer(invocation->{
            assertEquals("playwright-thread",Thread.currentThread().getName());
            return CompletableFuture.failedStage(new TimeoutException("no ACK"));
        });
        assertThrows(IllegalStateException.class,()->manager.handoffBossOnlyReady(owner));
        verify(owner).claimBossProfileForExecution();
        verifyNoInteractions(type);
    }
    @Test void managerAlreadyOwnedRejectsBeforeClaimingAnyTicket(){
        var owner=mock(LoginOnlyBrowserService.class);
        ReflectionTestUtils.setField(manager,"playwright",pw);
        assertThrows(IllegalStateException.class,()->manager.handoffBossOnlyReady(owner));
        verify(owner,never()).claimBossProfileForExecution();
        verify(owner,never()).releaseBossHandoff(any());
        verifyNoInteractions(type);
    }
    @Test void rejectedClaimIsReleasedBackToOwner()throws Exception{
        var ticket=ticket();
        var owner=mock(LoginOnlyBrowserService.class);
        when(owner.isCurrentBossHandoff(ticket)).thenReturn(true);
        ReflectionTestUtils.setField(manager,"playwright",pw);
        var error=assertThrows(IllegalStateException.class,()->manager.ensureBossOnlyReady(owner,ticket));
        assertEquals("MANAGER_ALREADY_OWNED",error.getMessage());
        verify(owner).releaseBossHandoff(ticket);
        login.releaseBossHandoff(ticket);
        verifyNoInteractions(type);
    }
    @Test void foreignOwnerTicketMismatchIsReleasedAndRetryable()throws Exception{
        var first=ticket();manager.ensureBossOnlyReady(login,first);
        var second=new LoginOnlyBrowserService();
        ReflectionTestUtils.setField(second,"configuredLoginDir",temp.toString());
        try{
            var secondTicket=second.claimBossProfileForExecution().toCompletableFuture().get(5,TimeUnit.SECONDS);
            var error=assertThrows(IllegalStateException.class,()->manager.ensureBossOnlyReady(second,secondTicket));
            assertEquals("BOSS_HANDOFF_OWNER_MISMATCH",error.getMessage());
            assertFalse(second.isCurrentBossHandoff(secondTicket));
            var retried=second.claimBossProfileForExecution().toCompletableFuture().get(5,TimeUnit.SECONDS);
            assertTrue(second.isCurrentBossHandoff(retried));
            second.releaseBossHandoff(retried);
        }finally{second.destroy();}
        verify(type,times(1)).launchPersistentContext(any(Path.class),any());
    }
    @Test void newTicketAfterOwnerCloseIsAcceptedAgain()throws Exception{
        var first=ticket();manager.ensureBossOnlyReady(login,first);
        manager.closeBossOnly();assertFalse(login.isCurrentBossHandoff(first));
        var second=ticket();manager.ensureBossOnlyReady(login,second);
        verify(type,times(2)).launchPersistentContext(eq(second.profilePath()),any());
        assertTrue(login.isCurrentBossHandoff(second));
    }
    @Test void releaseFailureRetainsFenceAndTicket()throws Exception{
        var failing=new LoginOnlyBrowserService(){
            @Override public synchronized void releaseBossHandoff(BossProfileHandoff ticket){
                throw new IllegalStateException("release failed");
            }
        };
        ReflectionTestUtils.setField(failing,"configuredLoginDir",temp.toString());
        try{
            var ticket=failing.claimBossProfileForExecution().toCompletableFuture().get(5,TimeUnit.SECONDS);
            Files.createDirectories(ticket.profilePath());
            manager.ensureBossOnlyReady(failing,ticket);
            assertThrows(IllegalStateException.class,manager::closeBossOnly);
            assertTrue(failing.isCurrentBossHandoff(ticket));
        }finally{failing.destroy();}
    }
    @Test void destroyShutsDownPlaywrightExecutorAfterBossOnlyClose()throws Exception{
        manager.ensureBossOnlyReady(login,ticket());
        manager.destroy();
        var executor=(ExecutorService)ReflectionTestUtils.getField(manager,"playwrightExecutor");
        assertTrue(executor.isShutdown());
    }
}
