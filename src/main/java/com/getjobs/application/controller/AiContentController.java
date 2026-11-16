package com.getjobs.application.controller;

import com.getjobs.application.search.SearchTemplateStore;
import com.getjobs.application.service.AiContentService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** Draft/configuration endpoints only. Saving a draft never sends anything to a recruiter. */
@RestController
@RequestMapping("/api/ai/content")
public class AiContentController {
    private final AiContentService content;

    public AiContentController(AiContentService content) {
        this.content = content;
    }

    @GetMapping
    public AiContentService.Draft load() {
        return content.loadDraft();
    }

    @PutMapping
    public AiContentService.Draft save(@RequestBody AiContentService.SaveDraftRequest request) {
        return content.saveDraft(request);
    }

    @PostMapping("/generate")
    public AiContentService.Generated generate(@RequestBody AiContentService.GenerateRequest request) {
        return content.generate(request);
    }

    @GetMapping("/options")
    public Map<String, Object> options() {
        return Map.of(
                "styles", List.of(
                        Map.of("id", "professional", "label", "专业正式"),
                        Map.of("id", "sincere", "label", "自然真诚"),
                        Map.of("id", "concise", "label", "简洁直接"),
                        Map.of("id", "friendly", "label", "轻松亲和"),
                        Map.of("id", "playful", "label", "活泼俏皮"),
                        Map.of("id", "custom", "label", "自定义")),
                "lengths", List.of(
                        Map.of("id", "short", "label", "短"),
                        Map.of("id", "medium", "label", "中"),
                        Map.of("id", "long", "label", "长")),
                "purposes", List.of(
                        Map.of("id", "job_search", "label", "求职打招呼"),
                        Map.of("id", "skills_intro", "label", "技能介绍"),
                        Map.of("id", "personal_intro", "label", "个人介绍"))
        );
    }

    @ExceptionHandler(SearchTemplateStore.Failure.class)
    public ResponseEntity<?> templateFailure(SearchTemplateStore.Failure e) {
        return ResponseEntity.status(e.status).body(Map.of(
                "success", false,
                "error", Map.of("code", e.code, "field", e.field, "message", e.getMessage())
        ));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<?> invalid(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<?> state(IllegalStateException e) {
        return ResponseEntity.status(409).body(Map.of("success", false, "message", e.getMessage()));
    }
}
