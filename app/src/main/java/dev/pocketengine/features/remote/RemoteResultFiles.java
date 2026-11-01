package dev.pocketengine.features.remote;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.attachments.AttachmentBook;
import dev.pocketengine.store.TaskStore;
import java.io.ByteArrayInputStream;
import java.util.Arrays;
import java.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;

/** Native original-snapshot bytes enter the existing private AttachmentBook, not a second store. */
public final class RemoteResultFiles {
    private RemoteResultFiles() { }
    public interface OriginalBytes { JSONObject read(JSONObject originalSnapshot,String exactPath)throws Exception; }
    public static final class Saved {
        private final TaskStore issuer;
        private final String value;
        private Saved(TaskStore issuer,JSONObject value){this.issuer=issuer;this.value=value.toString();}
        public boolean issuedBy(TaskStore tasks){return issuer==tasks;}
        public JSONObject view()throws Exception{return new JSONObject(value);}
    }
    private static JSONObject manifest(JSONObject snapshot,String path)throws Exception {
        JSONArray files=snapshot.getJSONArray("files");
        for(int i=0;i<files.length();i++)if(path.equals(files.getJSONObject(i).getString("path")))return new JSONObject(files.getJSONObject(i).toString());
        throw new SecurityException("文件不在原任务的不可变快照范围");
    }
    private static JSONObject origin(String taskId,JSONObject snapshot,JSONObject file)throws Exception {
        JSONObject source=snapshot.getJSONObject("source");
        JSONObject result=new JSONObject().put("kind","remote_result_snapshot").put("taskId",taskId)
                .put("snapshotId",snapshot.getString("snapshotId")).put("snapshotVersion",snapshot.getString("snapshotVersion"))
                .put("path",file.getString("path")).put("sha256",file.getString("sha256")).put("bytes",file.getLong("bytes"))
                .put("fileVersion",file.getString("fileVersion")).put("immutableArtifact",true).put("authorshipVerified",false)
                .put("captureSource",snapshot.getString("captureSource"));
        for(String key:new String[]{"operationId","jobId","jobOperationId","environmentId","worker","workspaceIdentity"})result.put(key,snapshot.get(key));
        for(String key:new String[]{"profileId","serviceBase"})result.put(key,source.get(key));
        return result;
    }
    private static boolean sameOrigin(JSONObject source,JSONObject expected)throws Exception {
        if(source==null||source.length()!=expected.length())return false;
        for(java.util.Iterator<String> keys=expected.keys();keys.hasNext();){String key=keys.next();if(key.equals("bytes")?source.optLong(key,-1)!=expected.getLong(key):!expected.get(key).equals(source.opt(key)))return false;}
        return true;
    }
    private static void stillOriginal(TaskStore tasks,String taskId,JSONObject original)throws Exception {
        JSONObject current=tasks.remoteResultSnapshot(taskId,original.getString("snapshotId"));
        if(!TaskStore.capabilityFingerprint(current).equals(TaskStore.capabilityFingerprint(original)))throw new SecurityException("原快照登记已变化，未关联本机成果");
    }
    private static Saved saved(TaskStore tasks,String taskId,JSONObject snapshot,JSONObject file,JSONObject meta)throws Exception {
        return new Saved(tasks,new JSONObject().put("taskId",taskId).put("snapshotId",snapshot.getString("snapshotId"))
                .put("snapshotVersion",snapshot.getString("snapshotVersion")).put("path",file.getString("path"))
                .put("fileVersion",file.getString("fileVersion")).put("bytes",file.getLong("bytes"))
                .put("sha256",file.getString("sha256")).put("attachment",new JSONObject(meta.toString())));
    }
    /** nativeRead must retain only lifecycle/profile checks: never acquire TaskStore while Book holds its monitor. */
    public static Saved save(TaskStore tasks,AttachmentBook book,String taskId,String snapshotId,String path,
                             OriginalBytes remoteBytes,RunControl nativeRead)throws Exception {
        nativeRead.check();JSONObject snapshot=tasks.remoteResultSnapshot(taskId,snapshotId),file=manifest(snapshot,path);
        JSONObject source=origin(taskId,snapshot,file);
        // Recover a prior Book commit if the following SQL association ACK was lost. No second GET/import.
        JSONArray known=book.list();
        for(int i=0;i<known.length();i++){
            JSONObject meta=known.getJSONObject(i);
            if(!sameOrigin(meta.optJSONObject("source"),source))continue;
            if(meta.getLong("size")!=file.getLong("bytes")||!meta.getString("sha256").equals(file.getString("sha256")))throw new SecurityException("本机原件登记不一致，不自动再下载");
            book.readVerifiedMetadata(meta.getString("id"),file.getString("sha256"),nativeRead);
            stillOriginal(tasks,taskId,snapshot);nativeRead.check();
            return saved(tasks,taskId,snapshot,file,meta);
        }
        nativeRead.check();JSONObject actual=remoteBytes.read(new JSONObject(snapshot.toString()),path);
        RemoteResultSnapshots.file(actual,snapshot,path);
        byte[] bytes=Base64.getDecoder().decode(actual.getString("base64"));
        try{
            stillOriginal(tasks,taskId,snapshot);nativeRead.check();
            String name=path.substring(path.lastIndexOf('/')+1);
            String mime=java.net.URLConnection.guessContentTypeFromName(name);
            if(mime==null)mime="application/octet-stream";
            JSONObject meta=book.importBytes(new ByteArrayInputStream(bytes),name,mime,source,nativeRead);
            if(meta.getLong("size")!=file.getLong("bytes")||!meta.getString("sha256").equals(file.getString("sha256")))throw new SecurityException("本机存储字节与原冻结文件不一致");
            stillOriginal(tasks,taskId,snapshot);nativeRead.check();
            return saved(tasks,taskId,snapshot,file,meta);
        }finally{Arrays.fill(bytes,(byte)0);}
    }
}
