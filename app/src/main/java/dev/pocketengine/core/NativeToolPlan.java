package dev.pocketengine.core;

import java.util.Objects;
import java.util.Set;
import org.json.JSONObject;

/** A short, one-use native plan. It supplies no approval, grant, execution loop or restored authority. */
public final class NativeToolPlan implements AutoCloseable {
    public enum Tool {
        CLICK_NODE("phone_click_node"),TYPE("phone_type"),SCROLL_NODE("phone_scroll_node"),CONDITION_WAIT("phone_condition_wait");
        public final String name;Tool(String name){this.name=name;}
    }
    public record Identity(String taskId,String runId,int epoch,int displayId,String sessionId,String hostUserIdentity,String observationId) {
        public Identity {for(String value:new String[]{taskId,runId,sessionId,hostUserIdentity,observationId})if(value==null||value.isEmpty()||value.length()>200)throw new IllegalArgumentException("native_sequence_identity_invalid");if(epoch<1||displayId<0)throw new IllegalArgumentException("native_sequence_identity_invalid");}
    }
    /** Resolve one step from a fresh native tree. Never execute it here. */
    @FunctionalInterface public interface Resolver {Resolved resolve(int stepIndex,RunControl original)throws Exception;}
    /** Native result only: fixed tool and actual current observation/node arguments, not model authority. */
    public static final class Resolved {
        private final Tool tool;private final String json,observation;private final RunControl.AuthorityCheck current;
        public Resolved(Tool tool,JSONObject actualArguments,RunControl.AuthorityCheck current)throws Exception{this(tool,actualArguments,null,current);}
        public Resolved(Tool tool,JSONObject actualArguments,JSONObject actualFreshObservation,RunControl.AuthorityCheck current)throws Exception{
            this.tool=Objects.requireNonNull(tool);this.current=Objects.requireNonNull(current);Objects.requireNonNull(actualArguments);
            Set<String> keys=switch(tool){case CLICK_NODE->Set.of("observationId","nodeId");case TYPE->Set.of("observationId","nodeId","text");case SCROLL_NODE->Set.of("observationId","nodeId","direction");case CONDITION_WAIT->Set.of("observationId","kind","selector","timeoutMs","stableForMs");};
            var names=actualArguments.keys();while(names.hasNext())if(!keys.contains(names.next()))throw new IllegalArgumentException("native_sequence_argument_not_allowed");
            if(actualArguments.toString().length()>16000||actualArguments.optString("observationId").isEmpty())throw new IllegalArgumentException("native_sequence_arguments_invalid");
            if(actualFreshObservation==null){if(tool==Tool.CLICK_NODE||tool==Tool.TYPE)throw new IllegalArgumentException("native_sequence_fresh_tree_required");observation=null;}
            else{if(!actualArguments.getString("observationId").equals(actualFreshObservation.optString("observationId"))||actualFreshObservation.has("imageBase64")||actualFreshObservation.has("image_url")||actualFreshObservation.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>131072)throw new IllegalArgumentException("native_sequence_fresh_tree_invalid");observation=actualFreshObservation.toString();}
            json=actualArguments.toString();
        }
    }
    public final class Step {
        public final int index,total;public final Tool tool;private final Resolved resolved;
        private Step(int index,Resolved resolved){this.index=index;total=count;tool=resolved.tool;this.resolved=resolved;}
        public JSONObject arguments()throws Exception{return new JSONObject(resolved.json);}
        public JSONObject observation()throws Exception{return resolved.observation==null?null:new JSONObject(resolved.observation);}
        public void requireCurrent(RunControl actual)throws Exception{require(actual);resolved.current.check();require(actual);}
    }
    private final String parentTool,parentOperation;private final RunControl original;private final Identity identity;private final long pause;
    private final int count;private final Resolver resolver;private final RunControl.AuthorityCheck current;
    private boolean taken,closed,resolving;private int next;
    private NativeToolPlan(String parentTool,String parentOperation,RunControl original,Identity identity,int count,Resolver resolver,RunControl.AuthorityCheck current)throws Exception{
        if(!"phone_prepare_sequence".equals(parentTool)||parentOperation==null||parentOperation.isEmpty()||parentOperation.length()>200||count<1||count>4)throw new IllegalArgumentException("native_sequence_invalid");
        this.parentTool=parentTool;this.parentOperation=parentOperation;this.original=Objects.requireNonNull(original);this.identity=Objects.requireNonNull(identity);this.count=count;this.resolver=Objects.requireNonNull(resolver);this.current=Objects.requireNonNull(current);pause=original.pauseRevision();require(original);
    }
    public static NativeToolPlan capture(String parentTool,String parentOperationId,RunControl original,Identity identity,int count,Resolver resolver,RunControl.AuthorityCheck current)throws Exception{return new NativeToolPlan(parentTool,parentOperationId,original,identity,count,resolver,current);}
    private void require(RunControl actual)throws Exception{
        if(actual!=original||!actual.matchesHostRun(identity.taskId(),identity.runId(),identity.epoch())||actual.isPaused()||actual.pauseRevision()!=pause)throw new SecurityException("native_sequence_original_run_changed");
        actual.check();current.check();actual.check();if(actual.isPaused()||actual.pauseRevision()!=pause)throw new SecurityException("native_sequence_pause_changed");synchronized(this){if(closed)throw new SecurityException("native_sequence_closed");}
    }
    public void consume(String parentTool,String parentOperationId,RunControl actual)throws Exception{
        require(actual);synchronized(this){if(closed||taken||!this.parentTool.equals(parentTool)||!parentOperation.equals(parentOperationId))throw new SecurityException("native_sequence_not_current");taken=true;}require(actual);
    }
    /** The Runner calls once per step, only after all preceding assistant/tool pairs settled. */
    public Step next(RunControl actual)throws Exception{
        require(actual);final int index;synchronized(this){if(!taken||closed||resolving)throw new SecurityException("native_sequence_not_available");if(next>=count)return null;index=next++;resolving=true;}
        try{Resolved resolved=Objects.requireNonNull(resolver.resolve(index,actual));require(actual);Step step=new Step(index,resolved);step.requireCurrent(actual);return step;}
        catch(Exception unavailable){close();throw unavailable;}
        finally{synchronized(this){resolving=false;}}
    }
    public int size(){return count;}
    @Override public synchronized void close(){closed=true;}
}
