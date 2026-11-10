package dev.pocketengine.phone.appcontrol;

import dev.pocketengine.core.RunControl;
import java.util.LinkedHashMap;
import java.util.UUID;
import org.json.JSONObject;

/** Bounded live references only. TaskStore remains the approval and operation authority. */
public final class PhoneAppRunSession implements AutoCloseable {
    static final class Selection {
        final String appRef=UUID.randomUUID().toString(),scopeJson,targetJson,tasksJson;
        final long expires;
        boolean consumed,stopAttempted,launchAttempted;
        int resolvedStage;
        String helperRef,dispatchScopeJson,approvalId,approvalArgs,approvalCapability;
        JSONObject stopReceipt,launchReceipt;
        Selection(JSONObject scope,JSONObject helper)throws Exception {
            scopeJson=scope.toString();targetJson=helper.getJSONObject("target").toString();helperRef=helper.getString("restartRef");
            tasksJson=helper.getJSONArray("tasks").toString();expires=helper.getLong("expiresAtUptimeMs");
        }
        JSONObject reviewScope()throws Exception{return new JSONObject(scopeJson);}
        JSONObject scope()throws Exception{return new JSONObject(dispatchScopeJson==null?scopeJson:dispatchScopeJson);}
        JSONObject target()throws Exception{return new JSONObject(targetJson);}
    }
    public final RunControl originalControl;
    private final LinkedHashMap<String,Selection> selections=new LinkedHashMap<>();
    private boolean closed;
    private final Runnable cancellation=this::close;
    public PhoneAppRunSession(RunControl original){originalControl=java.util.Objects.requireNonNull(original);if(!original.hasHostRun()||original.recoveryOnly())throw new SecurityException("original_app_run_required");original.onCancel(cancellation);}
    public synchronized void requireLive(RunControl original){if(closed||original!=originalControl||original.isCancelled())throw new SecurityException("original_app_run_changed");}
    public synchronized boolean ended(){return closed||originalControl.isCancelled();}
    synchronized Selection retain(JSONObject scope,JSONObject helper)throws Exception {
        requireLive(originalControl);prune();while(selections.size()>=32)selections.remove(selections.keySet().iterator().next());
        Selection target=new Selection(scope,helper);long now=android.os.SystemClock.uptimeMillis();
        if(target.expires<=now||target.expires-now>300_000)throw new SecurityException("app_reference_expiry_invalid");selections.put(target.appRef,target);return target;
    }
    synchronized Selection find(String id){prune();return selections.get(id);}
    private void prune(){long now=android.os.SystemClock.uptimeMillis();selections.values().removeIf(value->value.expires<=now);}
    @Override public synchronized void close(){closed=true;selections.clear();originalControl.removeCancelAction(cancellation);}
}
