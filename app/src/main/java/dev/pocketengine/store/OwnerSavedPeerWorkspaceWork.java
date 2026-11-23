package dev.pocketengine.store;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.features.devices.DeviceProtocol;
import dev.pocketengine.features.devices.DeviceTaskEnvelope;
import dev.pocketengine.features.devices.DirectedWorkspaceTarget;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** The owner's saved, direct workspace jobs for one paired peer. No endpoint or history controls. */
final class OwnerSavedPeerWorkspaceWork {
    private static final int PAGE = 12, OPERATION_WITNESSES = 8;
    private static final Set<String> STATUS = Set.of("queued","preparing","running","paused","pausing","stopping",
            "verifying","awaiting_user","needs_confirmation","waiting_approval","reconciling","completed","failed","cancelled","interrupted");
    private record UnconfirmedDispatch(String operation, String tool, long claimedAt) {
        JSONObject view() throws Exception {
            return new JSONObject().put("operationId",operation).put("tool",tool).put("claimedAt",claimedAt);
        }
    }
    private record WorkspaceOperations(String accepted, UnconfirmedDispatch unconfirmed) {}

    private OwnerSavedPeerWorkspaceWork() {}

    static void requirePeer(SQLiteDatabase db, DirectedWorkspaceTarget selected, long epoch) {
        if (epoch < 1 || epoch > DeviceProtocol.MAX_SAFE_INTEGER) throw new SecurityException("原配对版本无效");
        try (Cursor row = db.rawQuery("SELECT 1 FROM device_peers WHERE device_id=? AND instance_id=? AND fingerprint=? AND authorization_epoch=? AND revoked=0",
                new String[]{selected.deviceId(), selected.instanceId(), selected.certificateFingerprint(), Long.toString(epoch)})) {
            if (!row.moveToFirst()) throw new SecurityException("本机原配对已变化，未读取其他设备");
        }
    }

    static JSONObject page(SQLiteDatabase db, String here, DirectedWorkspaceTarget selected, long pairEpoch,
            String after, String exactTask, String exactRun, int exactEpoch) throws Exception {
        DeviceProtocol.deviceId(here,"localDeviceId");
        requirePeer(db,selected,pairEpoch);
        var args = new ArrayList<String>();
        args.add(selected.deviceId());
        String filter = "", linkedParent = "";
        if (!exactTask.isEmpty()) {
            DeviceProtocol.identifier(exactTask,"taskId"); DeviceProtocol.identifier(exactRun,"runId");
            if (!after.isEmpty() || exactEpoch < 1) throw new SecurityException("原工作位置已变化");
            linkedParent = " AND parent_task_id=?";
            args.add(exactTask);
        } else if (!after.isEmpty()) {
            DeviceProtocol.identifier(after,"afterTask");
            String mark;
            try (Cursor row = db.rawQuery("SELECT MAX(created_at) FROM device_child_budget WHERE target_device_id=? AND parent_task_id=?",
                    new String[]{selected.deviceId(),after})) {
                if (!row.moveToFirst() || row.isNull(0)) throw new SecurityException("本机原目录位置已变化，请从最近工作重新读取");
                mark=Long.toString(row.getLong(0));
            }
            // Android rawQuery binds String[] as text; MAX() has no SQLite column affinity.
            filter = " WHERE (d.latest_at<CAST(? AS INTEGER) OR (d.latest_at=CAST(? AS INTEGER) AND d.parent_task_id>?))";
            args.add(mark); args.add(mark); args.add(after);
        }
        // One row per already linked parent, for this device only, not a scan of the global viewport.
        String sql = "SELECT t.task_id,t.updated_at,CASE WHEN length(CAST(t.document AS BLOB))<="+TaskAuditStore.MAX_CORE_BYTES+" THEN t.document END"
                +" FROM (SELECT parent_task_id,MAX(created_at) AS latest_at FROM device_child_budget WHERE target_device_id=?"+linkedParent+" GROUP BY parent_task_id) d"
                +" JOIN tasks t ON t.task_id=d.parent_task_id"+filter
                +" ORDER BY d.latest_at DESC,d.parent_task_id LIMIT "+(PAGE+1);
        String last="";
        boolean more=false, includedUnconfirmed=false;
        int scanned=0;
        JSONArray rows=new JSONArray();
        try (Cursor work = db.rawQuery(sql,args.toArray(new String[0]))) {
            while (work.moveToNext()) {
                if (scanned++ == PAGE) { more=true; break; }
                last=work.getString(0);
                if (work.isNull(2)) continue;
                JSONObject parent;
                try { parent=new JSONObject(work.getString(2)); }
                catch (org.json.JSONException unreadable) { continue; }
                String run=parent.optString("runId"), status=parent.optString("status");
                int epoch=parent.optInt("coordinatorEpoch",-1);
                if (!ownerWork(parent,last,run,epoch,selected) || !STATUS.contains(status)
                        || !exactTask.isEmpty() && (!exactRun.equals(run) || exactEpoch!=epoch)) continue;
                // Rechecking a previously opened accepted row must not return an unconfirmed
                // operation as a replacement. The additional evidence belongs only to the directory.
                var operations=workspaceOperations(db,here,selected,last,run,epoch,exactTask.isEmpty());
                if (operations.accepted().isEmpty() && operations.unconfirmed()==null) continue;
                long at=work.getLong(1);
                if (at<=0 || at>DeviceProtocol.MAX_SAFE_INTEGER) continue;
                JSONObject row=new JSONObject().put("taskId",last).put("runId",run).put("epoch",epoch)
                        .put("lastSavedAt",at).put("status",status);
                if (!operations.accepted().isEmpty()) row.put("retainedOperation",operations.accepted());
                if (operations.unconfirmed()!=null) {
                    row.put("unconfirmedDispatch",operations.unconfirmed().view()); includedUnconfirmed=true;
                }
                rows.put(row);
            }
        }
        requirePeer(db,selected,pairEpoch);
        JSONObject page=new JSONObject().put("rows",rows).put("next",more ? last : "").put("readOnly",true);
        if (includedUnconfirmed) page.put("localDispatchReadAt",System.currentTimeMillis());
        return page;
    }

