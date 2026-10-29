package com.getjobs.worker.service;

import com.getjobs.application.service.ConfigService;
import com.getjobs.worker.boss.Boss;
import com.getjobs.worker.boss.BossConfig;
import com.getjobs.worker.boss.BossExecutionGuard;
import com.getjobs.worker.boss.delivery.DeliveryRun;
import com.getjobs.worker.boss.delivery.DeliveryTaskStore;
import com.getjobs.worker.boss.delivery.DeliveryConfiguration;
import java.util.concurrent.atomic.AtomicBoolean;
import com.getjobs.worker.dto.JobProgressMessage;
import com.getjobs.worker.manager.PlaywrightManager;
import com.microsoft.playwright.Page;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Boss直聘任务服务
 * 管理Boss平台的投递任务执行和状态
 */
@Slf4j
@Service
public class BossJobService implements JobPlatformService {
    private static final String PLATFORM = "boss";

    private final PlaywrightManager playwrightManager;
    private final ConfigService configService;
    private final ObjectProvider<Boss> bossProvider;
    private final DeliveryTaskStore deliveryTaskStore;
    private final BossExecutionGuard guard;
    private final AtomicBoolean executionLock = new AtomicBoolean();
    private volatile DeliveryRun deliveryRun;

    // 任务运行状态
    private volatile boolean isRunning = false;
    // 停止标志
    private volatile boolean shouldStop = false;
    // 本次任务的开始时间（毫秒），用于判断任务是不是卡死了
    private volatile long runningSince = 0L;

    public BossJobService(PlaywrightManager playwrightManager,
                          ConfigService configService,
                          ObjectProvider<Boss> bossProvider,
                          DeliveryTaskStore deliveryTaskStore) {
        this(playwrightManager, configService, bossProvider, deliveryTaskStore, new BossExecutionGuard(false));
    }

    @Autowired
    public BossJobService(PlaywrightManager playwrightManager,
                          ConfigService configService,
                          ObjectProvider<Boss> bossProvider,
                          DeliveryTaskStore deliveryTaskStore,
                          BossExecutionGuard guard) {
        this.playwrightManager = playwrightManager;
        this.configService = configService;
        this.bossProvider = bossProvider;
        this.deliveryTaskStore = deliveryTaskStore;
        this.guard = guard != null ? guard : new BossExecutionGuard(false);
    }

    public BossExecutionGuard getGuard() {
        return guard;
    }

    public record DeliveryReservation(String taskId, boolean resume, BossConfig config) {}

    /**
     * B2/B4: 同步原子预留执行权，校验前置，返回真实 taskId
     */
    public synchronized DeliveryReservation reserveDelivery(boolean resume) {
        guard.assertCanDeliver();

        if (!executionLock.compareAndSet(false, true)) {
            throw new IllegalStateException("当前已有投递任务正在运行中");
        }
        isRunning = true;
        shouldStop = false;
        runningSince = System.currentTimeMillis();

        try {
            var previous = deliveryTaskStore.latest();
            if (((Number) previous.getOrDefault("unknownCount", 0)).intValue() > 0) {
                throw new IllegalArgumentException("上次有发送状态待核实，请先在进度卡片核对后再" + (resume ? "继续" : "新建任务"));
            }

            BossConfig config = configService.getBossConfig();
            if (resume) {
                if (previous.isEmpty()) {
                    throw new IllegalArgumentException("当前没有可继续的旧任务，请新建任务");
                }
                String previousState = (String) previous.getOrDefault("state", "");
                if (!java.util.Set.of("interrupted", "reconciled", "cancelled", "failed").contains(previousState)) {
                    throw new IllegalArgumentException("当前旧任务状态为 " + previousState + "，不可继续；如需投递请新建任务");
                }
                int success = ((Number) previous.getOrDefault("successCount", 0)).intValue();
                int target = ((Number) previous.getOrDefault("targetCount", 50)).intValue();
                if (success >= target) {
                    throw new IllegalArgumentException("旧任务已完成全部目标（" + success + "/" + target + "），无需继续；如需投递请新建任务");
                }
                DeliveryConfiguration.requireSame(previous, config);
                deliveryRun = DeliveryRun.restore(previous, deliveryTaskStore::save);
                String taskId = (String) previous.get("taskId");
                return new DeliveryReservation(taskId, true, config);
            } else {
                deliveryRun = new DeliveryRun(
                        config.getTargetCount() == null ? 50 : config.getTargetCount(),
                        config.getCityCode(),
                        deliveryTaskStore::save,
                        DeliveryConfiguration.fingerprint(config)
                );
                String taskId = (String) deliveryRun.snapshot().get("taskId");
                return new DeliveryReservation(taskId, false, config);
            }
        } catch (RuntimeException e) {
            executionLock.set(false);
            isRunning = false;
            runningSince = 0L;
            deliveryRun = null;
            throw e;
        }
    }

    /**
     * 调度异常时释放预留，防止死锁或永久停留在 queued 状态
     */
    public void abortReservation(DeliveryReservation reservation, String reason) {
        try {
            if (deliveryRun != null) {
                deliveryRun.failedRun();
            } else if (reservation != null && !reservation.resume()) {
                deliveryTaskStore.findById(reservation.taskId()).ifPresent(map -> {
                    map.put("state", "failed");
                    map.put("errorMessage", reason);
                    deliveryTaskStore.save(map);
                });
            }
        } finally {
            executionLock.set(false);
            isRunning = false;
            shouldStop = false;
            runningSince = 0L;
            deliveryRun = null;
        }
    }

    public void startNewDelivery(Consumer<JobProgressMessage> progressCallback) {
        DeliveryReservation reservation;
        try {
            reservation = reserveDelivery(false);
        } catch (Exception e) {
            progressCallback.accept(JobProgressMessage.error(PLATFORM, e.getMessage()));
            return;
        }
        executeReservedDelivery(reservation, progressCallback);
    }

