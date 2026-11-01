package dev.pocketengine.features.ssh;

import java.net.IDN;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Locale;
import org.json.JSONObject;

/** Endpoint/workspace identity. Credentials are separate Keystore-backed references. */
public final class SshProfile {
    public final String id,name,host,user,root,authType,pinnedKey,configRevision,credentialRevision;
    public final int port;
    private final String nativeBackupOrigin;
    public SshProfile(JSONObject value)throws Exception {
        id=text(value,"id",80);if(!id.matches("[A-Za-z0-9_-]{1,80}"))throw new IllegalArgumentException("SSH连接编号无效");
        name=text(value,"name",80);String address=text(value,"host",253);
        if(address.startsWith("[")&&address.endsWith("]"))address=address.substring(1,address.length()-1);
        host=address.contains(":")?address.toLowerCase(Locale.ROOT):IDN.toASCII(address).toLowerCase(Locale.ROOT);
        if(!host.matches("[A-Za-z0-9_.:%-]{1,253}"))throw new IllegalArgumentException("SSH主机无效");
        Object number=value.opt("port");double p=number==null?22:number instanceof Number?((Number)number).doubleValue():-1;
        if(!Double.isFinite(p)||p!=Math.rint(p)||p<1||p>65535)throw new IllegalArgumentException("SSH端口无效");port=(int)p;
        user=text(value,"username",64);if(!user.matches("[A-Za-z_][A-Za-z0-9_.@-]{0,63}"))throw new IllegalArgumentException("SSH用户名无效");
        root=text(value,"workspace",1024);if(!root.startsWith("/")||root.contains("//")||root.length()>1&&root.endsWith("/"))throw new IllegalArgumentException("工作区需为无结尾斜线的绝对POSIX路径");
        for(String part:root.split("/"))if(part.equals(".")||part.equals("..")||part.contains("\\")||part.matches(".*[\\*?\\[\\]].*"))throw new IllegalArgumentException("工作区路径无效");
        authType=value.optString("authType","key");if(!authType.equals("key")&&!authType.equals("password"))throw new IllegalArgumentException("SSH认证方式无效");
        pinnedKey=value.optString("pinnedKey");if(!pinnedKey.isEmpty()&&(pinnedKey.length()>65536||Base64.getDecoder().decode(pinnedKey).length<16))throw new IllegalArgumentException("主机密钥记录无效");
        configRevision=revision(value,"configRevision");credentialRevision=revision(value,"credentialRevision");
        JSONObject backup=value.optJSONObject("nativeBackupOrigin");nativeBackupOrigin=backup==null?null:backup.toString();
    }
    public String binding(){return host+"\n"+port+"\n"+user+"\n"+root;}
    public String bindingHash(){String identity=id+"\n"+binding()+"\n"+authType+"\n"+pinnedKey;if(!configRevision.isEmpty()||!credentialRevision.isEmpty())identity+="\nconfigRevision="+configRevision+"\ncredentialRevision="+credentialRevision;return hash(identity.getBytes(StandardCharsets.UTF_8));}
    public void requireBinding(String expected){if(expected==null||!bindingHash().equals(expected))throw new SecurityException("SSH连接已改绑，请从当前主机与工作区重新取得任务授权");}
    public String accountKey(){if(pinnedKey.isEmpty())throw new SecurityException("SSH账号资源需要已确认的主机指纹");return hash((fingerprint(Base64.getDecoder().decode(pinnedKey))+"\n"+user).getBytes(StandardCharsets.UTF_8));}
    public String credentialSlot(){String identity=id+"\n"+binding();if(!credentialRevision.isEmpty())identity+="\ncredentialRevision="+credentialRevision;return "ssh.credentials."+hash(identity.getBytes(StandardCharsets.UTF_8));}
    public String hostAlias(){return "pocketworld-"+bindingHash();}
    public JSONObject document()throws Exception{return new JSONObject().put("id",id).put("name",name).put("host",host).put("port",port).put("username",user).put("workspace",root).put("authType",authType).put("pinnedKey",pinnedKey).put("configRevision",configRevision).put("credentialRevision",credentialRevision).putOpt("nativeBackupOrigin",nativeBackupOrigin==null?null:new JSONObject(nativeBackupOrigin));}
    private static String revision(JSONObject value,String key){Object raw=value.opt(key);if(raw==null)return "";if(!(raw instanceof String))throw new IllegalArgumentException("SSH配置代次无效");String revision=(String)raw;if(!revision.isEmpty()&&!revision.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))throw new IllegalArgumentException("SSH配置代次无效");return revision;}
    public static String relative(String value,boolean directory){
        if(value==null||value.length()>1024||value.startsWith("/")||value.contains("\\")||controls(value)||value.matches(".*[*?\\[\\]].*"))throw new IllegalArgumentException("文件路径无效");
        if(value.isEmpty()){if(directory)return "";throw new IllegalArgumentException("请指定文件");}
        for(String part:value.split("/",-1))if(part.isEmpty()||part.equals(".")||part.equals(".."))throw new IllegalArgumentException("文件路径不可离开所选工作区");return value;
    }
    public static String quote(String value){if(value.indexOf('\0')>=0)throw new IllegalArgumentException("Shell参数无效");return "'"+value.replace("'","'\"'\"'")+"'";}
    static String text(JSONObject value,String key,int limit){Object field=value.opt(key);if(!(field instanceof String))throw new IllegalArgumentException("缺少字段："+key);String s=((String)field).trim();if(s.isEmpty()||s.length()>limit||controls(s))throw new IllegalArgumentException("字段无效："+key);return s;}
    private static boolean controls(String value){for(int i=0;i<value.length();i++)if(value.charAt(i)<32||value.charAt(i)==127)return true;return false;}
    public static String hash(byte[] bytes){try{StringBuilder out=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();}catch(Exception impossible){throw new IllegalStateException(impossible);}}
    public static String fingerprint(byte[] key){try{return "SHA256:"+Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(key));}catch(Exception impossible){throw new IllegalStateException(impossible);}}
}
