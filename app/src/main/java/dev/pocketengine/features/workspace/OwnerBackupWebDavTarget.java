package dev.pocketengine.features.workspace;

import java.net.URI;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/** Exact owner-selected collection. No discovery, redirects or credentials in URLs. */
public final class OwnerBackupWebDavTarget {
    public final String collection;
    private final boolean loopback;
    public OwnerBackupWebDavTarget(String input){this(input,false);}
    private OwnerBackupWebDavTarget(String input,boolean loopback){
        if(input==null||input.length()>2048||!input.equals(input.trim())||input.codePoints().anyMatch(c->c<33||c==127))throw invalid();
        URI uri;try{uri=new URI(input);}catch(Exception malformed){throw invalid();}
        String scheme=uri.getScheme(),host=uri.getHost(),path=uri.getRawPath();int port=uri.getPort();
        if(uri.isOpaque()||host==null||host.isEmpty()||host.contains("%")||uri.getRawUserInfo()!=null||uri.getRawQuery()!=null||uri.getRawFragment()!=null||port==0||port>65535)throw invalid();
        if(loopback){if(!"http".equals(scheme)||!"127.0.0.1".equals(host)||port<1)throw invalid();}
        else if(!"https".equals(scheme))throw new IllegalArgumentException("WebDAV 备份仅支持明确的 HTTPS 目录；不降级 TLS");
        if(path==null||path.isEmpty())path="/";
        if(!path.startsWith("/")||!path.endsWith("/"))throw new IllegalArgumentException("请填写以 / 结尾的 WebDAV 目录 URL");
        for(String part:path.split("/",-1)){
            String decoded=decode(part);
            if(decoded.equals(".")||decoded.equals("..")||decoded.indexOf('/')>=0||decoded.indexOf('\\')>=0||decoded.indexOf('%')>=0||decoded.codePoints().anyMatch(c->c<32||c==127))throw invalid();
        }
        if(path.contains("//")||path.indexOf('\\')>=0)throw invalid();
        String authority=host.toLowerCase(Locale.ROOT);if(authority.indexOf(':')>=0&&!authority.startsWith("["))authority="["+authority+"]";
        if(port!=-1&&!(scheme.equals("https")&&port==443))authority+=":"+port;
        collection=URI.create(scheme+"://"+authority+path).toASCIIString();this.loopback=loopback;
    }
    /** Only a direct package-local test constructor; never a setting, model input or TLS exception. */
    static OwnerBackupWebDavTarget loopback(int port){return new OwnerBackupWebDavTarget("http://127.0.0.1:"+port+"/backups/",true);}
    boolean testLoopback(){return loopback;}
    public String destination(String originalAction){
        if(originalAction==null||!originalAction.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))throw new SecurityException("缺少原备份动作身份");
        return file("PocketWorld-"+originalAction+".pwbackup");
    }
    public String file(String name){if(name==null||!name.matches("PocketWorld-[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}\\.pwbackup"))throw new IllegalArgumentException("请选择 PocketWorld 生成的固定 .pwbackup 文件名");return collection+name;}
    String childName(String href){
        try{
            URI root=URI.create(collection),item=root.resolve(new URI(href));
            if(item.getRawUserInfo()!=null||item.getRawQuery()!=null||item.getRawFragment()!=null||!root.getScheme().equals(item.getScheme())||!root.getHost().equalsIgnoreCase(item.getHost())||effectivePort(root)!=effectivePort(item))return null;
            String path=item.getRawPath(),base=root.getRawPath();if(path==null||!path.startsWith(base))return null;
            String name=path.substring(base.length());file(name);return name;
        }catch(Exception rejected){return null;}
    }
    private static int effectivePort(URI uri){return uri.getPort()<0?("https".equals(uri.getScheme())?443:80):uri.getPort();}
    public String identity(){return sha(collection);}
    private static String decode(String segment){
        try{ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            for(int i=0;i<segment.length();){char c=segment.charAt(i);
                if(c=='%'){if(i+2>=segment.length())throw invalid();int high=Character.digit(segment.charAt(i+1),16),low=Character.digit(segment.charAt(i+2),16);if(high<0||low<0)throw invalid();bytes.write((high<<4)|low);i+=3;}
                else{int cp=segment.codePointAt(i);if(Character.isSurrogate(c)&&Character.charCount(cp)!=2)throw invalid();byte[] encoded=new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8);bytes.write(encoded,0,encoded.length);i+=Character.charCount(cp);}
            }
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
        }catch(Exception malformed){throw invalid();}
    }
    static String sha(String value){try{return hex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception unavailable){throw new IllegalStateException("SHA-256 不可用",unavailable);}}
    static String hex(byte[] bytes){StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("WebDAV 目录 URL 无效；不接受账号、查询、片段或路径逃逸");}
}
