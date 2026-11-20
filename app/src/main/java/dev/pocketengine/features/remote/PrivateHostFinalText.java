package dev.pocketengine.features.remote;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;
import java.util.regex.Pattern;
import org.json.JSONObject;

/** The old Companion GET still contains all of output; only a proven final item's exact bytes may become a local reply. */
public final class PrivateHostFinalText {
    public static final String FORMAT="pocketworld.codex-final-in-output.v1";
    private static final Set<String> PROOF=Set.of("format","threadId","turnId","itemId","startByte","bytes","sha256");
    private static final Pattern ID=Pattern.compile("[A-Za-z0-9_.:-]{1,200}");
    private static final Pattern SECRET=Pattern.compile(
            "-----BEGIN(?: [A-Z0-9]+)* PRIVATE KEY-----|\\bBearer\\s+[A-Za-z0-9._~+/-]{16,}|"
            +"(?:sk-(?:proj-)?[A-Za-z0-9_-]{16,}|gh[pousr]_[A-Za-z0-9]{16,}|github_pat_[A-Za-z0-9_]{16,}|xox[baprs]-[A-Za-z0-9-]{16,})|"
            +"(?:api[_ -]?key|access[_ -]?token|client[_ -]?secret|password)\\s*[:=]\\s*[\\x22\\x27]?[A-Za-z0-9._~+/-]{12,}",Pattern.CASE_INSENSITIVE);
    private PrivateHostFinalText() {}

    public static String digest(String value)throws Exception{return sha(utf8(value));}
    private static byte[] utf8(String value)throws Exception{
        ByteBuffer bytes=StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value));
        byte[] raw=new byte[bytes.remaining()];bytes.get(raw);return raw;
    }
    private static String sha(byte[] raw)throws Exception{StringBuilder value=new StringBuilder(64);for(byte b:MessageDigest.getInstance("SHA-256").digest(raw))value.append(Character.forDigit((b>>>4)&15,16)).append(Character.forDigit(b&15,16));return value.toString();}
    private static long number(JSONObject value,String name){Object v=value.opt(name);return v instanceof Integer||v instanceof Long?((Number)v).longValue():-1;}
    private static boolean identifier(Object raw){return raw instanceof String val&&ID.matcher(val).matches();}
    public static boolean safeReply(String text){return !text.isBlank()&&!SECRET.matcher(text).find()&&text.chars().allMatch(c->c=='\n'||c=='\r'||c=='\t'||c>=32&&(c<127||c>159));}
    public record Result(String stage,String text,String sha256){}
    static Result read(JSONObject receipt,String credential)throws Exception{
        String status=receipt.optString("status");
        if(Set.of("queued","running","stopping","awaiting_approval","awaiting_input").contains(status))return new Result("not_ready",null,null);
        JSONObject proof=receipt.optJSONObject("originalPrivateFinal");
        if(!"completed".equals(status)||proof==null||proof.length()!=PROOF.size()||!PROOF.stream().allMatch(proof::has)
                ||!FORMAT.equals(proof.opt("format"))||!"codex_app_server".equals(receipt.opt("protocol"))
                ||receipt.opt("outputTruncated")!=Boolean.FALSE||receipt.opt("protocolVerified")!=Boolean.TRUE
                ||receipt.opt("readOnly")!=Boolean.TRUE||receipt.opt("readOnlyConfirmed")!=Boolean.TRUE
                ||receipt.opt("externalToolsDisabled")!=Boolean.TRUE||receipt.opt("turnStartAcknowledged")!=Boolean.TRUE
                ||!identifier(proof.opt("threadId"))||!identifier(proof.opt("turnId"))||!identifier(proof.opt("itemId"))
                ||!proof.opt("threadId").equals(receipt.opt("threadId"))||!proof.opt("turnId").equals(receipt.opt("turnId"))
                ||!(receipt.opt("output") instanceof String whole)||whole.length()>196608)
            return new Result("no_verified_text",null,null);
        long start=number(proof,"startByte"),count=number(proof,"bytes");byte[] raw=utf8(whole);
        if(start<0||count<1||count>12000||start>raw.length||count>raw.length-start)return new Result("no_verified_text",null,null);
        byte[] bytes=java.util.Arrays.copyOfRange(raw,(int)start,(int)(start+count));
        String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        String hash=sha(bytes);
        if(!hash.equals(proof.opt("sha256"))||!safeReply(text)||credential!=null&&credential.length()>=32&&text.contains(credential))
            return new Result("no_verified_text",null,null);
        return new Result("verified",text,hash);
    }
}
