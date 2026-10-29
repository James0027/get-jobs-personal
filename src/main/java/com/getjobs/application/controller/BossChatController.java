package com.getjobs.application.controller;

import com.getjobs.application.chat.*;
import com.getjobs.application.chat.ChatContracts.*;
import com.getjobs.application.service.BossResumeDocumentService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/boss/chat")
public class BossChatController {
    private static final Set<String> ALLOWED_RECORD_STATES = Set.of("blocked", "unknown", "failed");

    private final BossChatStore store;
    private final BossAiWorkflowService workflow;
    private final BossChatMonitor monitor;
    private final BossChatBrowserGateway browser;
    private final BossResumeDocumentService documents;

    public BossChatController(BossChatStore store, BossAiWorkflowService workflow, BossChatMonitor monitor, BossChatBrowserGateway browser, BossResumeDocumentService documents) {
        this.store = store;
        this.workflow = workflow;
        this.monitor = monitor;
        this.browser = browser;
        this.documents = documents;
    }

    public record ApprovalRequest(String expectedProfileVersion, String expectedTextHash) {}
    public record ReconcileRequest(boolean confirmed) {}

    @GetMapping("/profile")
    public Profile profile() {
        return store.profile();
    }

    @PutMapping("/profile")
    public Profile profile(@RequestBody Profile profile) {
        String currentPdf = documents.currentAssetId();
        String approvedPdf = profile.approvedPdfAssetId() == null ? "" : profile.approvedPdfAssetId().trim();
        if (!approvedPdf.isEmpty() && !approvedPdf.equals(currentPdf)) {
            throw new IllegalArgumentException("请先上传并批准当前 PDF 简历");
        }
        if (profile.autoResume() && (approvedPdf.isEmpty() || !approvedPdf.equals(currentPdf))) {
            throw new IllegalArgumentException("请先上传并批准当前 PDF 简历");
        }
        return store.saveProfile(profile);
    }

    @PostMapping("/profile/resume-text/approve")
    public Profile approveResumeText(@RequestBody ApprovalRequest request) {
        if (request == null || request.expectedProfileVersion() == null || request.expectedTextHash() == null) {
            throw new IllegalArgumentException("缺少批准参数");
        }
        return store.approveResumeText(request.expectedProfileVersion(), request.expectedTextHash());
    }

    @GetMapping("/records")
    public Map<String, Object> records(
            @RequestParam(defaultValue = "blocked,unknown,failed") String states,
            @RequestParam(defaultValue = "50") int limit) {
        List<String> stateList = Arrays.stream(states.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        if (stateList.isEmpty()) {
            throw new IllegalArgumentException("状态过滤不能为空");
        }
        for (String st : stateList) {
            if (!ALLOWED_RECORD_STATES.contains(st)) {
                throw new IllegalArgumentException("非法的状态过滤: " + st);
            }
        }
        List<Suggestion> suggestions = store.records(stateList, limit);
        List<Map<String, Object>> records = new ArrayList<>();
        for (Suggestion s : suggestions) {
            String src = s.sourceText();
            if (src != null && src.length() > 200) {
                src = src.substring(0, 200);
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", s.id());
            item.put("conversationId", s.conversationId());
            item.put("lastInboundMessageId", s.lastInboundMessageId());
            item.put("state", s.state());
            item.put("reasonCode", s.reasonCode() == null ? "" : s.reasonCode());
            item.put("missingFacts", s.missingFacts() == null ? List.of() : s.missingFacts());
            item.put("sourceText", src);
            item.put("replyText", s.replyText() == null ? "" : s.replyText());
            item.put("profileVersion", s.profileVersion() == null ? "" : s.profileVersion());
            item.put("createdAt", s.createdAt());
            records.add(item);
        }
        return Map.of("records", records);
    }

    @PostMapping("/records/{id}/reconcile")
    public Map<String, String> reconcile(@PathVariable String id, @RequestBody ReconcileRequest request) {
        String state = store.reconcileUnknown(id, request.confirmed());
        return Map.of("id", id, "state", state);
    }

    @GetMapping("/resume-asset")
    public Map<String, String> asset() {
        return Map.of("assetId", browser.currentResumeAssetId());
    }

    /** 仅历史记录，只读 */
    @GetMapping("/suggestions")
    public List<Suggestion> suggestions() {
        return store.suggestions();
    }

    @PostMapping("/suggestions/{id}/confirm")
    public ResponseEntity<Map<String, Object>> confirm(@PathVariable String id, @RequestBody(required = false) Confirmation confirmation) {
        return ResponseEntity.status(410).body(Map.of(
                "success", false,
                "code", "LEGACY_SUGGESTIONS_RETIRED",
                "message", "旧建议队列已退役，请使用 AI 工作台的自动回复与执行记录"
        ));
    }

    @PostMapping("/suggestions/{id}/dismiss")
    public Map<String, Boolean> dismiss(@PathVariable String id) {
        workflow.dismiss(id);
        return Map.of("success", true);
    }

    @PostMapping("/scan")
    public Map<String, Object> scan() {
        return monitor.scan();
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return monitor.status();
    }

    /** 只读诊断入口：检查真实页面DOM结构与阶段，不发送任何内容，不返回敏感数据 */
    @GetMapping("/diagnostics")
    public Map<String, Object> diagnostics() {
        return browser.diagnose();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badInput(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> conflict(IllegalStateException e) {
        return ResponseEntity.status(409).body(Map.of("success", false, "message", e.getMessage()));
    }
}
