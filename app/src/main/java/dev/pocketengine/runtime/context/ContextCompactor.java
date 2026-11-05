package dev.pocketengine.runtime.context;

import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.ExecutionPlanning;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.core.RunJournal;
import dev.pocketengine.runtime.ModelGateway;
import dev.pocketengine.runtime.usage.InferenceCapture;
import dev.pocketengine.runtime.usage.InferenceReceipt;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** One existing Runner's single displayed projection, not another runner, retry loop, or source store. */
public final class ContextCompactor {
    private final RunJournal journal;
    private final CompactionJournal host;
    private final ContextBudgetPolicy.Source budgets;
    private final dev.pocketengine.runtime.AgentRunner.RequestProjection sourceProjection;
    private CompactionJournal.FrozenPrefix active;
    private SummaryDraft summary;
    private String projectionId="";
    public ContextCompactor(RunJournal journal,CompactionJournal host,ContextBudgetPolicy.Source budgets){this(journal,host,budgets,null);}
    public ContextCompactor(RunJournal journal,CompactionJournal host,ContextBudgetPolicy.Source budgets,dev.pocketengine.runtime.AgentRunner.RequestProjection sourceProjection){this.journal=journal;this.host=host;this.budgets=java.util.Objects.requireNonNull(budgets);this.sourceProjection=sourceProjection;}
    public boolean available(){return journal!=null&&host!=null;}

