package dev.pocketengine.features.workspace;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.store.SecretVault;
import dev.pocketengine.store.TaskStore;
import java.io.*;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.json.JSONObject;

/** Owner-only transport around OwnerDataArchives. The original TaskStore remains the upload journal;
 * remote bytes never restore data or execution rights without the existing archive reviews. */
public final class OwnerBackupWebDavHost implements AutoCloseable {
    private final File root,transfers;
    private final OwnerDataArchives archives;
    private final TaskStore tasks;
    private final OwnerBackupWebDavConfig config;
    private final OwnerBackupWebDavClient client=new OwnerBackupWebDavClient();
    private final Consumer<String> changed;
    private final Map<String,RunControl> controls=new ConcurrentHashMap<>();
    private final Set<Review> reviews=ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    public OwnerBackupWebDavHost(File privateRoot,OwnerDataArchives archives,TaskStore tasks,SecretVault vault,Consumer<String> changed)throws Exception {
        root=privateRoot.getAbsoluteFile();transfers=new File(root,"transfers");this.archives=Objects.requireNonNull(archives);this.tasks=Objects.requireNonNull(tasks);this.changed=Objects.requireNonNull(changed);
        directory(root);directory(transfers);config=new OwnerBackupWebDavConfig(root,vault);OwnerBackupPendingFiles.recoverIfIdle(transfers);
    }
    public JSONObject configuration()throws Exception {open();return config.read().view();}
    public JSONObject saveConfiguration(long expectedRevision,String collection,String username,char[] password,boolean enabled)throws Exception {
        try{open();JSONObject result=config.save(expectedRevision,collection,username,password,enabled);for(RunControl original:controls.values())original.cancel();for(Review review:reviews)review.close();return result;}finally{if(password!=null)Arrays.fill(password,'\0');}
    }
    public JSONObject listVersions(RunControl original)throws Exception {
        open();OwnerBackupWebDavConfig.Snapshot captured=config.read();config.requireCurrent(captured);
        var versions=client.list(new OwnerBackupWebDavTarget(captured.collection),config.authorization(captured),original);
        original.checkpoint();config.requireCurrent(captured);return captured.view().put("versions",versions).put("metadataOnly",true);
    }
    /** Password is used only for this preparation and cleared, never persisted with the connection. */
    public Review reviewUpload(OwnerDataArchives.Selection selected,char[] password,long expectedRevision,RunControl original)throws Exception {
        OwnerBackupPendingFiles staging=null;File encrypted=null;boolean retained=false;
        try{
            open();if(original.hasHostRun())throw new SecurityException("请从本机主人备份入口审阅上传");OwnerBackupEnvelope.requirePassword(password);
            OwnerBackupWebDavConfig.Snapshot connection=config.read();config.requireCurrent(connection);if(connection.revision!=expectedRevision)throw new SecurityException("所选 WebDAV 目录或凭据已变化，请重新审阅");JSONObject source=selected.metadata();
            String action=UUID.randomUUID().toString();staging=OwnerBackupPendingFiles.begin(directory(transfers));encrypted=staging.pending(action);
            MessageDigest hash=MessageDigest.getInstance("SHA-256");long bytes;
            try(FileChannel channel=staging.createPlain(encrypted)){
                DigestOutputStream output=new DigestOutputStream(Channels.newOutputStream(channel),hash);
                bytes=archives.exportEncrypted(selected,output,password,original);output.flush();channel.force(true);
            }
            if(bytes!=OwnerBackupEnvelope.encryptedBytes(source.getLong("archiveBytes"))||encrypted.length()!=bytes)throw new SecurityException("原加密备份未完整生成");
            original.checkpoint();config.requireCurrent(connection);open();OwnerBackupWebDavTarget target=new OwnerBackupWebDavTarget(connection.collection);
            JSONObject scope=new JSONObject().put("format","pocketworld.owner-backup-webdav-upload.v1").put("actionId",action)
                    .put("selectionId",source.getString("selectionId")).put("archiveSha256",source.getString("archiveSha256")).put("archiveBytes",source.getLong("archiveBytes"))
                    .put("encryptedSha256",OwnerBackupWebDavTarget.hex(hash.digest())).put("encryptedBytes",bytes).put("collection",target.collection).put("destination",target.destination(action))
                    .put("targetIdentity",connection.identity).put("connectionRevision",connection.revision).put("sections",source.getJSONObject("sections"))
                    .put("encrypted",true).put("overwrite",false).put("automaticUpload",false);
            Review review=new Review(this,scope,connection,encrypted,staging,original);reviews.add(review);retained=true;return review;
        }finally{
            if(password!=null)Arrays.fill(password,'\0');
            if(!retained&&staging!=null){try{if(encrypted!=null)Files.deleteIfExists(encrypted.toPath());}finally{staging.close();}}
        }
    }
    public static final class Review implements AutoCloseable {
        private final OwnerBackupWebDavHost issuer;private final JSONObject scope;
        private final OwnerBackupWebDavConfig.Snapshot connection;private final File encrypted;private final Object fileKey;
        private final OwnerBackupPendingFiles staging;private final RunControl original;private final long expires=System.nanoTime()+600_000_000_000L;
        private boolean confirmed,released;
        private Review(OwnerBackupWebDavHost issuer,JSONObject scope,OwnerBackupWebDavConfig.Snapshot connection,File encrypted,OwnerBackupPendingFiles staging,RunControl original)throws Exception {
            this.issuer=issuer;this.scope=new JSONObject(scope.toString());this.connection=connection;this.encrypted=encrypted;this.staging=staging;this.original=original;
            fileKey=Files.readAttributes(encrypted.toPath(),BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS).fileKey();if(fileKey==null)throw new SecurityException("原密文暂存身份不能确认");
        }
        public JSONObject metadata()throws Exception {return new JSONObject(scope.toString()).put("restoreVerified",false);}
        private synchronized void current()throws Exception {
            issuer.open();original.check();if(released||original.isPaused()||System.nanoTime()>expires)throw new SecurityException("原上传审阅已关闭、暂停或过期，请重新审阅");
            if(original.hasHostRun()&&!original.matchesHostRun(scope.getString("actionId"),scope.getString("actionId"),1))throw new SecurityException("原上传控制器不能用于另一任务");
            issuer.config.requireCurrent(connection);BasicFileAttributes actual=Files.readAttributes(encrypted.toPath(),BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            if(!actual.isRegularFile()||!fileKey.equals(actual.fileKey())||actual.size()!=scope.getLong("encryptedBytes"))throw new SecurityException("原密文暂存已变化");
        }
        public synchronized Confirmed confirm()throws Exception {current();if(confirmed)throw new SecurityException("原上传已确认，不能再次发送");confirmed=true;return new Confirmed(this);}
        @Override public void close()throws Exception {original.cancel();release();}
        private synchronized void release()throws Exception {
            if(released)return;released=true;issuer.reviews.remove(this);
            try{if(Files.exists(encrypted.toPath(),LinkOption.NOFOLLOW_LINKS)){BasicFileAttributes actual=Files.readAttributes(encrypted.toPath(),BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);if(!actual.isRegularFile()||!fileKey.equals(actual.fileKey()))throw new SecurityException("原密文暂存身份已变化，未清理其他文件");Files.delete(encrypted.toPath());}}
            finally{staging.close();}
        }
    }
    public static final class Confirmed {
        private final Review review;private boolean consumed;
        private Confirmed(Review review){this.review=review;}
        public JSONObject metadata()throws Exception {return review.metadata();}
        public String actionId()throws Exception {return review.scope.getString("actionId");}
        public RunControl original(){return review.original;}
        public void current()throws Exception {review.current();}
        private synchronized void consume()throws Exception {current();if(consumed)throw new SecurityException("原上传已经进入，只能核对原结果");consumed=true;}
    }
    public JSONObject upload(Confirmed confirmed)throws Exception {
        Objects.requireNonNull(confirmed);if(confirmed.review.issuer!=this)throw new SecurityException("上传确认不是原本机入口签发");
        String id=confirmed.actionId();RunControl original=confirmed.original();JSONObject row=null;AtomicBoolean entered=new AtomicBoolean();
        try{
            confirmed.consume();row=tasks.beginOwnerBackupUploadForOwner(confirmed);notifyChanged(id);if(row.getBoolean("knownOperation"))return row;
            original.bindHostRun(id,id,1);if(controls.putIfAbsent(id,original)!=null)throw new SecurityException("原上传已有执行，不能重新发送");
            JSONObject scope=confirmed.review.scope;OwnerBackupWebDavConfig.Snapshot connection=confirmed.review.connection;OwnerBackupWebDavTarget target=new OwnerBackupWebDavTarget(connection.collection);
            String authorization=config.authorization(connection);
            OwnerBackupWebDavClient.Result received=client.put(target,id,confirmed.review.encrypted,scope.getLong("encryptedBytes"),scope.getString("encryptedSha256"),authorization,original,()->{
                confirmed.current();tasks.requireOwnerBackupUploadCurrent(id,original);
                if(!tasks.beginOwnerBackupUploadPostForOwner(id,original))throw new SecurityException("原上传已消费派发，不能重新发送");entered.set(true);
            });
            JSONObject receipt=receipt(scope,received.status.equals("accepted")?"unknown":received.status,false).put("httpStatus",received.httpStatus);
            if(received.status.equals("accepted")){
                confirmed.current();client.readBack(target,id,scope.getLong("encryptedBytes"),scope.getString("encryptedSha256"),authorization,discard(),original);
                original.checkpoint();config.requireCurrent(connection);receipt=receipt(scope,"completed",true).put("httpStatus",received.httpStatus);
            }
            tasks.recordOwnerBackupUploadReceiptForOwner(id,receipt);notifyChanged(id);return tasks.ownerBackupUploadForOwner(id);
        }catch(Exception failed){
            if(row==null)throw failed;
            JSONObject actual=tasks.ownerBackupUploadForOwner(id);
            if(!"receipt".equals(actual.getString("operationState"))){
                // Even a failure before Call.execute is unknown once its durable entry was consumed.
                String status=entered.get()||"unknown".equals(actual.getString("operationState"))?"unknown":"cancelled";
                tasks.recordOwnerBackupUploadReceiptForOwner(id,receipt(actual.getJSONObject("scope"),status,false));
            }
            notifyChanged(id);return tasks.ownerBackupUploadForOwner(id).put("notice","原上传没有完整确认。保留本机备份，按原文件只读核对；不会自动再次发送。");
        }finally{controls.remove(id,original);confirmed.review.release();}
    }
    public JSONObject uploadRecord(String id)throws Exception {open();return tasks.ownerBackupUploadForOwner(id);}
    public JSONObject queryUpload(String id,RunControl original)throws Exception {
        open();JSONObject row=tasks.ownerBackupUploadForOwner(id),scope=row.getJSONObject("scope");
        if("prepared".equals(row.getString("operationState"))||"receipt".equals(row.getString("operationState"))&&!"completed".equals(row.optJSONObject("receipt").optString("status")))return row;
        OwnerBackupWebDavConfig.Snapshot connection=config.read();config.requireCurrent(connection);
        // A fresh owner read may use rotated credentials, but only at the exact original collection.
        if(!scope.getString("collection").equals(connection.collection))throw new SecurityException("当前 WebDAV 目录不是原上传目录；未改查或发送到其他目标");
        OwnerBackupWebDavTarget target=new OwnerBackupWebDavTarget(connection.collection);
        try{client.readBack(target,id,scope.getLong("encryptedBytes"),scope.getString("encryptedSha256"),config.authorization(connection),discard(),original);original.checkpoint();config.requireCurrent(connection);}
        catch(IOException|SecurityException unavailable){return tasks.ownerBackupUploadForOwner(id).put("notice","原远端密文尚未取得完全一致的字节证明；保持原状态，不重试上传。");}
        tasks.recordOwnerBackupUploadReceiptForOwner(id,receipt(scope,"completed",true).put("httpStatus",200));notifyChanged(id);return tasks.ownerBackupUploadForOwner(id);
    }
    public JSONObject cancelUpload(String id)throws Exception {
        JSONObject row=tasks.cancelOwnerBackupUploadForOwner(id);RunControl original=controls.get(id);if(original!=null)original.cancel();notifyChanged(id);return row;
    }
    public OwnerDataArchives.Selection readVersion(String name,long expectedRevision,char[] password,RunControl original)throws Exception {
        try{
            open();OwnerBackupEnvelope.requirePassword(password);OwnerBackupWebDavConfig.Snapshot connection=config.read();config.requireCurrent(connection);
            if(connection.revision!=expectedRevision)throw new SecurityException("WebDAV 目录或凭据已变化，请重新选择版本");
            OwnerBackupWebDavTarget target=new OwnerBackupWebDavTarget(connection.collection);target.file(name);
            try(OwnerBackupPendingFiles staging=OwnerBackupPendingFiles.begin(directory(transfers))){
                File downloaded=staging.pending(UUID.randomUUID().toString());
                try{
                    try(FileChannel channel=staging.createPlain(downloaded)){client.download(target,name,config.authorization(connection),Channels.newOutputStream(channel),original);channel.force(true);}
                    original.checkpoint();config.requireCurrent(connection);
                    try(InputStream input=Files.newInputStream(downloaded.toPath(),LinkOption.NOFOLLOW_LINKS)){OwnerDataArchives.Selection selected=archives.readEncrypted(input,password,original);original.checkpoint();config.requireCurrent(connection);return selected;}
                }finally{Files.deleteIfExists(downloaded.toPath());}
            }
        }finally{if(password!=null)Arrays.fill(password,'\0');}
    }
    private static JSONObject receipt(JSONObject scope,String status,boolean verified)throws Exception {
        JSONObject value=new JSONObject();for(String field:new String[]{"actionId","archiveSha256","encryptedSha256","encryptedBytes","targetIdentity"})value.put(field,scope.get(field));
        return value.put("status",status).put("remoteBytesVerified",verified).put("restoreVerified",false).put("replayAllowed",false).put("automaticUpload",false);
    }
    private void open()throws Exception {if(closed)throw new IllegalStateException("原备份入口已关闭");directory(root);}
    private static File directory(File file)throws Exception {if(Files.isSymbolicLink(file.toPath())||!file.isDirectory()&&!file.mkdirs()||!file.equals(file.getCanonicalFile()))throw new SecurityException("原备份传输目录不可用");return file;}
    private static OutputStream discard(){return new OutputStream(){@Override public void write(int b){}@Override public void write(byte[] b,int offset,int length){}};}
    private void notifyChanged(String id){try{changed.accept(id);}catch(RuntimeException unavailable){/* The original journal is already authoritative. */}}
    @Override public void close(){closed=true;for(RunControl original:controls.values())original.cancel();for(Review review:reviews)try{review.close();}catch(Exception retained){/* Original fixed archive and journal remain; idle recovery owns staging. */}}
}