    static boolean ownerWork(JSONObject parent, String task, String run, int epoch, DirectedWorkspaceTarget selected) {
        JSONObject marker=parent.optJSONObject("ownerDeviceWork");
        if (!task.equals(parent.opt("id")) || !"owner".equals(parent.opt("actorId")) || !"private".equals(parent.opt("spaceId"))
                || parent.has("origin") || parent.has("ownerPhoneControl") || !"assistant".equals(parent.opt("kind"))
                || !"capabilities".equals(parent.opt("executionMode")) || !"".equals(parent.opt("conversationId"))
                || Boolean.FALSE.equals(parent.opt("contextAvailable")) || parent.optBoolean("contextWithdrawn")
                || parent.optBoolean("sourceCurrentReadRequired") || "source_unavailable".equals(parent.opt("taskProjection"))
                || run.isEmpty() || !run.equals(parent.opt("runId")) || epoch<1 || parent.optInt("coordinatorEpoch",-1)!=epoch
                || marker==null || !"pocketworld.owner-device-work.v1".equals(marker.opt("format"))
                || !task.equals(marker.opt("requestId")) || !marker.optString("requestDigest").matches("[a-f0-9]{64}")) return false;
        try { return selected.equals(DirectedWorkspaceTarget.fromTask(parent)); }
        catch (Exception invalid) { return false; }
    }

