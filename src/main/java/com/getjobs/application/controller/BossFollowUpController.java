package com.getjobs.application.controller;

import com.getjobs.application.chat.BossFollowUpService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/boss/chat/follow-up")
public class BossFollowUpController {
    private final BossFollowUpService service;

    public BossFollowUpController(BossFollowUpService service) {
        this.service = service;
    }

    @GetMapping("/candidates")
    public ResponseEntity<?> candidates() {
        return ResponseEntity.ok(Map.of("candidates", service.candidates()));
    }

    @PostMapping("/{conversationId}")
    public ResponseEntity<?> send(@PathVariable String conversationId) {
        return ResponseEntity.ok(service.send(conversationId));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<?> handleIllegalArgument(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("success", false, "message", e.getMessage() == null ? "请求参数错误" : e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<?> handleIllegalState(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("success", false, "message", e.getMessage() == null ? "操作冲突" : e.getMessage()));
    }
}
