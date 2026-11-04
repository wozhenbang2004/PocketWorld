package dev.pocketengine.features.attachments;

import java.io.File;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.UUID;
import dev.pocketengine.core.RunControl;
import org.json.JSONArray;
import org.json.JSONObject;

/** Real imported bytes in private storage. Paths never come from a document name or external URI. */
public final class AttachmentBook {
    public static final long MAX_FILE_BYTES=20L*1024*1024,MAX_TOTAL_BYTES=100L*1024*1024;
    static final int MAX_ATTACHMENT_SLOTS=200,MAX_INDEX_ENTRIES=1024,MAX_INDEX_RECORD_BYTES=256*1024,MAX_INDEX_READ_BYTES=4*1024*1024;
    private final File root;
    private final long fileLimit,totalLimit;
    /** Read-only publication of this Book's own successful native byte/metadata checks.
     * Empty after cold construction: no file, JSON locator or another store can mint eligibility. */
    private volatile java.util.Map<String,OutputSourceEligibility> outputEligibility=java.util.Collections.emptyMap();
    public static final class OutputSourceEligibility {
        public final String id,sha256,generation;
        private OutputSourceEligibility(String id,String sha256,String generation){this.id=id;this.sha256=sha256;this.generation=generation;}
    }
    /** No Book/Tasks monitor, disk, callback or grant. A source token is not read permission. */
    public OutputSourceEligibility outputSourceEligibility(String id,String sha256){
        OutputSourceEligibility current=outputEligibility.get(id);return current!=null&&current.sha256.equals(sha256)?current:null;
    }
    public boolean outputSourceCurrent(String id,String sha256,String generation){
        OutputSourceEligibility current=outputEligibility.get(id);return current!=null&&current.sha256.equals(sha256)&&current.generation.equals(generation);
    }
    private static String outputGeneration(JSONObject meta)throws Exception{
        JSONObject source=meta.optJSONObject("source");if(source==null)source=new JSONObject();
        String value="attachment-native-source-v1\0"+meta.getString("id")+"\0"+meta.getString("sha256")+"\0"+meta.getLong("size")+"\0"+meta.optString("principalId")+"\0"+meta.optString("spaceId")+"\0"+source.optString("kind")+"\0"+source.optString("groupSpaceId")+"\0"+source.optString("groupAudience")+"\0"+source.optString("groupActorId")+"\0"+meta.optBoolean("outputSourceRetired");
        return "sha256:"+hex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }
    private static boolean privateOutputSource(JSONObject meta){
        JSONObject source=meta.optJSONObject("source");boolean group=source!=null&&(source.has("groupSpaceId")||source.optString("kind").startsWith("group_")||"group".equals(source.optString("kind")));
        return !group&&!meta.optBoolean("outputSourceRetired")&&"owner".equals(meta.optString("principalId"))&&"private".equals(meta.optString("spaceId"));
    }
    /** Called only while this original Book monitor is held, after its own native commit/check. */
    private void publishOutputEligibility(JSONObject meta)throws Exception{
        String id=meta.getString("id");java.util.Map<String,OutputSourceEligibility> next=new java.util.HashMap<>(outputEligibility);next.remove(id);
        if(privateOutputSource(meta))next.put(id,new OutputSourceEligibility(id,meta.getString("sha256"),outputGeneration(meta)));
        outputEligibility=java.util.Collections.unmodifiableMap(next);
    }
    private void revokeOutputEligibility(String id){java.util.Map<String,OutputSourceEligibility> next=new java.util.HashMap<>(outputEligibility);next.remove(id);outputEligibility=java.util.Collections.unmodifiableMap(next);}

