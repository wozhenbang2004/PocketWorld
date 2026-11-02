package dev.pocketengine.features.plugins;

import java.net.URI;
import java.nio.CharBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.json.JSONObject;

/** Native source evidence, independently authored. Remote text never supplies host authority. */
public final class McpSkillSource {
    private McpSkillSource(){}
    @FunctionalInterface public interface Validator { void check(JSONObject nativeBinding)throws Exception; }

    /** Only the original PluginsModule can issue fetched bytes and its current-installation check. */
    public static final class Fetched {
        private final String binding,text;
        private final Validator validator;
        Fetched(JSONObject binding,String text,Validator validator)throws Exception {
            validateUri(binding.getString("resourceURI"));
            if(!sha256(text).equals(binding.getString("contentDigest")))throw new SecurityException("MCP技能来源字节与摘要不符");
            this.binding=binding.toString();this.text=text;this.validator=java.util.Objects.requireNonNull(validator);
        }
        public JSONObject binding()throws Exception{return new JSONObject(binding);}
        public String text(){return text;}
        public void checkCurrent()throws Exception{validator.check(binding());}
    }

    public static void validateUri(String value)throws Exception {
        if(value==null||value.length()>2048||value.chars().anyMatch(c->c<33||c==127)||value.indexOf('\\')>=0)throw new IllegalArgumentException("MCP技能URI无效");
        URI uri=new URI(value);
        if(!"skill".equals(uri.getScheme())||uri.isOpaque()||uri.getRawAuthority()==null||uri.getRawAuthority().isEmpty()
                ||uri.getRawAuthority().contains("@")||uri.getRawAuthority().contains(":")||uri.getRawQuery()!=null||uri.getRawFragment()!=null)
            throw new IllegalArgumentException("MCP技能来源只接受无凭据、查询或片段的skill://资源URI");
        String path=uri.getPath();if(path!=null)for(String part:path.split("/",-1))if(part.equals(".")||part.equals("..")||part.indexOf('\\')>=0)throw new IllegalArgumentException("MCP技能URI不允许路径穿越");
    }
    static String sha256(String text)throws Exception {
        java.nio.ByteBuffer exact=StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(text));
        MessageDigest digest=MessageDigest.getInstance("SHA-256");digest.update(exact);byte[] bytes=digest.digest();StringBuilder hex=new StringBuilder();
        for(byte value:bytes)hex.append(String.format(java.util.Locale.ROOT,"%02x",value&255));return hex.toString();
    }
}
