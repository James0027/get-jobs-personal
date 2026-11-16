package com.getjobs.application.controller;

import com.getjobs.application.service.LoginOnlyBrowserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/login-only")
@RequiredArgsConstructor
public class LoginOnlyController {

    private final LoginOnlyBrowserService loginOnlyBrowserService;

    @PostMapping("/{platform}/start")
    public ResponseEntity<Map<String, Object>> start(@PathVariable String platform) {
        try {
            loginOnlyBrowserService.start(platform);
            return ResponseEntity.accepted().body(loginOnlyBrowserService.status());
        } catch (LoginOnlyBrowserService.ActiveLoginException error) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "state", "conflict",
                    "activePlatform", error.activePlatform(),
                    "message", error.getMessage(),
                    "tasksStarted", false
            ));
        } catch (IllegalArgumentException error) {
            return ResponseEntity.badRequest().body(Map.of(
                    "state", "invalid_platform",
                    "message", error.getMessage(),
                    "tasksStarted", false
            ));
        }
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return loginOnlyBrowserService.status();
    }

    @PostMapping("/stop")
    public Map<String, Object> stop() {
        loginOnlyBrowserService.stop();
        return loginOnlyBrowserService.status();
    }
}
