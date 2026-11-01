package dev.pocketengine.features.skills;

import dev.pocketengine.core.RunControl;
import java.util.Objects;
import java.util.Set;

/** Concrete canonical HostPort bridge. Store delegates only to the existing TaskStore authority.
 * This class owns no operation map, task database, launch ticket repository or scheduler. */
public final class CompanionSkillHostPort implements SkillExecution.HostPort {
    public interface Store {
        /** Validate consumed exact approval, backend grant, native scope/run/epoch, lease and expiry. */
        void checkCurrent(SkillExecution.Plan plan,SkillExecution.Grant grant,RunControl control)throws Exception;
        /** Existing TaskStore CAS consumes THIS invocation's fresh launch ticket and persists ref before I/O.
         * False for recovered/existing/unknown/cancelled operations. Release all store locks before return. */
        boolean claimFresh(SkillExecution.Plan plan,SkillExecution.Grant grant,CompanionSkillBackend.ReceiptRef ref,RunControl control)throws Exception;
        void record(CompanionSkillBackend.ReceiptRef ref,SkillExecution.Receipt receipt)throws Exception;
        /** Authorize a current native receipt reader without requiring an executable grant or live package. */
        void checkReceiptReader(CompanionSkillBackend.ReceiptRef ref)throws Exception;
        /** Local durable exact-original cancelPending mark, idempotent; no network. */
        void cancelRequested(CompanionSkillBackend.ReceiptRef ref)throws Exception;
    }
    private final Store store;
    private final CompanionSkillBackend backend;
    public CompanionSkillHostPort(Store store,CompanionSkillBackend backend){this.store=Objects.requireNonNull(store);this.backend=Objects.requireNonNull(backend);backend.bindDispatchCheck(store::checkCurrent);}
    @Override public void checkCurrent(SkillExecution.Plan plan,SkillExecution.Grant grant,RunControl control)throws Exception {
        CompanionSkillBackend.ReceiptRef.from(plan,grant);control.check();
        if(!control.hasHostRun()||!control.matchesHostRun(grant.taskId,grant.runId,grant.epoch)||control.isPaused())throw new SecurityException("原运行控制无效或暂停");
        store.checkCurrent(plan,grant,control);control.check();
    }
    @Override public SkillExecution.Receipt dispatchOnce(SkillExecution.Plan plan,SkillExecution.Grant grant,RunControl control,SkillExecution.Submission submission)throws Exception {
        CompanionSkillBackend.ReceiptRef ref=CompanionSkillBackend.ReceiptRef.from(plan,grant);checkCurrent(plan,grant,control);
        Runnable cancel=()->{try{store.cancelRequested(ref);backend.requestCancel(ref);}catch(Exception pending){/* Durable pending/unknown remains; no resend. */}};
        control.onCancel(cancel);boolean retain=true;
        try {
            // The store call finishes its short transaction before the canonical callback enters SkillBook.
            if(!store.claimFresh(plan,grant,ref,control)){SkillExecution.Receipt old=backend.query(ref);store.record(ref,old);retain=!terminal(old);return old;}
            SkillExecution.Receipt receipt;
            try{checkCurrent(plan,grant,control);receipt=submission.submit();}
            catch(Exception uncertain){receipt=SkillExecution.Receipt.unknown(grant.operationId);}
            if(receipt==null||!grant.operationId.equals(receipt.operationId))receipt=SkillExecution.Receipt.unknown(grant.operationId);
            store.record(ref,receipt);retain=!terminal(receipt);return receipt;
        } finally {if(!retain)control.removeCancelAction(cancel);}
    }
    @Override public SkillExecution.Receipt reconcile(SkillExecution.Plan plan,SkillExecution.Grant grant)throws Exception {
        return reconcile(CompanionSkillBackend.ReceiptRef.from(plan,grant));
    }
    /** Native restart path: this receipt-only DTO cannot submit or recreate a Plan. */
    public SkillExecution.Receipt reconcile(CompanionSkillBackend.ReceiptRef ref)throws Exception {
        store.checkReceiptReader(ref);SkillExecution.Receipt receipt=backend.query(ref);store.record(ref,receipt);return receipt;
    }
    public void cancelOriginal(CompanionSkillBackend.ReceiptRef ref)throws Exception {store.checkReceiptReader(ref);store.cancelRequested(ref);backend.requestCancel(ref);}
    private static boolean terminal(SkillExecution.Receipt receipt){return Set.of(SkillExecution.Status.COMPLETED,SkillExecution.Status.FAILED,SkillExecution.Status.CANCELLED,SkillExecution.Status.TIMED_OUT,SkillExecution.Status.REJECTED).contains(receipt.status);}
}
