package dev.pocketengine.features.devices;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.attachments.AttachmentBook;
import dev.pocketengine.features.devices.transport.DeviceArtifactTransfer;
import java.util.List;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Exact owner-selected Book bytes. No filesystem paths, new journal, or model-selected private material. */
final class DeviceSelectedFileTransfer {
    static final long MAX_BYTES=262144;
    private DeviceSelectedFileTransfer() { }
    static JSONArray catalog(AttachmentBook book)throws Exception{
        JSONArray out=new JSONArray();if(book==null)return out;JSONArray all=book.list();
        for(int i=0;i<all.length();i++){JSONObject item=all.getJSONObject(i);JSONObject source=item.optJSONObject("source");
            if(!"owner".equals(item.optString("principalId"))||!"private".equals(item.optString("spaceId"))||item.optBoolean("outputSourceRetired")||source!=null&&(source.has("groupSpaceId")||source.optString("kind").startsWith("group")))continue;
            out.put(new JSONObject().put("id",item.getString("id")).put("sha256",item.getString("sha256")).put("name",item.getString("name")).put("mimeType",item.getString("mimeType")).put("size",item.getLong("size")).put("available",item.getLong("size")<=MAX_BYTES).put("maximumBytes",MAX_BYTES));
        }return out;
    }
    static JSONArray resolve(AttachmentBook book,JSONArray catalog,List<NativeDeviceTaskScopeHost.FileChoice> choices)throws Exception{
        JSONArray out=new JSONArray();if(choices.size()>4)throw new SecurityException("每个准确目标最多选择4项文件");java.util.Set<String> targets=new java.util.HashSet<>(),ids=new java.util.HashSet<>();
        for(var choice:choices){JSONObject found=null;for(int i=0;i<catalog.length();i++){JSONObject item=catalog.getJSONObject(i);if(item.getString("id").equals(choice.attachmentId())&&item.getString("sha256").equals(choice.sha256()))found=item;}
            if(found==null||!found.getBoolean("available")||!targets.add(choice.path())||!ids.add(choice.attachmentId()))throw new SecurityException("不是本窗口原件或超过目标256KiB上限/重复目标");
            validateTarget(choice.path(),choice.expectedVersion());RunControl read=new RunControl();var metadata=book.readVerifiedMetadata(choice.attachmentId(),choice.sha256(),read);var eligibility=book.outputSourceEligibility(metadata.id,metadata.sha256);if(eligibility==null)throw new SecurityException("原件不是当前私人来源");
            out.put(new JSONObject(found.toString()).put("generation",eligibility.generation).put("path",choice.path()).put("expectedVersion",choice.expectedVersion()));
        }return out;
    }
    static void validateTarget(String path,String version)throws Exception{
        DeviceSourceToolContracts.validate("workspace-v1","workspace.write",new JSONObject().put("path",path).put("content","").put("expectedVersion",version));
    }
    static void revalidateReview(AttachmentBook book,JSONObject review)throws Exception{
        JSONArray routes=review.getJSONArray("routes");for(int i=0;i<routes.length();i++){JSONArray files=routes.getJSONObject(i).optJSONArray("files");for(int n=0;files!=null&&n<files.length();n++)revalidate(book,files.getJSONObject(n),new RunControl());}
    }
    static void revalidate(AttachmentBook book,JSONObject file,RunControl control)throws Exception{
        if(book==null||book.revalidateOutputSource(file.getString("id"),file.getString("sha256"),file.getString("generation"),MAX_BYTES,control)!=file.getLong("size"))throw new SecurityException("原所选文件已变化或撤下");
    }
    static JSONObject selected(JSONObject approval,String id)throws Exception{
        JSONArray files=approval.optJSONArray("files");for(int i=0;files!=null&&i<files.length();i++)if(files.getJSONObject(i).getString("id").equals(id))return files.getJSONObject(i);
        throw new SecurityException("文件不在本任务原owner明确选材范围");
    }
    static JSONArray contracts(JSONObject approval)throws Exception{
        JSONArray out=new JSONArray(),files=approval.optJSONArray("files");for(int i=0;files!=null&&i<files.length();i++){JSONObject file=files.getJSONObject(i);out.put(new JSONObject().put("type","object").put("properties",new JSONObject().put("materialId",new JSONObject().put("type","string").put("enum",new JSONArray().put(file.getString("id"))))).put("required",new JSONArray().put("materialId")).put("additionalProperties",false));}return out;
    }
    static JSONObject descriptor(JSONObject file,JSONObject wire,DeviceIdentity.LocalIdentity local)throws Exception{
        String operation=wire.getString("operationId");String transfer=UUID.nameUUIDFromBytes(("workspace.receive\0"+operation).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        var ref=new DeviceArtifactTransfer.Reference(new JSONObject().put("transferId",transfer).put("sourceDeviceId",local.deviceId()).put("artifactId",file.getString("id")).put("versionId",file.getString("sha256")).put("taskId",wire.getString("localTaskId")).put("operationId",operation).put("sha256",file.getString("sha256")).put("bytes",file.getLong("size")).put("kind","file").put("mimeType",file.getString("mimeType")).put("name",file.getString("name")));
        return DeviceArtifactTransfer.toWireDescriptor(ref,local.instanceId(),1,null,null,wire.getString("runId"),wire.getLong("executorEpoch"));
    }
}
