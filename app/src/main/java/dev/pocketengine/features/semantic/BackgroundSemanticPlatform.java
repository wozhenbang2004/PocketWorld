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
    private final AndroidSemanticPlatform discovery;private final BackgroundPhonePort phone;private final PhoneAccessPolicy policy;private final int nativeUser;
    public BackgroundSemanticPlatform(Context context,BackgroundPhonePort phone,PhoneAccessPolicy policy){discovery=new AndroidSemanticPlatform(context,null,null);this.phone=phone;this.policy=policy;nativeUser=dev.pocketengine.phone.setup.AndroidAppCopies.id(android.os.Process.myUserHandle());}
    @Override public List<Target> discover(SemanticRequest request,RunControl control)throws Exception {return discovery.discover(request,control);}
    @Override public JSONObject resourceIdentity()throws Exception {return phone.executionIdentity().put("executionMode","background").put("appPolicyRevision",policy.current().revision()).put("userId",nativeUser);}
    @Override public JSONObject dispatch(SemanticRequest request,Target target,RunControl control,Admission admission)throws Exception {
        long revision=policy.current().revision();String pkg=target.identity().getString("packageName");
        Admission gate=()->{admission.check();PhoneAccessPolicy.State state=policy.current();if(state.revision()!=revision)throw new SecurityException("phone_policy_changed");state.require(Set.of(new AppCopyKey(pkg,nativeUser)),true,PhoneAccessPolicy.Use.AUTOMATION);};
        gate.check();JSONObject spec=new JSONObject().put("kind",request.kind).put("parameters",request.parameters()).put("target",target.identity()).put("userId",nativeUser);
        return phone.dispatchSemantic(spec,control,gate::check);
    }
}