    public AttachmentBook(File root){this(root,MAX_FILE_BYTES,MAX_TOTAL_BYTES);}
    AttachmentBook(File root,long fileLimit,long totalLimit){this.root=root;this.fileLimit=fileLimit;this.totalLimit=totalLimit;}
    public synchronized JSONObject importBytes(InputStream input,String name,String mime,JSONObject source)throws Exception{return importBytesInternal(input,name,mime,source,null);}
    /** Native local save with revocable reads and a pause-revision fence at the actual file commit. */
    public synchronized JSONObject importBytes(InputStream input,String name,String mime,JSONObject source,RunControl control)throws Exception{
        return importBytesInternal(input,name,mime,source,java.util.Objects.requireNonNull(control));
    }
    /** Native producer retry identity in this SAME Book; not a second journal or authority.
     * A surviving orphan/retired record is unknown, never silently overwritten or given a new ID. */
    public synchronized JSONObject findNativeResult(String operation,String role,String task,String run,String planDigest,RunControl control)throws Exception{
        String id=nativeResultId(operation,role,task,run,planDigest);java.util.Objects.requireNonNull(control).checkpoint();
        File record=new File(root,id+".json"),blob=new File(root,id+".blob"),part=new File(root,id+".part"),pending=new File(root,id+".json.pending");
        if(!record.exists()&&!blob.exists()&&!part.exists()&&!pending.exists())return new JSONObject().put("found",false).put("id",id).put("publicationEntered",false);
        if(!record.isFile()||!blob.isFile()||Files.isSymbolicLink(record.toPath())||Files.isSymbolicLink(blob.toPath())||part.exists()||pending.exists())throw new IllegalStateException("原固定产物发布尚未完整确认；按原ID核对，未覆盖或重新保存："+id);
        JSONObject actual=internal(id),nativeSource=actual.getJSONObject("source").getJSONObject("nativePublication");
        for(String key:new String[]{"operationId","role","taskId","runId","planDigest"}){String wanted=switch(key){case "operationId"->operation;case "role"->role;case "taskId"->task==null?"":task;case "runId"->run==null?"":run;default->planDigest;};if(!wanted.equals(nativeSource.optString(key)))throw new SecurityException("原产物发布身份与这次固定计划不同");}
        verifyBytes(id,actual.getString("sha256"),control,false);return visible(actual).put("found",true).put("publicationEntered",true).put("originalPublication",true);
    }
    public synchronized JSONObject importNativeResult(InputStream input,String name,String mime,JSONObject source,String operation,String role,String task,String run,String planDigest,RunControl control)throws Exception{
        JSONObject prior=findNativeResult(operation,role,task,run,planDigest,control);if(prior.getBoolean("found"))return prior;
        JSONObject nativeSource=source==null?new JSONObject():new JSONObject(source.toString());requireNativeParents(nativeSource,control);nativeSource.put("nativePublication",new JSONObject().put("operationId",operation).put("role",role).put("taskId",task==null?"":task).put("runId",run==null?"":run).put("planDigest",planDigest));
        return importBytesInternal(input,name,mime,nativeSource,control,nativeResultId(operation,role,task,run,planDigest)).put("found",true).put("originalPublication",true);
    }
    private void requireNativeParents(JSONObject source,RunControl control)throws Exception{
        if(source==null)return;org.json.JSONArray inputs=source.optJSONArray("inputArtifacts");if(inputs!=null){if(inputs.length()>4)throw new SecurityException("原件输入引用超限");for(int i=0;i<inputs.length();i++){JSONObject selected=inputs.getJSONObject(i),parent=internal(selected.getString("id"));if(!privateOutputSource(parent))throw new SecurityException("原私人加工或编辑输入已撤下");verifyBytes(selected.getString("id"),selected.getString("sha256"),control,false);}}
        for(String key:new String[]{"parentArtifact","editableSource"}){JSONObject selected=source.optJSONObject(key);if(selected==null)continue;String id=selected.getString("id"),sha=selected.getString("sha256");JSONObject parent=internal(id);if(!privateOutputSource(parent))throw new SecurityException("原私人父来源已经撤下或范围变化");verifyBytes(id,sha,control,false);}
    }
    private static String nativeResultId(String operation,String role,String task,String run,String digest)throws Exception{
        checkId(operation);if(role==null||!role.matches("[a-z][a-z0-9_]{0,39}")||digest==null||!digest.matches("[a-f0-9]{64}"))throw new SecurityException("缺少原生固定产物计划身份");
        boolean scoped=task!=null&&!task.isEmpty()||run!=null&&!run.isEmpty();if(scoped){checkNativeScopeId(task);checkNativeScopeId(run);}
        // Do not include digest in the route: changed semantics under one operation/role must be rejected.
        return UUID.nameUUIDFromBytes(("pocketworld-native-result-v1\0"+operation+"\0"+role+"\0"+(task==null?"":task)+"\0"+(run==null?"":run)).getBytes(StandardCharsets.UTF_8)).toString();
    }
    private JSONObject importBytesInternal(InputStream input,String name,String mime,JSONObject source,RunControl control)throws Exception{return importBytesInternal(input,name,mime,source,control,null);}
    private JSONObject importBytesInternal(InputStream input,String name,String mime,JSONObject source,RunControl control,String nativeId)throws Exception{
        if(input==null)throw new IllegalArgumentException("附件来源无法读取");
        if(control!=null)control.checkpoint();long pauseRevision=control==null?0:control.pauseRevision();
        if(!root.isDirectory()&&!root.mkdirs())throw new IllegalStateException("无法创建附件目录");
        StorageScan stored=scanStorage();if(!stored.complete||!stored.quotaKnown)throw new IllegalStateException("附件存储占额无法完整核对；原文件和发布证据已保留，未导入");
        if(stored.slots.size()>=MAX_ATTACHMENT_SLOTS)throw new IllegalStateException("附件及保留的未确认原件数量已达200个，请先核对，未自动清理");long total=stored.bytes;
        if(total>totalLimit)throw new IllegalArgumentException("附件超出总容量限制；未确认原件仍占用容量");
        String id=nativeId==null?UUID.randomUUID().toString():nativeId;File part=new File(root,id+".part"),blob=new File(root,id+".blob"),record=new File(root,id+".json");long size=0;MessageDigest digest=MessageDigest.getInstance("SHA-256");
        if(nativeId!=null&&(part.exists()||blob.exists()||record.exists()||new File(root,id+".json.pending").exists()))throw new SecurityException("原固定产物发布状态变化，未覆盖");
        try{
            try(FileOutputStream output=new FileOutputStream(part)){byte[] buffer=new byte[32768];for(int n;(n=input.read(buffer))!=-1;){if(control!=null)control.checkpoint();if(Thread.currentThread().isInterrupted())throw new InterruptedException("附件导入已停止");if(n==0)continue;if(n>fileLimit-size||n>totalLimit-total-size)throw new IllegalArgumentException("附件超出单文件或总容量限制");size+=n;output.write(buffer,0,n);digest.update(buffer,0,n);}output.getFD().sync();}
            JSONObject meta=new JSONObject().put("id",id).put("name",displayName(name)).put("mimeType",mimeType(mime)).put("size",size).put("sha256",hex(digest.digest())).put("createdAt",System.currentTimeMillis()).put("principalId","owner").put("spaceId","private").put("source",source==null?new JSONObject():new JSONObject(source.toString()));
            byte[] indexBytes=encodedIndex(meta); // Refuse new unreadable metadata before entering publication.
            if(control==null){move(part,blob);atomicJson(record,indexBytes);}
            else control.dispatchAtPauseRevision(pauseRevision,()->{if(nativeId!=null)requireNativeParents(source,control);move(part,blob);atomicJson(record,indexBytes);});
            publishOutputEligibility(meta);return visible(meta);
        }catch(Exception failure){revokeOutputEligibility(id);part.delete();if(nativeId==null){blob.delete();record.delete();new File(root,id+".json.pending").delete();}throw failure;}
    }
    /** Complete catalog required by original-publication lookup and owner archive consumers.
     * They must not mistake an unreadable record for proof that a previous save never happened. */
    public synchronized JSONArray list()throws Exception{
        JSONObject inspected=inspectIndex();if(inspected.getJSONObject("indexDiagnostics").getBoolean("incomplete"))throw new IllegalStateException("部分附件索引无法核对；原件、索引和发布证据已原位保留，不能据此备份或重新发布");return inspected.getJSONArray("attachments");
    }
    /** Read-only owner display projection, not a recovery grant or an assertion of non-publication.
     * No index/blob is moved, repaired or deleted. Unknown scope never appears in diagnostics. */
    public synchronized JSONObject inspectIndex()throws Exception{
        StorageScan stored=scanStorage();JSONArray readable=new JSONArray();int unreadable=0,oversized=0,budgetExceeded=0;IndexReadBudget budget=new IndexReadBudget();
        stored.records.sort((a,b)->Long.compare(b.modified,a.modified));
        for(IndexRecord record:stored.records){
            if(record.size>MAX_INDEX_RECORD_BYTES){oversized++;revokeOutputEligibility(record.id);continue;}
            if(budget.bytes>=MAX_INDEX_READ_BYTES){budgetExceeded++;revokeOutputEligibility(record.id);continue;}
            try{
                JSONObject meta=readIndex(record.path,budget);BasicFileAttributes data=Files.readAttributes(new File(root,record.id+".blob").toPath(),BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
                if(!record.id.equals(meta.getString("id"))||!data.isRegularFile()||meta.getLong("size")!=data.size()||meta.getLong("size")<0||!meta.getString("sha256").matches("[a-f0-9]{64}")||meta.getString("name").isEmpty()||meta.getString("mimeType").isEmpty()||meta.getString("principalId").isEmpty()||meta.getString("spaceId").isEmpty()||meta.optJSONObject("source")==null)throw new IllegalStateException();
                readable.put(visible(meta));
            }catch(IndexReadLimit limit){if(limit.total)budgetExceeded++;else oversized++;revokeOutputEligibility(record.id);}
            catch(Exception invalid){unreadable++;revokeOutputEligibility(record.id);}
            catch(StackOverflowError invalid){unreadable++;revokeOutputEligibility(record.id);} // Malformed deep JSON must not defeat the bounded display scan.
        }
        boolean incomplete=!stored.complete||!stored.quotaKnown||unreadable+oversized+budgetExceeded>0;
        JSONObject diagnostics=new JSONObject().put("incomplete",incomplete).put("status",incomplete?"incomplete_retained":"complete").put("unreadableRecords",unreadable).put("oversizedRecords",oversized).put("readBudgetExceededRecords",budgetExceeded)
                .put("enumerationComplete",stored.complete).put("quotaKnown",stored.complete&&stored.quotaKnown).put("retainedSlots",stored.slots.size()).put("storedBytes",stored.bytes)
                .put("originalsPreserved",true).put("recovery","retained_in_place").put("automaticCleanup",false).put("publicationReconstructed",false);
        if(incomplete)diagnostics.put("message","部分附件索引无法核对；可读文件仍可查看，原索引、原件和发布证据已原位保留并占用配额。不能据此重新发布或自动清理。");
        return new JSONObject().put("attachments",readable).put("indexDiagnostics",diagnostics);
    }
    private static final class IndexRecord {
        final Path path;final String id;final long size,modified;
        IndexRecord(Path path,String id,long size,long modified){this.path=path;this.id=id;this.size=size;this.modified=modified;}
    }
    private static final class StorageScan {
        final java.util.Set<String> slots=new java.util.HashSet<>();final java.util.List<IndexRecord> records=new java.util.ArrayList<>();
        long bytes;boolean complete=true,quotaKnown=true;
    }
    /** Count physical original identities without trusting JSON. A corrupt/missing index,
     * .pending or crash orphan retains its slot; every blob/part byte still consumes quota. */
    private StorageScan scanStorage(){
        StorageScan result=new StorageScan();Path directory=root.toPath();
        try{
            if(!Files.readAttributes(directory,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS).isDirectory()){result.complete=false;result.quotaKnown=false;return result;}
        }catch(NoSuchFileException absent){return result;}catch(Exception unknown){result.complete=false;result.quotaKnown=false;return result;}
        try(var entries=Files.newDirectoryStream(directory)){
            int count=0;for(Path entry:entries){if(++count>MAX_INDEX_ENTRIES){result.complete=false;break;}String name=entry.getFileName().toString(),suffix=null;
                for(String candidate:new String[]{".json.pending",".json",".blob",".part"})if(name.endsWith(candidate)){suffix=candidate;break;}if(suffix==null)continue;
                String id=name.substring(0,name.length()-suffix.length());result.slots.add(id);BasicFileAttributes attributes;
                try{attributes=Files.readAttributes(entry,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);if(!attributes.isRegularFile())result.quotaKnown=false;
                    else if(suffix.equals(".blob")||suffix.equals(".part"))result.bytes=Math.addExact(result.bytes,attributes.size());
                }catch(Exception unknown){result.quotaKnown=false;attributes=null;}
                if(suffix.equals(".json")&&id.matches("[a-f0-9-]{36}"))result.records.add(new IndexRecord(entry,id,attributes==null?-1:attributes.size(),attributes==null?0:attributes.lastModifiedTime().toMillis()));
            }
        }catch(Exception unknown){result.complete=false;result.quotaKnown=false;}
        return result;
    }
    private static final class IndexReadBudget {long bytes;}
    private static final class IndexReadLimit extends Exception {final boolean total;IndexReadLimit(boolean total){this.total=total;}}
    private static JSONObject readIndex(Path path,IndexReadBudget budget)throws Exception{
        if(!Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS).isRegularFile())throw new IllegalStateException();
        try(InputStream input=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS);ByteArrayOutputStream bytes=new ByteArrayOutputStream()){
            byte[] buffer=new byte[8192];for(int count;(count=input.read(buffer,0,(int)Math.min(buffer.length,Math.min(MAX_INDEX_RECORD_BYTES-bytes.size()+1L,MAX_INDEX_READ_BYTES-budget.bytes+1))))!=-1;){
                if(count==0)continue;budget.bytes+=count;if(budget.bytes>MAX_INDEX_READ_BYTES)throw new IndexReadLimit(true);if(bytes.size()+count>MAX_INDEX_RECORD_BYTES)throw new IndexReadLimit(false);bytes.write(buffer,0,count);
            }
            return new JSONObject(StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes.toByteArray())).toString());
        }
    }
    public synchronized JSONObject get(String id)throws Exception{return visible(internal(id));}
    synchronized JSONObject internal(String id)throws Exception{
        checkId(id);try{File record=new File(root,id+".json"),data=new File(root,id+".blob");if(!record.isFile()||!data.isFile())throw new IllegalArgumentException("附件不存在");JSONObject value=readIndex(record.toPath(),new IndexReadBudget());if(!id.equals(value.optString("id"))||data.length()!=value.getLong("size"))throw new IllegalStateException("附件记录与文件不一致");return value;}
        catch(org.json.JSONException|IndexReadLimit invalid){revokeOutputEligibility(id);throw new IllegalStateException("附件索引无法核对；原件和发布证据已保留，未修复或删除");}
        catch(StackOverflowError invalid){revokeOutputEligibility(id);throw new IllegalStateException("附件索引无法核对；原件和发布证据已保留，未修复或删除");}
        catch(Exception missingOrChanged){revokeOutputEligibility(id);throw missingOrChanged;}
    }
    public synchronized File file(String id)throws Exception{internal(id);return new File(root,id+".blob");}
    public synchronized JSONObject readText(String id)throws Exception{return readText(id,internal(id).getString("sha256"));}
    synchronized JSONObject readText(String id,String expectedSha256)throws Exception{
        JSONObject meta=internal(id);if(meta.getLong("size")>65536)throw new IllegalArgumentException("文本预览最多64KiB，请打开附件查看");byte[] data=verifiedBytes(id,expectedSha256,new RunControl());
        try{String text=StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(data)).toString();if(text.indexOf('\0')>=0)throw new IllegalArgumentException("此附件不是可预览的UTF-8文本");return visible(meta).put("text",text).put("untrustedAttachmentContent",true);}
        finally{Arrays.fill(data,(byte)0);}
    }
    static final class InputChangedException extends Exception {
        InputChangedException(){super("Attachment no longer matches the requested digest");}
    }
    /** A private immutable snapshot; callers supply an attachment identity, never a file or URI. */
    synchronized byte[] verifiedBytes(String id,String expectedSha256,RunControl control)throws Exception {
        return verifyBytes(id,expectedSha256,control,true);
    }
    /** Native artifact adapters may read exact imported bytes; this is not a model/file-path endpoint. */
    public synchronized byte[] readVerifiedSnapshot(String id,String expectedSha256,RunControl control)throws Exception {
        return verifyBytes(id,expectedSha256,control,true);
    }
    /** Immutable native metadata after the same complete byte/hash check as a snapshot; no file path or authority is returned. */
    public static final class VerifiedMetadata {
        public final String id,sha256,name,mimeType;
        public final long size;
        private VerifiedMetadata(JSONObject record)throws Exception {
            id=record.getString("id");sha256=record.getString("sha256");name=record.getString("name");mimeType=record.getString("mimeType");size=record.getLong("size");
        }
    }
    public synchronized VerifiedMetadata readVerifiedMetadata(String id,String expectedSha256,RunControl control)throws Exception {
        verifyBytes(id,expectedSha256,control,false);
        JSONObject record=internal(id);if(!expectedSha256.equals(record.getString("sha256")))throw new InputChangedException();
        control.checkpoint();return new VerifiedMetadata(record);
    }
    /** Native owner archive stream. Same private Book and full digest before/after;
     * no file path, URI, group source or old execution permission is exported. */
    public synchronized long exportOwnerArchiveBytes(String id,String sha,java.io.OutputStream destination,RunControl original)throws Exception {
        JSONObject meta=internal(id);if(!privateOutputSource(meta))throw new SecurityException("只能备份当前可读的私人原件，不能借此导出群来源或撤下文件");verifyBytes(id,sha,original,false);long count=0;MessageDigest digest=MessageDigest.getInstance("SHA-256");
        try(InputStream input=Files.newInputStream(new File(root,id+".blob").toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS)){byte[] buffer=new byte[32768];for(int n;(n=input.read(buffer))>=0;){original.checkpoint();if(n==0)continue;if((count+=n)>meta.getLong("size"))throw new SecurityException("原件备份期间长度变化");digest.update(buffer,0,n);destination.write(buffer,0,n);}}
        if(count!=meta.getLong("size")||!sha.equals(hex(digest.digest())))throw new SecurityException("原件备份期间字节变化，未确认完整备份");verifyBytes(id,sha,original,false);return count;
    }
    /** Native cold-source admission only. The saved generation is not a grant; this original
     * Book must recheck its private scope and the complete actual file digest before publication.
     * Callers must not hold Tasks while entering this Book. No body or path is returned. */
    public synchronized long revalidateOutputSource(String id,String expectedSha256,String expectedGeneration,long remainingBytes,RunControl control)throws Exception {
        checkId(id);java.util.Objects.requireNonNull(control).check();
        try{
            JSONObject meta=internal(id);long size=meta.getLong("size");
            if(!privateOutputSource(meta)||expectedGeneration==null||!expectedGeneration.equals(outputGeneration(meta))||size<0||size>remainingBytes)throw new InputChangedException();
            verifyBytes(id,expectedSha256,control,false);
            if(!outputSourceCurrent(id,expectedSha256,expectedGeneration))throw new InputChangedException();
            control.check();return size;
        }catch(Exception unavailable){revokeOutputEligibility(id);throw unavailable;}
    }
    synchronized void requireUnchanged(String id,String expectedSha256,RunControl control)throws Exception {
        verifyBytes(id,expectedSha256,control,false);
    }
    private byte[] verifyBytes(String id,String expected,RunControl control,boolean retain)throws Exception {
        checkId(id);
        if(expected==null||!expected.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Expected SHA-256 required");
        if(control==null)throw new IllegalArgumentException("Live RunControl required");
        control.checkpoint();JSONObject meta;
        try {meta=internal(id);}catch(IllegalArgumentException|IllegalStateException absent){throw new InputChangedException();}
        if(meta.optBoolean("outputSourceRetired")||!expected.equals(meta.optString("sha256"))||meta.getLong("size")>MAX_FILE_BYTES||meta.getLong("size")<0){revokeOutputEligibility(id);throw new InputChangedException();}
        MessageDigest digest=MessageDigest.getInstance("SHA-256");long size=0;
        ByteArrayOutputStream output=retain?new ByteArrayOutputStream((int)meta.getLong("size")):null;
        try(InputStream input=new FileInputStream(new File(root,id+".blob"))) {
            byte[] buffer=new byte[32768];
            for(int n;(n=input.read(buffer))!=-1;) {
                control.checkpoint();if(n==0)continue;size+=n;
                if(size>MAX_FILE_BYTES||size>meta.getLong("size"))throw new InputChangedException();
                digest.update(buffer,0,n);if(output!=null)output.write(buffer,0,n);
            }
        }
        control.checkpoint();
        if(size!=meta.getLong("size")||!expected.equals(hex(digest.digest()))){revokeOutputEligibility(id);throw new InputChangedException();}
        JSONObject current=internal(id);if(!outputGeneration(meta).equals(outputGeneration(current))){revokeOutputEligibility(id);throw new InputChangedException();}
        publishOutputEligibility(current);return output==null?null:output.toByteArray();
    }
    /** Native original group request only: retain why this technical media cache is excluded from private files. */
    public synchronized void retainGroupOrigin(String id,dev.pocketengine.features.groups.GroupHostPort.Request request,String operation,String run,dev.pocketengine.features.groups.GroupHostPort.Authority read)throws Exception {
        read.checkRead();JSONObject record=internal(id),source=record.getJSONObject("source");
        if(!request.taskId.equals(source.optString("taskId"))||!operation.equals(source.optString("operationId"))||!run.equals(source.optString("runId")))throw new SecurityException("技术媒体副本不是原群操作");
        if(source.has("groupSpaceId")&&(!request.spaceId.equals(source.getString("groupSpaceId"))||!request.audience.equals(source.optString("groupAudience"))))throw new SecurityException("技术媒体副本的原群范围变化");
        if(source.has("groupSpaceId"))return;
        revokeOutputEligibility(id);source.put("groupSpaceId",request.spaceId).put("groupAudience",request.audience).put("groupActorId",request.actorId);record.put("source",source);read.checkRead();atomicJson(new File(root,id+".json"),record);read.checkRead();publishOutputEligibility(record);
    }
    public synchronized void remove(String id)throws Exception{
        JSONObject meta=internal(id);revokeOutputEligibility(id);
        // Persist retirement before deleting either file: partial deletion cannot revive an old
        // captured output on a cold reopen. Failure keeps this instance fail-closed as well.
        File data=new File(root,id+".blob"),record=new File(root,id+".json");meta.put("outputSourceRetired",true);atomicJson(record,meta);
        if(!data.delete())throw new IllegalStateException("附件文件未能删除，派生读取已停用");if(!record.delete())throw new IllegalStateException("附件索引未能删除，派生读取已停用");
    }
    public synchronized long exportTo(String id,java.io.OutputStream output)throws Exception{
        String expected=internal(id).getString("sha256");RunControl control=new RunControl();byte[] bytes=verifiedBytes(id,expected,control);
        try{for(int at=0;at<bytes.length;){control.checkpoint();int count=Math.min(32768,bytes.length-at);output.write(bytes,at,count);at+=count;}output.flush();requireUnchanged(id,expected,control);return bytes.length;}
        finally{Arrays.fill(bytes,(byte)0);}
    }
    public static String displayName(String input){String name=input==null?"attachment":input.replaceAll("[\\p{Cntrl}/\\\\]","_").trim();if(name.isEmpty()||name.equals(".")||name.equals(".."))name="attachment";return name.substring(0,Math.min(160,name.length()));}
    public static String mimeType(String raw){return raw!=null&&raw.matches("[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+")?raw.toLowerCase(java.util.Locale.ROOT):"application/octet-stream";}
    private static JSONObject visible(JSONObject original)throws Exception{JSONObject result=new JSONObject(original.toString()),source=result.optJSONObject("source");if(source!=null){source.remove("uri");source.remove("url");}return result;}
    /** These native task/run identifiers are hashed into a UUID, never used as a file path.
     * Keep blob/operation IDs on their original UUID validation; only the canonical device task
     * identity is added here. This is syntax validation, not an execution permission. */
    private static void checkNativeScopeId(String id){
        if(id!=null&&id.matches("dt1_[a-f0-9]{64}"))return;
        checkId(id);
    }
    private static void checkId(String id){if(id==null||!id.matches("[a-f0-9-]{36}"))throw new IllegalArgumentException("附件ID无效");}
    private static String hex(byte[] bytes){StringBuilder text=new StringBuilder();for(byte b:bytes)text.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return text.toString();}
    private static byte[] encodedIndex(JSONObject value){byte[] bytes=value.toString().getBytes(StandardCharsets.UTF_8);if(bytes.length>MAX_INDEX_RECORD_BYTES)throw new IllegalArgumentException("附件索引元数据超过256KiB，未发布或修改原索引");return bytes;}
    private static void atomicJson(File file,JSONObject value)throws Exception{atomicJson(file,encodedIndex(value));}
    private static void atomicJson(File file,byte[] bytes)throws Exception{File temp=new File(file.getParentFile(),file.getName()+".pending");try(FileOutputStream output=new FileOutputStream(temp)){output.write(bytes);output.getFD().sync();}move(temp,file);}
    private static void move(File from,File to)throws Exception{try{Files.move(from.toPath(),to.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException unavailable){Files.move(from.toPath(),to.toPath(),StandardCopyOption.REPLACE_EXISTING);}}
}