    /** One accepted operation keeps the ordinary Work link. A sealed local claim is separate
     * evidence and carries no Work or peer-reading authority. Do not copy payloads or grants. */
    private static WorkspaceOperations workspaceOperations(SQLiteDatabase db,String here,DirectedWorkspaceTarget target,
            String id,String run,int epoch,boolean includeUnconfirmed) throws Exception {
        String accepted=""; UnconfirmedDispatch unconfirmed=null;
        String sql="SELECT l.parent_operation_id,l.allocation_id,l.intent_digest,l.wire_digest,b.parent_operation_id,b.arguments_digest,"
                +"CASE WHEN length(CAST(l.wire_json AS BLOB))<="+DeviceProtocol.MAX_CONTROL_BYTES+" THEN l.wire_json END,"
                +"CASE WHEN length(CAST(o.receipt AS BLOB))<=131072 THEN o.receipt END,o.state"
                +" FROM device_child_budget b JOIN device_child_links l ON l.parent_task_id=b.parent_task_id AND l.allocation_id=b.allocation_id"
                +" JOIN operations o ON o.task_id=l.parent_task_id AND o.operation_id=l.parent_operation_id AND o.run_id=b.parent_run_id"
                +" JOIN runs r ON r.task_id=o.task_id AND r.run_id=o.run_id AND r.epoch=b.parent_epoch"
                +" WHERE b.parent_task_id=? AND b.parent_run_id=? AND b.parent_epoch=? AND b.target_device_id=?"
                +" AND o.tool='device_invoke_tool' AND o.state IN ('receipt','unknown') AND o.receipt IS NOT NULL AND l.wire_json IS NOT NULL"
                +" ORDER BY o.created_at DESC,o.operation_id LIMIT "+OPERATION_WITNESSES;
        try (Cursor op=db.rawQuery(sql,new String[]{id,run,Integer.toString(epoch),target.deviceId()})) {
            while(op.moveToNext()) {
                if(op.isNull(3)||op.isNull(5)||op.isNull(6)||op.isNull(7)) continue;
                try {
                    String operation=op.getString(0),allocation=op.getString(1),intent=op.getString(2),digest=op.getString(3);
                    byte[] bytes=op.getString(6).getBytes(StandardCharsets.UTF_8);
                    DeviceTaskEnvelope wire=DeviceProtocol.parseRequest(bytes);
                    JSONObject frozen=wire.json(), receipt=new JSONObject(op.getString(7));
                    JSONObject send=receipt.optJSONObject("nativeDeviceSend"), binding=receipt.optJSONObject("nativeDeviceAcceptedBinding");
                    if (send==null || binding==null&&!includeUnconfirmed || !"pocketworld.native-device-send.v1".equals(send.opt("format"))
                            || !"write_claimed".equals(send.opt("state")) || send.optLong("claimedAt")<=0
                            || !DeviceProtocol.sha256(bytes).equals(send.opt("wireSha256")) || !allocation.equals(send.opt("allocationId"))
                            || !intent.equals(send.opt("intentDigest")) || !wire.argumentsDigest().equals(digest)
                            || !"tool.invoke".equals(wire.method()) || !operation.equals(wire.operationId())
                            || !id.equals(wire.rootTaskId()) || !id.equals(wire.parentTaskId()) || !run.equals(wire.runId())
                            || epoch!=wire.executorEpoch() || epoch!=frozen.optInt("attempt")
                            || !here.equals(wire.originDeviceId()) || !here.equals(wire.callerDeviceId())
                            || !target.deviceId().equals(wire.targetDeviceId()) || !target.instanceId().equals(wire.instanceId())
                            || !allocation.equals(wire.localTaskId()) || !allocation.equals(wire.budget().opt("allocationId"))) continue;
                    var tool=wire.toolInvocation();
                    if (!DirectedWorkspaceTarget.allows(frozen.optString("capabilityVersion"),tool.toolName(),tool.effect())
                            || !allocation.equals(DeviceProtocol.allocationId(id,target.deviceId(),wire.resourceBinding(),"tool_session",null))) continue;
                    if (binding!=null && accepted.isEmpty()) {
                        String job=binding.optString("jobId");
                        if (Boolean.TRUE.equals(binding.opt("accepted")) && "job".equals(binding.opt("acceptedObjectKind"))
                                && job.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}") && job.equals(binding.opt("objectId"))
                                && id.equals(binding.opt("parentTaskId")) && op.getString(4).equals(binding.opt("parentOperationId"))
                                && operation.equals(binding.opt("sourceOperationId")) && operation.equals(binding.opt("targetOperationId"))
                                && digest.equals(binding.opt("operationArgumentsDigest")) && op.getString(5).equals(binding.opt("argumentsDigest"))
                                && target.deviceId().equals(binding.opt("executorDeviceId")) && target.instanceId().equals(binding.opt("instanceId"))
                                && binding.optLong("acceptedAt")>0) accepted=tool.toolName();
                    }
                    if (includeUnconfirmed && unconfirmed==null && "unknown".equals(op.getString(8))
                            && !receipt.has("nativeDeviceAcceptedBinding") && !receipt.optBoolean("accepted")
                            && !Boolean.TRUE.equals(receipt.optJSONObject("receipt")==null ? null : receipt.getJSONObject("receipt").opt("accepted"))
                            && !"not_dispatched".equals(receipt.opt("effectState")) && !receipt.optBoolean("notDispatched")
                            && !receipt.optBoolean("notDispatchedConfirmed") && intent.matches("[a-f0-9]{64}")) {
                        long claimedAt=DeviceProtocol.integer(send.opt("claimedAt"),"原本机发送登记时间",1,8_640_000_000_000_000L);
                        unconfirmed=new UnconfirmedDispatch(operation,tool.toolName(),claimedAt);
                    }
                    if (!accepted.isEmpty() && (!includeUnconfirmed || unconfirmed!=null)) break;
                } catch (Exception invalid) { /* Incomplete evidence does not become a displayed receipt. */ }
            }
        }
        return new WorkspaceOperations(accepted,unconfirmed);
    }
}
