package dev.pocketengine.features.attachments;

import android.content.Intent;
import android.os.Bundle;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Native picker provenance and a bounded receipt. Imported bytes and task links are separate facts. */
public final class OwnerAttachmentImport {
    private OwnerAttachmentImport() {}

    /** A non-null target may explicitly mean the library. Null means the original context is unavailable. */
    public static final class Target {
        public final String requestId, taskId, projectId;
        public Target(String taskId,String projectId){this(UUID.randomUUID().toString(),taskId,projectId);}
        private Target(String requestId,String taskId,String projectId){
            if(requestId==null||!requestId.matches("[a-f0-9-]{36}"))throw new IllegalArgumentException("原文件选择编号无效");
            this.requestId=requestId;this.taskId=reference(taskId);this.projectId=reference(projectId);
        }
        private static String reference(String value){if(value==null||value.isBlank())return null;if(value.length()>100)throw new IllegalArgumentException("原文件选择引用过长");return value;}
        public Bundle save(){
            Bundle saved=new Bundle();saved.putInt("version",1);saved.putString("requestId",requestId);
            saved.putBoolean("hasTask",taskId!=null);saved.putBoolean("hasProject",projectId!=null);
            saved.putString("taskId",taskId);saved.putString("projectId",projectId);return saved;
        }
        public static Target restore(Bundle saved){
            if(saved==null)return null;
            try{
                if(saved.getInt("version")!=1||!saved.containsKey("hasTask")||!saved.containsKey("hasProject"))return null;
                String task=saved.getString("taskId"),project=saved.getString("projectId");
                if(saved.getBoolean("hasTask")!=(task!=null&&!task.isBlank())||saved.getBoolean("hasProject")!=(project!=null&&!project.isBlank()))return null;
                return new Target(saved.getString("requestId"),task,project);
            }catch(RuntimeException invalid){return null;}
        }
    }

    public interface Linker {JSONObject link(String originalTask,JSONObject file)throws Exception;}
    public static final class Receipt {
        public final int imported,linked,associationUnconfirmed;
        public final String notice,error;
        private Receipt(int imported,int linked,int associationUnconfirmed,String notice,String error){
            this.imported=imported;this.linked=linked;this.associationUnconfirmed=associationUnconfirmed;this.notice=notice;this.error=error;
        }
    }

    public static Receipt receive(AttachmentsModule module,Intent intent,Target original,Linker linker)throws Exception {
        // Never place a task ID in import metadata as a substitute for an actual task link.
        JSONObject result=module.importIntent(intent,"",original==null||original.projectId==null?"":original.projectId);
        JSONArray files=result.getJSONArray("attachments"),failures=result.optJSONArray("errors");
        if(files.length()>10||failures!=null&&failures.length()>10)throw new IllegalStateException("附件接收回执超出单次范围，请先核对附件库");
        int linked=0,unconfirmed=0;
        if(original!=null&&original.taskId!=null)for(int i=0;i<files.length();i++){
            JSONObject file=files.getJSONObject(i);
            try{
                JSONObject actual=linker.link(original.taskId,file);
                if(actual==null||!file.getString("id").equals(actual.optString("id"))||!file.getString("sha256").equals(actual.optString("sha256")))throw new IllegalStateException("原任务关联回执不匹配");
                linked++;
            }catch(Exception uncertain){
                // A link may already be durable when a later check fails. Preserve bytes and never replay it.
                unconfirmed++;
            }
        }
        int count=files.length();String notice=count==0?null:"已保存 "+count+" 个附件到附件库";
        if(linked>0)notice+="；其中 "+linked+" 个已确认关联原任务";
        List<String> warnings=new ArrayList<>();
        if(count>0&&original==null)warnings.add("原文件选择的任务/项目上下文已丢失；这些附件未自动关联任何任务或项目。请先核对附件库，不要重复导入");
        if(unconfirmed>0)warnings.add("另有 "+unconfirmed+" 个附件与原任务的关联未确认；原文件已留在附件库，请核对原任务，不要重复导入");
        for(int i=0;failures!=null&&i<failures.length();i++){
            String text=failures.getJSONObject(i).optString("message","附件未能导入");
            warnings.add(text.contains("content://")?"附件未能导入，请重新核对文件授权":text.substring(0,Math.min(200,text.length())));
        }
        return new Receipt(count,linked,unconfirmed,notice,warnings.isEmpty()?null:String.join("；",warnings));
    }
}
