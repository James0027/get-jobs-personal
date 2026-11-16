package com.getjobs.worker.boss;

import com.getjobs.worker.boss.controlled.*;
import com.getjobs.worker.boss.delivery.ConfirmedTextSender;
import com.microsoft.playwright.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import static org.junit.jupiter.api.Assertions.*;
import static com.getjobs.worker.boss.controlled.ControlledTarget.*;

/** Offline local DOM only, all requests intercepted. No user profile or real platform. */
class ControlledBossExecutionDomTest {
    @TempDir Path temp;
    @Test void singleTargetReservesBeforeEarliestEntryAndNeverAddsText() {
        SQLiteDataSource source=new SQLiteDataSource();source.setUrl("jdbc:sqlite:"+temp.resolve("fixture.db"));
        var store=new ControlledBossRunStore(new JdbcTemplate(source));
        var guard=new BossExecutionGuard(true);
        var target=new ControlledTarget("job-1","hr-1","conversation-1","hello","profile-1","asset-1","config-1");
        String round=store.createRound(), id=store.freeze(round,"key-1",target);
        try(var pw=Playwright.create()) {
            var options=new BrowserType.LaunchOptions().setHeadless(true);
            String executable=System.getenv("BOSS_TEST_CHROME");if(executable!=null)options.setExecutablePath(Path.of(executable));
            try(var browser=pw.chromium().launch(options);var context=browser.newContext()){
                context.route("**/*",route->route.abort());
                var page=context.newPage();
                page.setContent("""
                    <button id='entry' data-hr='hr-1' data-job='job-1' data-conversation='conversation-1'>立即沟通</button>
                    <div class='chat-message'><ul class='im-list'></ul></div><textarea class='input-area'></textarea>
                    <button class='btn-send'>send</button>
                    """);
                page.evaluate("""
                    () => {
                    window.entryClicks=0;window.textClicks=0;
                    document.querySelector('#entry').onclick=()=>{
                        window.entryClicks++;
                        const li=document.createElement('li');li.className='message-item item-myself';
                        const body=document.createElement('span');body.className='message-content';body.textContent='hello';
                        li.appendChild(body);document.querySelector('.im-list').appendChild(li);
                    };
                    document.querySelector('.btn-send').onclick=()=>window.textClicks++;
                    }
                    """);
                page.exposeFunction("budgetProbe",args->{assertEquals(1,((Number)store.status(round).get("messages")).intValue());return true;});
                page.evaluate("document.querySelector('#entry').addEventListener('click',()=>window.budgetProbe(),true)");
                var gateway=new ControlledBossService.Gateway(){
                    public <T>T onOwner(Callable<T> work){try{return work.call();}catch(Exception e){throw new RuntimeException(e);}}
                    public ControlledBossService.Inspection inspect(ControlledTarget f){
                        assertEquals(f.hrId(),page.locator("#entry").getAttribute("data-hr"));
                        assertEquals(f.jobId(),page.locator("#entry").getAttribute("data-job"));
                        assertEquals(f.conversationId(),page.locator("#entry").getAttribute("data-conversation"));
                        return new ControlledBossService.Inspection(target,Action.ENTRY,1);
                    }
                    public Receipt execute(ControlledTarget f,Permit permit,Action action){
                        assertEquals(1,((Number)store.status(round).get("jobs")).intValue());
                        var nav=new BossChatNavigator(page);
                        return ConfirmedTextSender.observeEntry(page,f.text(),()->assertNotNull(nav.openControlled(page.locator("#entry"),f,permit,guard,()->inspect(f),10000)));
                    }
                };
                var service=new ControlledBossService(store,guard,gateway);
                var result=service.execute(round,id,0,"key-1");
                assertEquals("PAUSED",result.get("state"),result.toString());assertEquals("PDF_SEND_NOT_IMPLEMENTED",result.get("pdfStatus"));
                assertEquals(1,((Number)result.get("messages")).intValue());
                service.execute(round,id,2,"key-1");assertEquals(1,page.locator(".item-myself").count());
                assertEquals(1,page.evaluate("window.entryClicks"));
                assertEquals(0,page.evaluate("window.textClicks"));
                assertThrows(IllegalStateException.class,guard::assertCanDeliver);
                assertThrows(IllegalStateException.class,guard::assertCanSendChat);
            }
        }
    }
}




