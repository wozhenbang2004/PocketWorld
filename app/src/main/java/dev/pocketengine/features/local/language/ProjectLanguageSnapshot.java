package dev.pocketengine.features.local.language;

import dev.pocketengine.features.local.project.ProjectBuildPlan;
import dev.pocketengine.features.local.project.ProjectSources;
import dev.pocketengine.features.workspace.VersionedWorkspace;
import java.nio.charset.*;
import java.util.*;
import org.json.*;

/** Immutable native virtual files for a language query, not a checkout, plan or execution grant. */
public final class ProjectLanguageSnapshot {
    public static final String FORMAT="pocketworld.typescript-language.v1";
    public static final int MAX_FILES=128,MAX_BYTES=1024*1024;
    public final String projectId,checkoutId,path,sourceDigest,draftSha256,identity;public final long draftRevision;public final boolean originalFilePresent;
    private final JSONObject request;private final Map<String,String> hashes,texts;
    private ProjectLanguageSnapshot(JSONObject source,String path,String text,long revision)throws Exception {
        JSONObject checkout=source.getJSONObject("checkout");projectId=ProjectBuildPlan.identity(checkout.getString("projectId"));checkoutId=ProjectBuildPlan.identity(checkout.getString("id"));this.path=VersionedWorkspace.path(path);if(!supports(path))throw new IllegalArgumentException("本语言服务支持JavaScript/TypeScript；此文件保留普通编辑与查找");
        if(revision<0)throw new IllegalArgumentException("原编辑稿修订无效");draftRevision=revision;sourceDigest=ProjectSources.describe(source).getString("sourceDigest");
        Map<String,byte[]> actual=ProjectSources.byteFiles(source);originalFilePresent=actual.containsKey(path);byte[] overlay=text==null?actual.get(path):encode(text);if(overlay==null)throw new IllegalArgumentException("原语言查询没有所选实际文件或编辑稿");draftSha256=ProjectSources.sha256(overlay);
        TreeMap<String,byte[]> selected=new TreeMap<>();for(var file:actual.entrySet())if((supports(file.getKey())||file.getKey().endsWith(".json"))&&dev.pocketengine.features.local.project.ProjectPackageArchive.excluded(file.getKey(),false,false)==null)selected.put(file.getKey(),file.getValue());if(dev.pocketengine.features.local.project.ProjectPackageArchive.excluded(path,false,false)!=null)throw new SecurityException("语言快照不读取凭据、缓存或生成目录");selected.put(path,overlay);
        if(selected.size()>MAX_FILES)throw new IllegalStateException("语言服务本次最多128个源文件；未把未分析文件视为不存在");
        JSONArray files=new JSONArray(),pins=new JSONArray();Map<String,String> versioned=new TreeMap<>(),contents=new TreeMap<>();int total=0;
        for(var file:selected.entrySet()){
            total+=file.getValue().length;if(total>MAX_BYTES)throw new IllegalStateException("本次语言源快照超过1MiB；普通编辑、查找与构建保留，未声称完整语义覆盖");
            String value=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(file.getValue())).toString();if(value.indexOf('\0')>=0)throw new IllegalArgumentException("语言源文件含非文字字节，未猜测解码");String sha=ProjectSources.sha256(file.getValue());versioned.put(file.getKey(),sha);
            files.put(new JSONObject().put("path",file.getKey()).put("content",value).put("sha256",sha));pins.put(new JSONArray().put(file.getKey()).put(sha));contents.put(file.getKey(),value);
        }
        hashes=Collections.unmodifiableMap(versioned);texts=Collections.unmodifiableMap(contents);identity=ProjectSources.digest(new JSONObject().put("checkoutId",checkoutId).put("projectId",projectId).put("sourceDigest",sourceDigest).put("path",path).put("draftRevision",revision).put("draftSha256",draftSha256).put("files",pins));
        request=new JSONObject().put("format",FORMAT).put("snapshotIdentity",identity).put("files",files).put("path",path);
    }
    /** Only native LocalModule calls this with its original active-tree export and optional private editor text. */
    public static ProjectLanguageSnapshot fromNativeExport(JSONObject original,String selectedPath,String ownerDraft,long revision)throws Exception{return new ProjectLanguageSnapshot(original,selectedPath,ownerDraft,revision);}
    public static boolean supports(String path){return path!=null&&path.matches(".*\\.(?:[cm]?[jt]sx?)");}
    public static byte[] encode(String text)throws Exception{var encoded=StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(text));byte[] bytes=new byte[encoded.remaining()];encoded.get(bytes);return bytes;}
    public JSONObject request(String action,int position,String newName)throws Exception {
        if(!Set.of("diagnostics","complete","definition","references","rename","format","classify").contains(action))throw new IllegalArgumentException("未知语言动作");String text=null;JSONArray files=request.getJSONArray("files");for(int i=0;i<files.length();i++)if(path.equals(files.getJSONObject(i).getString("path")))text=files.getJSONObject(i).getString("content");if(position<0||text==null||position>text.length())throw new IllegalArgumentException("语言光标不在原稿范围");
        JSONObject result=ProjectSources.copy(request).put("action",action).put("position",position);if(action.equals("rename")){if(newName==null||newName.length()>100)throw new IllegalArgumentException("请填写新符号名");result.put("newName",newName);}return result;
    }
    public String expectedHash(String path)throws Exception{String sha=hashes.get(VersionedWorkspace.path(path));if(sha==null)throw new SecurityException("目标不在这个原语言快照");return sha;}
    public String originalText(String relative)throws Exception{expectedHash(relative);return texts.get(relative);}
    public JSONObject accept(JSONObject raw,String action,int position)throws Exception {
        if(!FORMAT.equals(raw.optString("format"))||!"5.9.3".equals(raw.optString("compilerVersion"))||!identity.equals(raw.optString("snapshotIdentity"))||!path.equals(raw.optString("path"))||!draftSha256.equals(raw.optString("sha256"))||!action.equals(raw.optString("action"))||raw.optInt("position",-1)!=position||!Boolean.FALSE.equals(raw.opt("userCodeExecuted"))||!Boolean.FALSE.equals(raw.opt("projectPluginsLoaded")))throw new SecurityException("语言结果不是本项目这份原稿与光标");
        JSONArray rows=raw.getJSONArray("rows");if(rows.length()>(action.equals("classify")?4000:action.equals("rename")||action.equals("format")?1000:100))throw new SecurityException("语言结果条数超限");
        for(int i=0;i<rows.length();i++){
            JSONObject row=rows.getJSONObject(i);String target=path;if(row.has("path")&&!row.optBoolean("library")){target=row.getString("path");if(!expectedHash(target).equals(row.optString("sha256")))throw new SecurityException("语言导航/修改跨过了原源版本");}
            if(row.optBoolean("library")){if(!action.equals("definition")||!row.optString("path").matches("lib\\.[a-z0-9.]+\\.d\\.ts")||!Boolean.FALSE.equals(row.opt("editable")))throw new SecurityException("库定义不能冒充可编辑的项目源文件");continue;}
            if(row.has("start")||row.has("length"))span(row,texts.get(target));if(row.has("replacementSpan"))span(row.getJSONObject("replacementSpan"),texts.get(path));
            if(row.has("newText"))encode(row.getString("newText"));if(row.has("insertText"))encode(row.getString("insertText"));
        }
        JSONArray syntax=raw.optJSONArray("syntax");if(syntax!=null){if(!action.equals("diagnostics")||syntax.length()>1200)throw new SecurityException("语法标注范围无效");for(int i=0;i<syntax.length();i++){JSONObject token=syntax.getJSONObject(i);span(token,texts.get(path));if(!Set.of("keyword","string","number","comment").contains(token.getString("kind")))throw new SecurityException("未知语法标注");}}
        if((action.equals("rename")||action.equals("format"))&&raw.optBoolean("truncated"))throw new SecurityException("修改预览不完整，未应用部分重命名或格式化");
        return ProjectSources.copy(raw).put("projectId",projectId).put("checkoutId",checkoutId).put("sourceDigest",sourceDigest).put("draftRevision",draftRevision).put("authorityAdded",false);
    }
    private static void span(JSONObject span,String text)throws Exception {Object start=span.get("start"),length=span.get("length");if(!(start instanceof Integer||start instanceof Long)||!(length instanceof Integer||length instanceof Long))throw new SecurityException("语言定位必须是原稿整数范围");long at=((Number)start).longValue(),count=((Number)length).longValue();if(at<0||count<0||text==null||at>text.length()||count>text.length()-at||at>0&&at<text.length()&&Character.isLowSurrogate(text.charAt((int)at))&&Character.isHighSurrogate(text.charAt((int)at-1))||at+count>0&&at+count<text.length()&&Character.isHighSurrogate(text.charAt((int)(at+count)-1))&&Character.isLowSurrogate(text.charAt((int)(at+count))))throw new SecurityException("语言定位越界或截断原字符");}
    public JSONObject identity()throws Exception{return new JSONObject().put("projectId",projectId).put("checkoutId",checkoutId).put("path",path).put("sourceDigest",sourceDigest).put("draftRevision",draftRevision).put("draftSha256",draftSha256).put("snapshotIdentity",identity).put("sourceFiles",hashes.size());}
}
