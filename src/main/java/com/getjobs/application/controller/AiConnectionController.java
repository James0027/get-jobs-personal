package com.getjobs.application.controller;

import com.getjobs.application.service.AiConnectionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** Read/write protocol view. Secrets remain server-side; MCP is never presented as a model API. */
@RestController
@RequestMapping("/api/ai")
public class AiConnectionController {
    private final AiConnectionService connections;

    public AiConnectionController(AiConnectionService connections) {
        this.connections = connections;
    }

    @GetMapping("/connections")
    public List<AiConnectionService.Connection> connections() {
        return List.of(connections.getActive());
    }

    @GetMapping("/provider-presets")
    public List<AiConnectionService.Preset> presets() {
        return connections.getPresets();
    }

    @PutMapping("/connection")
    public AiConnectionService.Connection save(@RequestBody AiConnectionService.SaveRequest request) {
        return connections.save(request);
    }

    @PostMapping("/connection/test")
    public AiConnectionService.TestResult test() {
        return connections.test();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> invalid(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
    }
}
