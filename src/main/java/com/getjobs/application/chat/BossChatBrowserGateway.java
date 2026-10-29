package com.getjobs.application.chat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.getjobs.application.service.BossResumeImageService;
import com.getjobs.worker.boss.Boss;
import com.getjobs.worker.boss.BossExecutionGuard;
import com.getjobs.worker.boss.delivery.ConfirmedTextSender;
import com.getjobs.worker.manager.PlaywrightManager;
import com.getjobs.worker.service.BossJobService;
import com.microsoft.playwright.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import static com.getjobs.application.chat.ChatContracts.*;

/** All browser calls, including revalidation immediately before send, run on the existing PW thread. */
@Slf4j
@Component
public class BossChatBrowserGateway {
    static final String ROWS="li[role='listitem']";
    static final String INPUT="div#chat-input.chat-input[contenteditable='true'], textarea.input-area";
    private final PlaywrightManager manager;
    private final ObjectProvider<BossJobService> jobs;
    private final ObjectProvider<Boss> bosses;
    private final BossResumeImageService resumes;
    private final BossChatStore store;
    private final ObjectMapper mapper;
    private final BossExecutionGuard guard;
    private Page chatPage;
    private int scanOffset;
    private volatile String lastScanNote="尚未扫描";

    public BossChatBrowserGateway(PlaywrightManager manager, ObjectProvider<BossJobService> jobs, ObjectProvider<Boss> bosses,
                                  BossResumeImageService resumes, BossChatStore store, ObjectMapper mapper) {
        this(manager, jobs, bosses, resumes, store, mapper, new BossExecutionGuard(false));
    }

    @Autowired
    public BossChatBrowserGateway(PlaywrightManager manager, ObjectProvider<BossJobService> jobs, ObjectProvider<Boss> bosses,
                                  BossResumeImageService resumes, BossChatStore store, ObjectMapper mapper, BossExecutionGuard guard) {
        this.manager = manager;
        this.jobs = jobs;
        this.bosses = bosses;
        this.resumes = resumes;
        this.store = store;
        this.mapper = mapper;
        this.guard = guard != null ? guard : new BossExecutionGuard(false);
    }

    public BossExecutionGuard getGuard() {
        return guard;
    }

