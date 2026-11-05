package dev.pocketengine.core;

import java.util.Set;
import org.json.JSONObject;

/** One interpretation of a native tool receipt. It is not an execution grant,
 * a retry instruction, a dispatch proof, or a replacement for the original facts. */
public final class ToolOutcome {
    public static final String KEY="toolOutcome",FORMAT="pocketworld.tool-outcome.v1";
    public enum Kind {
        SUCCESS("success","continue"),
        CORRECTABLE_FAILURE("correctable_failure","revise_request"),
        NEEDS_USER("needs_user","ask_owner"),
        EFFECT_UNKNOWN("effect_unknown","reconcile_original");
        public final String value,nextAction;
        Kind(String value,String nextAction){this.value=value;this.nextAction=nextAction;}
    }
    private static final Set<String> SUCCESS_STATES=Set.of("completed","succeeded","success","ok","created","observed","read","receipt","returned","partial","truncated","empty_range");
    private static final Set<String> FAILURE_STATES=Set.of("failed","error","rejected","refused","not_dispatched","unavailable","unsupported","invalid","limit_exceeded","input_changed");
    private static final Set<String> PENDING_STATES=Set.of("accepted","queued","submitting","pending","submitted","dispatched","starting","preparing","running","active","in_progress","pausing","stopping","verifying","project_running","project_stopping","remote_job_running");
    private final Kind kind;
    private final String completion;
    private ToolOutcome(Kind kind){this(kind,"unspecified");}
    private ToolOutcome(Kind kind,String completion){this.kind=kind;this.completion=completion;}
    public Kind kind(){return kind;}
    public String completion(){return completion;}
    public boolean successful(){return kind==Kind.SUCCESS;}
    public boolean correctable(){return kind==Kind.CORRECTABLE_FAILURE;}
    public boolean requiresUser(){return kind==Kind.NEEDS_USER;}
    public boolean requiresReconciliation(){return kind==Kind.EFFECT_UNKNOWN;}
    public boolean mayContinue(){return successful()||correctable();}
    public String eventStatus(){return successful()?("pending".equals(completion)?"running":"completed"):correctable()?"failed":requiresUser()?"awaiting_user":"unknown";}
    public String diagnosticStatus(){return successful()?("pending".equals(completion)?"running":"succeeded"):correctable()?"failed":requiresUser()?"awaiting_user":"unconfirmed";}
    public JSONObject view()throws Exception{return new JSONObject().put("format",FORMAT).put("kind",kind.value).put("completion",completion).put("nextAction",kind.nextAction).put("automaticReplayAllowed",false);}

    /** Incoming toolOutcome is always ignored. Only the native receipt fields are
     * interpreted; nested provider/file/page data cannot choose the host outcome. */
    public static ToolOutcome classify(JSONObject receipt){
        if(receipt==null)return new ToolOutcome(Kind.EFFECT_UNKNOWN);
        String code=receipt.optString("code",receipt.optString("error")),status=receipt.optString("status");
        if(hasUncertaintyEvidence(receipt))return new ToolOutcome(Kind.EFFECT_UNKNOWN);
        if(Set.of("approval_required","needs_user","awaiting_user","model_declared_waiting","planning_review_required","input_required","accessibility_unavailable").contains(code)
                ||Set.of("input_required","awaiting_user","cancelled","canceled","stopped").contains(status)
                ||receipt.optBoolean("requiresPause")||receipt.optBoolean("requiresOwnerReturn"))return new ToolOutcome(Kind.NEEDS_USER);
        if(Boolean.FALSE.equals(receipt.opt("ok"))||Boolean.FALSE.equals(receipt.opt("accepted"))||FAILURE_STATES.contains(status)
                ||receipt.opt("exitCode") instanceof Number&&((Number)receipt.opt("exitCode")).doubleValue()!=0)return new ToolOutcome(Kind.CORRECTABLE_FAILURE);
        if(PENDING_STATES.contains(status)||Boolean.TRUE.equals(receipt.opt("accepted"))||Boolean.FALSE.equals(receipt.opt("terminal")))
            return new ToolOutcome(Kind.SUCCESS,"pending");
        if(Boolean.TRUE.equals(receipt.opt("ok"))||SUCCESS_STATES.contains(status))
            return new ToolOutcome(Kind.SUCCESS,Boolean.TRUE.equals(receipt.opt("terminal"))||SUCCESS_STATES.contains(status)?"terminal":"unspecified");
        return new ToolOutcome(Kind.EFFECT_UNKNOWN);
    }

