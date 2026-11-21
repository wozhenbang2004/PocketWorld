package dev.pocketengine.features.devices;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.devices.transport.DeviceArtifactTransfer;
import dev.pocketengine.store.TaskStore;
import dev.pocketengine.store.TaskStoreDeviceTasks;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** Explicit local owner history UI, never a model tool or an execution-scope fallback. */
public final class NativeDeviceTaskHistoryHost {
    public interface NativeReads {
        /** Restore READ-only route using the actual currently authenticated target and original
         * persisted grant receipt; never reconstruct an execution OutgoingGrant from JSON. */
        PairingCoordinator.HistoryRoute currentHistoryRoute(String targetDeviceId,String originalGrantId)throws Exception;
        DeviceCapabilityPort.Reply readHistory(DeviceTaskEnvelope freshQuery,RunControl ioCancellation)throws Exception;
        default DeviceCapabilityPort.Reply readPhoneGoal(PairingCoordinator.HistoryQuery query,RunControl io)throws Exception{
            throw new SecurityException("原目标读取通道未开放。");
        }
    }
    private final TaskStore tasks;private final DeviceIdentity.LocalIdentity local;private final NativeReads source;
    public NativeDeviceTaskHistoryHost(TaskStore originalTasks,DeviceIdentity.LocalIdentity existingLocalIdentity,NativeReads source){tasks=java.util.Objects.requireNonNull(originalTasks);local=java.util.Objects.requireNonNull(existingLocalIdentity);this.source=java.util.Objects.requireNonNull(source);}
    public Window open(String originalTaskId,String originalOperationId)throws Exception{return new Window(tasks.deviceTasks().ownerHistoryEvidence(originalTaskId,originalOperationId));}

    @FunctionalInterface interface WindowsTextRead {
        DeviceCapabilityPort.Reply read(TaskStoreDeviceTasks.OwnerWindowsTextEvidence original, DeviceArtifactTransfer.Current current)throws Exception;
    }
    /** The caller owns the current private Work window. No generic History/stop window, and no journal update. */
    static JSONObject readOriginalWindowsText(TaskStore tasks,DeviceIdentity.LocalIdentity local,String task,String run,int epoch,String operation,
                                             DeviceArtifactTransfer.Current current,WindowsTextRead reader)throws Exception {
        current.require();
        var original=tasks.deviceTasks().ownerWindowsTextEvidence(task,run,epoch,operation,local.deviceId());
        var reply=reader.read(original,current);
        current.require();
        String knownJob=original.requireCurrentJob();
        JSONObject value=windowsTextSnapshot(original,reply,knownJob);
        current.require();
        return value;
    }

