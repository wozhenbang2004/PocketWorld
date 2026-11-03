package dev.pocketengine.phone.diagnostics;

import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import dev.pocketengine.EngineApplication;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.phone.observation.NativePhoneEntry;
import org.json.JSONObject;

/** One launch of the app's private target, using its existing owner task and native dispatch fence. */
public final class PhonePracticeTarget implements AutoCloseable {
    private static final Object HANDOFF=new Object();
    private static Launch pending;
    private record Launch(RunControl original,String task,String run,int epoch,long revision,int display,String session,String nonce,long deadline){}
    private final Context context;private final int display;private final String session;
    private final String nonce=java.util.UUID.randomUUID().toString();
    private boolean requested;private RunControl original;private Runnable cancel;
    public PhonePracticeTarget(Context context,int display,String session){
        if(display<0||session==null||session.isBlank())throw new IllegalArgumentException("practice_display_required");
        this.context=context.getApplicationContext();this.display=display;this.session=session;
    }
    public synchronized JSONObject launch(RunControl control,NativePhoneEntry.Admission admission)throws Exception {
        if(!(context instanceof EngineApplication app)||control==null||!control.hasHostRun())throw new SecurityException("owner_practice_required");
        var run=control.hostRunIdentity();app.tasks.requireOwnerPhonePractice(run.taskId(),run.runId(),run.epoch(),control);
        if(requested)return new JSONObject().put("ok",false).put("accepted",false).put("error","practice_already_requested").put("automaticReplayAllowed",false);
        long revision=control.pauseRevision();
        Intent intent=new Intent(context,PhonePracticeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                .putExtra("taskId",run.taskId()).putExtra("runId",run.runId()).putExtra("epoch",run.epoch())
                .putExtra("practiceSessionId",nonce).putExtra("executionSessionId",session).putExtra("displayId",display);
        ActivityOptions options=ActivityOptions.makeBasic().setLaunchDisplayId(display);
        try{
            return NativePhoneEntry.call(control,revision,()->{if(admission==null)throw new SecurityException("original_practice_admission_required");admission.check();if(app.control(run.taskId())!=control)throw new SecurityException("practice_run_changed");},()->{
                Launch launch=new Launch(control,run.taskId(),run.runId(),run.epoch(),revision,display,session,nonce,android.os.SystemClock.uptimeMillis()+10_000);
                synchronized(HANDOFF){if(pending!=null)return new JSONObject().put("ok",false).put("accepted",false).put("error","practice_launch_pending");pending=launch;}
                requested=true;original=control;
                cancel=()->PhonePracticeActivity.requestCloseOriginal(control);control.onCancel(cancel);
                if(control.isCancelled()){revokePending(control);return new JSONObject().put("ok",false).put("accepted",false).put("error","practice_cancelled_before_start");}
                // The original app UID can launch its own non-exported Activity on the exact allowed display.
                try{context.startActivity(intent,options.toBundle());}
                catch(android.content.ActivityNotFoundException|SecurityException unsupported){revokePending(control);return new JSONObject().put("ok",false).put("accepted",false).put("error","practice_display_unsupported").put("displayId",display).put("automaticReplayAllowed",false);}
                return new JSONObject().put("ok",true).put("accepted",true).put("verified",false)
                        .put("practiceSessionId",nonce).put("displayId",display).put("sessionId",session)
                        .put("osAccepted",true).put("verifiedBusinessSuccess",false).put("automaticReplayAllowed",false);
            });
        }catch(NativePhoneEntry.Refused refused){throw refused;}
        catch(Exception failed){if(requested)return new JSONObject().put("ok",false).put("accepted",JSONObject.NULL).put("error","practice_launch_unknown").put("practiceSessionId",nonce).put("automaticReplayAllowed",false);throw failed;}
    }
    /** Correlates only the just-dispatched native Activity callback; TaskStore remains the authorization source. */
    static void claim(Intent intent,RunControl original,int actualDisplay,Runnable register){
        synchronized(HANDOFF){
            Launch launch=pending;
            if(launch==null||launch.original!=original||original.isCancelled()||original.isPaused()||original.pauseRevision()!=launch.revision
                    ||android.os.SystemClock.uptimeMillis()>=launch.deadline||actualDisplay!=launch.display
                    ||!launch.task.equals(intent.getStringExtra("taskId"))||!launch.run.equals(intent.getStringExtra("runId"))||launch.epoch!=intent.getIntExtra("epoch",-1)
                    ||launch.display!=intent.getIntExtra("displayId",-1)||!launch.session.equals(intent.getStringExtra("executionSessionId"))||!launch.nonce.equals(intent.getStringExtra("practiceSessionId")))
                throw new SecurityException("practice_native_launch_changed_or_consumed");
            pending=null;register.run();
        }
    }
    static void revokePending(RunControl original){synchronized(HANDOFF){if(pending!=null&&pending.original==original)pending=null;}}
    @Override public synchronized void close(){if(original!=null){PhonePracticeActivity.requestCloseOriginal(original);if(cancel!=null)original.removeCancelAction(cancel);cancel=null;}}
}
