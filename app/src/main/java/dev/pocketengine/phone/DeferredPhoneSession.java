package dev.pocketengine.phone;

import dev.pocketengine.core.PhonePort;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.phone.observation.AdaptivePhonePort;
import dev.pocketengine.phone.observation.NativePhoneEntry;
import java.util.Objects;
import java.util.function.LongSupplier;
import org.json.JSONObject;

/** One original run's deferred native phone binding. No task, grant, worker or journal of its own. */
public final class DeferredPhoneSession implements PhonePort {
    public static final class PreparationFailure extends Exception {
        private final String evidence;
        public PreparationFailure(Throwable cause,JSONObject evidence){super(cause.getMessage(),cause);this.evidence=evidence.toString();}
        public JSONObject evidence()throws Exception{return new JSONObject(evidence);}
    }
    /** Preparation is a needs-user receipt, so expose a bounded message without a stack trace. */
    public static String preparationMessage(Throwable failure,ModelConfig config){
        String message=failure==null?null:failure.getMessage();
        if(message==null||message.isBlank()||config==null)return "手机准备条件不可用，请核对原任务的权限与连接";
        message=config.redactSensitive(message).replace('\n',' ').replace('\r',' ');
        return message.length()>350?message.substring(0,350):message;
    }
    @FunctionalInterface public interface Factory {
        /** The host owns cleanup of partial creation before returning a complete binding. */
        Binding open(RunControl original,long pauseRevision,NativePhoneEntry.Admission ownerPreparation)throws Exception;
    }
    @FunctionalInterface public interface Abort {JSONObject close()throws Exception;}
    public static final class Binding {
        public final PhonePort physical;
        public final AdaptivePhonePort agent;
        private final Abort abort;
        private JSONObject disposition;
        public Binding(PhonePort physical,AdaptivePhonePort agent,Abort abort) {
            this.physical=Objects.requireNonNull(physical);this.agent=Objects.requireNonNull(agent);
            this.abort=Objects.requireNonNull(abort);
        }
        private synchronized JSONObject release()throws Exception {
            if(disposition!=null)return new JSONObject(disposition.toString());
            // The same physical cleanup is issued once, including an unknown first receipt.
            disposition=new JSONObject().put("releaseConfirmed",false).put("error","cleanup_incomplete");
            try{JSONObject receipt=abort.close();if(receipt!=null)disposition=new JSONObject(receipt.toString());}
            catch(Exception unknown){disposition.put("error","cleanup_unknown");throw unknown;}
            return new JSONObject(disposition.toString());
        }
    }
    private final String task,run,mode;
    private final int epoch;
    private final RunControl original;
    private final Factory factory;
    private final LongSupplier policyRevision;
    private final boolean textTargeting;
    private volatile Binding binding;
    private boolean opening,preparationUncertain;
    private volatile boolean closed;