    public String currentResumeAssetId() {
        try {
            var path=resumes.getCurrentImagePath();
            return path.isEmpty()?"":"sha256:"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path.get())));
        } catch(Exception e){return "";}
    }

    public List<Inbound> scan() {
        if (guard.isReadOnlyCheckMode()) {
            lastScanNote = "只读检查模式，已禁用扫描";
            return List.of();
        }
        if (jobs.getObject().isRunning()) {
            lastScanNote = "投递进行中，聊天扫描暂缓";
            return List.of();
        }
        return manager.callOnPlaywright(() -> {
            if (guard.isReadOnlyCheckMode() || jobs.getObject().isRunning() || !manager.isLoggedIn("boss")) {
                lastScanNote = guard.isReadOnlyCheckMode() ? "只读检查模式，已禁用扫描" : "等待登录或投递结束";
                return List.of();
            }
            Page p=page();
            if(p==null)return List.of();
            List<Inbound> result=new ArrayList<>();
            Locator rows=p.locator(ROWS);
            int rowCount=rows.count();
            int count=Math.min(rowCount,30);
            int visited=0;
            for(int i=0;i<count && result.size()<10;i++){
                visited++;
                int index=(scanOffset+i)%rowCount;
                Locator row=rows.nth(index);String id=rowId(row);
                if(id==null)continue;
                // Only stable platform identities are accepted. No row indexes, names or text hashes.
                row.click(new Locator.ClickOptions().setTimeout(2000));p.waitForTimeout(300);
                if(!selected(row))continue;
                Inbound inbound=read(p,id);
                if(inbound!=null && !store.hasMessage(id,inbound.lastInboundMessageId()))result.add(inbound);
            }
            scanOffset=rowCount==0?0:(scanOffset+visited)%rowCount;
            lastScanNote=result.isEmpty()?"未发现可可靠识别的新入站消息；页面标识变化时不会自动发送":"已采集"+result.size()+"个会话，待去重与判断";
            return result;
        });
    }

    public SendResult send(Suggestion s,Profile profile,String text) {
        if(guard.isReadOnlyCheckMode()) {
            log.warn("[chat-guard] 只读检查模式已生效，拒绝外发消息/简历: suggestionId={}", s.id());
            return SendResult.FAILED;
        }
        if(jobs.getObject().isRunning())return SendResult.FAILED;
        return manager.callOnPlaywright(()->{
            if(guard.isReadOnlyCheckMode() || jobs.getObject().isRunning() || !manager.isLoggedIn("boss"))return SendResult.FAILED;
            if(!store.profile().version().equals(profile.version()))return SendResult.STALE;
            Page p=page();if(p==null)return SendResult.FAILED;
            Locator row=findRow(p,s.conversationId());if(row==null)return SendResult.STALE;
            row.click(new Locator.ClickOptions().setTimeout(3000));p.waitForTimeout(300);
            if(!selected(row))return SendResult.STALE;
            Inbound current=read(p,s.conversationId());
            if(current==null || !current.lastInboundMessageId().equals(s.lastInboundMessageId()) || !current.sourceText().equals(s.sourceText()))return SendResult.STALE;
            if(!store.profile().version().equals(profile.version()) || !store.isLatest(s))return SendResult.STALE;
            if(s.action()==Action.SEND_RESUME){
                if(!currentResumeAssetId().equals(s.resumeAssetId()))return SendResult.STALE;
                var asset=resumes.getCurrentImagePath();if(asset.isEmpty())return SendResult.STALE;
                byte[] bytes=Files.readAllBytes(asset.get());
                String digest="sha256:"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
                if(!digest.equals(s.resumeAssetId()))return SendResult.STALE;
                String fileName=asset.get().getFileName().toString();
                var snapshot=Files.createTempFile("boss-approved-resume-",fileName.substring(fileName.lastIndexOf('.')));
                try {
                    Files.write(snapshot,bytes);
                    return bosses.getObject().sendApprovedResume(p,snapshot)?SendResult.SENT:SendResult.UNKNOWN;
                } finally { Files.deleteIfExists(snapshot); }
            }
            var input=p.locator(INPUT).first();if(!input.isVisible())return SendResult.FAILED;
            return switch(ConfirmedTextSender.send(p,input,text)){case CONFIRMED->SendResult.SENT;case FAILED->SendResult.FAILED;case UNKNOWN->SendResult.UNKNOWN;};
        });
    }

    /**
     * 只读诊断入口：只经现有Playwright串行边界检查本平台页面，绝不调用任何AI/发送/上传/交换，
     * 绝不返回聊天文本、昵称、个人姓名或敏感Cookie/完整URL。
     */
    public Map<String, Object> diagnose() {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("timestamp", java.time.Instant.now().toString());
        report.put("readOnlyCheckMode", guard.isReadOnlyCheckMode());

        if (jobs.getObject().isRunning()) {
            report.put("stage", "DELIVERY_RUNNING");
            report.put("success", false);
            report.put("note", "投递进行中，聊天只读诊断暂缓");
            return report;
        }

        return manager.callOnPlaywright(() -> {
            boolean loggedIn = manager.isLoggedIn("boss");
            Page main = manager.getBossPage();
            if (main == null || main.isClosed()) {
                report.put("stage", "MAIN_PAGE_NOT_READY");
                report.put("success", false);
                report.put("note", "Boss主页面未就绪");
                return report;
            }
            if (!loggedIn) {
                report.put("stage", "NOT_LOGGED_IN");
                report.put("success", false);
                report.put("note", "请先登录Boss直聘");
                return report;
            }

            Page p = findExistingChatPage();
            if (p == null || p.isClosed()) {
                report.put("stage", "CHAT_PAGE_NOT_READY");
                report.put("success", false);
                report.put("note", "当前页不是聊天页，未新建标签页");
                return report;
            }

            // 1. 落地与重定向校验（脱敏 URL）
            String currentUrl = p.url();
            String sanitizedUrl = sanitizeUrl(currentUrl);
            boolean redirectedToLogin = currentUrl.contains("/login") || currentUrl.contains("login_action");
            Map<String, Object> landing = new LinkedHashMap<>();
            landing.put("url", sanitizedUrl);
            landing.put("redirectedToLogin", redirectedToLogin);
            landing.put("bossLoggedIn", loggedIn);
            report.put("landing", landing);

            if (redirectedToLogin) {
                report.put("stage", "REDIRECTED_TO_LOGIN");
                report.put("success", false);
                report.put("note", "聊天页面被重定向至登录页");
                return report;
            }

            // 2. 会话行选择器候选探测与计数
            List<String> rowCandidates = List.of(
                    "li[role='listitem']",
                    ".conversation-item",
                    "ul.chat-user-list > li",
                    ".chat-user-list li",
                    "li.list-item"
            );
            Map<String, Object> conversationsDiagnosis = new LinkedHashMap<>();
            String matchedRowSelector = null;
            int matchedRowCount = 0;
            for (String sel : rowCandidates) {
                try {
                    int c = p.locator(sel).count();
                    conversationsDiagnosis.put(sel, c);
                    if (c > 0 && matchedRowSelector == null) {
                        matchedRowSelector = sel;
                        matchedRowCount = c;
                    }
                } catch (Exception ignored) {}
            }
            conversationsDiagnosis.put("matchedSelector", matchedRowSelector);
            conversationsDiagnosis.put("count", matchedRowCount);

            // 脱敏抓取前 3 行脱敏元数据（仅 tagName、classList、属性名清单，无任何文本与敏感值）
            List<Map<String, Object>> sampleItems = new ArrayList<>();
            if (matchedRowSelector != null) {
                Locator matchedRows = p.locator(matchedRowSelector);
                int sampleCount = Math.min(matchedRowCount, 3);
                for (int i = 0; i < sampleCount; i++) {
                    Locator row = matchedRows.nth(i);
                    @SuppressWarnings("unchecked")
                    Map<String, Object> meta = (Map<String, Object>) row.evaluate("""
                        el => {
                            const attrs = Array.from(el.attributes).map(a => a.name);
                            return {
                                tagName: el.tagName.toLowerCase(),
                                classList: Array.from(el.classList),
                                attributeNames: attrs,
                                hasIdAttribute: attrs.some(a => ['data-uid','data-geek-id','data-user-id','data-id'].includes(a))
                            };
                        }
                    """);
                    sampleItems.add(meta);
                }
            }
            conversationsDiagnosis.put("sampleItems", sampleItems);
            report.put("conversations", conversationsDiagnosis);

            // 3. 选中态选择器候选探测
            List<String> selectedCandidates = List.of(
                    ".selected",
                    ".active",
                    ".current",
                    "[aria-selected='true']"
            );
            Map<String, Object> selectedDiagnosis = new LinkedHashMap<>();
            String matchedSelectedSelector = null;
            for (String sel : selectedCandidates) {
                try {
                    int c = p.locator(sel).count();
                    selectedDiagnosis.put(sel, c);
                    if (c > 0 && matchedSelectedSelector == null) {
                        matchedSelectedSelector = sel;
                    }
                } catch (Exception ignored) {}
            }
            selectedDiagnosis.put("matchedSelector", matchedSelectedSelector);
            report.put("selectedState", selectedDiagnosis);

            // 4. 消息根容器候选探测
            List<String> rootCandidates = List.of(
                    ".chat-message .im-list",
                    ".chat-record",
                    ".chat-message",
                    ".im-list",
                    ".chat-content",
                    ".message-content-list"
            );
            Map<String, Object> messageRootDiagnosis = new LinkedHashMap<>();
            String matchedRootSelector = null;
            for (String sel : rootCandidates) {
                try {
                    int c = p.locator(sel).count();
                    messageRootDiagnosis.put(sel, c);
                    if (c > 0 && matchedRootSelector == null) {
                        matchedRootSelector = sel;
                    }
                } catch (Exception ignored) {}
            }
            messageRootDiagnosis.put("matchedSelector", matchedRootSelector);
            if (matchedRootSelector != null) {
                @SuppressWarnings("unchecked")
                Map<String, Object> rootMeta = (Map<String, Object>) p.locator(matchedRootSelector).first().evaluate("""
                    el => ({
                        tagName: el.tagName.toLowerCase(),
                        classList: Array.from(el.classList),
                        attributeNames: Array.from(el.attributes).map(a => a.name)
                    })
                """);
                messageRootDiagnosis.put("structure", rootMeta);
            }
            report.put("messageRoot", messageRootDiagnosis);

            // 5. 消息行候选探测
            List<String> msgCandidates = List.of(
                    "li.message-item",
                    ".message-item",
                    ".chat-item",
                    "li[class*='item']"
            );
            Map<String, Object> msgDiagnosis = new LinkedHashMap<>();
            String matchedMsgSelector = null;
            int matchedMsgCount = 0;
            for (String sel : msgCandidates) {
                try {
                    int c = p.locator(sel).count();
                    msgDiagnosis.put(sel, c);
                    if (c > 0 && matchedMsgSelector == null) {
                        matchedMsgSelector = sel;
                        matchedMsgCount = c;
                    }
                } catch (Exception ignored) {}
            }
            msgDiagnosis.put("matchedSelector", matchedMsgSelector);
            msgDiagnosis.put("count", matchedMsgCount);
            report.put("messageRows", msgDiagnosis);

            // 6. 身份绑定属性探测（遵循 Hermes CE 反证核实要求）
            boolean rootHasIdentity = false;
            if (matchedRootSelector != null) {
                Boolean hasBinding = (Boolean) p.locator(matchedRootSelector).first().evaluate("""
                    el => ['data-uid','data-geek-id','data-user-id','data-conversation-id','data-id'].some(a => el.hasAttribute(a))
                """);
                rootHasIdentity = Boolean.TRUE.equals(hasBinding);
            }
            Map<String, Object> identityReport = new LinkedHashMap<>();
            identityReport.put("rootHasIdentityAttribute", rootHasIdentity);
            report.put("identityBinding", identityReport);

            // 7. 综合评定阶段
            if (matchedRowCount == 0) {
                report.put("stage", "NO_CONVERSATIONS_FOUND");
                report.put("success", false);
                report.put("note", "未找到会话列表行（候选选择器均未命中）");
            } else if (matchedRootSelector == null) {
                report.put("stage", "NO_MESSAGE_ROOT_FOUND");
                report.put("success", false);
                report.put("note", "找到会话行但未找到消息容器");
            } else if (!rootHasIdentity) {
                report.put("stage", "IDENTITY_NOT_BOUND");
                report.put("success", false);
                report.put("note", "消息根容器未检测到会话身份绑定属性，符合反证，禁止猜测发送");
            } else {
                report.put("stage", "DOM_RECOGNIZED");
                report.put("success", true);
                report.put("note", "DOM结构已脱敏识别");
            }

            return report;
        });
    }

    private static String sanitizeUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) return "";
        try {
            java.net.URI uri = java.net.URI.create(rawUrl);
            return uri.getScheme() + "://" + uri.getHost() + uri.getPath();
        } catch (Exception e) {
            int q = rawUrl.indexOf('?');
            return q >= 0 ? rawUrl.substring(0, q) : rawUrl;
        }
    }

    public String lastScanNote(){return lastScanNote;}

    Page findExistingChatPage() {
        if (chatPage != null && !chatPage.isClosed()) {
            try {
                String u = chatPage.url();
                if (u != null && u.contains("/web/geek/chat")) {
                    return chatPage;
                }
            } catch (Exception ignored) {}
        }
        Page main = manager.getBossPage();
        if (main != null && !main.isClosed()) {
            try {
                String u = main.url();
                if (u != null && u.contains("/web/geek/chat")) {
                    return main;
                }
                if (main.context() != null) {
                    for (Page p : main.context().pages()) {
                        if (p != null && !p.isClosed()) {
                            String pu = p.url();
                            if (pu != null && pu.contains("/web/geek/chat")) {
                                return p;
                            }
                        }
                    }
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    private Page page(){
        Page main=manager.getBossPage();if(main==null || main.isClosed()){lastScanNote="BOSS页面未就绪";return null;}
        if(chatPage==null || chatPage.isClosed()){
            chatPage=main.context().newPage();chatPage.setDefaultTimeout(3000);
            chatPage.navigate("https://www.zhipin.com/web/geek/chat",new Page.NavigateOptions().setWaitUntil(com.microsoft.playwright.options.WaitUntilState.DOMCONTENTLOADED));
            chatPage.waitForTimeout(1000);
        }
        return chatPage;
    }
    private Locator findRow(Page p,String id){Locator rows=p.locator(ROWS);for(int i=0;i<rows.count();i++)if(id.equals(rowId(rows.nth(i))))return rows.nth(i);return null;}
    private static String rowId(Locator row){
        for(String attribute:List.of("data-uid","data-geek-id","data-user-id","data-id")){
            String value=row.getAttribute(attribute);if(value!=null&&!value.isBlank())return attribute+":"+value;
        }return null;
    }
    private static boolean selected(Locator row){return Boolean.TRUE.equals(row.evaluate("el => el.matches('.selected,.active,.current,[aria-selected=\"true\"]')"));}
    @SuppressWarnings("unchecked")
    private Inbound read(Page p,String conversationId)throws Exception{
        return readSnapshot(p,conversationId,mapper);
    }
    static Inbound readSnapshot(Page p,String conversationId,ObjectMapper mapper)throws Exception{
        String expected=conversationId.substring(conversationId.indexOf(':')+1);
        String json=(String)p.evaluate("""
            expected => {
              const root=document.querySelector('.chat-message .im-list')||document.querySelector('.chat-record');
              if(!root)return '[]';
              // Identity MUST belong to the actual message root,
              // never to a separately rendered header that may switch ahead of the old messages.
              const bound=['data-uid','data-geek-id','data-user-id','data-conversation-id'].some(attr=>root.getAttribute(attr)===expected);
              if(!bound)return '[]';
              return JSON.stringify(Array.from(root.querySelectorAll('li.message-item')).slice(-30).map(el=>({
                id:el.getAttribute('data-id')||el.getAttribute('data-mid')||el.getAttribute('data-message-id')||el.id||'',
                sender:el.classList.contains('item-friend')?'RECRUITER':el.classList.contains('item-myself')?'SELF':'SYSTEM',
                text:(el.querySelector('.message-content')?.innerText||'').trim()
              })));
            }
            """,expected);
        List<Message> messages=mapper.readValue(json,new TypeReference<List<Message>>(){});
        if(messages.isEmpty())return null;
        Message last=messages.getLast();
        if(!last.sender().equals("RECRUITER")||last.id().isBlank()||last.text().isBlank())return null;
        return new Inbound(conversationId,last.id(),last.text(),"","",messages);
    }
}
