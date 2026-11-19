package dev.pocketengine.features.local.project;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.attachments.AttachmentBook;
import dev.pocketengine.features.remote.RemoteSavedOriginal;
import dev.pocketengine.features.workspace.VersionedWorkspace;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** An owner-selected subset of one host snapshot whose exact bytes are already in the phone Book.
 * Never downloads a file, contacts a profile, resolves a server path, or creates an execution grant. */
public final class ProjectHostSource {
    public static final int MAX_COUNT=8, MAX_FILE=256*1024, MAX_BYTES=512*1024;
    private static final Set<String> CLAIM=Set.of("taskId","attachmentId","snapshotId","snapshotVersion","fileVersion","path","sha256");
    private static final String[] BINDING={"operationId","jobId","jobOperationId","environmentId","worker","workspaceIdentity","profileId","serviceBase","captureSource"};
    private final TreeMap<String,byte[]> files;
    private final String receipt;
    public final String selectionId;
    private ProjectHostSource(TreeMap<String,byte[]> files,JSONObject original)throws Exception {
        this.files=files;
        String digest=ProjectSources.digest(original);
        selectionId=UUID.nameUUIDFromBytes(("owner-host-source-v1:"+digest).getBytes(StandardCharsets.UTF_8)).toString();
        receipt=original.put("selectionDigest",digest).toString();
    }
    public static void requireManifest(JSONObject evidence,JSONObject manifest)throws Exception{
        JSONObject src=ProjectSources.copy(evidence.getJSONObject("original"));
        String digest=src.getString("selectionDigest");src.remove("selectionDigest");
        String id=UUID.nameUUIDFromBytes(("owner-host-source-v1:"+digest).getBytes(StandardCharsets.UTF_8)).toString();
        JSONArray records=src.getJSONArray("files"),actual=manifest.getJSONArray("files");
        if(!digest.equals(ProjectSources.digest(src))||!id.equals(evidence.getString("selectionId"))||records.length()<1||
           records.length()>MAX_COUNT||records.length()!=actual.length()||src.getLong("bytes")!=manifest.getLong("bytes"))throw changed();
        TreeMap<String,JSONObject> claims=new TreeMap<>();for(int i=0;i<records.length();i++){
            JSONObject row=records.getJSONObject(i);if(claims.put(sourcePath(row),row)!=null)throw changed();
        }
        for(int i=0;i<actual.length();i++){JSONObject file=actual.getJSONObject(i),claim=claims.remove(file.getString("path"));
            if(claim==null||!claim.getString("sha256").equals(file.getString("sha256"))||claim.getLong("bytes")!=file.getLong("bytes"))throw changed();}
        if(!claims.isEmpty())throw changed();
    }
    public String digest()throws Exception{return evidence().getString("selectionDigest");}
    JSONObject evidence()throws Exception{return new JSONObject(receipt);}
    Set<String> paths(){return java.util.Collections.unmodifiableSet(files.keySet());}
    byte[] bytes(String path){return files.get(path).clone();}

