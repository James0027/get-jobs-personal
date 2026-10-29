package com.getjobs.worker.boss.delivery;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class DeliveryTaskStoreTest {
    @TempDir Path directory;
    private DeliveryTaskStore store() {
        SQLiteDataSource source=new SQLiteDataSource();source.setUrl("jdbc:sqlite:"+directory.resolve("tasks.db"));
        return new DeliveryTaskStore(new JdbcTemplate(source));
    }
    @Test void interruptedSendingRequiresReconciliationAndResumeKeepsConsumedQuota() {
        var store=store();var run=new DeliveryRun(3,List.of("A","B"),store::save);
        run.enterCity("A");run.claim("one");run.beginSend("one","hi",false);run.sent("one",false);
        run.claim("two");run.beginSend("two","hello",false);
        var interrupted=store.latest();assertEquals("awaiting_reconciliation",interrupted.get("state"));
        long version=((Number)interrupted.get("version")).longValue();
        String id=(String)interrupted.get("taskId");
        assertThrows(IllegalStateException.class,()->store.reconcile(id,"wrong",version,true));
        var checked=store.reconcile(id,"two",version,true);
        assertThrows(IllegalStateException.class,()->store.reconcile(id,"two",version,true));
        var resumed=DeliveryRun.restore(checked,store::save);
        assertEquals(2,resumed.successCount());assertFalse(resumed.claim("two"));
        resumed.enterCity("B");assertTrue(resumed.claim("three"));resumed.sent("three",false);
        assertFalse(resumed.maySend());assertEquals(3,resumed.successCount());
    }
    @Test void verifiedNotSentDoesNotAutomaticallyRetry() {
        var store=store();var run=new DeliveryRun(1,List.of("A"),store::save);
        run.enterCity("A");run.claim("job");run.beginSend("job","hi",false);run.unknown();
        var state=store.latest();var checked=store.reconcile((String)state.get("taskId"),"job",((Number)state.get("version")).longValue(),false);
        var resumed=DeliveryRun.restore(checked,null);
        assertEquals(0,resumed.successCount());assertFalse(resumed.claim("job"));assertTrue(resumed.maySend());
    }
    @Test void multipleTasksPreserveHistoryAndDoNotOverwrite() {
        var store=store();
        var run1=new DeliveryRun(2,List.of("A"),store::save);
        run1.enterCity("A");run1.claim("j1");run1.sent("j1",false);run1.finish(false);

        var run2=new DeliveryRun(5,List.of("B"),store::save);
        run2.enterCity("B");run2.claim("j2");run2.sent("j2",false);run2.finish(false);

        assertEquals(2, store.count());
        var history=store.history(10);
        assertEquals(2, history.size());

        String id1=(String)run1.snapshot().get("taskId");
        String id2=(String)run2.snapshot().get("taskId");
        assertNotEquals(id1, id2);

        var found1=store.findById(id1);
        assertTrue(found1.isPresent());
        assertEquals(2, ((Number)found1.get().get("targetCount")).intValue());

        var found2=store.findById(id2);
        assertTrue(found2.isPresent());
        assertEquals(5, ((Number)found2.get().get("targetCount")).intValue());

        assertEquals(id2, store.latest().get("taskId"));
    }
}
