package dev.pocketengine.features.notifications;

import android.util.AtomicFile;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Owner opt-in only. Never stores notification bodies, sender identities, tasks or runs. */
final class NotificationConsentStore {
    private final AtomicFile file;
    NotificationConsentStore(File directory){file=new AtomicFile(new File(directory,"owner-notification-access.json"));}
    NotificationInbox.Policy load()throws Exception {
        requireRegularLocations();
        if(!file.getBaseFile().exists()&&!new File(file.getBaseFile().getPath()+".bak").exists())return null;
        byte[] bytes;try(java.io.InputStream stream=file.openRead();java.io.ByteArrayOutputStream output=new java.io.ByteArrayOutputStream()){byte[] buffer=new byte[1024];int size;while((size=stream.read(buffer))!=-1){if(output.size()+size>16_384)throw new IllegalArgumentException("通知授权配置过大");output.write(buffer,0,size);}bytes=output.toByteArray();}
        JSONObject json=new JSONObject(new String(bytes,StandardCharsets.UTF_8));if(json.getInt("schema")!=1)throw new IllegalArgumentException("通知授权版本不可用");
        String revision=json.getString("revision");if(!java.util.UUID.fromString(revision).toString().equals(revision))throw new IllegalArgumentException("通知授权修订无效");
        Object enabledValue=json.get("enabled");if(!(enabledValue instanceof Boolean))throw new IllegalArgumentException("通知授权开关无效");boolean enabled=(Boolean)enabledValue;
        JSONArray input=json.getJSONArray("packages");if(input.length()>NotificationInbox.MAX_PACKAGES)throw new IllegalArgumentException("通知授权范围过大");Set<String> packages=new LinkedHashSet<>();for(int i=0;i<input.length();i++)if(!packages.add(input.getString(i)))throw new IllegalArgumentException("通知应用范围重复");
        if(enabled)packages=NotificationInbox.validatePackages(packages);else if(!packages.isEmpty())throw new IllegalArgumentException("关闭的通知授权仍包含应用");return new NotificationInbox.Policy(revision,enabled,packages);
    }
    void save(NotificationInbox.Policy policy)throws Exception {
        byte[] bytes=new JSONObject().put("schema",1).put("revision",policy.revision).put("enabled",policy.enabled).put("packages",new JSONArray(policy.packages)).toString().getBytes(StandardCharsets.UTF_8);
        requireRegularLocations();FileOutputStream stream=null;
        try{
            stream=file.startWrite();stream.write(bytes);
            // AtomicFile.finishWrite logs sync/close/rename failures instead of throwing.
            // Obtain an observable sync result, then verify the committed target itself.
            stream.getFD().sync();file.finishWrite(stream);stream=null;
            requireRegularLocations();File target=file.getBaseFile();
            if(!target.isFile()||new File(target.getPath()+".new").exists())throw new java.io.IOException("通知授权尚未完成原子替换");
            byte[] actual=new byte[bytes.length];int offset=0;
            try(java.io.FileInputStream input=new java.io.FileInputStream(target)){
                while(offset<actual.length){int count=input.read(actual,offset,actual.length-offset);if(count<0)break;offset+=count;}
                if(offset!=actual.length||input.read()!=-1||!java.util.Arrays.equals(bytes,actual))throw new java.io.IOException("通知授权提交后的原字节核对失败");
            }
        }catch(Exception failed){if(stream!=null)file.failWrite(stream);throw failed;}
    }
    private void requireRegularLocations()throws java.io.IOException {
        for(File location:locations())if(location.exists()&&!location.isFile())throw new java.io.IOException("通知授权位置不是普通配置文件，保留原有内容");
    }
    private File[] locations(){File base=file.getBaseFile();return new File[]{base,new File(base.getPath()+".bak"),new File(base.getPath()+".new")};}
    boolean erase(){
        // A storage error must never turn an unknown directory into a successful grant.
        for(File location:locations())if(location.exists()&&!location.isFile())return false;
        file.delete();for(File location:locations())if(location.exists())return false;return true;
    }
}
