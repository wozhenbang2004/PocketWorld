package dev.pocketengine.features.operations;

import dev.pocketengine.core.RunControl;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONObject;

/** One owner-triggered bounded pass. It owns no business task, retry queue or AgentRunner. */
public final class CompatibilityCheckRunner {
    public interface Probe extends AutoCloseable { JSONObject run(RunControl original)throws Exception; default void close()throws Exception{} }
    public static final class Check {
        final String id;final long millis;final Probe probe;
        public Check(String id,long millis,Probe probe){if(!id.matches("[a-z_]{1,32}")||millis<1||millis>15000)throw new IllegalArgumentException();this.id=id;this.millis=millis;this.probe=java.util.Objects.requireNonNull(probe);}
    }
    private CompatibilityCheckRunner() {}
    public static JSONObject run(List<Check> checks,RunControl original,Consumer<JSONObject> progress)throws Exception{
        if(checks.size()>12)throw new IllegalArgumentException();long start=System.nanoTime(),end=start+45_000_000_000L;JSONArray rows=new JSONArray();boolean released=true;
        ExecutorService worker=Executors.newSingleThreadExecutor(r->{Thread thread=new Thread(r,"owner-compatibility-probe");thread.setDaemon(true);return thread;});
        try{
            for(Check check:checks){
                JSONObject row=new JSONObject().put("id",check.id);rows.put(row);Future<JSONObject> future=null;
                long left=TimeUnit.NANOSECONDS.toMillis(end-System.nanoTime());
                try{
                    if(original.isCancelled()||original.isPaused()){row.put("status","skipped").put("reason","cancelled");continue;}
                    if(left<=0){row.put("status","skipped").put("reason","total_timeout");continue;}
                    row.put("status","running");progress.accept(snapshot(rows,start,false,released));
                    future=worker.submit(()->check.probe.run(original));JSONObject actual=future.get(Math.min(left,check.millis),TimeUnit.MILLISECONDS);
                    String status=actual.optString("status");if(!java.util.Set.of("passed","partial","failed","skipped").contains(status))throw new IllegalStateException("invalid_probe_outcome");
                    row.put("status",status).put("evidence",actual);
                    if(actual.has("resourcesReleased")&&!actual.optBoolean("resourcesReleased"))released=false;
                }catch(TimeoutException timedOut){row.put("status","failed").put("reason","step_timeout");if(future!=null)future.cancel(true);}
                catch(Exception failed){row.put("status",original.isCancelled()?"skipped":"failed").put("reason",original.isCancelled()?"cancelled":"probe_error");}
                finally{try{check.probe.close();}catch(Exception cleanup){released=false;row.put("cleanup","unconfirmed");}progress.accept(snapshot(rows,start,false,released));}
            }
        }finally{worker.shutdownNow();if(!worker.awaitTermination(2000,TimeUnit.MILLISECONDS))released=false;}
        JSONObject result=snapshot(rows,start,true,released);progress.accept(result);return result;
    }
    private static JSONObject snapshot(JSONArray rows,long start,boolean ended,boolean released){
        try{boolean partial=false,failed=false;for(int i=0;i<rows.length();i++){String status=rows.getJSONObject(i).optString("status");partial|=status.equals("partial")||status.equals("skipped");failed|=status.equals("failed");}
            return new JSONObject().put("format","pocketworld.compatibility-check.v1").put("checks",new JSONArray(rows.toString())).put("ended",ended).put("status",!ended?"running":failed?"failed":partial?"partial":"passed").put("elapsedMillis",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)).put("resourcesReleased",released&&ended).put("businessAppsOperated",false).put("modelCalled",false);
        }catch(Exception impossible){throw new IllegalStateException(impossible);}
    }
}