    private static JSONObject windowsTextSnapshot(TaskStoreDeviceTasks.OwnerWindowsTextEvidence original, DeviceCapabilityPort.Reply reply,
                                                  String knownJob)throws Exception {
        var wire=original.wire(); var target=original.target();
        JSONObject response=DeviceProtocol.validateMessage(reply.response());
        String unavailable="原电脑没有提供可核对的原短文本，不能据此判断文件没有内容。";
        if(!target.deviceId().equals(reply.peer().deviceId())||!target.certificateFingerprint().equals(reply.peer().certificateFingerprint())
                ||!"DeviceResponse".equals(response.opt("messageType"))||!"operation.get".equals(response.opt("method"))
                ||!target.deviceId().equals(response.opt("executorDeviceId"))||!target.instanceId().equals(response.opt("instanceId")))
            throw new SecurityException(unavailable);
        JSONObject receipt=response.getJSONObject("result"); String job=receipt.optString("jobId");
        if(!"OperationReceipt".equals(receipt.opt("messageType"))||!Boolean.TRUE.equals(receipt.opt("accepted"))
                ||!wire.operationId().equals(receipt.opt("operationId"))||!wire.argumentsDigest().equals(receipt.opt("argumentsDigest"))
                ||!job.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")||!knownJob.isEmpty()&&!knownJob.equals(job)
                ||receipt.has("localTaskId")||receipt.has("runId")||receipt.has("executorEpoch")||receipt.has("cancellation")
                ||!"succeeded".equals(receipt.opt("status"))||!"reported".equals(receipt.opt("effectState")))throw new SecurityException(unavailable);
        JSONObject audience=receipt.getJSONObject("audience"), frozen=wire.json();
        for(String field:new String[]{"rootTaskId","localTaskId","originDeviceId","callerDeviceId","targetDeviceId","instanceId","grantId","authorizationEpoch","argumentsDigest"})
            if(!DeviceProtocol.canonical(frozen.opt(field)).equals(DeviceProtocol.canonical(audience.opt(field))))throw new SecurityException(unavailable);
        if(!wire.runId().equals(audience.opt("requestRunId"))||wire.executorEpoch()!=audience.optLong("requestExecutorEpoch")
                ||!DeviceProtocol.resourceEquals(wire.resourceBinding(),audience.getJSONObject("resourceBinding")))throw new SecurityException(unavailable);

        JSONObject payload=receipt.getJSONObject("payload"), snapshot=payload.optJSONObject("windowsReadReceipt"), data=payload.optJSONObject("nativeToolResult");
        String path=wire.toolInvocation().arguments().getString("path");
        if(snapshot==null||data==null||!"completed".equals(payload.opt("status"))||!"device_tool".equals(payload.opt("worker"))
                ||!Boolean.TRUE.equals(payload.opt("historicalReadOnly"))||!Boolean.FALSE.equals(payload.opt("taskControl"))
                ||!wire.originDeviceId().equals(payload.opt("originalOriginDeviceId"))||wire.authorizationEpoch()!=payload.optLong("acceptedAuthorizationEpoch")
                ||!"windows-workspace-read-receipt-v1".equals(snapshot.opt("profile"))||!"workspace.read".equals(snapshot.opt("tool"))
                ||!"text_snapshot".equals(snapshot.opt("phase"))||!Boolean.FALSE.equals(snapshot.opt("readAgain"))
                ||!job.equals(snapshot.opt("jobId"))||!job.equals(payload.opt("id"))
                ||!wire.operationId().equals(snapshot.opt("operationId"))||!wire.operationId().equals(payload.opt("operationId"))
                ||!wire.argumentsDigest().equals(snapshot.opt("argumentsDigest"))
                ||!wire.resourceBinding().getString("environmentId").equals(snapshot.opt("environmentId"))
                ||!DeviceProtocol.sha256(path.getBytes(StandardCharsets.UTF_8)).equals(snapshot.opt("pathDigest"))||!path.equals(data.opt("path")))
            throw new SecurityException(unavailable);
        long bytes=DeviceProtocol.integer(snapshot.opt("bytes"),"原文本字节",0,24576);
        if(bytes>1024)throw new IllegalStateException("这份原文本超过此处的 1 KiB 阅读范围，未显示正文，也没有重读文件。");
        long at=DeviceProtocol.integer(snapshot.opt("updatedAt"),"原文本时间",receipt.getLong("acceptedAt"),8_640_000_000_000_000L);
        DeviceProtocol.integer(snapshot.opt("revision"),"原文本版本",1,DeviceProtocol.MAX_SAFE_INTEGER);
        if(!(data.opt("content") instanceof String content)||!(snapshot.opt("part") instanceof String text)
                ||!Boolean.FALSE.equals(snapshot.opt("partial"))
                ||bytes!=DeviceProtocol.integer(data.opt("bytes"),"原文件字节",0,1024)||!displayText(content).equals(text))throw new SecurityException(unavailable);
        byte[] raw=content.getBytes(StandardCharsets.UTF_8); String version="sha256:"+DeviceProtocol.sha256(raw);
        if(bytes!=raw.length||!version.equals(snapshot.opt("version"))||!version.equals(data.opt("version")))throw new SecurityException(unavailable);
        if(text.getBytes(StandardCharsets.UTF_8).length>1024)throw new IllegalStateException("原文本转成可显示文字后超过 1 KiB，未显示正文，也没有重读文件。");
        return new JSONObject().put("format","pocketworld.original-windows-text.v1").put("readOnly",true)
                .put("taskId",wire.parentTaskId()).put("runId",wire.runId()).put("epoch",wire.executorEpoch()).put("operationId",wire.operationId())
                .put("deviceId",target.deviceId()).put("instanceId",target.instanceId()).put("bytes",bytes).put("version",version)
                .put("recordedAt",at).put("text",text);
    }

