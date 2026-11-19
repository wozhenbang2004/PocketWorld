package dev.pocketengine.phone;

import java.util.Set;
import org.json.JSONObject;

/** Display facts from the native owner's refusal. These records cannot approve or execute anything. */
public final class MainPhoneOwnerStep {
    private MainPhoneOwnerStep() {}
    public static final String KEY="mainPhoneOwnerStep", RETURN="mainPhoneOwnerReturn";
    public static final Set<String> TOOLS=Set.of("phone_click_node","phone_type","phone_tap","phone_swipe","phone_click_text","phone_scroll_node","phone_scroll_region","phone_set_wheel","phone_press_key");
    private static final Set<String> REASONS=Set.of("login_code","permission_elevation","payment_confirmation","transfer_confirmation","sensitive_target_uncertain");
    public static String reason(JSONObject receipt){
        if(receipt==null||!"not_dispatched".equals(receipt.opt("status"))||!"native_sensitive_owner_required".equals(receipt.opt("code")))return "";
        for(String key:new String[]{"ok","accepted","nativeDispatched","automaticReplayAllowed","executionAuthorized"})if(!Boolean.FALSE.equals(receipt.opt(key)))return "";
        String reason=receipt.optString("reason");return REASONS.contains(reason)?reason:"";
    }
    private static boolean local(JSONObject task){
        return task!=null&&"phone".equals(task.opt("kind"))&&"main_screen".equals(task.opt("executionMode"))&&
                "owner".equals(task.opt("actorId"))&&"private".equals(task.opt("spaceId"))&&task.isNull("origin")&&task.isNull("parentTaskId")&&
                task.isNull("ownerDeviceWork")&&task.isNull("ownerPhoneControl");
    }
    private static boolean matches(JSONObject t,JSONObject row){
        return row!=null&&!t.optString("id").isEmpty()&&t.optString("id").equals(row.opt("taskId"))&&!t.optString("runId").isEmpty()&&
                t.optString("runId").equals(row.opt("runId"))&&t.optInt("coordinatorEpoch")>0&&t.optInt("coordinatorEpoch")==row.optInt("epoch",-1)&&
                row.optString("operationId").matches("[a-fA-F0-9-]{36}");
    }
    /** Read only from full native evidence, never a header or an owner-supplied action argument. */
    public static JSONObject source(JSONObject t){
        if(!local(t))return null;
        JSONObject e=t.optJSONObject("evidence"),proof=e==null?null:e.optJSONObject(KEY);
        String reason=reason(e==null?null:e.optJSONObject("nativeSensitiveStep"));
        return !reason.isEmpty()&&Boolean.FALSE.equals(e.opt("automaticReplayAllowed"))&&Boolean.TRUE.equals(e.opt("originalEffectsRetained"))&&
                matches(t,proof)&&reason.equals(proof.opt("reason"))?proof:null;
    }
    public static boolean exactReturn(JSONObject t,JSONObject direct){
        JSONObject ack=t.optJSONObject(RETURN);
        return direct!=null&&matches(t,ack)&&direct.optString("operationId").equals(ack.opt("operationId"))&&
                Boolean.TRUE.equals(ack.opt("ownerHandedBack"))&&Boolean.FALSE.equals(ack.opt("businessResultVerified"));
    }
    public static boolean needsOwner(JSONObject t){
        if(!local(t))return false;
        JSONObject e=t.optJSONObject("evidence");String why=reason(e==null?null:e.optJSONObject("nativeSensitiveStep"));
        if(why.isEmpty())return false;
        JSONObject direct=source(t);
        // A legacy refusal without an original operation is review-only, never a generic resume.
        if(direct==null)return Set.of("awaiting_user","paused","interrupted","needs_confirmation").contains(t.optString("status"));
        if(!exactReturn(t,direct))return true;
        JSONObject check=e.optJSONObject("mainOwnerPageCheck"),ack=t.optJSONObject(RETURN);
        return check!=null&&"not_verified".equals(check.opt("state"))&&direct.optString("operationId").equals(check.opt("operationId"))&&
                check.optLong("pauseRevision",-1)==ack.optLong("pauseRevision",-2);
    }
    /** Nav hints even when a large core is omitted; native admission always re-reads the original row. */
    public static void project(JSONObject task,JSONObject header)throws Exception {
        if(!local(task))return;
        JSONObject source=source(task),ack=task.optJSONObject(RETURN);
        if(needsOwner(task)){
            String why=reason(task.getJSONObject("evidence").optJSONObject("nativeSensitiveStep"));
            JSONObject hint=new JSONObject().put("taskId",task.getString("id")).put("runId",task.optString("runId")).put("epoch",task.optInt("coordinatorEpoch")).put("reason",why);
            if(source!=null){hint.put("operationId",source.getString("operationId")).put("observedSurfaceKnown",source.optString("observedSurfaceKey").matches("[0-9a-f]{64}"));
                JSONObject check=task.getJSONObject("evidence").optJSONObject("mainOwnerPageCheck");if(check!=null&&"not_verified".equals(check.opt("state")))hint.put("returnPageNotVerified",true);}

            header.put(KEY,hint);
        }
        if(matches(task,ack)&&Boolean.TRUE.equals(ack.opt("ownerHandedBack"))&&Boolean.FALSE.equals(ack.opt("businessResultVerified")))header.put(RETURN,new JSONObject(ack.toString()));
    }
}
