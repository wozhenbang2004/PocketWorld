package dev.pocketengine.features.workspace;

import dev.pocketengine.store.SecretVault;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import org.json.JSONObject;

/** Native connection preference only. Upload intents/results remain in the original TaskStore. */
final class OwnerBackupWebDavConfig {
    private static final Object LOCK=new Object();
    private static final String FORMAT="pocketworld.owner-backup-webdav.v1";
    private final File file;private final SecretVault vault;
    OwnerBackupWebDavConfig(File nativeRoot,SecretVault vault){file=new File(nativeRoot,"connection.json");this.vault=Objects.requireNonNull(vault);}
    static final class Snapshot {
        final long revision;final boolean enabled;final String collection,username,slot,identity;
        Snapshot(JSONObject row)throws Exception{
            Set<String> keys=new HashSet<>();row.keys().forEachRemaining(keys::add);
            if(!keys.equals(Set.of("format","revision","enabled","collection","username","secretSlot"))||!FORMAT.equals(row.getString("format"))||!(row.get("revision") instanceof Integer||row.get("revision") instanceof Long)||!(row.get("enabled") instanceof Boolean))throw new SecurityException("原 WebDAV 配置不能确认，未采用默认替代");
            revision=row.getLong("revision");enabled=row.getBoolean("enabled");collection=row.getString("collection");username=row.getString("username");slot=row.getString("secretSlot");
            if(revision<0||!slot.isEmpty()&&!slot.matches("backup_webdav:[a-f0-9-]{36}"))throw new SecurityException("原 WebDAV 配置身份不完整");
            if(!collection.isEmpty()&&!new OwnerBackupWebDavTarget(collection).collection.equals(collection))throw new SecurityException("原 WebDAV 目录不是固定规范地址");
            user(username);if(username.isEmpty()!=slot.isEmpty()||enabled&&collection.isEmpty())throw new SecurityException("原 WebDAV 配置凭据范围不完整");
            identity=OwnerBackupWebDavTarget.sha(row.getString("format")+"\n"+revision+"\n"+enabled+"\n"+collection+"\n"+username+"\n"+slot);
        }
        JSONObject view()throws Exception{return new JSONObject().put("revision",revision).put("enabled",enabled).put("collection",collection).put("username",username).put("credentialConfigured",!slot.isEmpty()).put("automaticUpload",false).put("redirects",false).put("retryUnknown",false).put("authentication","basic_utf8_over_https");}
    }
    Snapshot read()throws Exception{synchronized(LOCK){
        if(!file.exists())return new Snapshot(row(0,false,"","",""));
        if(Files.isSymbolicLink(file.toPath())||!file.isFile()||file.length()>16384)throw new SecurityException("原 WebDAV 配置无法确认");
        return new Snapshot(new JSONObject(new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8)));
    }}
    JSONObject save(long expectedRevision,String collection,String username,char[] password,boolean enabled)throws Exception{
        synchronized(LOCK){String created="";
            try{
                Snapshot old=read();if(old.revision!=expectedRevision)throw new SecurityException("WebDAV 配置已变化，请重新读取后保存");
                if(collection==null)throw new IllegalArgumentException("请填写 WebDAV 目录");String endpoint=collection.isEmpty()?"":new OwnerBackupWebDavTarget(collection).collection;user(username);
                if(enabled&&endpoint.isEmpty())throw new IllegalArgumentException("请先指定 HTTPS WebDAV 目录");
                String slot="";
                if(!username.isEmpty()){
                    if(password!=null&&password.length>0){if(password.length>4096)throw new IllegalArgumentException("WebDAV 凭据过长");for(char c:password)if(c==0||c=='\r'||c=='\n')throw new IllegalArgumentException("WebDAV 凭据含不支持字符");
                        created="backup_webdav:"+UUID.randomUUID();vault.create(created,new String(password));slot=created;
                    }else{if(!endpoint.equals(old.collection)||!username.equals(old.username)||old.slot.isEmpty()||!vault.contains(old.slot))throw new SecurityException("新目录或账号须重新填写专用凭据，不能沿用另一目标的凭据");slot=old.slot;}
                }else if(password!=null&&password.length>0)throw new IllegalArgumentException("填写口令时必须明确用户名");
                JSONObject next=row(Math.addExact(old.revision,1),enabled,endpoint,username,slot);Snapshot checked=new Snapshot(next);write(next);
                created="";if(!old.slot.isEmpty()&&!old.slot.equals(slot))try{vault.remove(old.slot);}catch(Exception retained){/* An unreferenced old slot grants no connection; never roll back the committed target. */}
                return checked.view();
            }finally{if(password!=null)Arrays.fill(password,'\0');if(!created.isEmpty())try{vault.remove(created);}catch(Exception retained){/* No configuration points at this failed candidate slot. */}}
        }
    }
    void requireCurrent(Snapshot expected)throws Exception{Snapshot actual=read();if(!actual.enabled||!actual.identity.equals(expected.identity))throw new SecurityException("原 WebDAV 目标已停用或改变，请重新审阅；没有发送到替代目标");}
    String authorization(Snapshot expected)throws Exception{synchronized(LOCK){
        requireCurrent(expected);if(expected.username.isEmpty())return "";String password=vault.get(expected.slot);if(password.isEmpty())throw new SecurityException("原 WebDAV 凭据已不存在，请重新配置");
        // This explicit connection mode follows RFC 7617 UTF-8/NFC; no challenge retries.
        byte[] bytes=java.text.Normalizer.normalize(expected.username+":"+password,java.text.Normalizer.Form.NFC).getBytes(StandardCharsets.UTF_8);try{return "Basic "+Base64.getEncoder().encodeToString(bytes);}finally{Arrays.fill(bytes,(byte)0);}
    }}
    private void write(JSONObject value)throws Exception{
        Path pending=file.toPath().resolveSibling("connection-"+UUID.randomUUID()+".pending");boolean moved=false;
        try{
            byte[] bytes=value.toString().getBytes(StandardCharsets.UTF_8);
            try(FileChannel channel=FileChannel.open(pending,Set.of(StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS),PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))){ByteBuffer buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())channel.write(buffer);channel.force(true);}
            Files.move(pending,file.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);moved=true;
        }finally{if(!moved)Files.deleteIfExists(pending);}
    }
    private static JSONObject row(long revision,boolean enabled,String collection,String username,String slot)throws Exception{return new JSONObject().put("format",FORMAT).put("revision",revision).put("enabled",enabled).put("collection",collection).put("username",username).put("secretSlot",slot);}
    private static void user(String value){if(value==null||value.length()>256||value.indexOf(':')>=0||value.codePoints().anyMatch(c->c<32||c==127))throw new IllegalArgumentException("WebDAV 用户名无效");}
}