    public interface OriginalTask { JSONObject read() throws Exception; }
    private static SecurityException changed(){return new SecurityException("原任务、主机冻结版本或手机原件不再匹配，没有准备项目。");}
    private static JSONObject live(JSONObject task,JSONObject claim,AttachmentBook book)throws Exception{
        RemoteSavedOriginal.resolve(task,claim);
        JSONObject meta=book.get(claim.getString("attachmentId"));
        if(!"owner".equals(meta.opt("principalId"))||!"private".equals(meta.opt("spaceId"))||meta.optBoolean("outputSourceRetired")||
           !claim.getString("sha256").equals(meta.opt("sha256")))throw changed();
        RemoteSavedOriginal.requireLive(task,meta);return meta;
    }
    static String bindingDigest(JSONObject meta)throws Exception {
        JSONObject source=meta.getJSONObject("source"),binding=new JSONObject();
        for(String key:BINDING)binding.put(key,source.getString(key));
        return ProjectSources.digest(binding); // no server address or account settings leave the original Book
    }
    private static String sourcePath(JSONObject claim)throws Exception{
        String path=claim.getString("path"),raw=VersionedWorkspace.path(path),folded=raw.toLowerCase(Locale.ROOT);
        StandardCharsets.UTF_8.newEncoder().encode(java.nio.CharBuffer.wrap(raw));
        if(!path.equals(raw)||path.split("/",-1).length>ProjectSourceImport.MAX_DEPTH||
           path.codePoints().anyMatch(c->Character.getType(c)==Character.FORMAT)||ProjectPackageArchive.excluded(folded,false,false)!=null)
            throw new IllegalArgumentException("所选文件含不适合进入新工程的路径，请另选源码文件。");
        return raw;
    }
    public static ProjectHostSource read(String taskId,JSONArray requested,OriginalTask currentTask,AttachmentBook book,RunControl control)throws Exception{
        if(requested==null||requested.length()<1||requested.length()>MAX_COUNT)throw new IllegalArgumentException("请从一个冻结版本选择 1 至 8 份已存手机的源码。");
        control.check();JSONObject task=currentTask.read();if(task==null||!taskId.equals(task.opt("id")))throw changed();
        TreeMap<String,JSONObject> claims=new TreeMap<>();HashSet<String> ids=new HashSet<>(),normalized=new HashSet<>();
        TreeMap<String,String> prefixSpellings=new TreeMap<>();
        String snapshot=null,version=null,binding=null;long bytes=0;
        for(int i=0;i<requested.length();i++){
            control.check();JSONObject claim=ProjectSources.copy(requested.getJSONObject(i));
            HashSet<String> keys=new HashSet<>();claim.keys().forEachRemaining(keys::add);
            if(!CLAIM.equals(keys)||!taskId.equals(claim.opt("taskId")))throw changed();
            JSONObject meta=live(task,claim,book);long size=meta.getLong("size");String path=sourcePath(claim),scope=bindingDigest(meta);
            String normal=Normalizer.normalize(path,Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
            for(int slash=path.indexOf('/');slash>=0;slash=path.indexOf('/',slash+1)){
                String prefix=path.substring(0,slash),before=prefixSpellings.putIfAbsent(Normalizer.normalize(prefix,Normalizer.Form.NFC).toLowerCase(Locale.ROOT),prefix);
                if(before!=null&&!before.equals(prefix))throw changed();
            }
            if(!normalized.add(normal)||!ids.add(claim.getString("attachmentId"))||claims.put(path,claim)!=null)throw changed();
            if(size<0||size>MAX_FILE||size>MAX_BYTES-bytes)throw new IllegalArgumentException("单份最多 256 KiB，所选源码合计最多 512 KiB。");
            bytes+=size;
            if(i==0){snapshot=claim.getString("snapshotId");version=claim.getString("snapshotVersion");binding=scope;}
            else if(!snapshot.equals(claim.getString("snapshotId"))||!version.equals(claim.getString("snapshotVersion"))||!binding.equals(scope))throw changed();
        }
        for(String path:normalized)for(int at=path.indexOf('/');at>=0;at=path.indexOf('/',at+1))if(normalized.contains(path.substring(0,at)))throw changed();
        TreeMap<String,byte[]> files=new TreeMap<>();JSONArray receipts=new JSONArray();
        for(var row:claims.entrySet()){
            control.check();JSONObject claim=row.getValue(),meta=live(task,claim,book);
            byte[] content=book.readVerifiedSnapshot(claim.getString("attachmentId"),claim.getString("sha256"),control);
            if(content.length!=meta.getLong("size")||content.length>MAX_FILE||!claim.getString("sha256").equals(ProjectSources.sha256(content)))throw changed();
            try{StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(content));}catch(java.nio.charset.CharacterCodingException invalid){throw new IllegalArgumentException("所选文件不是完整 UTF-8 文本，请去掉二进制文件。");}
            for(byte value:content)if(value==0)throw new IllegalArgumentException("所选文件包含二进制内容，没有生成部分源码包。");
            files.put(row.getKey(),content);receipts.put(ProjectSources.copy(claim).put("bytes",content.length));
        }
        // Re-read the live private task and Book after copying. A removed receipt cannot be silently replaced by a similarly named file.
        control.check();JSONObject after=currentTask.read();if(after==null||!taskId.equals(after.opt("id")))throw changed();
        for(JSONObject claim:claims.values()){control.check();if(!binding.equals(bindingDigest(live(after,claim,book))))throw changed();}
        return new ProjectHostSource(files,new JSONObject().put("taskId",taskId).put("snapshotId",snapshot).put("snapshotVersion",version)
            .put("originalBindingDigest",binding).put("files",receipts).put("bytes",bytes).put("entireRepository",false).put("networkUsed",false));
    }
}
