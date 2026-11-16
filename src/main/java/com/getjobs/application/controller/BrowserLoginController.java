package com.getjobs.application.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Legacy all-platform entry is intentionally disabled. Login is now
 * single-platform through {@link LoginOnlyController}.
 */
@RestController
@RequestMapping("/api/browser")
public class BrowserLoginController {

    @PostMapping("/login/start")
    public ResponseEntity<Map<String, Object>> start() {
        return ResponseEntity.status(HttpStatus.GONE).body(Map.of(
                "state", "deprecated",
                "message", "请使用 /api/login-only/{platform}/start 单平台入口",
                "tasksStarted", false
        ));
    }

    @GetMapping("/login/status")
    public ResponseEntity<Map<String, Object>> status() {
        return ResponseEntity.status(HttpStatus.GONE).body(Map.of(
                "state", "deprecated",
                "message", "请使用 /api/login-only/status",
                "tasksStarted", false
        ));
    }
}
