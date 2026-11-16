package com.getjobs.application.controller;

import com.getjobs.application.search.SearchTemplateApplicationService;
import com.getjobs.application.search.SearchTemplateStore;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** Effective inheritance view and explicit template application. Applying only updates platform config. */
@RestController
@RequestMapping("/api/search-templates")
public class SearchTemplateApplicationController {
    private final SearchTemplateApplicationService service;

    public SearchTemplateApplicationController(SearchTemplateApplicationService service) {
        this.service = service;
    }

    @GetMapping("/{id}/effective")
    public Map<String, Object> effective(@PathVariable String id) {
        return service.effective(id);
    }

    @PostMapping("/{id}/apply/{platform}")
    public Map<String, Object> apply(@PathVariable String id, @PathVariable String platform) {
        return service.apply(id, platform.toUpperCase(java.util.Locale.ROOT));
    }

    @ExceptionHandler(SearchTemplateStore.Failure.class)
    public ResponseEntity<?> failure(SearchTemplateStore.Failure e) {
        return ResponseEntity.status(e.status).body(Map.of(
                "success", false,
                "error", Map.of("code", e.code, "field", e.field, "message", e.getMessage())
        ));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<?> invalid(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "error", Map.of("code", "INVALID_REQUEST", "field", "request", "message", e.getMessage())
        ));
    }
}
