package dev.pocketengine.features.local.project;

import dev.pocketengine.features.workspace.VersionedWorkspace;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.json.JSONArray;
import org.json.JSONObject;

/** Exact, preflighted edits and frozen-text copy/append. No alternate writer or cross-file atomicity. */
public final class ProjectExactPatch {
    public static final int MAX_FILES=8,MAX_TEXT_BYTES=256*1024,MAX_HUNKS=24;
    private ProjectExactPatch() {}
    public static JSONArray prepare(JSONObject snapshot,JSONArray edits)throws Exception {
        if(edits.length()<1||edits.length()>MAX_FILES)throw new IllegalArgumentException("一次精确补丁需1至8个文件");
        Map<String,String> text=ProjectSources.files(snapshot);Map<String,byte[]> all=ProjectSources.byteFiles(snapshot);Map<String,JSONObject> prepared=new TreeMap<>();
        for(int i=0;i<edits.length();i++){
            JSONObject edit=edits.getJSONObject(i);String path=VersionedWorkspace.path(edit.getString("path")),action=edit.optString("action","replace");
            if(!ProjectSources.sourcePath(path)||!Set.of("replace","create","delete","copy","append").contains(action)||prepared.containsKey(path))throw new IllegalArgumentException("补丁路径重复、不是源码路径或动作无效");
            boolean exists=all.containsKey(path);if(exists&&!text.containsKey(path))throw new IllegalArgumentException("精确文字补丁不能改写二进制文件："+path);
            String before=text.getOrDefault(path,""),beforeHash=exists?ProjectSources.sha256(all.get(path)):"absent";
            if(!beforeHash.equals(edit.getString("expectedHash")))throw new SecurityException("补丁基准SHA已变化："+path);
            if(!action.equals("copy")&&(action.equals("create")&&exists||!action.equals("create")&&!exists))throw new SecurityException("补丁动作与文件实际存在状态不同："+path);
            if(before.getBytes(StandardCharsets.UTF_8).length>MAX_TEXT_BYTES)throw new IllegalArgumentException("本次文字补丁只修改256KiB以内的UTF-8文件："+path);
            String after;JSONArray changes=new JSONArray();JSONObject copiedFrom=null;
            if(action.equals("copy")){
                if(edit.has("content")||edit.has("replacements"))throw new IllegalArgumentException("复制只取固定原文件字节，不混合新的content/replacements");
                String from=VersionedWorkspace.path(edit.getString("fromPath"));if(path.equals(from)||!ProjectSources.sourcePath(from)||!all.containsKey(from))throw new IllegalArgumentException("复制需同一已选副本内不同的实际源码文件");
                if(!text.containsKey(from))throw new IllegalArgumentException("此精确文字复制不支持二进制源，不解码或截断原件");
                byte[] source=all.get(from);if(source.length>MAX_TEXT_BYTES)throw new IllegalArgumentException("本次文字复制源超过256KiB："+from);String sourceHash=ProjectSources.sha256(source);
                if(!sourceHash.equals(edit.getString("expectedSourceHash")))throw new SecurityException("复制源SHA已变化："+from);
                after=text.get(from);copiedFrom=new JSONObject().put("path",from).put("sha256",sourceHash).put("bytes",source.length).put("source","original_frozen_snapshot");
                changes.put(new JSONObject().put("kind","copy").put("fromPath",from).put("sourceHash",sourceHash).put("overwritesExistingTarget",exists));action=exists?"replace":"create";
            }
            else if(action.equals("append")){
                if(edit.has("fromPath")||edit.has("expectedSourceHash")||edit.has("replacements"))throw new IllegalArgumentException("追加只接收目标旧SHA和明确content，不混合复制或替换上下文");
                String suffix=edit.getString("content");if(suffix.isEmpty())throw new IllegalArgumentException("追加内容不能为空，不生成无变化文件操作");encode(suffix);after=before+suffix;
                changes.put(new JSONObject().put("kind","append").put("offset",before.length()).put("offsetUnit","UTF16_code_units").put("text",suffix));action="replace";
            }
            else if(action.equals("delete")){after="";if(edit.has("content")||edit.has("replacements"))throw new IllegalArgumentException("删除操作不能同时传替换内容");}
            else if(action.equals("create")){if(edit.has("replacements"))throw new IllegalArgumentException("创建文件需明确content，不猜测补丁上下文");after=edit.getString("content");changes.put(new JSONObject().put("kind","create").put("text",after));}
            else {
                if(edit.has("content"))throw new IllegalArgumentException("现有文件需明确精确replacements；整文件替换仍可用原edit");JSONArray hunks=edit.getJSONArray("replacements");if(hunks.length()<1||hunks.length()>MAX_HUNKS)throw new IllegalArgumentException("单文件需1至24个精确替换");after=before;
                for(int h=0;h<hunks.length();h++){
                    JSONObject hunk=hunks.getJSONObject(h);String old=hunk.getString("oldText"),value=hunk.getString("newText");boolean positioned=hunk.has("startOffset");if(!positioned&&old.isEmpty()||old.length()>6000||value.length()>6000||old.equals(value))throw new IllegalArgumentException("替换需6000字以内明确旧/新文字；空旧文字只可在明确startOffset插入，不模糊猜测");
                    int first;
                    if(positioned){
                        Object raw=hunk.opt("startOffset");if(!(raw instanceof Integer||raw instanceof Long)||((Number)raw).longValue()<0||((Number)raw).longValue()>after.length())throw new IllegalArgumentException("startOffset需当前工作文字范围内的UTF16整数位置");first=((Number)raw).intValue();
                        if(old.length()>after.length()-first||!after.regionMatches(first,old,0,old.length()))throw new SecurityException("精确范围旧文字不同，未移位搜索或部分应用："+path+" 第"+(h+1)+"处");
                    }else{first=after.indexOf(old);if(first<0||after.indexOf(old,first+1)>=0)throw new SecurityException("旧文字未唯一命中，未猜测或部分应用："+path+" 第"+(h+1)+"处");}
                    if(!ProjectEditorDocument.codePointBoundary(after,first)||!ProjectEditorDocument.codePointBoundary(after,first+old.length()))throw new IllegalArgumentException("精确补丁范围切断Unicode字符，未修改原文");
                    int line=1;for(int c=0;c<first;c++)if(after.charAt(c)=='\n')line++;
                    changes.put(new JSONObject().put("line",line).put("oldText",old).put("newText",value).put("startOffset",first).put("endOffset",first+old.length()).put("offsetUnit","UTF16_code_units").put("offsetBasis","after_preceding_hunks"));after=after.substring(0,first)+value+after.substring(first+old.length());
                }
            }
            byte[] bytes=encode(after);if(bytes.length>MAX_TEXT_BYTES)throw new IllegalArgumentException("补丁结果超过256KiB："+path);
            String afterHash=action.equals("delete")?"absent":ProjectSources.sha256(bytes);
            JSONObject file=new JSONObject().put("path",path).put("action",action).put("expectedHash",beforeHash).put("afterHash",afterHash).put("beforeContent",before).put("content",after).put("beforeBytes",before.getBytes(StandardCharsets.UTF_8).length).put("afterBytes",action.equals("delete")?0:bytes.length).put("changes",changes);if(copiedFrom!=null)file.put("copiedFrom",copiedFrom);prepared.put(path,file);
        }
        long total=0;int count=all.size();for(byte[] bytes:all.values())total+=bytes.length;
        for(JSONObject file:prepared.values()){String path=file.getString("path");boolean exists=all.containsKey(path);total-=exists?all.get(path).length:0;total+=file.getLong("afterBytes");if(file.getString("action").equals("delete"))count--;else if(!exists)count++;all.put(path,new byte[0]);}
        if(total>ProjectSources.MAX_TOTAL_BYTES||count>ProjectSources.MAX_FILES)throw new IllegalArgumentException("全部补丁应用后超过原源码清单16MiB/2000文件范围");
        Map<String,Boolean> paths=new TreeMap<>();for(String path:ProjectSources.byteFiles(snapshot).keySet())paths.put(path,true);for(JSONObject file:prepared.values())if(file.getString("action").equals("delete"))paths.remove(file.getString("path"));else paths.put(file.getString("path"),true);
        for(String path:paths.keySet())for(int slash=path.indexOf('/');slash>=0;slash=path.indexOf('/',slash+1))if(paths.containsKey(path.substring(0,slash)))throw new IllegalArgumentException("补丁结果存在文件/目录冲突");
        JSONArray result=new JSONArray();for(JSONObject file:prepared.values())result.put(file);return result;
    }
    /** A settled failure can contain confirmed effects. Never infer effects from an unknown operation. */
    public static void requireSettledReceipt(JSONObject receipt)throws Exception {
        if(!receipt.optBoolean("receiptReceived")||!Set.of("completed","failed").contains(receipt.optString("status")))throw new SecurityException("撤销需原确定文件回执；unknown保留原操作核对，不自动反向");
    }
    public static JSONArray reverse(JSONObject snapshot,JSONArray original,JSONObject receipt,String originalOperation)throws Exception {
        requireSettledReceipt(receipt);
        JSONArray observed=receipt.getJSONArray("files");if(original.length()!=observed.length())throw new SecurityException("原文件回执不完整");Map<String,byte[]> current=ProjectSources.byteFiles(snapshot);JSONArray result=new JSONArray();Set<String> paths=new java.util.HashSet<>();
        for(int i=0;i<original.length();i++){
            JSONObject file=original.getJSONObject(i),actual=observed.getJSONObject(i);String path=file.getString("path"),after=file.getString("afterHash"),state=actual.getString("state");
            if(!paths.add(path)||!path.equals(actual.getString("path"))||!Set.of("applied","failed","skipped").contains(state))throw new SecurityException("原文件回执不完整或仍有unknown；保留原操作核对");
            if(!"applied".equals(state))continue;
            if(!after.equals(actual.getString("afterHash"))||actual.has("actualAfterHash")&&!after.equals(actual.getString("actualAfterHash")))throw new SecurityException("原applied回执与完整方案不同");
            String currentHash=current.containsKey(path)?ProjectSources.sha256(current.get(path)):"absent";if(!after.equals(currentHash))throw new SecurityException("撤销冲突：后续修改尚在，不覆盖主人新内容："+path);
            String action=file.getString("action").equals("create")?"delete":file.getString("action").equals("delete")?"create":"replace",before=file.getString("beforeContent");byte[] bytes=encode(before);String target=file.getString("expectedHash");
            result.put(new JSONObject().put("path",path).put("action",action).put("expectedHash",after).put("afterHash",target).put("beforeContent",file.getString("content")).put("content",before).put("beforeBytes",file.getLong("afterBytes")).put("afterBytes",file.getLong("beforeBytes")).put("revertsOperationId",ProjectBuildPlan.identity(originalOperation)).put("changes",new JSONArray().put(new JSONObject().put("kind","restore_original_bytes").put("beforeHash",after).put("afterHash",target).put("restoredBytes",bytes.length))));
        }
        if(result.length()==0)throw new SecurityException("原补丁没有确证applied文件，未生成或执行撤销");
        return result;
    }
    /** Kept in the immutable proposal and its receipt, so excluded files never look undone. */
    public static JSONObject reversalScope(JSONObject receipt)throws Exception {
        JSONArray selected=new JSONArray(),excluded=new JSONArray(),files=receipt.getJSONArray("files");
        for(int i=0;i<files.length();i++){JSONObject file=files.getJSONObject(i);if("applied".equals(file.getString("state")))selected.put(file.getString("path"));else excluded.put(new JSONObject().put("path",file.getString("path")).put("originalState",file.getString("state")).put("reversalAttempted",false).put("reason","no_confirmed_original_effect"));}
        return new JSONObject().put("selectedPaths",selected).put("excludedFiles",excluded).put("selection","confirmed_applied_only").put("originalStatus",receipt.getString("status"));
    }
    public static byte[] encode(String value)throws Exception {if(value.indexOf('\0')>=0)throw new IllegalArgumentException("文字补丁不能包含空字节");var encoded=StandardCharsets.UTF_8.newEncoder().encode(java.nio.CharBuffer.wrap(value));byte[] bytes=new byte[encoded.remaining()];encoded.get(bytes);return bytes;}
}
