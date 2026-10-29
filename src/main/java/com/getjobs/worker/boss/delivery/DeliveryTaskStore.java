package com.getjobs.worker.boss.delivery;

import org.json.JSONObject;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.*;

/** Durable audit snapshots. Interrupted runs are never silently resumed or re-sent. */
@Service
public class DeliveryTaskStore {
    private final JdbcTemplate jdbc;
    private boolean ready;
    public DeliveryTaskStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    private synchronized void ensureTable() {
        if (ready) return;
        jdbc.execute("CREATE TABLE IF NOT EXISTS boss_delivery_task (task_id TEXT PRIMARY KEY, snapshot TEXT NOT NULL, updated_at TEXT NOT NULL)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS boss_delivery_reconciliation (id INTEGER PRIMARY KEY AUTOINCREMENT,task_id TEXT NOT NULL,job_id TEXT NOT NULL,version INTEGER NOT NULL,confirmed INTEGER NOT NULL,created_at TEXT NOT NULL)");
        ready = true;
    }
    public synchronized void save(Map<String, Object> state) {
        ensureTable();
        jdbc.update("INSERT INTO boss_delivery_task(task_id,snapshot,updated_at) VALUES(?,?,?) ON CONFLICT(task_id) DO UPDATE SET snapshot=excluded.snapshot,updated_at=excluded.updated_at",
                state.get("taskId"), new JSONObject(state).toString(), java.time.Instant.now().toString());
    }
    public synchronized Map<String, Object> latest() {
        ensureTable();
        var rows = jdbc.queryForList("SELECT snapshot FROM boss_delivery_task ORDER BY updated_at DESC LIMIT 1", String.class);
        if (rows.isEmpty()) return Map.of();
        var result = new JSONObject(rows.getFirst()).toMap();
        if ("running".equals(result.get("state"))) result.put("state", "interrupted");
        if (result.get("pendingAction") instanceof Map<?,?> action && !action.isEmpty()) {
            result.put("state", "awaiting_reconciliation");
            result.put("unknownCount",1);
        }
        return result;
    }
    public synchronized Map<String,Object> reconcile(String taskId,String jobId,long version,boolean confirmed) {
        var current=latest();
        if (!taskId.equals(current.get("taskId")) || ((Number)current.getOrDefault("version",0)).longValue()!=version)
            throw new IllegalStateException("任务版本已变化，请刷新后核对");
        @SuppressWarnings("unchecked") Map<String,Object> action=(Map<String,Object>)current.getOrDefault("pendingAction",Map.of());
        if (((Number)current.getOrDefault("unknownCount",0)).intValue()!=1 || !jobId.equals(action.get("jobId")))
            throw new IllegalStateException("没有对应的待核实消息");
        if (confirmed) {
            if (Boolean.TRUE.equals(action.get("repeat"))) current.put("repeatCount",((Number)current.getOrDefault("repeatCount",0)).intValue()+1);
            else {
                @SuppressWarnings("unchecked") var ids=new ArrayList<>((List<String>)current.getOrDefault("confirmedJobIds",List.of()));
                if(!ids.contains(jobId)) {
                    ids.add(jobId);current.put("confirmedJobIds",ids);current.put("successCount",ids.size());
                    @SuppressWarnings("unchecked") Map<String,Object> city=(Map<String,Object>)current.get("citySuccess");
                    String name=(String)action.get("city");
                    city.put(name,((Number)city.getOrDefault(name,0)).intValue()+1);
                }
            }
        } else current.put("failedCount",((Number)current.getOrDefault("failedCount",0)).intValue()+1);
        current.put("unknownCount",0);current.put("pendingAction",Map.of());current.put("state","reconciled");current.put("version",version+1);
        jdbc.update("INSERT INTO boss_delivery_reconciliation(task_id,job_id,version,confirmed,created_at) VALUES(?,?,?,?,?)",
                taskId,jobId,version,confirmed?1:0,java.time.Instant.now().toString());
        save(current);
        return current;
    }
    public synchronized List<Map<String, Object>> history(int limit) {
        ensureTable();
        var rows = jdbc.queryForList("SELECT snapshot FROM boss_delivery_task ORDER BY updated_at DESC LIMIT ?", String.class, Math.max(1, limit));
        List<Map<String, Object>> list = new ArrayList<>();
        for (String row : rows) {
            list.add(new JSONObject(row).toMap());
        }
        return list;
    }
    public synchronized Optional<Map<String, Object>> findById(String taskId) {
        ensureTable();
        var rows = jdbc.queryForList("SELECT snapshot FROM boss_delivery_task WHERE task_id = ?", String.class, taskId);
        if (rows.isEmpty()) return Optional.empty();
        return Optional.of(new JSONObject(rows.getFirst()).toMap());
    }
    public synchronized int count() {
        ensureTable();
        Integer val = jdbc.queryForObject("SELECT COUNT(1) FROM boss_delivery_task", Integer.class);
        return val == null ? 0 : val;
    }
}