    public DeferredPhoneSession(String task,String run,int epoch,String mode,RunControl original,
                                boolean textTargeting,LongSupplier policyRevision,Factory factory) {
        this.task=Objects.requireNonNull(task);this.run=Objects.requireNonNull(run);this.epoch=epoch;
        this.mode=Objects.requireNonNull(mode);this.original=Objects.requireNonNull(original);
        if(!mode.matches("main_screen|background")||!original.matchesHostRun(task,run,epoch))throw new SecurityException("original_phone_run_required");
        this.textTargeting=textTargeting;this.policyRevision=Objects.requireNonNull(policyRevision);
        this.factory=Objects.requireNonNull(factory);
    }
    public boolean matchesHostScope(String task,String run,int epoch) {return this.task.equals(task)&&this.run.equals(run)&&this.epoch==epoch;}
    public String executionMode(){return mode;}
    public RunControl originalControl(){return original;}
    public boolean initialized(){return binding!=null;}
    public synchronized boolean pending(){return !closed&&!opening&&!preparationUncertain&&binding==null;}
    public synchronized boolean cleanupUnconfirmed(){return preparationUncertain;}
    public synchronized JSONObject initializationEvidence()throws Exception {
        return new JSONObject().put("state",preparationUncertain?"preparation_unconfirmed":initialized()?"initialized":"not_initialized")
                .put("nativeRunRetained",!closed).put("physicalDisplayHeld",preparationUncertain?JSONObject.NULL:initialized());
    }
    public PhonePort physicalPort(){Binding actual=binding;return actual==null?null:actual.physical;}
    public AdaptivePhonePort readyAdapter(){Binding actual=binding;return actual==null?null:actual.agent;}
    public long appPolicyRevision(){return policyRevision.getAsLong();}
    public boolean textTargetingConfigured(){return textTargeting;}
    /** A logical original-run hold is explicit; it is never presented as a physical display. */
    public synchronized void requirePendingHold(RunControl control) {
        if(control!=original||closed||opening||preparationUncertain||binding!=null||!original.matchesHostRun(task,run,epoch)||original.isCancelled())
            throw new SecurityException("original_pending_phone_session_changed");
    }
    private void requireLive(RunControl control,long revision,NativePhoneEntry.Admission ownerPreparation)throws Exception {
        if(control!=original||!original.matchesHostRun(task,run,epoch))throw new SecurityException("original_phone_control_changed");
        if(ownerPreparation!=null)ownerPreparation.check();
        original.check();
        if(original.isPaused()!=(ownerPreparation!=null)||original.pauseRevision()!=revision||!original.phoneSurfaceActive())throw new SecurityException("original_phone_dispatch_stopped");
        synchronized(this){if(closed||preparationUncertain)throw new SecurityException("original_phone_session_closed_or_unconfirmed");}
    }
    public AdaptivePhonePort requireAdapter(RunControl control)throws Exception {
        return resolve(control,null);
    }
    /** Explicit native owner takeover only. Preparation never resumes the original runner. */
    public PhonePort prepareForOwner(RunControl control,NativePhoneEntry.Admission admission)throws Exception {
        resolve(control,Objects.requireNonNull(admission));return physicalPort();
    }
    private AdaptivePhonePort resolve(RunControl control,NativePhoneEntry.Admission ownerPreparation)throws Exception {
        long revision=original.pauseRevision();requireLive(control,revision,ownerPreparation);
        Binding actual=binding;
        if(actual!=null){if(ownerPreparation==null)actual.agent.requireHistoryCurrent(control);requireLive(control,revision,ownerPreparation);return actual.agent;}
        synchronized(this){
            if(closed||opening)throw new SecurityException("original_phone_preparation_unavailable");
            actual=binding;if(actual==null)opening=true;
        }
        if(actual!=null){if(ownerPreparation==null)actual.agent.requireHistoryCurrent(control);requireLive(control,revision,ownerPreparation);return actual.agent;}
        Binding prepared=null;boolean published=false;Exception failureSeen=null;
        try {
            prepared=factory.open(original,revision,ownerPreparation);requireLive(control,revision,ownerPreparation);
            if(prepared==null||!prepared.agent.matchesHostScope(task,run,epoch)||!prepared.physical.isConnected())throw new SecurityException("original_phone_binding_unconfirmed");
            if(ownerPreparation==null)prepared.agent.requireHistoryCurrent(control);requireLive(control,revision,ownerPreparation);
            Binding selected=prepared;
            original.dispatchAtPauseState(revision,ownerPreparation!=null,()->{
                if(original.isPaused()!=(ownerPreparation!=null)||!original.phoneSurfaceActive())throw new SecurityException("original_phone_dispatch_stopped");
                synchronized(this){
                    if(closed||binding!=null)throw new SecurityException("original_phone_binding_changed");
                    binding=selected;
                }
            });
            published=true;return prepared.agent;
        } catch(Exception failure) {
            failureSeen=failure;
            if(failure instanceof PreparationFailure unavailable)
                synchronized(this){preparationUncertain=!unavailable.evidence().optBoolean("releaseConfirmed",false);}
            for(Throwable detail:failure.getSuppressed())if(detail instanceof PreparationFailure unavailable)
                synchronized(this){preparationUncertain|=!unavailable.evidence().optBoolean("releaseConfirmed",false);}
            throw failure;
        } finally {
            try{if(prepared!=null&&!published){
                JSONObject disposition=prepared.release();
                if(disposition==null||!Boolean.TRUE.equals(disposition.opt("releaseConfirmed")))
                    synchronized(this){preparationUncertain=true;}
            }}
            catch(Exception cleanup){
                synchronized(this){preparationUncertain=true;}
                if(failureSeen!=null)failureSeen.addSuppressed(cleanup);else throw cleanup;
            }
            finally{synchronized(this){opening=false;}}
        }
    }
    /** Close never creates a phone. The host still settles its exact physical resource receipt. */
    public void close(){synchronized(this){closed=true;}clearReferences();}
    /** Host cleanup only, with the same immutable physical binding and one retained receipt. */
    public JSONObject releasePhysical()throws Exception {
        close();Binding actual=binding;
        if(actual!=null)return actual.release();
        synchronized(this){return new JSONObject().put("releaseConfirmed",!opening&&!preparationUncertain).put("notInitialized",true);}
    }
    @Override public boolean isConnected(){Binding actual=binding;return !closed&&actual!=null&&actual.agent.isConnected();}
    @Override public JSONObject observe(boolean image,RunControl control)throws Exception{return requireAdapter(control).observe(image,control);}
    @Override public JSONObject execute(String tool,JSONObject args,RunControl control)throws Exception{return requireAdapter(control).execute(tool,args,control);}
    @Override public void clearReferences(){Binding actual=binding;if(actual!=null)actual.agent.clearReferences();}

