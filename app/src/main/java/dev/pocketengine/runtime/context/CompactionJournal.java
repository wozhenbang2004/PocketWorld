package dev.pocketengine.runtime.context;

import dev.pocketengine.core.RunControl;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Short original-TaskStore transactions only. The model/network call must stay outside all three. */
public interface CompactionJournal {
    FrozenPrefix freeze(CompactionPlan hostCut,RunControl original)throws Exception;
    PublishReceipt publish(FrozenPrefix expected,SummaryDraft actual,RunControl original)throws Exception;
    /** Recheck the published source prefix, current authority/route/forget and relevant input/approval barriers. */
    boolean current(FrozenPrefix published,RunControl original)throws Exception;
    /** Only a freshly re-admitted native source scope may discard an old pause-bound projection and continue from raw. */
    default boolean scopeCurrent(RunControl original)throws Exception{return false;}
    /** Same checkpoint owner, preserving only a currently valid derived field while raw rows append. */
    default void checkpoint(dev.pocketengine.core.RunJournal original,JSONArray messages,int steps,JSONObject observation)throws Exception{original.checkpoint(messages,steps,observation);}
    default void checkpoint(dev.pocketengine.core.RunJournal original,JSONArray messages,int steps,JSONObject observation,JSONObject progressGuard)throws Exception{original.checkpoint(messages,steps,observation,progressGuard);}
    default JSONObject restore(dev.pocketengine.core.RunJournal original)throws Exception{return original.restore();}

    final class FrozenPrefix {
        public final CompactionPlan plan;
        public final String taskId,runId;
        public final int epoch;
        public final long pauseRevision,sourceBarrier;
        private final RunControl original;
        private final String guard,sources;
        private FrozenPrefix(CompactionPlan plan,JSONObject state,JSONArray actualSources,RunControl original)throws Exception {
            this.plan=java.util.Objects.requireNonNull(plan);this.original=java.util.Objects.requireNonNull(original);
            for(String key:Set.of("taskId","runId","epoch","pauseRevision","sourceBarrier","inputsFingerprint","approvalsFingerprint","operationsFingerprint","routeFingerprint","rawFingerprint","tailFingerprint"))if(!state.has(key))throw new SecurityException("摘要冻结缺少真实屏障："+key);
            taskId=state.getString("taskId");runId=state.getString("runId");epoch=state.getInt("epoch");pauseRevision=state.getLong("pauseRevision");sourceBarrier=state.getLong("sourceBarrier");
            if(!original.matchesHostRun(taskId,runId,epoch)||pauseRevision!=original.pauseRevision()||sourceBarrier<0
                    ||!plan.rawFingerprint.equals(state.getString("rawFingerprint"))||!plan.tailFingerprint.equals(state.getString("tailFingerprint"))||!plan.routeFingerprint.equals(state.getString("routeFingerprint")))throw new SecurityException("摘要冻结不属于原执行或原上下文");
            if(actualSources.length()!=plan.coveredIndexes().size())throw new SecurityException("原件覆盖不完整");
            JSONArray raw=plan.rawMessages();for(int i=0;i<actualSources.length();i++){
                int index=plan.coveredIndexes().get(i);JSONObject source=actualSources.getJSONObject(i),range=source.getJSONObject("readRange");
                if(!("task:"+taskId+":run:"+runId+":checkpoint-message:"+index).equals(source.optString("sourceId"))
                        ||!("sha256:"+CompactionPlan.fingerprint(raw.getJSONObject(index))).equals(source.optString("revision"))||!"sha256_content_fingerprint".equals(source.optString("revisionKind"))
                        ||range.optInt("offset",-1)!=0||range.optInt("chars",-1)!=raw.getJSONObject(index).toString(2).length()||range.optInt("totalChars",-1)!=range.optInt("chars",-2)
                        ||!Boolean.TRUE.equals(range.opt("complete"))||!Boolean.FALSE.equals(range.opt("hasMore")))throw new SecurityException("摘要原件必须完整且具有真实内容指纹");
            }
            guard=state.toString();sources=actualSources.toString();check(original);
        }
        public static FrozenPrefix fromHost(CompactionPlan plan,JSONObject state,JSONArray actualSources,RunControl original)throws Exception{return new FrozenPrefix(plan,new JSONObject(state.toString()),new JSONArray(actualSources.toString()),original);}
        public JSONObject guard()throws Exception{return new JSONObject(guard);}
        public JSONArray sources()throws Exception{return new JSONArray(sources);}
        public boolean samePublicationState(JSONObject actual)throws Exception{return CompactionPlan.fingerprint(new JSONObject(guard)).equals(CompactionPlan.fingerprint(actual));}
        public void check(RunControl actual)throws Exception{if(actual!=original||!actual.matchesHostRun(taskId,runId,epoch))throw new SecurityException("摘要不属于原控制器");actual.check();if(actual.isPaused()||actual.pauseRevision()!=pauseRevision)throw new SecurityException("摘要暂停代次已变化");}
    }
    final class PublishReceipt {
        public final String status,projectionId;
        public PublishReceipt(String status,String projectionId){if(!Set.of("published","stale","unavailable").contains(status)||projectionId==null||status.equals("published")&&projectionId.isEmpty())throw new IllegalArgumentException("摘要发布回执无效");this.status=status;this.projectionId=projectionId;}
        public boolean published(){return status.equals("published");}
    }
}
