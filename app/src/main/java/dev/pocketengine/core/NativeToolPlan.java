package dev.pocketengine.core;

import java.util.Objects;
import java.util.Set;
import org.json.JSONObject;

/** A short, one-use native plan. It supplies no approval, grant, execution loop or restored authority. */
public final class NativeToolPlan implements AutoCloseable {
    public enum Tool {
        CLICK_NODE("phone_click_node"),TYPE("phone_type"),SCROLL_NODE("phone_scroll_node"),WHEEL_STEP("phone_set_wheel"),CONDITION_WAIT("phone_condition_wait"),
        BROWSER_OBSERVE("browser_task_observe"),BROWSER_CLICK("browser_task_click"),BROWSER_TYPE("browser_task_type"),BROWSER_SCROLL("browser_task_scroll"),BROWSER_WAIT("browser_task_wait");
        public final String name;Tool(String name){this.name=name;}
        public boolean requiresMatched(){return this==CONDITION_WAIT||this==BROWSER_WAIT;}
    }
    public sealed interface RunIdentity permits Identity,BrowserIdentity {String taskId();String runId();int epoch();}
    public record Identity(String taskId,String runId,int epoch,int displayId,String sessionId,String hostUserIdentity,String observationId) implements RunIdentity {
        public Identity {for(String value:new String[]{taskId,runId,sessionId,hostUserIdentity,observationId})if(value==null||value.isEmpty()||value.length()>200)throw new IllegalArgumentException("native_sequence_identity_invalid");if(epoch<1||displayId<0)throw new IllegalArgumentException("native_sequence_identity_invalid");}
    }
    public record BrowserIdentity(String taskId,String runId,int epoch,String profileId,long profileRevision,String sessionId,String browserSpaceId,String tabId,long tabGeneration,String documentId,long navigation) implements RunIdentity {
        public BrowserIdentity {for(String value:new String[]{taskId,runId,profileId,sessionId,browserSpaceId,tabId,documentId})if(value==null||value.isEmpty()||value.length()>200)throw new IllegalArgumentException("native_browser_sequence_identity_invalid");if(epoch<1||profileRevision<1||tabGeneration<1||navigation<0)throw new IllegalArgumentException("native_browser_sequence_identity_invalid");}
    }
    /** Resolve one step from a fresh native tree. Never execute it here. */
    @FunctionalInterface public interface Resolver {Resolved resolve(int stepIndex,RunControl original)throws Exception;}
    /** Native result only: fixed tool and actual current observation/node arguments, not model authority. */
    public static final class Resolved {
        private final Tool tool;private final String json,observation;private final RunControl.AuthorityCheck current;
        private Resolved(JSONObject completion,RunControl.AuthorityCheck current)throws Exception {
            this.tool=null;this.current=Objects.requireNonNull(current);observation=null;
            if(completion==null||completion.has("imageBase64")||completion.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>131072)throw new IllegalArgumentException("native_sequence_completion_invalid");
            json=completion.toString();
        }
        /** A read result that ends a bounded native plan; it cannot dispatch or restore an operation. */
        public static Resolved complete(JSONObject observation,RunControl.AuthorityCheck current)throws Exception{return new Resolved(observation,current);}
        public Resolved(Tool tool,JSONObject actualArguments,RunControl.AuthorityCheck current)throws Exception{this(tool,actualArguments,null,current);}
        public Resolved(Tool tool,JSONObject actualArguments,JSONObject actualFreshObservation,RunControl.AuthorityCheck current)throws Exception{
            this.tool=Objects.requireNonNull(tool);this.current=Objects.requireNonNull(current);Objects.requireNonNull(actualArguments);
            Set<String> keys=switch(tool){case CLICK_NODE->Set.of("observationId","nodeId");case TYPE->Set.of("observationId","nodeId","text");case SCROLL_NODE->Set.of("observationId","nodeId","direction");case WHEEL_STEP->Set.of("observationId","nodeId","direction","value");case CONDITION_WAIT->Set.of("observationId","kind","selector","timeoutMs","stableForMs");
                case BROWSER_OBSERVE->Set.of("tabId");case BROWSER_CLICK->Set.of("tabId","observationId","ref");case BROWSER_TYPE->Set.of("tabId","observationId","ref","text");case BROWSER_SCROLL->Set.of("tabId","observationId","dy");case BROWSER_WAIT->Set.of("tabId","condition","value","target","timeoutMs");};
            var names=actualArguments.keys();while(names.hasNext())if(!keys.contains(names.next()))throw new IllegalArgumentException("native_sequence_argument_not_allowed");
            boolean browser=tool.name.startsWith("browser_task_"),unreferenced=tool==Tool.BROWSER_OBSERVE||tool==Tool.BROWSER_WAIT;
            if(actualArguments.toString().length()>16000||!unreferenced&&actualArguments.optString("observationId").isEmpty()||browser&&actualArguments.optString("tabId").isEmpty())throw new IllegalArgumentException("native_sequence_arguments_invalid");
            if(actualFreshObservation==null){if(tool==Tool.CLICK_NODE||tool==Tool.TYPE||tool==Tool.WHEEL_STEP||tool==Tool.BROWSER_CLICK||tool==Tool.BROWSER_TYPE||tool==Tool.BROWSER_SCROLL)throw new IllegalArgumentException("native_sequence_fresh_tree_required");observation=null;}
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
    private final String parentTool,parentOperation;private final RunControl original;private final RunIdentity identity;private final long pause;
    private final int count;private final Resolver resolver;private final RunControl.AuthorityCheck current;
    private boolean taken,closed,resolving;private int next;private String completion;
    private NativeToolPlan(String parentTool,String parentOperation,RunControl original,RunIdentity identity,int count,Resolver resolver,RunControl.AuthorityCheck current)throws Exception{
        boolean bounded=bounded(parentTool);
        boolean allowed=identity instanceof Identity&&(java.util.Set.of("phone_prepare_sequence","phone_replay_route").contains(parentTool)||bounded)||identity instanceof BrowserIdentity&&"browser_task_prepare_sequence".equals(parentTool);
        if(!allowed||parentOperation==null||parentOperation.isEmpty()||parentOperation.length()>200||count<1||count>(bounded?31:4))throw new IllegalArgumentException("native_sequence_invalid");
        this.parentTool=parentTool;this.parentOperation=parentOperation;this.original=Objects.requireNonNull(original);this.identity=Objects.requireNonNull(identity);this.count=count;this.resolver=Objects.requireNonNull(resolver);this.current=Objects.requireNonNull(current);pause=original.pauseRevision();require(original);
    }
    public static NativeToolPlan capture(String parentTool,String parentOperationId,RunControl original,Identity identity,int count,Resolver resolver,RunControl.AuthorityCheck current)throws Exception{return new NativeToolPlan(parentTool,parentOperationId,original,identity,count,resolver,current);}
    public static NativeToolPlan captureBrowser(String parentOperationId,RunControl original,BrowserIdentity identity,int count,Resolver resolver,RunControl.AuthorityCheck current)throws Exception{return new NativeToolPlan("browser_task_prepare_sequence",parentOperationId,original,identity,count,resolver,current);}
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
        try{Resolved resolved=Objects.requireNonNull(resolver.resolve(index,actual));require(actual);
            if(resolved.tool==null){if(!bounded(parentTool))throw new SecurityException("native_sequence_completion_not_allowed");resolved.current.check();require(actual);synchronized(this){completion=resolved.json;next=count;}return null;}
            if((identity instanceof BrowserIdentity)!=resolved.tool.name.startsWith("browser_task_"))throw new SecurityException("native_sequence_tool_identity_mismatch");
            Step step=new Step(index,resolved);step.requireCurrent(actual);return step;}
        catch(Exception unavailable){close();throw unavailable;}
        finally{synchronized(this){resolving=false;}}
    }
    public int size(){return count;}
    public String parentTool(){return parentTool;}
    public synchronized JSONObject completion()throws Exception{return completion==null?null:new JSONObject(completion);}
    private static boolean bounded(String tool){return "phone_find".equals(tool)||"phone_prepare_wheel".equals(tool);}
    @Override public synchronized void close(){closed=true;}
}
