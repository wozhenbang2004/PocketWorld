package dev.pocketengine.features.groups.host;

import dev.pocketengine.features.groups.GroupHostPort;
import dev.pocketengine.core.RunControl;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Group-owned immutable artifact bytes, physically separate from private AttachmentRepository. */
public final class GroupArtifactStore {
    private final File root;
    public GroupArtifactStore(File privateGroupDirectory)throws Exception {root=new File(privateGroupDirectory,"artifacts");Files.createDirectories(root.toPath());}
    public synchronized JSONObject importOutput(GroupHostPort.Request request,String operationId,String mimeType,InputStream input,GroupHostPort.Authority authority)throws Exception {
        authority.check();if(!operationId.matches("[a-f0-9-]{36}"))throw new SecurityException("产物缺少原操作身份");
        return write(request.spaceId,request.actorId,request.audience,request.taskId,operationId,mimeType,input,request.maxMediaBytes,authority::check,null);
    }
    /** A proven original native MediaJobs artifact, including recovery after a newer task run. No send authority. */
    synchronized JSONObject retainOriginalMedia(GroupMediaService.OriginalArtifact original)throws Exception {
        original.check();GroupHostPort.Request request=original.request;JSONObject origin=original.origin();
        File directory=new File(root,request.spaceId.substring(6));File[] records=directory.listFiles((d,n)->n.endsWith(".json"));
        for(File file:records==null?new File[0]:records){
            original.check();JSONObject record=new JSONObject(new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8)),saved=record.optJSONObject("mediaSource");
            if(!request.taskId.equals(record.optString("taskId"))||!request.actorId.equals(record.optString("actorId"))||!request.audience.equals(record.optString("audience"))||!original.operation.equals(record.optString("operationId")))continue;
            if(saved==null){
                if(record.optLong("bytes")!=original.size||!record.optString("mimeType").equals(original.mime)||!record.optString("sha256").equals(original.sha256))continue;
                required(request.spaceId,record.getString("artifactId"),original::check);original.check();record.put("mediaSource",origin);atomic(file,record);saved=origin;
            }
            if(!origin.getString("artifactKey").equals(saved.optString("artifactKey")))continue;
            JSONObject actual=required(request.spaceId,record.getString("artifactId"),original::check);
            for(String key:new String[]{"runId","configurationRevision","provider","providerJobId","artifactKey"})if(!origin.getString(key).equals(saved.optString(key)))throw new SecurityException("原媒体产物来源冲突");
            if(actual.getLong("bytes")!=original.size||!actual.getString("mimeType").equals(original.mime)||!actual.getString("sha256").equals(original.sha256))throw new SecurityException("原媒体产物内容已变化");
            original.check();return visible(actual); // Durable canonical file wins after a crash before TaskStore's receipt was saved.
        }
        try(InputStream input=original.open()){
            JSONObject stored=write(request.spaceId,request.actorId,request.audience,request.taskId,original.operation,original.mime,input,request.maxMediaBytes,original::check,origin);
            JSONObject actual=required(request.spaceId,stored.getString("artifactId"),original::check);
            if(actual.getLong("bytes")!=original.size||!actual.getString("sha256").equals(original.sha256))throw new SecurityException("原媒体拷贝校验不符");
            original.check();return stored;
        }
    }
    /** Native authenticated-media import only; payload JSON/URLs never call this method. */
    public synchronized JSONObject importInput(String spaceId,String senderActorId,String audience,String eventId,String mimeType,InputStream input,long maxBytes,GroupHostPort.Authority nativeProof)throws Exception {
        nativeProof.check();if(eventId==null||!eventId.matches("[a-f0-9-]{36}"))throw new SecurityException("群附件缺少认证事件");
        return write(spaceId,senderActorId,audience,"",eventId,mimeType,input,Math.min(maxBytes,20*1024*1024L),nativeProof::check,null);
    }
    private JSONObject write(String space,String actor,String audience,String task,String operation,String mime,InputStream input,long maximum,ReadCheck authority,JSONObject mediaSource)throws Exception {
        requireSpace(space);if(actor==null||actor.equals("owner")||!actor.matches("[A-Za-z0-9_:.@+-]{1,240}")||!audience.matches("group|requester"))throw new SecurityException("群产物原生身份无效");
        if(input==null||!mime.matches("[a-z0-9.+-]+/[a-z0-9.+-]+"))throw new IllegalArgumentException("群产物来源或类型无效");
        File directory=new File(root,space.substring(6));Files.createDirectories(directory.toPath());
        File[] existing=directory.listFiles((d,n)->n.endsWith(".blob")||n.endsWith(".part"));long total=0;if(existing!=null){if(existing.length>=200)throw new IllegalStateException("群产物数量已达上限");for(File file:existing)total+=file.length();}
        String id=UUID.randomUUID().toString();File part=new File(directory,id+".part"),blob=new File(directory,id+".blob"),meta=new File(directory,id+".json");long bytes=0;MessageDigest digest=MessageDigest.getInstance("SHA-256");
        try {
            try(FileOutputStream output=new FileOutputStream(part)){
                byte[] buffer=new byte[32768];for(int n;(n=input.read(buffer))!=-1;){authority.check();if(n==0)continue;bytes+=n;if(bytes>Math.min(maximum,20*1024*1024L)||total+bytes>100*1024*1024L)throw new IllegalArgumentException("群产物超过大小或空间配额");output.write(buffer,0,n);digest.update(buffer,0,n);}output.getFD().sync();
            }
            authority.check();JSONObject record=new JSONObject().put("artifactId",id).put("spaceId",space).put("actorId",actor).put("audience",audience).put("mimeType",mime).put("bytes",bytes)
                    .put("sha256",hex(digest.digest())).put("taskId",task).put("operationId",operation);
            if(mediaSource!=null)record.put("mediaSource",new JSONObject(mediaSource.toString()));
            Files.move(part.toPath(),blob.toPath(),StandardCopyOption.ATOMIC_MOVE);
            atomic(meta,record);return visible(record);
        }catch(Exception failed){part.delete();blob.delete();meta.delete();new File(meta.getParentFile(),meta.getName()+".pending").delete();throw failed;}
    }
    public synchronized JSONArray inputs(GroupHostPort.Request request,GroupHostPort.Authority authority)throws Exception {
        authority.checkRead();JSONArray input=request.inputArtifacts(),result=new JSONArray();
        for(int i=0;i<input.length();i++){JSONObject pinned=input.getJSONObject(i),record=required(request.spaceId,pinned.getString("artifactId"));
            if(!record.getString("operationId").equals(request.event.eventId)||!record.getString("actorId").equals(request.actorId)||record.getLong("bytes")!=pinned.getLong("bytes")||!record.getString("mimeType").equals(pinned.getString("mimeType")))throw new SecurityException("群附件与原认证消息不一致");
            result.put(visible(record));}
        authority.checkRead();return result;
    }
    public synchronized JSONObject readInputText(GroupHostPort.Request request,String artifactId,GroupHostPort.Authority authority)throws Exception {
        JSONArray allowed=inputs(request,authority);boolean found=false;for(int i=0;i<allowed.length();i++)if(artifactId.equals(allowed.getJSONObject(i).getString("artifactId")))found=true;
        if(!found)throw new SecurityException("只能读取本次原群消息的附件");JSONObject record=required(request.spaceId,artifactId);if(record.getLong("bytes")>65536||!record.getString("mimeType").matches("text/.*|application/(json|xml)"))throw new IllegalArgumentException("该群附件不支持64KiB内UTF-8预览");
        byte[] bytes=Files.readAllBytes(blob(request.spaceId,artifactId).toPath());String text=StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();if(text.indexOf('\0')>=0)throw new IllegalArgumentException("不是可预览的群文本");authority.checkRead();return visible(record).put("text",text).put("untrustedGroupArtifact",true);
    }
    public synchronized GroupHostPort.Output output(GroupHostPort.Request request,String artifactId,GroupHostPort.Authority authority)throws Exception {
        authority.checkRead();JSONObject record=required(request.spaceId,artifactId);
        if(!request.taskId.equals(record.getString("taskId"))||!request.actorId.equals(record.getString("actorId"))||!request.audience.equals(record.getString("audience")))throw new SecurityException("产物不属于原群任务或受众");
        authority.checkRead();return new GroupHostPort.Output(artifactId,request.spaceId,request.audience,record.getString("mimeType"),artifactId,"",record.getLong("bytes"));
    }
    /** Delivery owns a typed authoritative output; never accepts a path, URI or arbitrary attachment id. */
    public synchronized byte[] deliveryBytes(GroupHostPort.Output output,String expectedTaskId,GroupHostPort.Authority authority)throws Exception {
        authority.check();JSONObject record=required(output.spaceId,output.artifactId);if(!record.optString("taskId").equals(expectedTaskId)||!record.getString("mimeType").equals(output.mimeType)||record.getLong("bytes")!=output.bytes||!record.getString("audience").equals(output.audience))throw new SecurityException("群发送产物身份不符");
        byte[] bytes=Files.readAllBytes(blob(output.spaceId,output.artifactId).toPath());authority.check();return bytes;
    }
    /** Private native result/document viewer only. Read authority is never upgraded to send authority. */
    public synchronized JSONObject readOutputMetadata(GroupHostPort.Request request,String artifactId,GroupHostPort.Authority authority,RunControl control)throws Exception {
        control.check();authority.checkRead();JSONObject record=required(request.spaceId,artifactId,()->{control.check();authority.checkRead();});
        if(!request.taskId.equals(record.getString("taskId"))||!request.actorId.equals(record.getString("actorId"))||!request.audience.equals(record.getString("audience")))throw new SecurityException("产物不属于原群任务或受众");
        control.check();authority.checkRead();return new JSONObject(record.toString());
    }
    /** Private native result/document viewer only. Read authority is never upgraded to send authority. */
    public synchronized ReadSnapshot readOutputSnapshot(GroupHostPort.Request request,String artifactId,GroupHostPort.Authority authority,RunControl control)throws Exception {
        java.util.Objects.requireNonNull(control).check();authority.checkRead();
        JSONObject record=required(request.spaceId,artifactId,()->{control.check();authority.checkRead();});
        if(!request.taskId.equals(record.getString("taskId"))||!request.actorId.equals(record.getString("actorId"))||!request.audience.equals(record.getString("audience")))throw new SecurityException("产物不属于原群任务或受众");
        return readSnapshot(record,authority,control);
    }
    /** Original authenticated input only; supports paged PDF/Office/OCR without exposing a private path. */
    public synchronized ReadSnapshot readInputSnapshot(GroupHostPort.Request request,String artifactId,GroupHostPort.Authority authority,RunControl control)throws Exception {
        java.util.Objects.requireNonNull(control).check();authority.checkRead();JSONObject pinned=null;
        JSONArray inputs=request.inputArtifacts();for(int i=0;i<inputs.length();i++)if(artifactId.equals(inputs.getJSONObject(i).optString("artifactId")))pinned=inputs.getJSONObject(i);
        if(pinned==null)throw new SecurityException("只能读取本次认证原群消息的附件");
        JSONObject record=required(request.spaceId,artifactId,()->{control.check();authority.checkRead();});
        if(!record.getString("taskId").isEmpty()||!request.event.eventId.equals(record.getString("operationId"))||!request.actorId.equals(record.getString("actorId"))
                ||record.getLong("bytes")!=pinned.getLong("bytes")||!record.getString("mimeType").equals(pinned.getString("mimeType")))throw new SecurityException("原群输入附件的认证来源已变化");
        return readSnapshot(record,authority,control);
    }
    private ReadSnapshot readSnapshot(JSONObject record,GroupHostPort.Authority authority,RunControl control)throws Exception {
        byte[] bytes=new byte[record.getInt("bytes")];boolean returned=false;
        try {
            try(InputStream input=new FileInputStream(blob(record.getString("spaceId"),record.getString("artifactId")))) {
                Runnable cancel=()->{try{input.close();}catch(Exception ignored){}};control.onCancel(cancel);
                try {int offset=0;while(offset<bytes.length){control.check();authority.checkRead();int n=input.read(bytes,offset,Math.min(32768,bytes.length-offset));if(n<0)throw new java.io.EOFException("群产物字节不完整");offset+=n;}
                    control.check();authority.checkRead();if(input.read()!=-1||!record.getString("sha256").equals(hex(MessageDigest.getInstance("SHA-256").digest(bytes))))throw new SecurityException("群产物字节已变化");
                } finally {control.removeCancelAction(cancel);}
            }
            control.check();authority.checkRead();returned=true;return new ReadSnapshot(record,bytes);
        } finally {if(!returned)java.util.Arrays.fill(bytes,(byte)0);}
    }
    /** Verified immutable native bytes; no filename, URL, URI, permission or transmission route. */
    public static final class ReadSnapshot {
        private final String metadata;private final byte[] bytes;
        private ReadSnapshot(JSONObject metadata,byte[] bytes){this.metadata=metadata.toString();this.bytes=bytes;}
        public JSONObject metadata()throws Exception{return new JSONObject(metadata);}
        public InputStream openStream(){return new ByteArrayInputStream(bytes);}
        public byte[] copyBytes(){return bytes.clone();}
    }
    private interface ReadCheck {void check()throws Exception;}
    private JSONObject required(String space,String id)throws Exception {
        return required(space,id,()->{});
    }
    private JSONObject required(String space,String id,ReadCheck guard)throws Exception {
        guard.check();
        requireSpace(space);if(id==null||!id.matches("[a-f0-9-]{36}"))throw new SecurityException("群产物编号无效");File meta=new File(root,space.substring(6)+"/"+id+".json"),blob=blob(space,id);
        if(!meta.isFile()||!blob.isFile()||meta.length()>16384)throw new IllegalArgumentException("群产物不存在");JSONObject record=new JSONObject(new String(Files.readAllBytes(meta.toPath()),StandardCharsets.UTF_8));
        if(!space.equals(record.getString("spaceId"))||!id.equals(record.getString("artifactId"))||blob.length()!=record.getLong("bytes")||blob.length()>20*1024*1024L)throw new SecurityException("群产物已变化");
        MessageDigest digest=MessageDigest.getInstance("SHA-256");try(InputStream input=new FileInputStream(blob)){byte[] buffer=new byte[32768];for(int n;(n=input.read(buffer))!=-1;){guard.check();digest.update(buffer,0,n);}}
        guard.check();if(!hex(digest.digest()).equals(record.getString("sha256")))throw new SecurityException("群产物字节校验不符");return record;
    }
    private File blob(String space,String id){return new File(root,space.substring(6)+"/"+id+".blob");}
    private static void requireSpace(String space){if(space==null||!space.matches("group:[a-f0-9-]{36}"))throw new SecurityException("非原生群空间");}
    private static JSONObject visible(JSONObject record)throws Exception {JSONObject result=new JSONObject(record.toString());result.remove("sha256");result.remove("mediaSource");return result;}
    private static void atomic(File file,JSONObject value)throws Exception {File pending=new File(file.getParentFile(),file.getName()+".pending");try(FileOutputStream output=new FileOutputStream(pending)){output.write(value.toString().getBytes(StandardCharsets.UTF_8));output.getFD().sync();}Files.move(pending.toPath(),file.toPath(),StandardCopyOption.ATOMIC_MOVE);}
    private static String hex(byte[] bytes){StringBuilder text=new StringBuilder();for(byte b:bytes)text.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return text.toString();}
}
