package com.getjobs.application.controller;

import com.getjobs.worker.boss.controlled.ControlledBossService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

/** Execute references server-frozen records only. No approval flag, target facts, URL or profile path accepted. */
@RestController
@RequestMapping("/api/boss/controlled")
public final class ControlledBossController {
    private final ControlledBossService service;
    public ControlledBossController(ControlledBossService service){this.service=service;}
    public record Command(String targetId,long version,String idempotencyKey) {}
    @PostMapping("/{roundId}/execute") public ResponseEntity<?> execute(@PathVariable String roundId,@RequestBody Command command){
        try{return ResponseEntity.ok(service.execute(roundId,command.targetId(),command.version(),command.idempotencyKey()));}
        catch(IllegalStateException | IllegalArgumentException e){return ResponseEntity.status(409).body(Map.of("reason",e.getMessage()));}
    }
    @GetMapping("/{roundId}") public Map<String,Object> status(@PathVariable String roundId){return service.status(roundId);}
}
