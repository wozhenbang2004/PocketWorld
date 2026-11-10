package dev.pocketengine.features.semantic;

import android.content.Context;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.phone.access.*;
import dev.pocketengine.phone.display.BackgroundPhonePort;
import java.util.List;
import java.util.Set;
import org.json.JSONObject;

/** Same semantic discovery/approval/session, dispatched only into the original physical background display. */
public final class BackgroundSemanticPlatform implements SemanticPlatform {
    private final Context context;private final AndroidSemanticPlatform discovery;private final BackgroundPhonePort phone;private final PhoneAccessPolicy policy;private final int nativeUser;
    public BackgroundSemanticPlatform(Context context,BackgroundPhonePort phone,PhoneAccessPolicy policy){this.context=context.getApplicationContext();discovery=new AndroidSemanticPlatform(context,null,null);this.phone=phone;this.policy=policy;nativeUser=dev.pocketengine.phone.setup.AndroidAppCopies.id(android.os.Process.myUserHandle());}
    @Override public List<Target> discover(SemanticRequest request,RunControl control)throws Exception {return discovery.discover(request,control);}
    @Override public JSONObject resourceIdentity()throws Exception {return phone.executionIdentity().put("executionMode","background").put("appPolicyRevision",policy.current().revision()).put("userId",nativeUser).put("profileSerial",profileSerial());}
    @Override public JSONObject dispatch(SemanticRequest request,Target target,RunControl control,Admission admission)throws Exception {
        long revision=policy.current().revision();JSONObject identity=target.identity();String pkg=identity.getString("packageName");
        Admission gate=()->{
            admission.check();
            if(identity.getInt("userId")!=nativeUser||identity.getInt("uid")/100000!=nativeUser||identity.getLong("profileSerial")!=profileSerial())
                throw new SecurityException("semantic_profile_changed");
            PhoneAccessPolicy.State state=policy.current();if(state.revision()!=revision)throw new SecurityException("phone_policy_changed");
            state.require(Set.of(new AppCopyKey(pkg,nativeUser)),true,PhoneAccessPolicy.Use.AUTOMATION);
        };
        gate.check();JSONObject spec=new JSONObject().put("kind",request.kind).put("parameters",request.parameters()).put("target",identity).put("route",target.route)
                .put("userId",nativeUser).put("profileSerial",profileSerial());
        NextAlarmEvidence alarm=NextAlarmEvidence.forRequest(context,request,target);
        JSONObject result=phone.dispatchSemantic(spec,control,()->{gate.check();if(alarm!=null)alarm.captureBefore();});
        return alarm==null?result:alarm.afterDispatch(result,control,gate);
    }
    private long profileSerial()throws Exception {
        android.os.UserManager users=context.getSystemService(android.os.UserManager.class);
        long serial=users==null?-1:users.getSerialNumberForUser(android.os.Process.myUserHandle());
        if(serial<0||!users.isUserUnlocked())throw new SecurityException("semantic_profile_identity_unavailable");return serial;
    }
}
