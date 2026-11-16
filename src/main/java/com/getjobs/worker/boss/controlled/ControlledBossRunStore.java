package com.getjobs.worker.boss.controlled;

import org.json.JSONObject;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.stereotype.Service;
import java.sql.*;
import java.util.*;
import static com.getjobs.worker.boss.controlled.ControlledTarget.*;

/** SQLite serializes claims across stores/processes. Never holds a DB lock during browser work. */
@Service
public final class ControlledBossRunStore {
    private final JdbcTemplate jdbc;
    public ControlledBossRunStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    private void schema(Connection c) throws SQLException {
        try (var s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS boss_controlled_round(id TEXT PRIMARY KEY,version INTEGER NOT NULL DEFAULT 0,state TEXT NOT NULL DEFAULT 'PAUSED',jobs INTEGER NOT NULL DEFAULT 0,messages INTEGER NOT NULL DEFAULT 0)");
            s.execute("CREATE TABLE IF NOT EXISTS boss_controlled_target(id TEXT PRIMARY KEY,round_id TEXT NOT NULL,command_key TEXT UNIQUE NOT NULL,job_id TEXT UNIQUE NOT NULL,hr_id TEXT UNIQUE NOT NULL,conversation_id TEXT UNIQUE NOT NULL,payload TEXT NOT NULL,state TEXT NOT NULL DEFAULT 'FROZEN',outcome TEXT,evidence TEXT,observed INTEGER NOT NULL DEFAULT 0)");
        }
    }
    private interface Work<T> { T run(Connection c) throws SQLException; }
    private <T> T tx(Work<T> work) {
        return jdbc.execute((ConnectionCallback<T>) c -> {
            try (var s = c.createStatement()) { s.execute("PRAGMA busy_timeout=5000"); s.execute("BEGIN IMMEDIATE"); }
            try { schema(c); T value = work.run(c); try(var s=c.createStatement()){s.execute("COMMIT");} return value; }
            catch (SQLException | RuntimeException e) { try(var s=c.createStatement()){s.execute("ROLLBACK");} throw e; }
        });
    }
    private static int update(Connection c, String sql, Object... args) throws SQLException {
        try(var s=c.prepareStatement(sql)){for(int i=0;i<args.length;i++)s.setObject(i+1,args[i]); return s.executeUpdate();}
    }
    private static List<Map<String,Object>> rows(Connection c,String sql,Object... args) throws SQLException {
        try(var s=c.prepareStatement(sql)){for(int i=0;i<args.length;i++)s.setObject(i+1,args[i]);try(var r=s.executeQuery()){
            List<Map<String,Object>> out=new ArrayList<>();while(r.next()){Map<String,Object> row=new LinkedHashMap<>();for(int i=1;i<=r.getMetaData().getColumnCount();i++)row.put(r.getMetaData().getColumnName(i),r.getObject(i));out.add(row);}return out;
        }}
    }
    private static Map<String,Object> one(Connection c,String sql,Object... args) throws SQLException {
        var result=rows(c,sql,args);if(result.size()!=1)throw new IllegalStateException("UNKNOWN_ROUND_OR_TARGET");return result.getFirst();
    }
    public String createRound() { return tx(c->{String id=UUID.randomUUID().toString();update(c,"INSERT INTO boss_controlled_round(id) VALUES(?)",id);return id;}); }
    /** Trusted server-side preview only. Intentionally not an HTTP endpoint accepting approved=true. */
    public String freeze(String round,String commandKey,ControlledTarget target) {
        return tx(c->{
            var r=one(c,"SELECT * FROM boss_controlled_round WHERE id=?",round);
            if(!"PAUSED".equals(r.get("state")))throw new IllegalStateException("ROUND_NOT_PAUSED");
            String id=UUID.randomUUID().toString();
            update(c,"INSERT INTO boss_controlled_target(id,round_id,command_key,job_id,hr_id,conversation_id,payload) VALUES(?,?,?,?,?,?,?)",id,round,commandKey,target.jobId(),target.hrId(),target.conversationId(),new JSONObject(Map.of("jobId",target.jobId(),"hrId",target.hrId(),"conversationId",target.conversationId(),"text",target.text(),"profileVersion",target.profileVersion(),"assetVersion",target.assetVersion(),"configVersion",target.configVersion(),"contentHash",target.contentHash())).toString());
            return id;
        });
    }
    public ControlledTarget target(String round,String id) {
        return tx(c->decode((String)one(c,"SELECT payload FROM boss_controlled_target WHERE id=? AND round_id=?",id,round).get("payload")));
    }
    private static ControlledTarget decode(String payload) {
        var p=new JSONObject(payload);var target=new ControlledTarget(p.getString("jobId"),p.getString("hrId"),p.getString("conversationId"),p.getString("text"),p.getString("profileVersion"),p.getString("assetVersion"),p.getString("configVersion"));if(!target.contentHash().equals(p.getString("contentHash")))throw new IllegalStateException("FROZEN_CONTENT_CHANGED");return target;
    }
    public record Claim(ControlledTarget target, boolean acquired) {}
    public Claim reserve(String round,String id,long version,String key) {
        return tx(c->{
            var t=one(c,"SELECT * FROM boss_controlled_target WHERE id=? AND round_id=?",id,round);
            if(!Objects.equals(key,t.get("command_key")))throw new IllegalStateException("IDEMPOTENCY_KEY_MISMATCH");
            var target=decode((String)t.get("payload"));
            if(!"FROZEN".equals(t.get("state")))return new Claim(target,false);
            checkHistory(c);
            int n=update(c,"UPDATE boss_controlled_round SET state='SENDING',jobs=jobs+1,messages=messages+1,version=version+1 WHERE id=? AND version=? AND state='PAUSED' AND jobs<10 AND messages<10",round,version);
            if(n!=1)throw new IllegalStateException("ROUND_PAUSED_VERSION_OR_BUDGET_REQUIRED");
            update(c,"UPDATE boss_controlled_target SET state='SENDING' WHERE id=?",id);
            return new Claim(target,true);
        });
    }
    /** Any historic ambiguous action blocks all new claims. Legacy identities cannot safely establish HR dedup. */
    private static void checkHistory(Connection c) throws SQLException {
        if(!rows(c,"SELECT id FROM boss_controlled_target WHERE state IN ('UNKNOWN','SENDING') LIMIT 1").isEmpty())throw new IllegalStateException("HISTORICAL_UNKNOWN_OR_INFLIGHT");
        var tables=rows(c,"SELECT name FROM sqlite_master WHERE type='table'");
        for(var table:tables){
            if("boss_delivery_task".equals(table.get("name")))for(var row:rows(c,"SELECT snapshot FROM boss_delivery_task")){
                var p=new JSONObject((String)row.get("snapshot"));
                if(!p.optJSONObject("pendingAction",new JSONObject()).isEmpty() || p.optInt("unknownCount")>0)throw new IllegalStateException("HISTORICAL_DELIVERY_UNKNOWN");
                if(!p.optJSONArray("attemptedJobIds",new org.json.JSONArray()).isEmpty() || !p.optJSONArray("confirmedJobIds",new org.json.JSONArray()).isEmpty())throw new IllegalStateException("LEGACY_HR_IDENTITY_REQUIRES_AUDIT");
            }
            if("boss_chat_suggestion".equals(table.get("name")) && !rows(c,"SELECT id FROM boss_chat_suggestion WHERE state IN ('sending','unknown','sent') LIMIT 1").isEmpty())throw new IllegalStateException("HISTORICAL_CHAT_REQUIRES_AUDIT");
        }
    }
    public void finish(String round,String id,Receipt receipt) {
        tx(c->{
            String state=receipt.outcome()==com.getjobs.worker.boss.delivery.ConfirmedTextSender.Outcome.UNKNOWN || receipt.observedOutbound()!=1?"UNKNOWN":receipt.outcome().name();
            if(update(c,"UPDATE boss_controlled_target SET state=?,outcome=?,observed=?,evidence=? WHERE id=? AND round_id=? AND state='SENDING'",state,receipt.outcome().name(),receipt.observedOutbound(),receipt.evidence(),id,round)!=1)throw new IllegalStateException("CLAIM_NOT_SENDING");
            update(c,"UPDATE boss_controlled_round SET state=?,version=version+1 WHERE id=?","UNKNOWN".equals(state)?"UNKNOWN":"PAUSED",round);return null;
        });
    }
    /** Explicit startup recovery only; never invoked by a competing store constructor. No resend/reconcile path. */
    public void recoverInterrupted() { tx(c->{update(c,"UPDATE boss_controlled_round SET state='UNKNOWN',version=version+1 WHERE state='SENDING'");update(c,"UPDATE boss_controlled_target SET state='UNKNOWN' WHERE state='SENDING'");return null;}); }
    public Map<String,Object> status(String round) { return tx(c->{var r=one(c,"SELECT * FROM boss_controlled_round WHERE id=?",round);r.put("targets",rows(c,"SELECT id,state,outcome,observed,evidence FROM boss_controlled_target WHERE round_id=?",round));r.put("pdfStatus","PDF_SEND_NOT_IMPLEMENTED");return r;}); }
}


