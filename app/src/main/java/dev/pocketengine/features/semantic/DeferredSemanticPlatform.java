package dev.pocketengine.features.semantic;

import android.content.Context;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.phone.DeferredPhoneSession;
import dev.pocketengine.phone.PhoneAccessibilityService;
import dev.pocketengine.phone.access.AndroidPhoneAccessStore;
import dev.pocketengine.phone.display.BackgroundPhonePort;
import java.util.List;
import org.json.JSONObject;

/** Resolve Android entry discovery/dispatch only inside the original semantic tool call. */
final class DeferredSemanticPlatform implements SemanticPlatform {
    private final Context context;
    private final DeferredPhoneSession phone;
    private SemanticPlatform actual;
    DeferredSemanticPlatform(Context context,DeferredPhoneSession phone){this.context=context;this.phone=phone;}
    private synchronized SemanticPlatform require(RunControl original)throws Exception {
        phone.requireAdapter(original);
        if(actual==null){
            if(phone.physicalPort() instanceof BackgroundPhonePort background)
                actual=new BackgroundSemanticPlatform(context,background,new AndroidPhoneAccessStore(context));
            else {
                PhoneAccessibilityService service=PhoneAccessibilityService.get();
                if(!"main_screen".equals(phone.executionMode())||service==null)throw new SecurityException("original_semantic_phone_unavailable");
                actual=new AndroidSemanticPlatform(context,service,phone::clearReferences);
            }
        }
        return actual;
    }
    @Override public List<Target> discover(SemanticRequest request,RunControl control)throws Exception{return require(control).discover(request,control);}
    @Override public JSONObject resourceIdentity()throws Exception{return require(phone.originalControl()).resourceIdentity();}
    @Override public JSONObject dispatch(SemanticRequest request,Target target,RunControl control,Admission admission)throws Exception{return require(control).dispatch(request,target,control,admission);}
}
