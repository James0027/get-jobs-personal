package com.getjobs.application.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

/** Local DOM fixtures only; never visits BOSS or sends messages. */
@EnabledIfEnvironmentVariable(named="BOSS_CHAT_DOM_TEST",matches="true")
class BossChatBrowserGatewayDomTest {
    static Playwright pw;static Browser browser;BrowserContext context;Page page;
    @BeforeAll static void launch(){
        var env=new java.util.HashMap<>(System.getenv());env.put("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD","1");
        pw=Playwright.create(new Playwright.CreateOptions().setEnv(env));
        var options=new BrowserType.LaunchOptions().setHeadless(true);
        String chrome=System.getenv("BOSS_TEST_CHROME");
        System.out.println("[D1核对] BossChatBrowserGatewayDomTest 实际使用浏览器内核: " + (chrome != null ? chrome : "Playwright默认通道"));
        if(chrome!=null)options.setExecutablePath(Path.of(chrome));
        browser=pw.chromium().launch(options);
    }
    @AfterAll static void close(){if(browser!=null)browser.close();if(pw!=null)pw.close();}
    @BeforeEach void open(){context=browser.newContext();page=context.newPage();}
    @AfterEach void cleanup(){context.close();}
    String fixture(String rootIdentity){return "<div class='chat-header' data-uid='B'>会话B</div><div class='chat-message'><ul class='im-list' "+rootIdentity+"><li class='message-item item-friend' data-id='mA'><div class='message-content'>请发简历</div></li></ul></div>";}
    @Test void headerBDoesNotAuthorizeReadingRootA()throws Exception{
        page.setContent(fixture("data-uid='A'"));assertNull(BossChatBrowserGateway.readSnapshot(page,"data-uid:B",new ObjectMapper()));
    }
    @Test void headerAloneDoesNotProvideMessageIdentity()throws Exception{
        page.setContent(fixture(""));assertNull(BossChatBrowserGateway.readSnapshot(page,"data-uid:B",new ObjectMapper()));
    }
    @Test void actualMessageRootIdentityAllowsRead()throws Exception{
        page.setContent(fixture("data-uid='B'"));var inbound=BossChatBrowserGateway.readSnapshot(page,"data-uid:B",new ObjectMapper());
        assertNotNull(inbound);assertEquals("mA",inbound.lastInboundMessageId());assertEquals("data-uid:B",inbound.conversationId());
    }
}