    public void resumeDelivery(Consumer<JobProgressMessage> progressCallback) {
        DeliveryReservation reservation;
        try {
            reservation = reserveDelivery(true);
        } catch (Exception e) {
            progressCallback.accept(JobProgressMessage.error(PLATFORM, e.getMessage()));
            return;
        }
        executeReservedDelivery(reservation, progressCallback);
    }

    /**
     * B1: 兼容开始接口明确仅新建，不能隐式续跑
     */
    @Override
    public void executeDelivery(Consumer<JobProgressMessage> progressCallback) {
        startNewDelivery(progressCallback);
    }

    public void executeDelivery(boolean resume, Consumer<JobProgressMessage> progressCallback) {
        if (resume) {
            resumeDelivery(progressCallback);
        } else {
            startNewDelivery(progressCallback);
        }
    }

    public void executeReservedDelivery(DeliveryReservation reservation, Consumer<JobProgressMessage> progressCallback) {
        AtomicBoolean browserFinished = new AtomicBoolean(true);
        boolean restoreInterrupt = false;
        try {
            progressCallback.accept(JobProgressMessage.info(PLATFORM, "检查浏览器状态..."));
            playwrightManager.ensureReady();

            Page page = playwrightManager.getBossPage();
            if (page == null) {
                progressCallback.accept(JobProgressMessage.error(PLATFORM, "Boss页面未初始化"));
                if (deliveryRun != null) deliveryRun.failedRun();
                return;
            }

            if (!playwrightManager.isLoggedIn(PLATFORM)) {
                progressCallback.accept(JobProgressMessage.error(PLATFORM, "请先登录Boss直聘"));
                if (deliveryRun != null) deliveryRun.failedRun();
                return;
            }

            if (shouldStop) {
                if (deliveryRun != null) deliveryRun.failedRun();
                return;
            }

            playwrightManager.pauseBossMonitoring();

            progressCallback.accept(JobProgressMessage.info(PLATFORM, "配置加载成功"));
            progressCallback.accept(JobProgressMessage.info(PLATFORM, "开始投递任务..."));

            Boss.ProgressCallback bossCallback = (message, current, total) -> {
                if (current != null && total != null) {
                    progressCallback.accept(JobProgressMessage.progress(PLATFORM, message, deliveryRun.successCount(),
                            (Integer) deliveryRun.snapshot().get("targetCount")));
                } else {
                    progressCallback.accept(JobProgressMessage.info(PLATFORM, message));
                }
            };

            Boss boss = bossProvider.getObject();
            boss.setPage(page);
            boss.setConfig(reservation.config());
            boss.setDeliveryRun(deliveryRun);
            boss.setProgressCallback(bossCallback);
            boss.setShouldStopCallback(this::shouldStop);

            browserFinished.set(false);
            int deliveredCount = playwrightManager.callOnPlaywright(() -> {
                try {
                    boss.prepare();
                    return boss.execute();
                } finally { browserFinished.set(true); }
            });

            String finalState = (String) deliveryRun.snapshot().get("state");
            if ("awaiting_reconciliation".equals(finalState)) {
                progressCallback.accept(JobProgressMessage.warning(PLATFORM, "任务已暂停：消息发送状态待核实，未继续投递。"));
            } else {
                progressCallback.accept(JobProgressMessage.success(PLATFORM,
                        String.format("任务结束，已确认首次成功%d份；状态：%s", deliveredCount, finalState)));
            }
        } catch (Exception e) {
            if (Thread.currentThread().isInterrupted() && !browserFinished.get()) {
                shouldStop = true;
                restoreInterrupt = true;
                Thread.interrupted();
                while (!browserFinished.get()) {
                    try { Thread.sleep(100); }
                    catch (InterruptedException again) { restoreInterrupt = true; }
                }
            }
            if (deliveryRun != null) deliveryRun.failedRun();
            log.error("Boss投递任务执行失败", e);
            progressCallback.accept(JobProgressMessage.error(PLATFORM, "投递失败: " + e.getMessage()));
        } finally {
            isRunning = false;
            shouldStop = false;
            runningSince = 0L;
            try {
                playwrightManager.resumeBossMonitoring();
            } catch (Exception ignored) {}
            executionLock.set(false);
            if (restoreInterrupt) Thread.currentThread().interrupt();
        }
    }

    @Override
    public void stopDelivery() {
        if (!isRunning) {
            return;
        }
        log.info("收到停止Boss投递任务的请求");
        shouldStop = true;
    }

    public Map<String, Object> reconcileDelivery(String taskId, String jobId, long version, boolean confirmed) {
        if (!executionLock.compareAndSet(false, true)) throw new IllegalStateException("请先停止投递，等待任务退出后再核对");
        try {
            var result = deliveryTaskStore.reconcile(taskId, jobId, version, confirmed);
            deliveryRun = null;
            return result;
        } finally { executionLock.set(false); }
    }

    public long runningForMillis() {
        long since = runningSince;
        return since == 0L ? 0L : System.currentTimeMillis() - since;
    }

    @Override
    public Map<String, Object> getStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("platform", PLATFORM);
        status.put("isRunning", isRunning);
        status.put("isLoggedIn", playwrightManager.isLoggedIn(PLATFORM));
        status.put("delivery", deliveryRun == null ? deliveryTaskStore.latest() : deliveryRun.snapshot());
        return status;
    }

    @Override
    public String getPlatformName() {
        return PLATFORM;
    }

    @Override
    public boolean isRunning() {
        return isRunning;
    }

    public boolean shouldStop() {
        return shouldStop;
    }
}
