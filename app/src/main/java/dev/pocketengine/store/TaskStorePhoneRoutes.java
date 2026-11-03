package dev.pocketengine.store;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.memory.TaskMemoryTrace;
import dev.pocketengine.runtime.routes.PhoneRouteSession;
import java.util.LinkedHashMap;
import java.util.concurrent.Callable;
import org.json.JSONArray;
import org.json.JSONObject;

/** Original confirmed phone-task sources and native event dependencies, not another route ledger. */
public final class TaskStorePhoneRoutes implements PhoneRouteSession.Repository {
    public interface Versions {long version(String packageName)throws Exception;}
    public interface Sources {<T>T current(JSONArray exactProofs,Callable<T> action)throws Exception;}
    private final TaskStore tasks;private final String task,goal;private final RunControl original;private final Versions versions;private final Sources sources;
    public TaskStorePhoneRoutes(TaskStore tasks,String task,RunControl original,Versions versions,Sources sources)throws Exception{
        this.tasks=java.util.Objects.requireNonNull(tasks);this.task=task;this.original=java.util.Objects.requireNonNull(original);this.versions=java.util.Objects.requireNonNull(versions);this.sources=java.util.Objects.requireNonNull(sources);
        JSONObject caller=current(original);goal=caller.getString("goal");
    }
    private JSONObject current(RunControl live)throws Exception{
        if(live!=original||!live.hasHostRun()||live.isPaused())throw new SecurityException("路线目录不属于本次原运行");live.check();JSONObject caller=tasks.get(task,false);
        if(caller==null||caller.optJSONObject("origin")!=null||!"phone".equals(caller.optString("kind"))||!"owner".equals(caller.optString("actorId"))||!"private".equals(caller.optString("spaceId"))
                ||!live.matchesHostRun(task,caller.optString("runId"),caller.optInt("coordinatorEpoch")))throw new SecurityException("路线只对本机主人私人原手机任务开放");
        return caller;
    }
    private <T>T protectedSource(JSONArray proofs,Callable<T> action)throws Exception{
        return sources.current(proofs,()->tasks.withPhoneRouteSourcesCurrent(proofs,action));
    }
    private JSONArray sourceProofs(JSONObject root)throws Exception{
        LinkedHashMap<String,JSONObject> all=new LinkedHashMap<>();all.put(TaskStore.capabilityFingerprint(root),root);
        JSONArray inherited=tasks.phoneRouteDependencies(TaskMemoryTrace.sourceTaskId(root.getJSONObject("sourceRef")));
        for(int i=0;i<inherited.length();i++){JSONObject proof=inherited.getJSONObject(i);all.put(TaskStore.capabilityFingerprint(proof),proof);}
        if(all.size()>64)throw new IllegalStateException("原路线来源超过64项，未截断");JSONArray result=new JSONArray();for(JSONObject proof:all.values())result.put(new JSONObject(proof.toString()));return result;
    }
    private void used(JSONArray proofs,RunControl live)throws Exception{
        RunControl.HostRunIdentity run=live.hostRunIdentity();tasks.recordPhoneRouteSources(task,run.runId(),run.epoch(),live,proofs);
    }
    @Override public void requireCurrentSources(RunControl live)throws Exception{
        try{JSONObject caller=current(live);if(goal!=null&&!sameGoal(goal,caller.getString("goal")))throw new SecurityException("原手机任务目标已变化");JSONArray proofs=tasks.phoneRouteDependencies(task);if(proofs.length()>0)protectedSource(proofs,()->{current(live);return null;});}
        catch(InterruptedException stopped){throw stopped;}catch(PhoneRouteSession.SourceUnavailable stopped){throw stopped;}catch(Exception unavailable){live.check();throw new PhoneRouteSession.SourceUnavailable(unavailable);}
    }
    @Override public JSONArray routes(String exactGoal,RunControl live)throws Exception{
        requireCurrentSources(live);JSONArray ids=tasks.phoneRouteTaskIds(),result=new JSONArray();
        for(int i=0;i<ids.length()&&result.length()<32;i++){
            String sourceTask=ids.getString(i);if(task.equals(sourceTask))continue;
            try{
                JSONObject metadata=tasks.phoneRouteSourceMetadata(sourceTask);if(metadata==null)continue;JSONArray proofs=sourceProofs(metadata);
                JSONObject row=protectedSource(proofs,()->{
                    current(live);JSONObject candidate=tasks.originalPhoneRoute(metadata);if(!sameGoal(exactGoal,candidate.getString("goal")))return null;
                    used(proofs,live);return new JSONObject().put("routeId",candidate.getString("routeId")).put("digest",candidate.getString("digest")).put("packageName",candidate.getString("packageName")).put("packageVersion",candidate.getLong("packageVersion")).put("steps",candidate.getJSONArray("steps").length()).put("ownerConfirmed",true).put("executionAuthorized",false);
                });if(row!=null)result.put(row);
            }catch(InterruptedException stopped){throw stopped;}catch(Exception unavailable){live.check();/* No current route is published and no old source is erased. */}
        }
        requireCurrentSources(live);return result;
    }
    @Override public JSONObject route(String id,String digest,String exactGoal,RunControl live)throws Exception{
        requireCurrentSources(live);JSONArray ids=tasks.phoneRouteTaskIds();
        for(int i=0;i<ids.length();i++){
            String sourceTask=ids.getString(i);if(task.equals(sourceTask))continue;JSONObject metadata;
            try{metadata=tasks.phoneRouteSourceMetadata(sourceTask);}catch(InterruptedException stopped){throw stopped;}catch(Exception unrelatedUnavailable){live.check();continue;}
            if(metadata==null||!digest.equals(metadata.optString("routeDigest")))continue;
            JSONArray proofs=sourceProofs(metadata);
            try{return protectedSource(proofs,()->{current(live);JSONObject candidate=tasks.originalPhoneRoute(metadata);if(!id.equals(candidate.getString("routeId"))||!sameGoal(exactGoal,candidate.getString("goal")))throw new SecurityException("准确路线不属于本次原目标");used(proofs,live);return candidate;});}
            catch(InterruptedException stopped){throw stopped;}catch(Exception unavailable){live.check();throw new PhoneRouteSession.SourceUnavailable(unavailable);}
        }
        throw new SecurityException("原成功路线已撤回、变版或不匹配此精确目标");
    }
    @Override public void withdraw(String id,String digest,RunControl live)throws Exception{
        JSONObject selected=route(id,digest,goal,live);JSONObject metadata=tasks.phoneRouteSourceMetadata(selected.getString("sourceTaskId"));if(metadata==null)throw new PhoneRouteSession.SourceUnavailable(new IllegalStateException("原路线来源当前不可用"));JSONArray proofs=sourceProofs(metadata);
        protectedSource(proofs,()->{current(live);tasks.originalPhoneRoute(metadata);tasks.event(selected.getString("sourceTaskId"),new JSONObject().put("type","phone_route_withdrawn").put("routeId",id).put("digest",digest).put("byTaskId",task));return null;});
    }
    @Override public long packageVersion(String packageName)throws Exception{return versions.version(packageName);}
    @Override public String hostUserIdentity(){return android.os.Process.myUserHandle().toString();}
    private static boolean sameGoal(String first,String second){return first.trim().replaceAll("\\s+"," ").equals(second.trim().replaceAll("\\s+"," "));}
}
