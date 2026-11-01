package dev.pocketengine.runtime.extensions;

import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.json.JSONObject;

/** Exact owner-reviewed source, not an installation, grant, task or script execution approval. */
public final class ExtensionProgram {
    public static final String API_VERSION="pocketworld.extension.v1";
    public static final int MAX_SOURCE_BYTES=131072;
    public final String id,version,installationId,sourceSha256;
    public final long approvalRevision;
    public final int maxMillis;
    private final byte[] source;

    public ExtensionProgram(String id,String version,String installationId,byte[] sourceUtf8,String expectedSha256,int maxMillis)throws Exception {
        this(id,version,installationId,1,sourceUtf8,expectedSha256,maxMillis);
    }
    /** Catalogue increments approvalRevision on every enable/disable/approval or package change. */
    public ExtensionProgram(String id,String version,String installationId,long approvalRevision,byte[] sourceUtf8,String expectedSha256,int maxMillis)throws Exception {
        if(id==null||!id.matches("[A-Za-z][A-Za-z0-9_.-]{0,79}")||version==null||version.isBlank()||version.length()>40
                ||version.chars().anyMatch(Character::isISOControl)||installationId==null||!installationId.matches("[a-f0-9-]{36}"))throw new IllegalArgumentException("扩展身份无效");
        if(approvalRevision<1||sourceUtf8==null||sourceUtf8.length==0||sourceUtf8.length>MAX_SOURCE_BYTES||maxMillis<50||maxMillis>2000)throw new IllegalArgumentException("扩展源码、授权版本或单次执行上限无效");
        source=sourceUtf8.clone();String text=decode(source);if(text.indexOf('\0')>=0)throw new IllegalArgumentException("扩展源码含无效字符");
        sourceSha256=sha256(source);
        if(!sourceSha256.equals(expectedSha256))throw new SecurityException("扩展源码与审阅摘要不符");
        this.id=id;this.version=version;this.installationId=installationId;this.approvalRevision=approvalRevision;this.maxMillis=maxMillis;
    }

    public byte[] sourceBytes(){return source.clone();}
    String sourceText()throws Exception{return decode(source);}
    public JSONObject identity()throws Exception {
        return new JSONObject().put("id",id).put("version",version).put("installationId",installationId).put("approvalRevision",approvalRevision).put("sourceSha256",sourceSha256)
                .put("apiVersion",API_VERSION).put("execution","isolated_pure_javascript").put("hostBridge",false).put("maxMillis",maxMillis);
    }
    static String decode(byte[] bytes)throws Exception {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }
    public static String sha256(byte[] bytes)throws Exception {
        byte[] hash=MessageDigest.getInstance("SHA-256").digest(bytes);StringBuilder out=new StringBuilder();for(byte value:hash)out.append(String.format(java.util.Locale.ROOT,"%02x",value&255));return out.toString();
    }
}
