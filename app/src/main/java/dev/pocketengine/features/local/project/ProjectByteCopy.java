package dev.pocketengine.features.local.project;

import dev.pocketengine.features.workspace.VersionedWorkspace;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.json.JSONArray;
import org.json.JSONObject;

/** Frozen same-checkout source bytes, never model supplied Base64 or a new writer/grant.
 * New targets only. The inverse can only remove this original copy under a new approval. */
final class ProjectByteCopy {
    static final String CONTRACT="pocketworld.exact-source-byte-copy.v1";
    private ProjectByteCopy() {}
    static boolean matches(JSONObject file){return CONTRACT.equals(file.optString("bytePatchContract"));}
    private static int count(Object value)throws Exception{if(!(value instanceof Integer||value instanceof Long)||((Number)value).longValue()<0||((Number)value).longValue()>ProjectExactPatch.MAX_TEXT_BYTES)throw new SecurityException("原字节复制大小需0至256KiB内准确整数");return ((Number)value).intValue();}
    private static Set<String> keys(JSONObject object){TreeSet<String> keys=new TreeSet<>();object.keys().forEachRemaining(keys::add);return keys;}
    private static String path(String value)throws Exception{String path=VersionedWorkspace.path(value);if(!ProjectSources.sourcePath(path))throw new SecurityException("原字节复制只支持同副本的source路径，不读写依赖或构建输出");return path;}
    private static String encoded(byte[] bytes){return Base64.getEncoder().encodeToString(bytes);}
    private static byte[] decode(JSONObject file,String field)throws Exception{String value=file.getString(field);if(value.length()>((ProjectExactPatch.MAX_TEXT_BYTES+2)/3)*4)throw new SecurityException("原字节复制正文超过256KiB");byte[] bytes;try{bytes=Base64.getDecoder().decode(value);}catch(IllegalArgumentException invalid){throw new SecurityException("原字节复制正文不是完整Base64",invalid);}if(bytes.length>ProjectExactPatch.MAX_TEXT_BYTES||!encoded(bytes).equals(value))throw new SecurityException("原字节复制需canonical完整字节");return bytes;}
    static JSONObject prepare(String target,Map<String,byte[]> frozen,JSONObject edit)throws Exception {
        for(String mixed:new String[]{"content","replacements","contentBase64","beforeContentBase64","newHex","expectedHex","byteOffset"})if(edit.has(mixed))throw new IllegalArgumentException("copy_bytes只复制实际固定source，不接受模型正文、Base64或hex修改");
        target=path(target);String from=path(edit.getString("fromPath"));if(target.equals(from)||frozen.containsKey(target)||!"absent".equals(edit.getString("expectedHash")))throw new SecurityException("copy_bytes只创建不同且确实absent的目标，不覆盖既有二进制或文字文件");byte[] source=frozen.get(from);if(source==null)throw new SecurityException("原字节复制源不存在");if(source.length>ProjectExactPatch.MAX_TEXT_BYTES)throw new IllegalArgumentException("原字节复制源超过256KiB，未截断或发布部分目标");String sha=ProjectSources.sha256(source);if(!sha.equals(edit.getString("expectedSourceHash")))throw new SecurityException("原字节复制源SHA已变化");
        JSONObject copied=new JSONObject().put("path",from).put("sha256",sha).put("bytes",source.length).put("source","original_frozen_snapshot");return row(target,copied,source,false,null);
    }
    private static JSONObject row(String target,JSONObject copied,byte[] source,boolean inverse,String original)throws Exception {
        JSONObject change=new JSONObject().put("kind",inverse?"remove_original_byte_copy":"copy_bytes").put("fromPath",copied.getString("path")).put("sourceHash",copied.getString("sha256")).put("sourceBytes",source.length);
        if(inverse)change.put("removesOriginalCopiedTarget",true);else change.put("overwritesExistingTarget",false);
        JSONObject result=new JSONObject().put("path",target).put("action",inverse?"delete":"create").put("encoding","base64").put("bytePatchContract",CONTRACT).put("copyDirection",inverse?"revert":"forward").put("expectedHash",inverse?copied.getString("sha256"):"absent").put("afterHash",inverse?"absent":copied.getString("sha256")).put("beforeContentBase64",inverse?encoded(source):"").put("contentBase64",inverse?"":encoded(source)).put("beforeBytes",inverse?source.length:0).put("afterBytes",inverse?0:source.length).put("copiedFrom",new JSONObject(copied.toString())).put("changes",new JSONArray().put(change));if(inverse)result.put("revertsOperationId",ProjectBuildPlan.identity(original));return result;
    }
    static byte[][] validated(JSONObject file)throws Exception {
        boolean inverse="revert".equals(file.optString("copyDirection"));Set<String> fields=new TreeSet<>(Set.of("path","action","encoding","bytePatchContract","copyDirection","expectedHash","afterHash","beforeContentBase64","contentBase64","beforeBytes","afterBytes","copiedFrom","changes"));if(inverse)fields.add("revertsOperationId");
        if(!keys(file).equals(fields)||!matches(file)||!"base64".equals(file.optString("encoding"))||!(inverse||"forward".equals(file.optString("copyDirection")))||!(inverse?"delete":"create").equals(file.optString("action")))throw new SecurityException("原字节复制仅允许新建，或原操作条件撤销删除，不是通用二进制写入");
        String target=path(file.getString("path"));JSONObject copied=file.getJSONObject("copiedFrom");if(!keys(copied).equals(Set.of("path","sha256","bytes","source"))||target.equals(path(copied.getString("path")))||!"original_frozen_snapshot".equals(copied.optString("source"))||!copied.optString("sha256").matches("[a-f0-9]{64}"))throw new SecurityException("原字节复制源描述不完整");
        byte[] before=decode(file,"beforeContentBase64"),after=decode(file,"contentBase64"),source=inverse?before:after;String sha=ProjectSources.sha256(source);if(count(copied.get("bytes"))!=source.length||!sha.equals(copied.getString("sha256"))||count(file.get("beforeBytes"))!=before.length||count(file.get("afterBytes"))!=after.length||(inverse?after:before).length!=0||!(inverse?sha:"absent").equals(file.optString("expectedHash"))||!(inverse?"absent":sha).equals(file.optString("afterHash")))throw new SecurityException("原字节复制正文/源SHA/大小/目标存在状态不同");
        JSONArray changes=file.getJSONArray("changes");if(changes.length()!=1)throw new SecurityException("原字节复制需一个完整明确变更");JSONObject change=changes.getJSONObject(0);if(!keys(change).equals(Set.of("kind","fromPath","sourceHash","sourceBytes",inverse?"removesOriginalCopiedTarget":"overwritesExistingTarget"))||!(inverse?"remove_original_byte_copy":"copy_bytes").equals(change.optString("kind"))||!copied.getString("path").equals(change.optString("fromPath"))||!sha.equals(change.optString("sourceHash"))||count(change.get("sourceBytes"))!=source.length||!Boolean.valueOf(inverse).equals(change.opt(inverse?"removesOriginalCopiedTarget":"overwritesExistingTarget")))throw new SecurityException("原字节复制审阅与完整正文不同");if(inverse)ProjectBuildPlan.identity(file.getString("revertsOperationId"));return new byte[][]{before,after};
    }
    /** Bind a forward body to the actual original source manifest, not a self-asserted hash.
     * Reversal provenance is read by the original TaskStore factory; its current target is pinned here. */
    static void requirePlanSource(JSONObject core,JSONObject step,JSONObject file)throws Exception {
        byte[][] bytes=validated(file);boolean inverse="revert".equals(file.getString("copyDirection"));JSONObject payload=step.getJSONObject("payload"),copied=file.getJSONObject("copiedFrom");if(!"patchFiles".equals(step.optString("localCommand"))||!(inverse?"revert_operation":"patch").equals(core.optString("intent"))||!core.getString("checkoutId").equals(payload.optString("checkoutId"))||!core.getString("sourceDigest").equals(payload.optString("expectedSourceDigest")))throw new SecurityException("原字节复制必须沿原精确补丁/撤销方案");if(inverse&&!file.getString("revertsOperationId").equals(payload.optString("revertsOperationId")))throw new SecurityException("原字节复制撤销不是同一原操作");
        String wanted=inverse?file.getString("path"):copied.getString("path"),sha=inverse?file.getString("expectedHash"):copied.getString("sha256");int size=inverse?bytes[0].length:bytes[1].length;boolean found=false;JSONArray manifest=core.getJSONArray("sourceManifest");for(int i=0;i<manifest.length();i++){JSONObject row=manifest.getJSONObject(i);if(!inverse&&file.getString("path").equals(row.getString("path")))throw new SecurityException("原字节复制目标已存在于固定source清单");if(wanted.equals(row.getString("path"))){if(!sha.equals(row.getString("sha256"))||size!=count(row.get("bytes")))throw new SecurityException("原字节复制正文并非固定source清单的原字节");found=true;}}if(!found)throw new SecurityException("原字节复制没有实际固定source来源/当前撤销目标");
    }
    static JSONObject reverse(JSONObject original,String operation)throws Exception {byte[][] bytes=validated(original);if(!"forward".equals(original.getString("copyDirection")))throw new SecurityException("原字节复制撤销不递归恢复或复制其它原件");return row(original.getString("path"),original.getJSONObject("copiedFrom"),bytes[1],true,operation);}
}