    public RunContextBudget.Projection request(JSONArray raw,JSONArray visible,List<Integer> sourceMap,JSONArray tools,ModelConfig selected,RunControl original,RunContextBudget budget)throws Exception {
        ExecutionPlanning planning=journal==null?null:journal.planning();
        return request(raw,visible,sourceMap,tools,selected,original,budget,planning,planning==null?null:planning.capture());
    }
    /** Runner passes the same native turn snapshot used by its MAIN request and tool admission. */
    public RunContextBudget.Projection request(JSONArray raw,JSONArray visible,List<Integer> sourceMap,JSONArray tools,ModelConfig selected,RunControl original,RunContextBudget budget,
                                              ExecutionPlanning planning,ExecutionPlanning.Snapshot turn)throws Exception {
        return request(raw,visible,sourceMap,tools,selected,original,budget,false,planning,turn);
    }
    /** One native provider context-limit recovery. Raw rows remain authoritative; never summarize a summary. */
    public RunContextBudget.Projection afterContextLimit(JSONArray raw,JSONArray visible,List<Integer> sourceMap,JSONArray tools,ModelConfig selected,RunControl original,RunContextBudget budget)throws Exception {
        ExecutionPlanning planning=journal==null?null:journal.planning();
        return afterContextLimit(raw,visible,sourceMap,tools,selected,original,budget,planning,planning==null?null:planning.capture());
    }
    public RunContextBudget.Projection afterContextLimit(JSONArray raw,JSONArray visible,List<Integer> sourceMap,JSONArray tools,ModelConfig selected,RunControl original,RunContextBudget budget,
                                                        ExecutionPlanning planning,ExecutionPlanning.Snapshot turn)throws Exception {
        if(!available()||active!=null)throw new CompactionPlan.Unavailable("context_limit_no_new_raw_prefix");
        return request(raw,visible,sourceMap,tools,selected,original,budget,true,planning,turn);
    }
    private RunContextBudget.Projection request(JSONArray raw,JSONArray visible,List<Integer> sourceMap,JSONArray tools,ModelConfig selected,RunControl original,RunContextBudget budget,boolean forced,
                                               ExecutionPlanning planning,ExecutionPlanning.Snapshot turn)throws Exception {
        boolean generated=false,ownerInputRebuilt=false;
        for(;;){
            original.check();requirePlanning(planning,turn);
            if(active!=null&&original.pauseRevision()!=active.pauseRevision){
                original.check();
                if(original.isPaused()||!host.scopeCurrent(original))throw new CompactionPlan.Unavailable("native_resume_scope_unavailable");
                // A new native epoch/scope never inherits an old summary's authority. Rebuild from raw.
                active=null;summary=null;projectionId="";
            }
            JSONArray before=visible;
            if(active!=null){
                active.check(original);
                boolean current;try{current=host.current(active,original)&&active.plan.matchesPrefix(raw,visible,sourceMap);}catch(Exception changed){current=false;}
                if(!current){
                    boolean rebuild=!ownerInputRebuilt&&active.plan.matchesPrefix(raw,visible,sourceMap)
                            &&host.canRebuildAfterOwnerInput(active,original);
                    active=null;summary=null;projectionId="";
                    if(!rebuild)throw new CompactionPlan.Unavailable("published_summary_source_changed");
                    // Never reuse the old summary. The new input boundary must freeze and
                    // validate fresh originals before any separately charged SUMMARY call.
                    ownerInputRebuilt=true;continue;
                }
                before=active.plan.project(visible,sourceMap,summary.text);
                try{return withEvidence(budget.request(before,tools,selected),generated);}
                catch(RunContextBudget.Blocked full){if(!"budget_exceeded".equals(full.status))throw full;}
            }
            if(active==null&&!forced)try{return budget.request(visible,tools,selected);}
            catch(RunContextBudget.Blocked blocked){if(!available()||!"budget_exceeded".equals(blocked.status))throw blocked;}
            ContextBudgetPolicy.Resolved resolved=ContextBudgetPolicy.resolve(budgets,selected,ContextBudgetPolicy.REQUEST_BYTES);
            CompactionPlan plan;
            try{plan=active==null?CompactionPlan.choose(raw,visible,sourceMap,tools,selected,resolved.limits):CompactionPlan.chooseIncremental(raw,visible,sourceMap,tools,selected,resolved.limits,active,summary,projectionId,original);}
            catch(CompactionPlan.Unavailable unavailable){
                if(!"no_bounded_summary_fit".equals(unavailable.code))throw unavailable;
                // Cold raw can be larger than one SUMMARY request. Each intermediate prefix is
                // published under this CURRENT native run and advances original coverage. It is
                // not permission to issue MAIN, and uses a real separately charged SUMMARY below.
                var summaryBudget=ContextBudgetPolicy.resolve(budgets,selected.forPurpose("SUMMARY"),ContextBudgetPolicy.REQUEST_BYTES);
                plan=CompactionPlan.chooseReconstructionStep(raw,visible,sourceMap,tools,selected,summaryBudget.limits,active,summary,projectionId,original);
            }
            if(active!=null&&(plan.endExclusive<=active.plan.endExclusive||!plan.coveredIndexes().containsAll(active.plan.coveredIndexes())||plan.coveredIndexes().size()<=active.plan.coveredIndexes().size()))throw new CompactionPlan.Unavailable("summary_reconstruction_did_not_advance");
            requirePlanning(planning,turn);
            CompactionJournal.FrozenPrefix frozen=host.freeze(plan,original);frozen.check(original);requirePlanning(planning,turn);
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
            if(planning!=null)target=InferenceCapture.withAdmission(target,()->requirePlanning(planning,turn));
            if(sourceProjection!=null)target=InferenceCapture.withAdmission(target,()->sourceProjection.derivedPrepared(raw,original));
            JSONArray input=plan.summaryMessages();ModelConfig summarySelection=selected.forPurpose("SUMMARY");original.check();
            JSONObject answer=ModelGateway.completeWithAdmission(summarySelection,input,null,original,target,candidate->budget.request(input,null,candidate));
            requirePlanning(planning,turn);
            ModelConfig actualSummary=ModelGateway.actualConfigForReceipt(summarySelection,terminal[0]);
            frozen.check(original);SummaryDraft actual=SummaryDraft.completed(frozen,answer,terminal[0],actualSummary);
            JSONArray projected=plan.project(visible,sourceMap,actual.text);
            if(projected.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>=before.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length)throw new CompactionPlan.Unavailable("summary_made_no_progress");
            RunContextBudget.Projection checked=null;
            try{checked=budget.request(projected,tools,selected);}
            catch(RunContextBudget.Blocked blocked){if(!"budget_exceeded".equals(blocked.status))throw blocked;}
            requirePlanning(planning,turn);
            CompactionJournal.PublishReceipt receipt;
            try{receipt=host.publish(frozen,actual,original);}
            catch(CompactionPlan.Unavailable unavailable){
                if("owner_input_not_materialized".equals(unavailable.code)&&host.canRebuildAfterOwnerInput(frozen,original)){
                    // A new native steer is not a stale-source recovery grant. Discard this
                    // candidate and let Runner claim/checkpoint the real input before replanning.
                    active=null;summary=null;projectionId="";
                    throw new CompactionPlan.Unavailable("summary_owner_input_changed");
                }
                throw unavailable;
            }
            requirePlanning(planning,turn);
            if(!receipt.published())throw new CompactionPlan.Unavailable("summary_publish_"+receipt.status);
            active=frozen;summary=actual;projectionId=receipt.projectionId;generated=true;
            if(checked!=null)return withEvidence(checked,true);
            // Finite by strict end/coverage advancement and the original per-physical-call budget.
            // A pause, source change, unknown effect or exhausted reservation stops before another HTTP.
        }
    }
    private static void requirePlanning(ExecutionPlanning planning,ExecutionPlanning.Snapshot turn)throws Exception {
        if(planning!=null)planning.requireCurrent(turn);else if(turn!=null)throw new SecurityException("摘要丢失原任务规划控制器");
    }
    private RunContextBudget.Projection withEvidence(RunContextBudget.Projection projection,boolean generated)throws Exception {
        JSONObject diagnostics=projection.diagnostics().put("summariesGenerated",generated).put("summaryProjectionUsed",true).put("summaryLayers",1).put("summaryDerivationDepth",active.plan.summaryDerivationDepth).put("summaryInputMode",active.plan.incremental()?"published_summary_plus_new_originals":"original_rows")
                .put("summaryCallId",summary.callId).put("rawRetained",true).put("coveredIndexes",new JSONArray(active.plan.coveredIndexes()))
                .put("summaryTrust","derived_untrusted_context").put("rawReader","memory_raw_query_then_memory_raw_read");
        return new RunContextBudget.Projection(projection.plan(),diagnostics);
    }
}