    /** Explicit legacy uncertainty, also used to repair already stored receipt rows.
     * An old data-only receipt is not retroactively called unknown merely because it
     * predates this contract; actual uncertainty signals still restore the barrier. */
    public static boolean hasUncertaintyEvidence(JSONObject receipt){
        if(receipt==null)return false;
        for(String key:new String[]{"ok","accepted","terminal","requiresReconciliation","reconciliationRequired","requiresPause","requiresOwnerReturn"})
            if(receipt.has(key)&&!(receipt.opt(key) instanceof Boolean)&&!(key.equals("accepted")&&receipt.isNull(key)))return true;
        for(String key:new String[]{"status","state","code","effectStatus","dispatchStatus"})if(receipt.has(key)&&!(receipt.opt(key) instanceof String))return true;
        if(receipt.has("exitCode")&&!(receipt.opt("exitCode") instanceof Number))return true;
        if(Boolean.TRUE.equals(receipt.opt("requiresReconciliation"))||Boolean.TRUE.equals(receipt.opt("reconciliationRequired"))
                ||"reconcile".equals(receipt.optString("nextObservationAction"))||receipt.has("accepted")&&receipt.isNull("accepted"))return true;
        String code=receipt.optString("code",receipt.optString("error"));
        // These exact admission refusals mean an unsupported input, not an effect
        // that happened without a receipt. All other legacy unknown codes stay fenced.
        boolean unknownCode=code.contains("unknown")&&!Set.of("unknown_tool","unknown_command","unknown_method").contains(code);
        return unknownCode||Set.of("action_timeout","empty_receipt","empty_observation","planning_read_unconfirmed").contains(code)
                ||uncertainState(receipt.optString("status"))||uncertainState(receipt.optString("state"))
                ||uncertainState(receipt.optString("effectStatus"))||uncertainState(receipt.optString("dispatchStatus"));
    }
    public static boolean uncertainState(String state){
        return state!=null&&(state.contains("unknown")||state.endsWith("_unconfirmed")
                ||Set.of("uncertain","reconciliation_required","pending_reconciliation").contains(state));
    }

    /** Copy for model/display delivery; never mutate a native receipt or its source fingerprint. */
    public static JSONObject project(JSONObject receipt)throws Exception{
        JSONObject out=receipt==null?new JSONObject():new JSONObject(receipt.toString());
        return out.put(KEY,classify(receipt).view());
    }

    /** Fresh native audit projection; unread bodies and owner resolutions keep their
     * own meaning. A durable unknown state overrides a superficially positive body. */
    public static JSONObject projectOperation(JSONObject operation)throws Exception{
        JSONObject out=new JSONObject(operation.toString());out.remove(KEY);
        ToolOutcome outcome=operationOutcome(operation);
        return outcome==null?out:out.put(KEY,outcome.view());
    }
    public static ToolOutcome operationOutcome(JSONObject operation){
        String state=operation.optString("state");
        if(uncertainState(state)||Boolean.TRUE.equals(operation.opt("requiresReconciliation")))
            return new ToolOutcome(Kind.EFFECT_UNKNOWN);
        if(state.equals("prepared")||state.equals("resolved")||operation.optString("status").equals("read_limited")
                ||Boolean.TRUE.equals(operation.opt("readLimited"))||Boolean.FALSE.equals(operation.opt("bodyAvailable")))return null;
        JSONObject receipt=operation.optJSONObject("receipt");
        if(receipt==null)return null;
        ToolOutcome actual=classify(receipt);
        if(Set.of("project_running","project_stopping").contains(state)&&actual.requiresReconciliation()&&!hasUncertaintyEvidence(receipt))
            actual=new ToolOutcome(Kind.SUCCESS,"pending");
        return PENDING_STATES.contains(state)&&actual.successful()?new ToolOutcome(actual.kind,"pending"):actual;
    }
}