    private static String displayText(String original) {
        // Same plain-text representation as the Windows retained snapshot, not a new file read.
        StringBuilder out=new StringBuilder();
        original.replace("\r\n","\n").replace('\r','\n').codePoints().forEach(cp->{
            int type=Character.getType(cp);
            if(cp!='\n'&&cp!='\t'&&(type==Character.CONTROL||type==Character.FORMAT||type==Character.SURROGATE
                    ||type==Character.PRIVATE_USE||type==Character.UNASSIGNED))out.append('\uFFFD');
            else out.appendCodePoint(cp);
        });
        return out.toString();
    }

    public final class Window implements AutoCloseable {
        private final TaskStoreDeviceTasks.OwnerHistoryEvidence original;private final RunControl io=new RunControl();private volatile boolean closed;
        private Window(TaskStoreDeviceTasks.OwnerHistoryEvidence original){this.original=original;}
        private void require()throws Exception{if(closed)throw new SecurityException("原历史窗口已关闭");io.check();original.requireCurrent();}
        public JSONObject view()throws Exception{
            require();JSONObject view=original.view(),wire=original.originalWire();
            JSONObject invocation=wire.optJSONObject("arguments");
            if("tool.invoke".equals(wire.optString("method")) && invocation!=null
                && "workspace-v1".equals(wire.optString("capabilityVersion"))
                && "workspace".equals(original.resourceBinding().optString("kind")) && local.deviceId().equals(wire.optString("originDeviceId"))
                && local.deviceId().equals(wire.optString("callerDeviceId")) && original.taskId().equals(wire.optString("rootTaskId"))
                && original.taskId().equals(wire.optString("parentTaskId"))){
                String tool=invocation.optString("toolName"),effect=invocation.optString("effect");
                if("command.run".equals(tool)&&"write".equals(effect)){
                    view.put("directWorkspaceCommand",true);
                    String name=privateCommandName(wire,invocation);
                    if(name!=null)view.put("originalRequestedCommandName",name); // own fixed source request, not a receiver result or a tool declaration
                }
                if("workspace.export".equals(tool)&&"read".equals(effect)&&original.directExportForLocalBook(local.deviceId()))
                    view.put("canCheckLocalExportedFile",true).put("exportRequestRunId",wire.getString("runId")).put("exportRequestEpoch",wire.getInt("executorEpoch"));
                if(OwnerWindowsFileDelivery.source(wire,local.deviceId(),local.instanceId()))view.put("directWorkspaceFileDelivery",true);
                if(("workspace.read".equals(tool)||"workspace.list".equals(tool))&&"read".equals(effect)){
                    JSONObject arguments=invocation.optJSONObject("arguments");
                    Object path=arguments==null?null:arguments.has("path")?arguments.opt("path"):"workspace.list".equals(tool)?"":null;
                    if(path instanceof String relative&&relative.length()<=2048)
                        view.put("directWorkspaceRead",tool).put("directWorkspaceReadPathDigest",DeviceProtocol.sha256(relative.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                }
            }
            if(directPhoneGoal(wire))view.put("phoneGoalResult",true).put("localPhoneResultPath",localPhoneResultPath(wire));
            require();return view;
        }
        private String privateCommandName(JSONObject wire,JSONObject invocation)throws Exception{
            if(!"ToolInvocation".equals(wire.optString("messageType"))||!original.taskId().equals(wire.optString("localTaskId"))
                ||!original.operationId().equals(wire.optString("operationId"))||!DeviceProtocol.argumentsDigest(wire).equals(wire.opt("argumentsDigest")))return null;
            JSONObject parent=tasks.get(original.taskId(),false);
            if(parent.optJSONObject("origin")!=null||!"owner".equals(parent.optString("actorId"))||!"private".equals(parent.optString("spaceId")))return null;
            JSONObject args=invocation.optJSONObject("arguments");org.json.JSONArray words=args==null?null:args.optJSONArray("argv");
            if(words==null||words.length()<1||words.length()>80)return null;
            Object first=words.opt(0);return first instanceof String alias&&alias.matches("[A-Za-z][A-Za-z0-9_-]{0,47}")?alias:null;
        }
        public JSONObject checkLocalExportedFile(dev.pocketengine.features.attachments.AttachmentBook book)throws Exception{
            require();JSONObject result=dev.pocketengine.features.devices.transport.OwnerReceivedFileBook.check(book,original,local.deviceId(),io);require();return result;
        }
        private boolean directPhoneGoal(JSONObject wire) {
            return DeviceProtocol.isDirectPhoneGoal(wire,local.deviceId(),original.taskId(),original.operationId());
        }
        private static final class NoAcceptedRun extends IllegalStateException {
            NoAcceptedRun(){super("还没有这份目标的已核实执行编号。先读取原派发回执；不会另开任务。");}
        }
        /** Navigation only. Never connect to a peer or imply task.get permission or a finished result. */
        private String localPhoneResultPath(JSONObject wire)throws Exception{
            try { phoneGoalBinding(wire);return "original_run"; }
            catch(NoAcceptedRun pending){return "need_dispatch_receipt";}
            catch(SecurityException mismatch){return "unverified_source";}
        }
        /** The permitted object comes from the original stored, authenticated acceptance, not UI input.
         * A deterministic child ID or an accepted submission without a run is not a result binding. */
        private JSONObject phoneGoalBinding(JSONObject wire)throws Exception{
            require();if(!directPhoneGoal(wire))throw new SecurityException("这不是本机直接委派给指定手机的原目标。");
            JSONObject parent=tasks.get(original.taskId(),false);
            if(parent.optJSONObject("origin")!=null||!"owner".equals(parent.optString("actorId"))||!"private".equals(parent.optString("spaceId")))
                throw new SecurityException("只能从本机机主的原工作读取这份目标。");
            JSONObject allocation=tasks.deviceTasks().childAllocationForOperation(original.taskId(),original.operationId());
            JSONObject binding=allocation.optJSONObject("childBinding");
            if(!original.operationId().equals(allocation.optString("anchorOperationId")))throw new SecurityException("这条记录不是原目标派发");
            if(binding==null)throw new NoAcceptedRun();
            String child=DeviceProtocol.receiverTaskId(wire);
            if(!original.operationId().equals(allocation.optString("anchorOperationId"))||!"task".equals(binding.optString("acceptedObjectKind"))
                ||!original.taskId().equals(binding.optString("parentTaskId"))||!original.operationId().equals(binding.optString("sourceOperationId"))
                ||!original.operationId().equals(binding.optString("parentOperationId"))||!wire.getString("argumentsDigest").equals(binding.optString("operationArgumentsDigest"))
                ||!original.targetDeviceId().equals(binding.optString("executorDeviceId"))||!wire.getString("instanceId").equals(binding.optString("instanceId"))
                ||!child.equals(binding.optString("objectId"))||!child.equals(binding.optString("localTaskId")))
                throw new SecurityException("原派发与目标设备、任务、实例的接受回执不一致，未读取结果。");
            if(binding.optString("runId").isEmpty()||binding.optLong("executorEpoch")<=0)throw new NoAcceptedRun();
            return new JSONObject().put("taskId",child).put("runId",binding.getString("runId")).put("executorEpoch",binding.getLong("executorEpoch"));
        }
        /** Explicit task.get, independently authorized even if the owner can read operation.get.
         * Only a small presentation of this exact phone result crosses the Web bridge. No lease,
         * owner input, evidence object, storage write, retry or new run. */
        public JSONObject queryPhoneGoalResult()throws Exception{
            return queryPhoneGoalResult(null,()->{});
        }
        /** Same decoder and task.get, with the selected Work's stronger local pin. No
         * archive selector, generic HistoryAction, stop or acceptance query is returned. */
        JSONObject queryPhoneGoalResult(TaskStoreDeviceTasks.OwnerSentPhoneText pinned,DeviceArtifactTransfer.Current ownerCurrent)throws Exception{
            JSONObject wire=original.originalWire();
            ownerCurrent.require();
            if(pinned!=null && !DeviceProtocol.canonical(wire).equals(DeviceProtocol.canonical(pinned.wire().json())))
                throw new SecurityException("原工作与派单不再匹配，未联系手机。");
            JSONObject binding=pinned==null?phoneGoalBinding(wire):pinned.originalChild();
            PairingCoordinator.HistoryQuery query;
            try {
                PairingCoordinator.HistoryRoute route=source.currentHistoryRoute(original.targetDeviceId(),original.grantId());
                long now=System.currentTimeMillis();
                query=pinned==null?route.historyQuery(local,"task.get",binding.getString("taskId"),original.resourceBinding(),now,5000,null)
                        :route.originalAndroidTextQuery(local,pinned.wire(),binding.getString("taskId"),binding.getString("runId"),
                                binding.getLong("executorEpoch"),now);
            } catch(java.io.IOException offline){throw new java.io.IOException("原手机未连接，未读取结果。",offline);}
            catch(SecurityException denied){throw new SecurityException("原设备身份或授权已变化，或原授权不含任务结果读取（task.get）。未向对端发送读取请求。",denied);}
            if(!wire.getString("instanceId").equals(query.envelope().instanceId()))throw new SecurityException("原手机实例已变化，未读取结果。");
            if(pinned!=null && pinned.wire().authorizationEpoch()!=query.envelope().authorizationEpoch())
                throw new SecurityException("原手机读取授权已变化，未联系手机。");
            ownerCurrent.require();
            DeviceCapabilityPort.Reply actual;
            try{actual=source.readPhoneGoal(query,io);}
            catch(java.io.IOException|java.util.concurrent.TimeoutException|java.util.concurrent.ExecutionException unavailable){throw new java.io.IOException("没有收到原手机的可核对答复。连接可能中断，对端也可能未放行；不能判为没有成果。",unavailable);}
            require();ownerCurrent.require();
            try{query.requireTargetPeer(actual.peer(),System.currentTimeMillis());}
            catch(SecurityException changed){throw new SecurityException("原手机身份或读取权限已变化，不展示结果。",changed);}
            JSONObject response=DeviceProtocol.validateMessage(actual.response());
            if(!query.envelope().requestId().equals(response.optString("requestId")))throw new SecurityException("结果回执不属于这次读取。");
            if("DeviceError".equals(response.optString("messageType"))){
                String code=response.getJSONObject("error").getString("code");
                if("unauthenticated".equals(code)||"delegation_denied".equals(code)||"wrong_audience".equals(code)||"expired".equals(code))
                    throw new SecurityException("原手机未放行这次读取，不能用派发回执替代目标结果。");
                throw new IllegalStateException("原手机没有提供可核对的目标答复。不能判为没有成果。");
            }
            if(!"DeviceResponse".equals(response.optString("messageType"))||!"task.get".equals(response.optString("method"))
                ||!original.targetDeviceId().equals(response.optString("executorDeviceId"))||!wire.getString("instanceId").equals(response.optString("instanceId")))
                throw new SecurityException("结果回执不是原目标手机与实例。");
            JSONObject task=response.getJSONObject("result"),detail=task.getJSONObject("result"),origin=detail.getJSONObject("origin");
            if(!"TaskSnapshot".equals(task.optString("messageType"))||!"GOAL".equals(detail.optString("executionKind"))
                ||!binding.getString("taskId").equals(task.optString("taskId"))||!binding.getString("runId").equals(task.optString("runId"))
                ||binding.getLong("executorEpoch")!=task.optLong("executorEpoch",-1)||!original.targetDeviceId().equals(task.optString("executorDeviceId"))
                ||!wire.getString("instanceId").equals(task.optString("instanceId"))||!original.taskId().equals(detail.optString("rootTaskId"))
                ||!original.taskId().equals(detail.optString("remoteParentTaskId"))||!"paired_device".equals(origin.optString("kind"))
                ||DeviceProtocol.isAndroidTextReturn(wire)&&!DeviceProtocol.ANDROID_TEXT_RETURN.equals(origin.opt("textReturnContract"))
                ||!original.operationId().equals(origin.optString("remoteOperationId"))||!wire.getString("localTaskId").equals(origin.optString("remoteLocalTaskId"))
                ||!local.deviceId().equals(origin.optString("originDeviceId"))||!local.deviceId().equals(origin.optString("callerDeviceId"))
                ||!original.targetDeviceId().equals(origin.optString("targetDeviceId"))||!original.grantId().equals(origin.optString("grantId"))
                ||wire.getLong("authorizationEpoch")!=origin.optLong("authorizationEpoch",-1)
                ||!DeviceProtocol.canonical(binding).equals(DeviceProtocol.canonical(pinned==null?phoneGoalBinding(wire):pinned.originalChild())))
                throw new SecurityException("不是同一派发、同一设备和同一次执行的目标结果，不展示内容。");
            JSONObject outcome=detail.optJSONObject("phoneGoalOutcome");
            String review=outcome==null?"unavailable":outcome.optString("review");
            if(!java.util.Set.of("confirmed","none","pending","not_confirmed","unverified","unavailable").contains(review)
                ||outcome!=null&&(!(outcome.opt("summary") instanceof String)||!(outcome.opt("truncated") instanceof Boolean)))
                throw new SecurityException("原手机返回的成果格式不能核对。");
            boolean confirmed="confirmed".equals(review);String summary=confirmed?outcome.getString("summary"):"";
            boolean kept=outcome!=null&&outcome.has("summaryWithheld");
            if(kept&&(!Boolean.TRUE.equals(outcome.opt("summaryWithheld"))||!confirmed||!summary.isEmpty()||outcome.getBoolean("truncated")))
                throw new SecurityException("原手机的只回状态选择与摘要不一致，不展示内容");
            if(confirmed&&(!"succeeded".equals(task.optString("state"))||summary.codePointCount(0,summary.length())>2048))
                throw new SecurityException("对端确认状态与原目标的结束状态或摘要范围不一致。");
            if(!summary.isEmpty()){
                require();ownerCurrent.require();long now=System.currentTimeMillis(),until=query.originalTextDisplayUntil(actual.peer(),now);
                if(until<=now)throw new SecurityException("这次原结果读取已到期，未展示旧文字；请手动重新读取。");
                binding.put("textReadUntil",until).put("textReadRemainingMs",until-now).put("textTargetFingerprint",actual.peer().certificateFingerprint());
            }
            return binding.put("state",task.getString("state")).put("updatedAt",task.getLong("updatedAt")).put("review",review).put("summary",summary)
                .put("truncated",confirmed&&outcome.getBoolean("truncated")).put("summaryWithheld",kept?Boolean.TRUE:null)
                .put("instanceId",wire.getString("instanceId")).put("readOnly",true);
        }
        /** Original bounded IO worker only; no network IO while holding TaskStore/SQLite lock. */
        public JSONObject query()throws Exception{
            require();JSONObject frozen=original.originalWire();if(!local.deviceId().equals(frozen.getString("callerDeviceId")))throw new SecurityException("当前原生设备身份不是原SQL caller");PairingCoordinator.HistoryRoute route=source.currentHistoryRoute(original.targetDeviceId(),original.grantId());long now=System.currentTimeMillis();var query=route.historyQuery(local,"operation.get",original.operationId(),original.resourceBinding(),now,5000,null);if(!frozen.getString("instanceId").equals(query.envelope().instanceId()))throw new SecurityException("目标原实例已改变；不能把新实例空结果认作旧操作未发生");
            DeviceCapabilityPort.Reply actual=source.readHistory(query.envelope(),io);require();query.requireTargetPeer(actual.peer(),System.currentTimeMillis());JSONObject response=DeviceProtocol.validateMessage(actual.response());if(!"DeviceResponse".equals(response.getString("messageType"))||!query.envelope().requestId().equals(response.getString("requestId"))||!"operation.get".equals(response.getString("method"))||!original.targetDeviceId().equals(response.getString("executorDeviceId"))||!frozen.getString("instanceId").equals(response.getString("instanceId")))throw new SecurityException("不是这次原对象历史query的实际响应");JSONObject typed=response.getJSONObject("result");tasks.deviceTasks().recordOwnerHistory(original,actual.peer(),typed);JSONObject read=new JSONObject().put("readOnly",true).put("newRun",false).put("retrySubmitted",false).put("unknownRefunded",false).put("operationReceipt",typed).put("unknown",!typed.getBoolean("accepted")||typed.getString("effectState").equals("unknown")).put("businessVerified",false);
            JSONObject delivery=OwnerWindowsFileDelivery.checked(frozen,typed,local.deviceId(),local.instanceId());
            if(delivery!=null)read.put("originalWindowsFileDelivery",delivery);
            // Keep the signed operation receipt unchanged. Re-read only this local binding after recording it.
            if(directPhoneGoal(frozen))read.put("localPhoneResultPath",localPhoneResultPath(frozen));
            require();return read;
        }
        @Override public void close(){closed=true;io.cancel();}
    }
}
