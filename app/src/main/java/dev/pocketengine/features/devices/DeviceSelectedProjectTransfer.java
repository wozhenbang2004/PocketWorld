package dev.pocketengine.features.devices;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.local.LocalModule;
import dev.pocketengine.features.local.project.ProjectPackageDelivery;
import dev.pocketengine.features.devices.transport.DeviceArtifactTransfer;
import dev.pocketengine.store.TaskStore;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Original native package evidence/catalog only. Ordinary attachments and ZIPs cannot enter here. */
final class DeviceSelectedProjectTransfer {
    static final long MAX_BYTES=20L*1024*1024;
    private DeviceSelectedProjectTransfer() { }
    static JSONArray catalog(TaskStore tasks,String taskId,LocalModule local)throws Exception{
        JSONArray out=new JSONArray();if(local==null)return out;JSONArray evidence=tasks.deviceTasks().projectPackageCandidatesForOwner(taskId);
        for(int i=0;i<evidence.length();i++)try{JSONObject fixed=evidence.getJSONObject(i),meta=local.handle("project.package.get",new JSONObject().put("artifactId",fixed.getJSONObject("packageArtifact").getString("id")).put("sha256",fixed.getString("archiveSha256")));var ref=ProjectPackageDelivery.requireArtifact(fixed,meta);
            out.put(new JSONObject().put("id",ref.artifactId).put("sha256",ref.archiveSha256).put("name",meta.optString("name","项目包")).put("size",ref.archiveBytes).put("manifestDigest",ref.manifestIdentity).put("manifest",meta.getJSONObject("manifest")).put("sourceTaskId",ref.taskId).put("sourceOperationId",ref.operationId).put("available",true).put("maximumBytes",MAX_BYTES));
        }catch(Exception retired){/* A retired/unverified artifact is not an eligible native candidate. */}return out;
    }
    static ProjectPackageDelivery.Reference require(TaskStore tasks,LocalModule local,JSONObject selected,RunControl control)throws Exception{
        control.check();if(local==null)throw new SecurityException("原项目模块不可用");JSONObject evidence=tasks.projectPackageEvidenceForOwner(selected.getString("sourceTaskId"),selected.getString("sourceOperationId"));JSONObject actual=local.handle("project.package.get",new JSONObject().put("artifactId",selected.getString("id")).put("sha256",selected.getString("sha256")));var ref=ProjectPackageDelivery.requireArtifact(evidence,actual);
        if(!ref.artifactId.equals(selected.getString("id"))||!ref.archiveSha256.equals(selected.getString("sha256"))||ref.archiveBytes!=selected.getLong("size")||!ref.manifestIdentity.equals(selected.getString("manifestDigest"))||!DeviceProtocol.canonical(actual.getJSONObject("manifest")).equals(DeviceProtocol.canonical(selected.getJSONObject("manifest"))))throw new SecurityException("原所选项目包/清单已变化");control.check();return ref;
    }
    static JSONArray resolve(TaskStore tasks,LocalModule local,JSONArray catalog,List<NativeDeviceTaskScopeHost.ProjectChoice> choices)throws Exception{
        if(choices.size()>1)throw new SecurityException("每个目标一次仅选择一个固定项目包");JSONArray out=new JSONArray();for(var choice:choices){JSONObject found=null;for(int i=0;i<catalog.length();i++)if(catalog.getJSONObject(i).getString("id").equals(choice.artifactId())&&catalog.getJSONObject(i).getString("sha256").equals(choice.sha256()))found=catalog.getJSONObject(i);if(found==null)throw new SecurityException("项目包不是本窗口原生候选");DeviceSelectedFileTransfer.validateTarget(choice.path(),"absent");require(tasks,local,found,new RunControl());out.put(new JSONObject(found.toString()).put("path",choice.path()).put("expectedVersion","absent"));}return out;
    }
    static JSONObject selected(JSONObject approval,String id)throws Exception{JSONArray list=approval.optJSONArray("projects");for(int i=0;list!=null&&i<list.length();i++)if(list.getJSONObject(i).getString("id").equals(id))return list.getJSONObject(i);throw new SecurityException("项目包不在原主人选材范围");}
    static JSONArray contracts(JSONObject approval)throws Exception{JSONObject surrogate=new JSONObject().put("files",approval.optJSONArray("projects")==null?new JSONArray():approval.getJSONArray("projects"));return DeviceSelectedFileTransfer.contracts(surrogate);}
    static JSONObject descriptor(JSONObject project,JSONObject wire,DeviceIdentity.LocalIdentity local)throws Exception{
        String transfer=java.util.UUID.nameUUIDFromBytes(("workspace.project.receive\0"+wire.getString("operationId")).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        var ref=new DeviceArtifactTransfer.Reference(new JSONObject().put("transferId",transfer).put("sourceDeviceId",local.deviceId()).put("artifactId",project.getString("id")).put("versionId",project.getString("sha256")).put("taskId",wire.getString("localTaskId")).put("operationId",wire.getString("operationId")).put("sha256",project.getString("sha256")).put("bytes",project.getLong("size")).put("kind","project_bundle").put("mimeType","application/zip").put("name","project-source.zip"));
        return DeviceArtifactTransfer.toWireDescriptor(ref,local.instanceId(),1,null,null,wire.getString("runId"),wire.getLong("executorEpoch"));
    }
    static byte[] bytes(TaskStore tasks,LocalModule local,JSONObject selected,RunControl control)throws Exception{
        var ref=require(tasks,local,selected,control);java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream((int)ref.archiveBytes);
        local.exportProjectPackageForOwner(ref.artifactId,ref.archiveSha256,new java.io.OutputStream(){public void write(int b)throws java.io.IOException{write(new byte[]{(byte)b},0,1);}public void write(byte[] b,int off,int len)throws java.io.IOException{try{control.check();if((long)bytes.size()+len>ref.archiveBytes)throw new SecurityException("原项目包字节超限");bytes.write(b,off,len);}catch(Exception revoked){throw new java.io.IOException(revoked);}}});
        require(tasks,local,selected,control);byte[] result=bytes.toByteArray();if(result.length!=ref.archiveBytes||!DeviceProtocol.sha256(result).equals(ref.archiveSha256))throw new SecurityException("原项目包导出字节不一致");return result;
    }
}
