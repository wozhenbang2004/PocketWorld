package dev.pocketengine.features.devices;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.json.JSONObject;

/** Human history only. Never a receipt used by TaskStore to permit a write, resume bytes or retry. */
final class OwnerWindowsFileDelivery {
    private static final String PROFILE="windows-original-file-delivery-v1";
    private static boolean eq(JSONObject a,String ak,JSONObject b,String bk){return a.opt(ak) instanceof String x&&x.equals(b.opt(bk));}
    private static boolean integer(Object o){return o instanceof Integer||o instanceof Long;}
    static boolean source(JSONObject wire,String thisDevice,String thisInstance){
        try{
            JSONObject call=wire.getJSONObject("arguments"),a=call.getJSONObject("arguments"),d=a.getJSONObject("artifact");
            DeviceProtocol.validateMessage(d);
            return "tool.invoke".equals(wire.opt("method"))&&"workspace-v1".equals(wire.opt("capabilityVersion"))
                &&"workspace".equals(wire.getJSONObject("resourceBinding").opt("kind"))&&"workspace.receive".equals(call.opt("toolName"))
                &&"write".equals(call.opt("effect"))&&DeviceProtocol.objectKeys(a).equals(Set.of("path","expectedVersion","artifact"))
                &&a.opt("path") instanceof String p&&!p.isEmpty()&&p.length()<=2048
                &&a.opt("expectedVersion") instanceof String v&&v.matches("absent|sha256:[a-f0-9]{64}")
                &&thisDevice.equals(wire.opt("callerDeviceId"))&&thisDevice.equals(wire.opt("originDeviceId"))&&eq(wire,"rootTaskId",wire,"parentTaskId")
                &&"ArtifactDescriptor".equals(d.opt("messageType"))&&"file".equals(d.opt("kind"))
                &&thisDevice.equals(d.opt("executorDeviceId"))&&thisInstance.equals(d.opt("instanceId"))
                &&eq(d,"operationId",wire,"operationId")&&eq(d,"taskId",wire,"localTaskId")&&eq(d,"requestRunId",wire,"runId")
                &&integer(d.opt("requestExecutorEpoch"))&&d.getLong("requestExecutorEpoch")==wire.getLong("executorEpoch")
                &&!d.has("runId")&&!d.has("executorEpoch")&&eq(d,"versionId",d,"contentDigest")&&integer(d.opt("size"))
                &&0<=d.getLong("size")&&d.getLong("size")<=262144;
        }catch(Exception ignored){return false;}
    }
    static JSONObject checked(JSONObject wire,JSONObject r,String thisDevice,String thisInstance){
        try{
            if(!source(wire,thisDevice,thisInstance))return null;
            JSONObject args=wire.getJSONObject("arguments").getJSONObject("arguments"),d=args.getJSONObject("artifact"),
                a=r.getJSONObject("audience"),p=r.getJSONObject("payload"),w=p.getJSONObject("windowsFileReceipt");
            if(!"OperationReceipt".equals(r.opt("messageType"))||!Boolean.TRUE.equals(r.opt("accepted"))||p.optBoolean("managementOnly")
                ||r.has("cancellation")||r.has("localTaskId")||r.has("runId")||r.has("executorEpoch")
                ||!eq(r,"operationId",wire,"operationId")||!eq(r,"argumentsDigest",wire,"argumentsDigest")||!eq(r,"executorDeviceId",wire,"targetDeviceId")
                ||!eq(r,"instanceId",wire,"instanceId")||!eq(a,"objectId",r,"operationId")||!eq(a,"requestRunId",wire,"runId")
                ||!integer(a.opt("requestExecutorEpoch"))||a.getLong("requestExecutorEpoch")!=wire.getLong("executorEpoch")
                ||!DeviceProtocol.resourceEquals(wire.getJSONObject("resourceBinding"),a.getJSONObject("resourceBinding")))return null;
            for(String key:new String[]{"originDeviceId","callerDeviceId","targetDeviceId","instanceId","rootTaskId","localTaskId","grantId"})if(!eq(a,key,wire,key))return null;
            if(!integer(a.opt("authorizationEpoch"))||a.getLong("authorizationEpoch")!=wire.getLong("authorizationEpoch"))return null;
            if(!PROFILE.equals(w.opt("profile"))||!"workspace.receive".equals(w.opt("tool"))
                ||!Boolean.FALSE.equals(w.opt("targetReread"))||!Boolean.FALSE.equals(w.opt("transferRepeated"))
                ||!eq(w,"operationId",r,"operationId")||!eq(w,"argumentsDigest",r,"argumentsDigest")||!eq(w,"jobId",r,"jobId")
                ||!eq(w,"environmentId",a.getJSONObject("resourceBinding"),"environmentId")
                ||!integer(w.opt("updatedAt"))||w.getLong("updatedAt")<a.getLong("acceptedAt"))return null;
            String phase=w.getString("phase");
            JSONObject safe=new JSONObject().put("phase",phase).put("operationId",r.getString("operationId")).put("jobId",r.getString("jobId"))
                .put("environmentId",w.getString("environmentId")).put("updatedAt",w.getLong("updatedAt"));
            if("receipt_only".equals(phase))return safe;
            String path=args.getString("path"),sha=d.getString("contentDigest"),version="sha256:"+sha;long size=d.getLong("size");
            if(!Set.of("receiving","not_dispatched","target_unconfirmed","delivered","write_verified_only").contains(phase)
                ||!integer(w.opt("revision"))||w.getLong("revision")<=0||!integer(w.opt("bytes"))||w.getLong("bytes")!=size
                ||!eq(w,"transferId",d,"transferId")||!sha.equals(w.opt("sha256"))||!eq(w,"expectedVersion",args,"expectedVersion")
                ||!DeviceProtocol.sha256(DeviceProtocol.canonicalBytes(args)).equals(w.opt("inputDigest"))
                ||!DeviceProtocol.sha256(path.getBytes(StandardCharsets.UTF_8)).equals(w.opt("pathDigest"))
                ||!path.equals(w.opt("path"))||path.codePoints().anyMatch(c->Character.isISOControl(c)||Character.getType(c)==Character.FORMAT))return null;
            if("receiving".equals(phase)){
                if(!"running".equals(r.opt("status"))||!integer(w.opt("verifiedOffset"))
                    ||w.getLong("verifiedOffset")<0||w.getLong("verifiedOffset")>size)return null;
                safe.put("verifiedOffset",w.getLong("verifiedOffset"));
            }else{
                JSONObject f=p.getJSONObject("originalFileReceipt");
                if(!eq(f,"operationId",r,"operationId")||!eq(f,"environmentId",w,"environmentId")||!eq(f,"path",args,"path")
                    ||!version.equals(f.opt("desiredVersion"))
                    ||!"optimistic_raw_bytes_cas".equals(f.opt("consistency")))return null;
                boolean mismatch=Boolean.FALSE.equals(f.opt("targetConditionMatched"))&&eq(f,"reviewedVersion",args,"expectedVersion")&&!f.has("previousVersion");
                if(!mismatch&&!eq(f,"previousVersion",args,"expectedVersion")||mismatch&&!"not_dispatched".equals(phase))return null;
                if("not_dispatched".equals(phase)){
                    if(!Boolean.valueOf(mismatch).equals(w.opt("targetConditionFailed")))return null;
                    safe.put("targetConditionFailed",mismatch);
                    if(!"failed".equals(r.opt("status"))||!"not_dispatched".equals(r.opt("effectState"))||!"failed".equals(f.opt("status"))
                        ||!Boolean.FALSE.equals(f.opt("replacementAttempted"))||!Boolean.FALSE.equals(f.opt("verified"))
                        ||!Boolean.FALSE.equals(p.opt("delivered")))return null;
                }else if("target_unconfirmed".equals(phase)){
                    if(!"unknown".equals(f.opt("status"))||Boolean.TRUE.equals(f.opt("verified")))return null;
                }else if(!version.equals(w.opt("version"))||!"completed".equals(f.opt("status"))||!Boolean.TRUE.equals(f.opt("verified"))
                    ||!Boolean.TRUE.equals(f.opt("replacementAttempted"))||!version.equals(f.opt("version"))
                    ||!integer(f.opt("bytes"))||f.getLong("bytes")!=size)return null;
                if("delivered".equals(phase)||"write_verified_only".equals(phase)){
                    JSONObject n=f.getJSONObject("nativeReplacement"),prev=n.getJSONObject("beforeObject"),bak=n.getJSONObject("objects").getJSONObject("backup");
                    Object old=n.get("beforeSha256"); // get, not opt/isNull: a missing proof is NOT an absent destination.
                    if("absent".equals(args.opt("expectedVersion"))){
                        if(old!=JSONObject.NULL||prev.length()!=1||!"absent".equals(prev.opt("state"))||bak.length()!=1||!"not_requested".equals(bak.opt("state")))return null;
                    }else if(!(old instanceof String digest)||!("sha256:"+digest).equals(args.opt("expectedVersion"))
                            ||!digest.equals(prev.opt("sha256"))||!digest.equals(bak.opt("sha256")))return null;
                }
                if("delivered".equals(phase)&&(!"succeeded".equals(r.opt("status"))||!Boolean.TRUE.equals(p.opt("delivered"))
                        ||!eq(p,"transferId",d,"transferId")||!sha.equals(p.opt("sha256"))))return null;
                if("write_verified_only".equals(phase)&&!Set.of("unknown","interrupted","running").contains(r.optString("status")))return null;
            }
            return safe.put("path",path).put("sha256",sha).put("bytes",size).put("newFile","absent".equals(args.opt("expectedVersion")));
        }catch(Exception ignored){return null;}
    }
    private OwnerWindowsFileDelivery(){}
}
