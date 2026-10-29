package com.getjobs.application.controller;

import com.getjobs.application.service.ConfigService;
import com.getjobs.worker.boss.BossConfig;
import com.getjobs.worker.boss.delivery.DeliveryConfiguration;
import com.getjobs.worker.boss.delivery.DeliveryTaskStore;
import com.getjobs.worker.manager.PlaywrightManager;
import com.getjobs.worker.service.BossJobService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Slf4j
@RestController
@RequestMapping("/api/boss/delivery")
@CrossOrigin(origins = "*")
public class BossDeliveryController {
    private final BossJobService jobs;
    private final DeliveryTaskStore deliveryTaskStore;
    private final ConfigService configService;
    private final PlaywrightManager playwrightManager;

    public BossDeliveryController(BossJobService jobs, DeliveryTaskStore deliveryTaskStore, ConfigService configService, PlaywrightManager playwrightManager) {
        this.jobs = jobs;
        this.deliveryTaskStore = deliveryTaskStore;
        this.configService = configService;
        this.playwrightManager = playwrightManager;
    }

    public record ReconcileRequest(String taskId, String jobId, long version, Boolean confirmed) {}

    @PostMapping("/reconcile")
    public ResponseEntity<?> reconcile(@RequestBody ReconcileRequest request) {
        if (request.taskId() == null || request.jobId() == null || request.confirmed() == null)
            return ResponseEntity.badRequest().body(Map.of("message", "请提供任务、消息、版本及人工核对结果"));
        try {
            return ResponseEntity.ok(jobs.reconcileDelivery(request.taskId(), request.jobId(), request.version(), request.confirmed()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("message", e.getMessage()));
        }
    }

    @PostMapping("/new")
    public ResponseEntity<Map<String, Object>> startNewDelivery() {
        if (playwrightManager != null && !playwrightManager.isLoggedIn("boss")) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "请先登录Boss直聘"
            ));
        }

        BossJobService.DeliveryReservation reservation;
        try {
            // 原子预留执行权，前置完成校验并分配真实 taskId
            reservation = jobs.reserveDelivery(false);
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "success", false,
                    "message", e.getMessage()
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", e.getMessage()
            ));
        }

        try {
            CompletableFuture.runAsync(() -> jobs.executeReservedDelivery(reservation, pm -> {
                log.info("[boss-new] {}", pm.getMessage());
            }));
        } catch (Exception e) {
            jobs.abortReservation(reservation, "调度执行失败: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "success", false,
                    "message", "任务调度失败，已释放预留执行权: " + e.getMessage(),
                    "taskId", reservation.taskId()
            ));
        }

        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "已启动新建投递任务",
                "taskId", reservation.taskId()
        ));
    }

    @PostMapping("/resume")
    public ResponseEntity<Map<String, Object>> resumeDelivery() {
        if (playwrightManager != null && !playwrightManager.isLoggedIn("boss")) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "请先登录Boss直聘"
            ));
        }

        BossJobService.DeliveryReservation reservation;
        try {
            // 原子预留执行权，前置完成校验（旧任务存在性、状态、指纹等）
            reservation = jobs.reserveDelivery(true);
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "success", false,
                    "message", e.getMessage()
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", e.getMessage()
            ));
        }

        try {
            CompletableFuture.runAsync(() -> jobs.executeReservedDelivery(reservation, pm -> {
                log.info("[boss-resume] {}", pm.getMessage());
            }));
        } catch (Exception e) {
            jobs.abortReservation(reservation, "调度执行失败: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "success", false,
                    "message", "任务调度失败，已释放预留执行权: " + e.getMessage(),
                    "taskId", reservation.taskId()
            ));
        }

        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "已继续旧投递任务",
                "taskId", reservation.taskId()
        ));
    }

    @PostMapping("/stop")
    public ResponseEntity<Map<String, Object>> stopDelivery() {
        jobs.stopDelivery();
        return ResponseEntity.ok(Map.of("success", true, "message", "已请求停止投递"));
    }

    @GetMapping("/tasks")
    public ResponseEntity<?> getTaskHistory(@RequestParam(defaultValue = "10") int limit) {
        return ResponseEntity.ok(deliveryTaskStore.history(limit));
    }
}
