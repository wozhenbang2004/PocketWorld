package dev.pocketengine.features.skills;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.local.LocalModule;
import dev.pocketengine.features.local.project.*;
import java.util.*;
import org.json.*;

/** Bounded exact native input snapshot from the original private project only.
 * Relative requested rules bound the copied input, not the same-UID OS process. */
final class ManagedSkillInputs {
    static final int MAX_FILES=200,MAX_BYTES=2*1024*1024;
    final String projectId,checkoutId,sourceDigest,selectedDigest;private final Map<String,byte[]> files;private final JSONArray metadata;
    private ManagedSkillInputs(String projectId,String checkoutId,String sourceDigest,Map<String,byte[]> bytes,JSONArray metadata)throws Exception{this.projectId=projectId;this.checkoutId=checkoutId;this.sourceDigest=sourceDigest;this.metadata=new JSONArray(metadata.toString());Map<String,byte[]> frozen=new TreeMap<>();bytes.forEach((name,value)->frozen.put(name,value.clone()));files=Collections.unmodifiableMap(frozen);selectedDigest=ProjectSources.digest(new JSONObject().put("files",this.metadata).put("sourceDigest",sourceDigest).put("projectId",projectId).put("checkoutId",checkoutId));}
    static ManagedSkillInputs capture(LocalModule original,String checkout,List<String> rules,RunControl control)throws Exception {
        control.check();if(control.isPaused())throw new SecurityException("原Skill输入读取已暂停，未派发");if(checkout.isEmpty()){if(!rules.isEmpty())throw new SecurityException("空工作区不包含原项目文件");return new ManagedSkillInputs("native_task_copy","",ProjectSources.manifestDigest(new JSONArray()),Map.of(),new JSONArray());}
        JSONObject snapshot=original.managedSkillSourceForHost(checkout,control),description=ProjectSources.describe(snapshot);Map<String,byte[]> all=ProjectSources.byteFiles(snapshot),chosen=new TreeMap<>();Set<String> matched=new HashSet<>();JSONArray items=new JSONArray();long total=0;
        for(var entry:all.entrySet()){boolean selected=false;for(String rule:rules)if(rule.endsWith("/")?entry.getKey().startsWith(rule):entry.getKey().equals(rule)){selected=true;matched.add(rule);}if(!selected)continue;if(ProjectPackageArchive.excluded(entry.getKey(),false,false)!=null)throw new SecurityException("本机Skill不把已知凭据、私人配置或工具缓存作为普通项目输入复制");total+=entry.getValue().length;if(chosen.size()>=MAX_FILES||total>MAX_BYTES)throw new SkillExecution.Unavailable("本次Skill实际输入超过200文件或2MiB，请由主人收窄读取范围");chosen.put(entry.getKey(),entry.getValue());items.put(new JSONObject().put("path",entry.getKey()).put("sha256",ProjectSources.sha256(entry.getValue())).put("bytes",entry.getValue().length));}
        for(String rule:rules)if(!matched.contains(rule))throw new SkillExecution.Unavailable("原项目未完整列出所选输入："+rule+"；未把未显示的文件当空文件或自动换路径");control.check();if(control.isPaused())throw new SecurityException("原Skill输入读取已暂停，未派发");return new ManagedSkillInputs(description.getString("projectId"),checkout,description.getString("sourceDigest"),chosen,items);
    }
    Map<String,byte[]> bytes(){Map<String,byte[]> copy=new TreeMap<>();files.forEach((name,value)->copy.put(name,value.clone()));return copy;}
    JSONObject metadata()throws Exception{return new JSONObject().put("projectId",projectId).put("checkoutId",checkoutId).put("sourceDigest",sourceDigest).put("selectedDigest",selectedDigest).put("files",new JSONArray(metadata.toString())).put("fileCount",files.size()).put("sourceScopeAdded",false);}
}
