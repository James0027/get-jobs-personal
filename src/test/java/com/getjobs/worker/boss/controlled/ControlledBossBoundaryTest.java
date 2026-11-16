package com.getjobs.worker.boss.controlled;

import com.getjobs.worker.boss.BossExecutionGuard;
import com.getjobs.worker.boss.delivery.DeliveryTaskStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;
import java.nio.file.Path;
import java.util.Map;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static com.getjobs.worker.boss.controlled.ControlledTarget.*;
import static com.getjobs.worker.boss.delivery.ConfirmedTextSender.Outcome.*;

class ControlledBossBoundaryTest {
    @TempDir Path temp;
    JdbcTemplate jdbc(){var ds=new SQLiteDataSource();ds.setUrl("jdbc:sqlite:"+temp.resolve("round.db"));return new JdbcTemplate(ds);}
    ControlledBossRunStore store(){return new ControlledBossRunStore(jdbc());}
    ControlledTarget target(int i){return new ControlledTarget("job"+i,"hr"+i,"conv"+i,"hello"+i,"p1","a1","c1");}
    long version(ControlledBossRunStore s,String r){return ((Number)s.status(r).get("version")).longValue();}
    @Test void tenAttemptsIncludingFailuresConsumeBudgetAndEleventhNeverClaims(){
        var s=store();String r=s.createRound();
        for(int i=0;i<10;i++){
            String id=s.freeze(r,"k"+i,target(i));assertTrue(s.reserve(r,id,version(s,r),"k"+i).acquired());
            s.finish(r,id,new Receipt(i%2==0?CONFIRMED:FAILED,1,"visible local receipt"));assertEquals("PAUSED",s.status(r).get("state"));
        }
        String id=s.freeze(r,"k10",target(10));assertThrows(IllegalStateException.class,()->s.reserve(r,id,version(s,r),"k10"));
        assertEquals(10,((Number)s.status(r).get("jobs")).intValue());assertEquals(10,((Number)s.status(r).get("messages")).intValue());
    }
    @Test void independentStoresRaceForOneDurableClaim() throws Exception {
        var a=store();var b=store();String r=a.createRound(),id=a.freeze(r,"key",target(1));
        var gate=new CountDownLatch(1);var wins=new AtomicInteger();
        try(var executor=Executors.newFixedThreadPool(2)){
            var fa=executor.submit(()->{gate.await();if(a.reserve(r,id,0,"key").acquired())wins.incrementAndGet();return null;});
            var fb=executor.submit(()->{gate.await();if(b.reserve(r,id,0,"key").acquired())wins.incrementAndGet();return null;});
            gate.countDown();fa.get(5,TimeUnit.SECONDS);fb.get(5,TimeUnit.SECONDS);
        }
        assertEquals(1,wins.get());assertEquals(1,((Number)a.status(r).get("messages")).intValue());
    }
    @Test void independentStoresRaceForLastSlotWithDifferentTargets() throws Exception {
        var a=store();var b=store();String r=a.createRound();
        for(int i=0;i<9;i++){String id=a.freeze(r,"k"+i,target(i));a.reserve(r,id,version(a,r),"k"+i);a.finish(r,id,new Receipt(CONFIRMED,1,"receipt"));}
        String x=a.freeze(r,"x",target(10)),y=a.freeze(r,"y",target(11));long v=version(a,r);
        var gate=new CountDownLatch(1);var wins=new AtomicInteger();
        try(var executor=Executors.newFixedThreadPool(2)){
            var futures=List.of(executor.submit(()->race(a,r,x,v,"x",gate,wins)),executor.submit(()->race(b,r,y,v,"y",gate,wins)));
            gate.countDown();for(var f:futures)f.get(5,TimeUnit.SECONDS);
        }
        assertEquals(1,wins.get());assertEquals(10,((Number)a.status(r).get("messages")).intValue());
    }
    private boolean race(ControlledBossRunStore s,String r,String id,long v,String key,CountDownLatch gate,AtomicInteger wins)throws Exception{
        gate.await();try{if(s.reserve(r,id,v,key).acquired())wins.incrementAndGet();}catch(IllegalStateException expected){}return true;
    }
    @Test void interruptedSendingAndUnknownSurviveRestartAndBlockEntireHistory(){
        var s=store();String r=s.createRound(),id=s.freeze(r,"key",target(1));s.reserve(r,id,0,"key");
        var restarted=store();restarted.recoverInterrupted();assertEquals("UNKNOWN",restarted.status(r).get("state"));
        assertFalse(restarted.reserve(r,id,0,"key").acquired());
        String newer=restarted.createRound(),next=restarted.freeze(newer,"next",target(2));
        assertThrows(IllegalStateException.class,()->restarted.reserve(newer,next,0,"next"));
        assertEquals(1,((Number)restarted.status(r).get("messages")).intValue());
        assertEquals(0,((Number)restarted.status(newer).get("messages")).intValue());
    }
    @Test void unknownReceiptAndUnprovenOutboundCountStopRoundWithoutRefund(){
        var s=store();String r=s.createRound(),id=s.freeze(r,"key",target(1));s.reserve(r,id,0,"key");s.finish(r,id,new Receipt(UNKNOWN,-1,"unknown"));
        assertEquals("UNKNOWN",s.status(r).get("state"));assertThrows(IllegalStateException.class,()->s.freeze(r,"new",target(2)));
        assertEquals(1,((Number)s.status(r).get("messages")).intValue());
    }
    @Test void globalJobHrConversationAndCommandDedupArePersistent(){
        var s=store();String r=s.createRound();s.freeze(r,"key",target(1));var next=store();String r2=next.createRound();
        assertThrows(RuntimeException.class,()->next.freeze(r2,"other",target(1)));
        var sameHr=new ControlledTarget("differentJob","hr1","differentConv","hi","p1","a1","c1");
        assertThrows(RuntimeException.class,()->next.freeze(r2,"other",sameHr));
        assertThrows(RuntimeException.class,()->next.freeze(r2,"key",target(2)));
    }
    @Test void allHistoricalDeliverySnapshotsAreCheckedNotJustLatest(){
        var delivery=new DeliveryTaskStore(jdbc());
        delivery.save(Map.of("taskId","old","unknownCount",1,"pendingAction",Map.of("jobId","past")));
        delivery.save(Map.of("taskId","new","unknownCount",0,"pendingAction",Map.of()));
        var s=store();String r=s.createRound(),id=s.freeze(r,"k",target(1));
        assertThrows(IllegalStateException.class,()->s.reserve(r,id,0,"k"));assertEquals(2,delivery.count());
    }
    @Test void legacyConfirmedDeliveryWithoutHrBindingFailsClosed(){
        new DeliveryTaskStore(jdbc()).save(Map.of("taskId","old","confirmedJobIds",List.of("past")));
        var s=store();String r=s.createRound(),id=s.freeze(r,"k",target(1));assertThrows(IllegalStateException.class,()->s.reserve(r,id,0,"k"));
    }
    @Test void chatSendingUnknownAndSentHistoryRequireAudit(){
        var j=jdbc();j.execute("CREATE TABLE boss_chat_suggestion(id TEXT,state TEXT)");
        var s=store();String r=s.createRound(),id=s.freeze(r,"k",target(1));
        for(String state:List.of("sending","unknown","sent")){
            j.update("INSERT INTO boss_chat_suggestion VALUES('old',?)",state);assertThrows(IllegalStateException.class,()->s.reserve(r,id,0,"k"));j.update("DELETE FROM boss_chat_suggestion");
        }
        assertEquals(0,((Number)s.status(r).get("messages")).intValue());
    }
    @Test void mismatchedKeyVersionOrFrozenContentCannotClaim(){
        var s=store();String r=s.createRound(),id=s.freeze(r,"key",target(1));
        assertThrows(IllegalStateException.class,()->s.reserve(r,id,0,"wrong"));
        assertThrows(IllegalStateException.class,()->s.reserve(r,id,5,"key"));
        jdbc().update("UPDATE boss_controlled_target SET payload=replace(payload,'hello1','tampered') WHERE id=?",id);
        assertThrows(IllegalStateException.class,()->s.reserve(r,id,0,"key"));assertEquals(0,((Number)s.status(r).get("messages")).intValue());
    }
    @Test void sequentialReplayAfterSuccessNeverResendsAndWrongKeyIsRejected(){
        var s=store();String r=s.createRound(),id=s.freeze(r,"key",target(1));
        var executions=new AtomicInteger();
        var gateway=new ControlledBossService.Gateway(){
            public <T> T onOwner(java.util.concurrent.Callable<T> work){try{return work.call();}catch(Exception e){throw new IllegalStateException(e);}}
            public ControlledBossService.Inspection inspect(ControlledTarget frozen){return new ControlledBossService.Inspection(frozen,Action.TEXT,1);}
            public Receipt execute(ControlledTarget frozen,Permit permit,Action action){
                executions.incrementAndGet();permit.consume(frozen,action);return new Receipt(CONFIRMED,1,"local receipt");
            }
        };
        var service=new ControlledBossService(s,new BossExecutionGuard(true),gateway);
        var first=service.execute(r,id,0,"key");
        assertEquals(1,executions.get());
        @SuppressWarnings("unchecked")
        var firstTargets=(List<Map<String,Object>>)first.get("targets");
        assertEquals("CONFIRMED",firstTargets.get(0).get("state"));

        var replay=service.execute(r,id,version(s,r),"key");
        assertEquals(1,executions.get(),"replay after success must not call the gateway again");
        @SuppressWarnings("unchecked")
        var replayTargets=(List<Map<String,Object>>)replay.get("targets");
        assertEquals("CONFIRMED",replayTargets.get(0).get("state"));

        assertThrows(IllegalStateException.class,()->service.execute(r,id,version(s,r),"wrong"));
        assertEquals(1,executions.get());
        assertEquals(1,((Number)s.status(r).get("messages")).intValue());
    }
    @Test void missingRealAdapterAndGlobalReadOnlyOffCannotBeApprovedByRequest(){
        var s=store();String r=s.createRound(),id=s.freeze(r,"key",target(1));
        assertThrows(IllegalStateException.class,()->new ControlledBossService(s,new BossExecutionGuard(true),(ControlledBossService.Gateway)null).execute(r,id,0,"key"));
        assertThrows(IllegalStateException.class,()->new ControlledBossService(s,new BossExecutionGuard(false),(ControlledBossService.Gateway)null).execute(r,id,0,"key"));
    }
}
