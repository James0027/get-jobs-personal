package com.getjobs.worker.boss.delivery;

import java.util.*;
import java.util.function.Consumer;

/** One serialized delivery run. Unknown sends block all subsequent sends until reconciled. */
public final class DeliveryRun {
    private String taskId = UUID.randomUUID().toString();
    private final int targetCount;
    private final LinkedHashMap<String, Integer> cityQuotas = new LinkedHashMap<>();
    private final LinkedHashMap<String, Integer> citySuccess = new LinkedHashMap<>();
    private final Set<String> attempted = new LinkedHashSet<>();
    private final Set<String> confirmed = new LinkedHashSet<>();
    private Consumer<Map<String, Object>> persistence;
    private int failedCount, unknownCount, skippedCount, repeatCount, explanationFailedCount;
    private String currentCity = "", state = "running";
    private long version;
    private Map<String,Object> pendingAction = Map.of();
    private String configFingerprint;

    public DeliveryRun(int target, List<String> cities, Consumer<Map<String, Object>> persistence) {
        this(target,cities,persistence,"");
    }
    public DeliveryRun(int target, List<String> cities, Consumer<Map<String, Object>> persistence, String configFingerprint) {
        this.configFingerprint = configFingerprint;
        if (target < 1 || target > 10000) throw new IllegalArgumentException("目标数量必须为1至10000");
        targetCount = target;
        var unique = cities == null ? List.<String>of() : cities.stream().filter(Objects::nonNull).map(String::trim).distinct().toList();
        if (unique.isEmpty()) throw new IllegalArgumentException("请至少选择一个城市");
        for (int i = 0; i < unique.size(); i++) {
            cityQuotas.put(unique.get(i), target / unique.size() + (i < target % unique.size() ? 1 : 0));
            citySuccess.put(unique.get(i), 0);
        }
        this.persistence = persistence == null ? ignored -> {} : persistence;
        changed();
    }
    public synchronized List<String> cities() { return List.copyOf(cityQuotas.keySet()); }
    public synchronized boolean maySend() { return unknownCount == 0 && confirmed.size() < targetCount && state.equals("running"); }
    public synchronized boolean cityQuotaReached(String city) { return citySuccess.getOrDefault(city, 0) >= cityQuotas.getOrDefault(city, 0); }
    public synchronized void enterCity(String city) { currentCity = city; changed(); }
    public synchronized boolean claim(String jobId) {
        if (!maySend() || jobId == null || jobId.isBlank() || !attempted.add(jobId)) return false;
        changed();
        return true;
    }
    public synchronized void beginSend(String jobId, String text, boolean repeat) {
        if (!maySend()) throw new IllegalStateException("任务不可发送");
        pendingAction = Map.of("jobId",jobId,"text",text,"city",currentCity,"repeat",repeat);
        changed();
    }
    public synchronized void sent(String jobId, boolean repeat) {
        pendingAction = Map.of();
        if (repeat) repeatCount++;
        else if (confirmed.size() < targetCount && confirmed.add(jobId)) citySuccess.merge(currentCity, 1, Integer::sum);
        changed();
    }
    public synchronized void failed() { pendingAction = Map.of(); failedCount++; changed(); }
    public synchronized void unknown() { unknownCount++; state = "awaiting_reconciliation"; changed(); }
    public synchronized void skipped() { skippedCount++; changed(); }
    public synchronized void explanationFailed() { explanationFailedCount++; changed(); }
    public synchronized void finish(boolean cancelled) {
        if (unknownCount == 0) state = cancelled ? "cancelled" : confirmed.size() >= targetCount ? "completed" : "exhausted";
        changed();
    }
    public synchronized void failedRun() { if (unknownCount == 0) state = "failed"; changed(); }
    public synchronized int successCount() { return confirmed.size(); }
    public synchronized Map<String, Object> snapshot() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("taskId", taskId); result.put("targetCount", targetCount); result.put("successCount", confirmed.size());
        result.put("failedCount", failedCount); result.put("unknownCount", unknownCount); result.put("skippedCount", skippedCount);
        result.put("repeatCount", repeatCount); result.put("explanationFailedCount", explanationFailedCount);
        result.put("currentCity", currentCity); result.put("state", state);
        result.put("version", version); result.put("pendingAction", pendingAction);
        result.put("configFingerprint", configFingerprint);
        result.put("cityQuotas", new LinkedHashMap<>(cityQuotas)); result.put("citySuccess", new LinkedHashMap<>(citySuccess));
        result.put("attemptedJobIds", List.copyOf(attempted)); result.put("confirmedJobIds", List.copyOf(confirmed));
        return result;
    }
    public static DeliveryRun restore(Map<String,Object> saved, Consumer<Map<String,Object>> persistence) {
        @SuppressWarnings("unchecked") Map<String,Object> quotas = (Map<String,Object>) saved.get("cityQuotas");
        var run = new DeliveryRun(((Number)saved.get("targetCount")).intValue(),new ArrayList<>(quotas.keySet()),null);
        run.taskId = (String)saved.get("taskId");
        run.cityQuotas.clear();quotas.forEach((k,v)->run.cityQuotas.put(k,((Number)v).intValue()));
        @SuppressWarnings("unchecked") Map<String,Object> successes=(Map<String,Object>)saved.get("citySuccess");
        if(successes!=null)successes.forEach((k,v)->run.citySuccess.put(k,((Number)v).intValue()));
        @SuppressWarnings("unchecked") List<String> attempts=(List<String>)saved.getOrDefault("attemptedJobIds",List.of());
        @SuppressWarnings("unchecked") List<String> confirmations=(List<String>)saved.getOrDefault("confirmedJobIds",List.of());
        run.attempted.addAll(attempts);run.confirmed.addAll(confirmations);
        run.failedCount=((Number)saved.getOrDefault("failedCount",0)).intValue();
        run.skippedCount=((Number)saved.getOrDefault("skippedCount",0)).intValue();
        run.repeatCount=((Number)saved.getOrDefault("repeatCount",0)).intValue();
        run.explanationFailedCount=((Number)saved.getOrDefault("explanationFailedCount",0)).intValue();
        run.version=((Number)saved.getOrDefault("version",0)).longValue();
        run.currentCity=(String)saved.getOrDefault("currentCity","");
        run.configFingerprint=(String)saved.getOrDefault("configFingerprint","");
        // Persist only after all restored fields are installed.
        run.persistence=persistence == null ? ignored -> {} : persistence;
        run.changed();
        return run;
    }
    private void changed() { version++; persistence.accept(snapshot()); }
}
