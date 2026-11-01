package dev.pocketengine.features.local.language;

import dev.pocketengine.features.local.project.ProjectSources;
import java.util.*;
import org.json.*;

/** Complete semantic edits become owner-reviewable private buffers, never workspace writes.
 * Source CAS/approval is still performed separately by the original project save path. */
public final class ProjectLanguageEdits {
    private ProjectLanguageEdits(){}
    public static final class FileEdit {
        public final String path,beforeSha256,afterSha256,beforeText,afterText;
        public final int replacements;
        private FileEdit(String path,String before,String after,int replacements)throws Exception{this.path=path;beforeText=before;afterText=after;beforeSha256=ProjectSources.sha256(ProjectLanguageSnapshot.encode(before));afterSha256=ProjectSources.sha256(ProjectLanguageSnapshot.encode(after));this.replacements=replacements;}
        public JSONObject summary()throws Exception{return new JSONObject().put("path",path).put("beforeSha256",beforeSha256).put("afterSha256",afterSha256).put("replacements",replacements).put("beforeBytes",ProjectLanguageSnapshot.encode(beforeText).length).put("afterBytes",ProjectLanguageSnapshot.encode(afterText).length);}
    }
    public static List<FileEdit> review(ProjectLanguageSnapshot original,JSONObject result)throws Exception {
        String action=result.getString("action");if(!action.equals("rename")&&!action.equals("format"))throw new IllegalArgumentException("只预览完整重命名或格式化修改");original.accept(result,action,result.getInt("position"));
        Map<String,List<JSONObject>> grouped=new TreeMap<>();JSONArray rows=result.getJSONArray("rows");if(rows.length()>1000||result.optBoolean("truncated"))throw new SecurityException("修改范围不完整，未应用部分结果");
        for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);String path=row.getString("path");if(row.optBoolean("library"))throw new SecurityException("语言库定义不可修改");grouped.computeIfAbsent(path,key->new ArrayList<>()).add(row);}
        List<FileEdit> edits=new ArrayList<>();
        for(var entry:grouped.entrySet()){
            String before=original.originalText(entry.getKey());List<JSONObject> replacements=entry.getValue();replacements.sort(Comparator.comparingInt(value->value.optInt("start",-1)));
            StringBuilder after=new StringBuilder();int cursor=0;
            for(JSONObject replacement:replacements){int start=replacement.getInt("start"),length=replacement.getInt("length");if(start<cursor||start>before.length()||length<0||length>before.length()-start)throw new SecurityException("语言修改重叠或已不属于所选原稿");after.append(before,cursor,start).append(replacement.getString("newText"));cursor=start+length;}
            after.append(before,cursor,before.length());String value=after.toString();if(ProjectLanguageSnapshot.encode(value).length>262144||value.indexOf('\0')>=0)throw new IllegalArgumentException("修改后超过原文件256KiB保存范围或含NUL，当前草稿未变化");
            if(!before.equals(value))edits.add(new FileEdit(entry.getKey(),before,value,replacements.size()));
        }
        return Collections.unmodifiableList(edits);
    }
    public static String applyCompletion(ProjectLanguageSnapshot original,JSONObject result,int index,String stillCurrentText)throws Exception {
        original.accept(result,"complete",result.getInt("position"));if(!original.draftSha256.equals(ProjectSources.sha256(ProjectLanguageSnapshot.encode(stillCurrentText))))throw new IllegalStateException("补全返回后文字已变化，未覆盖更新的草稿");
        JSONObject row=result.getJSONArray("rows").getJSONObject(index),span=row.getJSONObject("replacementSpan");int start=span.getInt("start"),length=span.getInt("length");String next=stillCurrentText.substring(0,start)+row.getString("insertText")+stillCurrentText.substring(start+length);if(ProjectLanguageSnapshot.encode(next).length>262144||next.indexOf('\0')>=0)throw new IllegalArgumentException("补全超过原保存范围，当前草稿未变化");return next;
    }
}
