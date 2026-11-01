package dev.pocketengine.runtime.context;

import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.core.RunJournal;
import dev.pocketengine.runtime.ModelGateway;
import dev.pocketengine.runtime.usage.InferenceCapture;
import dev.pocketengine.runtime.usage.InferenceReceipt;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** One existing Runner's single-layer projection, not another runner, retry loop, or source store. */
public final class ContextCompactor {
    private final RunJournal journal;
    private final CompactionJournal host;
    private final ContextBudgetPolicy.Source budgets;
    private CompactionJournal.FrozenPrefix active;
    private SummaryDraft summary;
    public ContextCompactor(RunJournal journal,CompactionJournal host,ContextBudgetPolicy.Source budgets){this.journal=journal;this.host=host;this.budgets=java.util.Objects.requireNonNull(budgets);}
    public boolean available(){return journal!=null&&host!=null;}

    public RunContextBudget.Projection request(JSONArray raw,JSONArray visible,List<Integer> sourceMap,JSONArray tools,ModelConfig selected,RunControl original,RunContextBudget budget)throws Exception {
        if(active!=null&&original.pauseRevision()!=active.pauseRevision){
            original.check();
            if(original.isPaused()||!host.scopeCurrent(original))throw new CompactionPlan.Unavailable("native_resume_scope_unavailable");
            // C4 may advance the same binding only at fresh native CapabilitySnapshot admission.
            // Old summary/cursors never advance with it; this request starts from the retained raw.
            active=null;summary=null;
        }
        if(active!=null){
            active.check(original);
            boolean current;try{current=host.current(active,original)&&active.plan.matchesPrefix(raw,visible,sourceMap);}catch(Exception changed){current=false;}
            if(!current){
                active=null;summary=null;
                // Never silently resend an invalidated summary or its stale raw reference body.
                throw new CompactionPlan.Unavailable("published_summary_source_changed");
            }
            try{return withEvidence(budget.request(active.plan.project(visible,sourceMap,summary.text),tools,selected),false);}
            catch(RunContextBudget.Blocked full){if(!"budget_exceeded".equals(full.status))throw full;}
        }
        try{return budget.request(visible,tools,selected);}
        catch(RunContextBudget.Blocked blocked){if(!available()||!"budget_exceeded".equals(blocked.status))throw blocked;}
        ContextBudgetPolicy.Resolved resolved=ContextBudgetPolicy.resolve(budgets,selected,ContextBudgetPolicy.REQUEST_BYTES);
        CompactionPlan plan=CompactionPlan.choose(raw,visible,sourceMap,tools,selected,resolved.limits);
        CompactionJournal.FrozenPrefix frozen=host.freeze(plan,original);frozen.check(original);
        // This is a physical, separately attributed SUMMARY invocation. No network under a host/DB monitor.
        InferenceReceipt[] terminal={null};
        InferenceCapture.Target nativeTarget=journal.inferenceTarget(InferenceReceipt.Purpose.SUMMARY,"");
        if(nativeTarget.context.purpose!=InferenceReceipt.Purpose.SUMMARY||!frozen.taskId.equals(nativeTarget.context.taskId)
                ||!frozen.runId.equals(nativeTarget.context.runId)||frozen.epoch!=nativeTarget.context.epoch)
            throw new CompactionPlan.Unavailable("summary_attribution_unavailable");
        InferenceCapture.Target target=InferenceCapture.observe(nativeTarget,new InferenceCapture.Sink(){
            public void started(InferenceReceipt receipt){}
            public void finished(InferenceReceipt receipt){terminal[0]=receipt;}
        });
        JSONArray input=plan.summaryMessages();budget.request(input,null,selected);original.check();
        JSONObject answer=ModelGateway.complete(selected,input,null,original,target);
        frozen.check(original);SummaryDraft actual=SummaryDraft.completed(frozen,answer,terminal[0],selected);
        // A text that did not strictly reduce the complete final request cannot be published.
        JSONArray projected=plan.project(visible,sourceMap,actual.text);
        if(projected.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>=visible.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length)throw new CompactionPlan.Unavailable("summary_made_no_progress");
        RunContextBudget.Projection checked=budget.request(projected,tools,selected);
        CompactionJournal.PublishReceipt receipt=host.publish(frozen,actual,original);
        if(!receipt.published())throw new CompactionPlan.Unavailable("summary_publish_"+receipt.status);
        active=frozen;summary=actual;return withEvidence(checked,true);
    }
    private RunContextBudget.Projection withEvidence(RunContextBudget.Projection projection,boolean generated)throws Exception {
        JSONObject diagnostics=projection.diagnostics().put("summariesGenerated",generated).put("summaryProjectionUsed",true).put("summaryLayers",1)
                .put("summaryCallId",summary.callId).put("rawRetained",true).put("coveredIndexes",new JSONArray(active.plan.coveredIndexes()))
                .put("summaryTrust","derived_untrusted_context").put("rawReader","memory_raw_query_then_memory_raw_read");
        return new RunContextBudget.Projection(projection.plan(),diagnostics);
    }
}
