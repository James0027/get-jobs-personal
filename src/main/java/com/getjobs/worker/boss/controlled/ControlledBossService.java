package com.getjobs.worker.boss.controlled;

import com.getjobs.worker.boss.BossExecutionGuard;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.Map;
import static com.getjobs.worker.boss.controlled.ControlledTarget.*;
import static com.getjobs.worker.boss.delivery.ConfirmedTextSender.Outcome.UNKNOWN;

/** One explicit target per call. No scheduler, loop, AI, configuration writes or legacy delivery calls. */
@Service
public final class ControlledBossService {
    public record Inspection(ControlledTarget observed, Action action, int maximumOutbound) {}
    public interface Gateway {
        /** Must run the entire callback on its own browser owner, including both identity checks. */
        <T> T onOwner(java.util.concurrent.Callable<T> work);
        Inspection inspect(ControlledTarget frozen);
        Receipt execute(ControlledTarget frozen, Permit permit, Action action);
    }
    private final ControlledBossRunStore store;
    private final BossExecutionGuard guard;
    private final Gateway gateway;
    @Autowired public ControlledBossService(ControlledBossRunStore store, BossExecutionGuard guard, ObjectProvider<Gateway> gateways) {
        this(store,guard,gateways.getIfAvailable());
    }
    public ControlledBossService(ControlledBossRunStore store, BossExecutionGuard guard, Gateway gateway) {
        this.store=store;this.guard=guard;this.gateway=gateway;
    }
    public Map<String,Object> execute(String round,String targetId,long version,String key) {
        if(!guard.isReadOnlyCheckMode())throw new IllegalStateException("CONTROLLED_REQUIRES_GLOBAL_READ_ONLY");
        if(gateway==null)throw new IllegalStateException("PLATFORM_ENTRY_COUNT_AND_IDENTITY_NOT_VERIFIED");
        return gateway.onOwner(()->{
            var frozen=store.target(round,targetId);
            var inspection=gateway.inspect(frozen);
            if(inspection==null || !frozen.equals(inspection.observed()) || inspection.action()==null || inspection.maximumOutbound()!=1)
                throw new IllegalStateException("UNPROVEN_IDENTITY_VERSION_OR_OUTBOUND_BOUND");
            var claim=store.reserve(round,targetId,version,key);
            if(!claim.acquired())return store.status(round);
            var permit=new Permit(frozen,inspection.action());
            Receipt receipt;
            try { receipt=gateway.execute(frozen,permit,inspection.action());
                if(!permit.consumed() || receipt==null)receipt=new Receipt(UNKNOWN,-1,"PERMIT_NOT_CONSUMED_OR_RECEIPT_MISSING"); }
            catch(Exception error){receipt=new Receipt(UNKNOWN,-1,"ACTION_FAILED_OR_RECEIPT_UNAVAILABLE");}
            finally { permit.close(); }
            store.finish(round,targetId,receipt);
            return store.status(round);
        });
    }
    public Map<String,Object> status(String round){return store.status(round);}
}