    public static boolean adaptive(PhonePort selected){return selected instanceof AdaptivePhonePort||selected instanceof DeferredPhoneSession;}
    public static AdaptivePhonePort require(PhonePort selected,RunControl control)throws Exception {
        if(selected instanceof DeferredPhoneSession deferred)return deferred.requireAdapter(control);
        if(selected instanceof AdaptivePhonePort adapter)return adapter;
        throw new SecurityException("original_adaptive_phone_required");
    }
    public static boolean initialized(PhonePort selected){return selected!=null&&(!(selected instanceof DeferredPhoneSession deferred)||deferred.initialized());}
    public static boolean matches(PhonePort selected,String task,String run,int epoch) {
        return selected instanceof DeferredPhoneSession deferred?deferred.matchesHostScope(task,run,epoch)
                :selected instanceof AdaptivePhonePort adapter&&adapter.matchesHostScope(task,run,epoch);
    }
    public static boolean declared(PhonePort selected){return selected instanceof DeferredPhoneSession||selected instanceof AdaptivePhonePort&&selected.isConnected();}
    public static boolean textTargeting(PhonePort selected){return selected instanceof DeferredPhoneSession deferred?deferred.textTargetingConfigured():selected instanceof AdaptivePhonePort adapter&&adapter.textTargetingAvailable();}
    public static long policyRevision(PhonePort selected){return selected instanceof DeferredPhoneSession deferred?deferred.appPolicyRevision():selected instanceof AdaptivePhonePort adapter?adapter.appPolicyRevision():0;}
    public static boolean policyBound(PhonePort selected){return selected instanceof DeferredPhoneSession||selected instanceof AdaptivePhonePort adapter&&adapter.appPolicyBound();}
    public static void requireHistoryCurrent(PhonePort selected,RunControl control)throws Exception {
        if(selected instanceof DeferredPhoneSession deferred){
            AdaptivePhonePort ready=deferred.readyAdapter();if(ready!=null)ready.requireHistoryCurrent(control);
            else deferred.requirePendingHold(control);
        }else if(selected instanceof AdaptivePhonePort adapter)adapter.requireHistoryCurrent(control);
    }
}
