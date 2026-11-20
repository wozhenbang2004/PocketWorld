package dev.pocketengine.features.devices.transport;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.attachments.AttachmentBook;
import dev.pocketengine.store.TaskStoreDeviceTasks;
import org.json.JSONObject;

/** Original receiving phone only. Computes the first-party publication identity from stored SQL;
 * never searches other attachments, invokes a route, reads remote bytes or sends an acknowledgement. */
public final class OwnerReceivedFileBook {
    private OwnerReceivedFileBook(){}
    private static String display(String name){
        StringBuilder text=new StringBuilder();name.codePoints().forEach(c->{int t=Character.getType(c);text.appendCodePoint(
            t==Character.FORMAT||t==Character.CONTROL||t==Character.SURROGATE?0xfffd:c);});return text.toString();
    }
    private static DeviceArtifactTransfer.Reference directFile(JSONObject w,String task,String op,JSONObject descriptor)throws Exception{
        DeviceArtifactTransfer.Reference file=DeviceArtifactTransfer.fromWireDescriptor(descriptor);
        if(!"file".equals(file.json().getString("kind"))||!file.sha256.equals(file.versionId)||!op.equals(file.operationId)||!task.equals(file.taskId)
                ||!w.getString("targetDeviceId").equals(file.sourceDeviceId)||!w.getString("runId").equals(file.json().getString("requestRunId"))
                ||w.getInt("executorEpoch")!=file.json().getInt("requestExecutorEpoch"))throw new SecurityException("不是这次固定导出");
        return file;
    }
    /** Inventory proof only, not a file reader, a provider success verdict, or a new result publication.
     * The sender identity comes from the already bound original native wire, not this phone's current connection. */
    public static JSONObject savedFileProvenance(TaskStoreDeviceTasks.OwnerHistoryEvidence original,JSONObject metadata,RunControl control)throws Exception{
        control.checkpoint();JSONObject wire=original.originalWire(),source=metadata.getJSONObject("source");
        String task=original.taskId(),op=original.operationId(),run=wire.getString("runId");int epoch=wire.getInt("executorEpoch");
        if(!"owner".equals(metadata.opt("principalId"))||!"private".equals(metadata.opt("spaceId"))||metadata.optBoolean("outputSourceRetired")
                ||!"device_received_file".equals(source.opt("kind"))||!"file".equals(source.opt("artifactKind"))||source.has("groupSpaceId")||epoch<1)
            throw new SecurityException("不是本机保留的原普通文件");
        DeviceArtifactTransfer.Reference file=directFile(wire,task,op,original.exportedFileForLocalBook(wire.getString("callerDeviceId")));
        String role=DeviceReceivedAttachmentPublisher.role(file),plan=DeviceReceivedAttachmentPublisher.plan(file,task,run,epoch);
        JSONObject exact=DeviceReceivedAttachmentPublisher.source(file,task,run,epoch).put("nativePublication",new JSONObject()
                .put("operationId",op).put("role",role).put("taskId",task).put("runId",run).put("planDigest",plan));
        if(!AttachmentBook.isOriginalPublicationId(metadata.getString("id"),op,role,task,run,plan)
                ||!file.sha256.equals(metadata.opt("sha256"))||metadata.getLong("size")!=file.bytes||!dev.pocketengine.features.devices.DeviceProtocol.resourceEquals(exact,source))
            throw new SecurityException("本机登记不再属于原任务的固定文件");
        original.requireCurrent();control.checkpoint();
        return new JSONObject().put("sourceTaskId",task).put("sourceTaskRunId",run).put("sourceCoordinatorEpoch",epoch)
                .put("sourceOperationId",op).put("sourceRunEvidence","original_device_file_receive");
    }
    public static JSONObject check(AttachmentBook book,TaskStoreDeviceTasks.OwnerHistoryEvidence original,String local,RunControl io)throws Exception{
        io.checkpoint();if(!original.directExportForLocalBook(local))throw new SecurityException("只从原机主文件导出检查本机附件");
        JSONObject w=original.originalWire();String task=original.taskId(),op=original.operationId(),run=w.getString("runId");int epoch=w.getInt("executorEpoch");
        JSONObject out=new JSONObject().put("format","pocketworld.owner-local-received-file.v1").put("taskId",task).put("operationId",op)
            .put("targetDeviceId",w.getString("targetDeviceId")).put("instanceId",w.getString("instanceId")).put("argumentsDigest",w.getString("argumentsDigest"))
            .put("requestRunId",run).put("requestExecutorEpoch",epoch);
        JSONObject descriptor;
        try{descriptor=original.exportedFileForLocalBook(local);}
        catch(Exception unavailable){original.requireCurrent();io.checkpoint();return out.put("state","no_verified_descriptor");}
        try{
            DeviceArtifactTransfer.Reference file=directFile(w,task,op,descriptor);
            AttachmentBook.VerifiedMetadata found=book.verifiedPrivateReceivedOriginal(op,DeviceReceivedAttachmentPublisher.role(file),task,run,
                DeviceReceivedAttachmentPublisher.plan(file,task,run,epoch),DeviceReceivedAttachmentPublisher.source(file,task,run,epoch),file.sha256,file.bytes,io);
            original.requireCurrent();io.checkpoint();
            if(found==null)return out.put("state","no_current_publication");
            return out.put("state","verified_here").put("attachmentId",found.id).put("name",display(found.name)).put("bytes",found.size).put("sha256",found.sha256)
                .put("sourceArtifactId",file.artifactId).put("sourceTransferId",file.transferId).put("checkedAt",System.currentTimeMillis());
        }catch(Exception changed){original.requireCurrent();io.checkpoint();return out.put("state","not_verified");}
    }
}
