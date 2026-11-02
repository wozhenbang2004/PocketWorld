package dev.pocketengine.phone.access;

import dev.pocketengine.core.PhonePort;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.phone.PhoneUiSignals;
import dev.pocketengine.phone.observation.*;
import java.util.Set;
import org.json.JSONObject;

/** No content is read to decide permission. Live checks also run at the original native dispatch fence. */
public final class GuardedPhonePort implements PhonePort,NativeOcrTapPort,NativeTreeScrollPort,NativePhoneSignals,NativePhoneExtensions {
    private final PhonePort inner;private final PhoneAccessPolicy policy;private final PhoneOwnerPackages.Probe owners;private final int nativeUser;
    private volatile long referenceRevision=-1;
    private final ThreadLocal<Boolean> probing=new ThreadLocal<>();
    public GuardedPhonePort(PhonePort inner,PhoneAccessPolicy policy,PhoneOwnerPackages.Probe owners,int nativeUser){this.inner=java.util.Objects.requireNonNull(inner);this.policy=java.util.Objects.requireNonNull(policy);this.owners=java.util.Objects.requireNonNull(owners);if(nativeUser<0||nativeUser>99999)throw new IllegalArgumentException("native_user_required");this.nativeUser=nativeUser;}
    public PhonePort physicalPort(){return inner;}
    public long policyRevision(){return policy.current().revision();}
    public boolean referencePolicyCurrent(){PhoneAccessPolicy.State current=policy.current();return current.available()&&(referenceRevision<0||referenceRevision==current.revision());}
    @Override public boolean isConnected(){return policy.current().available()&&inner.isConnected();}
    @Override public void clearReferences(){referenceRevision=-1;inner.clearReferences();}
    @FunctionalInterface private interface Body {JSONObject run(NativePhoneEntry.Admission live)throws Exception;}
    private JSONObject guarded(RunControl control,boolean needsReference,Set<AppCopyKey> target,Body body)throws Exception {
        PhoneAccessPolicy.State captured=policy.current();long revision=captured.revision();
        NativePhoneEntry.Admission gate=()->{
            PhoneAccessPolicy.State current=policy.current();if(current.revision()!=revision){clearReferences();throw new PhoneAccessPolicy.Denied("app_policy_changed");}
            if(needsReference&&referenceRevision!=revision)throw new PhoneAccessPolicy.Denied("app_policy_reference_expired");
            if(target!=null)current.require(target,true,PhoneAccessPolicy.Use.AUTOMATION);
            else {if(Boolean.TRUE.equals(probing.get()))return;probing.set(true);try{PhoneOwnerPackages actual=owners.read(control);current.require(actual.owners(),actual.known(),PhoneAccessPolicy.Use.AUTOMATION);}finally{probing.remove();}}
        };
        boolean entered=false;
        try{gate.check();try(AutoCloseable retained=control.retainAuthorityCheck(gate::check)){
            entered=true;JSONObject result=body.run(gate);gate.check();if(result!=null)result.put("appPolicyRevision",revision);return result;
        }}catch(PhoneAccessPolicy.Denied denied){clearReferences();return new JSONObject().put("ok",false).put("accepted",entered?JSONObject.NULL:false).put("error",denied.code).put("requiresUser",true).put("requiresReconciliation",entered).put("automaticFallback",false);}
    }
    @Override public JSONObject observe(boolean includeImage,RunControl control)throws Exception {
        JSONObject result=guarded(control,false,null,gate->inner.observe(includeImage,control));
        if(result!=null&&result.optBoolean("ok"))referenceRevision=result.getLong("appPolicyRevision");return result;
    }
    @Override public JSONObject execute(String tool,JSONObject arguments,RunControl control)throws Exception {
        if("phone_observe".equals(tool))return observe(false,control);
        if("phone_list_apps".equals(tool))return guarded(control,false,Set.of(),gate->inner.execute(tool,arguments,control));
        Set<AppCopyKey> target=null;if("phone_launch_app".equals(tool)){
            target=Set.of(new AppCopyKey(arguments.getString("packageName"),nativeUser));}
        return guarded(control,target==null,target,gate->inner.execute(tool,arguments,control));
    }
    @Override public JSONObject tapOcrText(OcrTextTarget target,RunControl control,NativePhoneEntry.Admission admission)throws Exception {
        if(!(inner instanceof NativeOcrTapPort))throw new SecurityException("native_ocr_unavailable");
        return guarded(control,true,null,live->((NativeOcrTapPort)inner).tapOcrText(target,control,()->{live.check();admission.check();}));
    }
    @Override public JSONObject scrollNode(JSONObject arguments,RunControl control,NativePhoneEntry.Admission admission)throws Exception {
        if(!(inner instanceof NativeTreeScrollPort))throw new SecurityException("native_scroll_unavailable");
        return guarded(control,true,null,live->((NativeTreeScrollPort)inner).scrollNode(arguments,control,()->{live.check();admission.check();}));
    }
    @Override public PhoneUiSignals uiSignals(RunControl control)throws Exception {PhoneAccessPolicy.State state=policy.current();PhoneOwnerPackages actual=owners.read(control);state.require(actual.owners(),actual.known(),PhoneAccessPolicy.Use.AUTOMATION);return inner instanceof NativePhoneSignals?((NativePhoneSignals)inner).uiSignals(control):null;}
    @Override public JSONObject extension(String tool,JSONObject arguments,RunControl control,NativePhoneEntry.Admission admission)throws Exception {
        if(!(inner instanceof NativePhoneExtensions))throw new SecurityException("native_extension_unavailable");
        boolean listing="phone_list_app_copies".equals(tool),launch="phone_launch_app_copy".equals(tool)||"phone_practice_target".equals(tool)||"phone_app_after_task".equals(tool);
        Set<AppCopyKey> target=listing?Set.of():launch?Set.of(new AppCopyKey(arguments.getString("packageName"),arguments.getInt("userId"))):null;
        return guarded(control,!listing&&!launch,target,live->((NativePhoneExtensions)inner).extension(tool,arguments,control,()->{live.check();if(admission!=null)admission.check();}));
    }
    /** Explicit owner capture is separate from automation permission; the same no-content probe is reused. */
    public void requireOwnerShare(RunControl control)throws Exception {PhoneOwnerPackages actual=owners.read(control);policy.current().require(actual.owners(),actual.known(),PhoneAccessPolicy.Use.OWNER_SHARE);}
}
