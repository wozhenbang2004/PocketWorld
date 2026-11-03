package dev.pocketengine.features.local.project;

import java.util.Arrays;
import java.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;

/** Exact fixed-length byte transformation only. The original plan/owner approval and CAS
 * writer still authorize any mutation. A contract string or Base64 body is never a grant. */
final class ProjectHexOverwrite {
    static final String CONTRACT="pocketworld.exact-hex-overwrite.v1";
    static final int MAX_RANGE_BYTES=2048;
    private ProjectHexOverwrite() {}
    static byte[] hex(String value)throws Exception {
        if(value==null||value.isEmpty()||value.length()>MAX_RANGE_BYTES*2||(value.length()&1)!=0)throw new IllegalArgumentException("hex_overwrite需1至2048字节的连续ASCII十六进制旧/新字串");byte[] result=new byte[value.length()/2];
        for(int i=0;i<result.length;i++){char a=value.charAt(2*i),b=value.charAt(2*i+1);int high=Character.digit(a,16),low=Character.digit(b,16);if(a>127||b>127||high<0||low<0)throw new IllegalArgumentException("hex_overwrite不接受非ASCII十六进制、空格、0x或通配符");result[i]=(byte)((high<<4)|low);}return result;
    }
    private static String hex(byte[] bytes){char[] result=new char[bytes.length*2];char[] digits="0123456789abcdef".toCharArray();for(int i=0;i<bytes.length;i++){result[2*i]=digits[(bytes[i]&255)>>>4];result[2*i+1]=digits[bytes[i]&15];}return new String(result);}
    private static int integer(Object value,String field)throws Exception{if(!(value instanceof Integer||value instanceof Long)||((Number)value).longValue()<0||((Number)value).longValue()>ProjectExactPatch.MAX_TEXT_BYTES)throw new IllegalArgumentException("原字节覆盖"+field+"需范围内准确整数");return ((Number)value).intValue();}
    static JSONObject prepare(String path,byte[] actual,JSONObject edit)throws Exception {
        for(String mixed:new String[]{"content","replacements","fromPath","expectedSourceHash"})if(edit.has(mixed))throw new IllegalArgumentException("hex_overwrite不能混合文字、追加或复制参数");if(actual==null||actual.length>ProjectExactPatch.MAX_TEXT_BYTES)throw new IllegalArgumentException("原字节覆盖只支持既有256KiB以内source文件");
        String expected=ProjectSources.sha256(actual);if(!expected.equals(edit.getString("expectedHash")))throw new SecurityException("原字节覆盖的整文件SHA已变化");int offset=integer(edit.get("byteOffset"),"byteOffset");byte[] old=hex(edit.getString("expectedHex")),next=hex(edit.getString("newHex"));if(old.length!=next.length||offset>actual.length||old.length>actual.length-offset||Arrays.equals(old,next))throw new IllegalArgumentException("原字节覆盖仅允许既有范围内等长真实变化，不插入、扩展、截断或空操作");
        for(int i=0;i<old.length;i++)if(actual[offset+i]!=old[i])throw new SecurityException("原字节覆盖的指定旧字节不匹配，未移位搜索");byte[] after=actual.clone();System.arraycopy(next,0,after,offset,next.length);
        return row(path,actual,after,offset,old,next);
    }
    private static JSONObject row(String path,byte[] before,byte[] after,int offset,byte[] old,byte[] next)throws Exception {
        JSONObject change=new JSONObject().put("kind","hex_overwrite").put("byteOffset",offset).put("byteLength",old.length).put("offsetUnit","bytes").put("oldHex",hex(old)).put("newHex",hex(next));
        return new JSONObject().put("path",path).put("action","replace").put("encoding","base64").put("bytePatchContract",CONTRACT).put("expectedHash",ProjectSources.sha256(before)).put("afterHash",ProjectSources.sha256(after)).put("beforeContentBase64",Base64.getEncoder().encodeToString(before)).put("contentBase64",Base64.getEncoder().encodeToString(after)).put("beforeBytes",before.length).put("afterBytes",after.length).put("changes",new JSONArray().put(change));
    }
    private static byte[] decode(JSONObject row,String field)throws Exception{String encoded=row.getString(field);if(encoded.length()>((ProjectExactPatch.MAX_TEXT_BYTES+2)/3)*4)throw new IllegalArgumentException("原字节覆盖正文超过256KiB");byte[] value;try{value=Base64.getDecoder().decode(encoded);}catch(IllegalArgumentException invalid){throw new SecurityException("原字节覆盖正文不是完整Base64",invalid);}if(value.length>ProjectExactPatch.MAX_TEXT_BYTES||!encoded.equals(Base64.getEncoder().encodeToString(value)))throw new SecurityException("原字节覆盖正文不是canonical完整字节");return value;}
    static byte[][] validated(JSONObject file)throws Exception {
        if(!"base64".equals(file.optString("encoding"))||!CONTRACT.equals(file.optString("bytePatchContract"))||!"replace".equals(file.optString("action"))||file.has("content")||file.has("beforeContent")||file.has("copiedFrom"))throw new SecurityException("原字节覆盖字段、编码或动作不明确，不能转成通用二进制写入");
        byte[] before=decode(file,"beforeContentBase64"),after=decode(file,"contentBase64");if(before.length!=after.length||before.length!=integer(file.get("beforeBytes"),"beforeBytes")||after.length!=integer(file.get("afterBytes"),"afterBytes")||!ProjectSources.sha256(before).equals(file.getString("expectedHash"))||!ProjectSources.sha256(after).equals(file.getString("afterHash")))throw new SecurityException("原字节覆盖长度或完整前后SHA不同");
        JSONArray changes=file.getJSONArray("changes");if(changes.length()!=1)throw new SecurityException("原字节覆盖只允许一个明确等长窗口");JSONObject change=changes.getJSONObject(0);if(!"hex_overwrite".equals(change.optString("kind"))||!"bytes".equals(change.optString("offsetUnit")))throw new SecurityException("原字节覆盖单位/类型不同");int offset=integer(change.get("byteOffset"),"byteOffset");byte[] old=hex(change.getString("oldHex")),next=hex(change.getString("newHex"));if(old.length!=next.length||old.length!=integer(change.get("byteLength"),"byteLength")||Arrays.equals(old,next)||offset>before.length||old.length>before.length-offset)throw new SecurityException("原字节覆盖不是指定等长范围");
        for(int i=0;i<before.length;i++){boolean in=i>=offset&&i<offset+old.length;if(in&&before[i]!=old[i-offset]||after[i]!=(in?next[i-offset]:before[i]))throw new SecurityException("原字节覆盖实际变化超出审阅窗口或指定旧字节不同");}return new byte[][]{before,after};
    }
    static JSONObject reverse(JSONObject original,String operation)throws Exception {byte[][] bytes=validated(original);JSONObject change=original.getJSONArray("changes").getJSONObject(0);return row(original.getString("path"),bytes[1],bytes[0],change.getInt("byteOffset"),hex(change.getString("newHex")),hex(change.getString("oldHex"))).put("revertsOperationId",ProjectBuildPlan.identity(operation));}
}
