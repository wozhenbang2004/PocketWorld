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
    private final Context context;private final int display;private final String session;
    private final String nonce=java.util.UUID.randomUUID().toString();
    private boolean requested;private RunControl original;
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
                requested=true;original=control;
                // The original app UID can launch its own non-exported Activity on the exact allowed display.
                context.startActivity(intent,options.toBundle());
                control.onCancel(()->PhonePracticeActivity.requestCloseOriginal(control));
                return new JSONObject().put("ok",true).put("accepted",true).put("verified",false)
                        .put("practiceSessionId",nonce).put("displayId",display).put("sessionId",session)
                        .put("osAccepted",true).put("verifiedBusinessSuccess",false).put("automaticReplayAllowed",false);
            });
        }catch(NativePhoneEntry.Refused refused){throw refused;}
        catch(Exception failed){if(requested)return new JSONObject().put("ok",false).put("accepted",JSONObject.NULL).put("error","practice_launch_unknown").put("practiceSessionId",nonce).put("automaticReplayAllowed",false);throw failed;}
    }
    @Override public synchronized void close(){if(original!=null)PhonePracticeActivity.requestCloseOriginal(original);}
}
