package dev.pocketengine.features.local.project;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.local.runtime.RuntimeStorage;
import java.io.File;
import java.nio.file.*;
import java.util.*;
import org.json.*;

/** One native project-storage review. Runtime leases and LocalModule's original
 * monitor protect admitted writers; private app files are not an OS sandbox. */
public final class ProjectStorage {
    public interface Current {void check()throws Exception;}
    public interface Checkouts {JSONArray list()throws Exception;File root(String checkout)throws Exception;void requireIdle(String checkout)throws Exception;}
    private static final Set<String> DEPENDENCIES=Set.of("node_modules",".venv","venv",".gradle","__pycache__",".pytest_cache");
    private static final Set<String> GENERATED=Set.of("build","dist");
    private final dev.pocketengine.features.local.LocalModule nativeIssuer;private final RuntimeStorage runtime;private final Checkouts checkouts;private final File files,cache;private final NativeExecutionCaches nativeCaches;
    public ProjectStorage(dev.pocketengine.features.local.LocalModule nativeIssuer,RuntimeStorage originalRuntime,Checkouts originalCheckouts,File nativeFiles,File nativeCache){this(nativeIssuer,originalRuntime,originalCheckouts,nativeFiles,nativeCache,null);}
    public ProjectStorage(dev.pocketengine.features.local.LocalModule nativeIssuer,RuntimeStorage originalRuntime,Checkouts originalCheckouts,File nativeFiles,File nativeCache,NativeExecutionCaches originalExecutionCaches){this.nativeCaches=originalExecutionCaches;this.nativeIssuer=Objects.requireNonNull(nativeIssuer);runtime=Objects.requireNonNull(originalRuntime);checkouts=Objects.requireNonNull(originalCheckouts);files=nativeFiles.getAbsoluteFile();cache=nativeCache.getAbsoluteFile();}
    public JSONObject inventory(RunControl original)throws Exception {
        JSONArray groups=runtime.inventory(original),all=checkouts.list();if(nativeCaches!=null){JSONArray retained=nativeCaches.inventory(original);for(int i=0;i<retained.length();i++)groups.put(retained.get(i));}else{for(String folder:java.util.List.of("git-source-intakes","skill-execution-copies","mcp-discovery-copies"))groups.put(measure("protected:"+folder,"原生执行暂存 / "+folder,"原任务账本未绑定，不能判定哪些副本已释放或授予清理",new File(new File(files,"local-development"),folder),false,original));}
        for(int i=0;i<all.length();i++){JSONObject checkout=all.getJSONObject(i);String id=checkout.getString("checkoutId");File root=checkouts.root(id);groups.put(measure("project:"+id+":source","项目副本："+checkout.optString("name"),"源码、未固定修改及Git元数据保留；下面分别列出可审阅的依赖/生成目录",root,false,original));
            for(String folder:DEPENDENCIES)groups.put(measure("project:"+id+":"+folder,"依赖 / "+folder,"清理会丢失该目录中的手工修改；依赖可能需按原范围重装，锁文件与源码保留，不自动安装",new File(root,folder),true,original));
            for(String folder:GENERATED)groups.put(measure("project:"+id+":"+folder,"未固定生成目录 / "+folder,"其中未保存为固定成果的内容会删除；请先核对或保存需要的输出",new File(root,folder),true,original));}
        for(String[] protectedGroup:new String[][]{{"workspace","已提交项目与保留版本"},{"workspace-artifacts","固定项目包与文件成果"},{"attachments","原件与派生产物"},{"memory","记忆、屏障与学习档案"},{"owner-data-archives","完整备份的本机临时副本"}})groups.put(measure("protected:"+protectedGroup[0],protectedGroup[1],"不作为运行缓存删除；从该对象原生页面审阅其精确版本和移除结果",new File(files,protectedGroup[0]),false,original));
        groups.put(measure("protected:app-cache","应用其它缓存","浏览器、音视频与解析器可能仍在使用；本工具不泛删整个应用缓存",cache,false,original));
        return new JSONObject().put("groups",groups).put("filesystemUsableBytes",files.getUsableSpace()).put("logicalByteCounts",true).put("physicalBlocksOrExclusiveBytesMeasured",false).put("nestedProjectGroupsOverlap",true).put("automaticCleanup",false).put("maximumMeasuredEntriesPerGroup",NativeStorageTree.MAX_ENTRIES).put("originalsRetainedByDefault",true);
    }
    private static JSONObject measure(String id,String label,String note,File target,boolean cleanable,RunControl original)throws Exception{JSONObject row=new JSONObject().put("id",id).put("label",label).put("note",note).put("cleanable",cleanable);try{row.put("usage",NativeStorageTree.measure(target,original));}catch(Exception unavailable){original.checkpoint();row.put("cleanable",false).put("usage",new JSONObject().put("complete",false).put("logicalBytes",JSONObject.NULL).put("reason","此范围无法完整计量，不把未知当0字节，也不授予截断清单删除权限"));}return row;}
    private static final class Selected {final String id,checkout;final NativeStorageTree.Snapshot project;final RuntimeStorage.Entry environment;final NativeExecutionCaches.Entry nativeCopy;Selected(String id,String checkout,NativeStorageTree.Snapshot project,RuntimeStorage.Entry environment){this(id,checkout,project,environment,null);}Selected(String id,String checkout,NativeStorageTree.Snapshot project,RuntimeStorage.Entry environment,NativeExecutionCaches.Entry nativeCopy){this.id=id;this.checkout=checkout;this.project=project;this.environment=environment;this.nativeCopy=nativeCopy;}}
    public final class Review {
        private final String action=UUID.randomUUID().toString(),digest;private final List<Selected> selected;private final RunControl original;private final long expires=System.currentTimeMillis()+300000;private boolean confirmed;
        private Review(List<Selected> selected,RunControl original)throws Exception{this.selected=selected;this.original=original;digest=ProjectSources.digest(new JSONObject().put("targets",targets()));}
        private JSONArray targets()throws Exception{JSONArray rows=new JSONArray();for(Selected item:selected)rows.put((item.nativeCopy!=null?item.nativeCopy.metadata():item.environment==null?item.project.metadata():item.environment.metadata()).put("id",item.id));return rows;}
        public JSONObject metadata()throws Exception{current();return new JSONObject().put("actionId",action).put("planDigest",digest).put("targets",targets()).put("expiresAt",expires).put("sourceFilesDeleted",false).put("fixedArtifactsDeleted",false).put("entireHomeDeleted",false).put("automaticRetry",false).put("crossTargetTransaction",false).put("originalRuntimeWriteGate",true).put("nativeExecutionCacheAuthorityRevokedBeforeDeletion",selected.stream().anyMatch(item->item.nativeCopy!=null)).put("osSandbox",false);}
        private ProjectStorage issuer(){return ProjectStorage.this;}
        private void current()throws Exception{original.checkpoint();if(System.currentTimeMillis()>=expires)throw new SecurityException("这份存储清理审阅已过期，请重新读取实际范围");}
        public synchronized Confirmed confirm()throws Exception{current();if(confirmed)throw new SecurityException("这份原生清理审阅已经确认；不能重复提交");confirmed=true;return new Confirmed(this);}
    }
    public static final class Confirmed {private final Review review;private boolean entered;private Confirmed(Review review){this.review=review;}public RunControl original(){return review.original;}public JSONObject metadata()throws Exception{return review.metadata();}public String actionId(){return review.action;}}
    public Review review(Set<String> chosen,RunControl original)throws Exception {
        if(original.hasHostRun()||chosen.isEmpty()||chosen.size()>12)throw new SecurityException("请从主人存储页明确选择1至12个缓存范围");List<Selected> selected=new ArrayList<>();long entries=0;Set<String> ids=new HashSet<>();
        for(String id:chosen){if(!ids.add(id))throw new SecurityException("存储范围重复");if(id.startsWith("runtime:")){RuntimeStorage.Entry entry=runtime.review(id,original);entries+=entry.metadata().getInt("entries");selected.add(new Selected(id,null,null,entry));}
            else if(id.startsWith("native_cache:")){if(nativeCaches==null)throw new SecurityException("原缓存账本未配置，不能授予清理");NativeExecutionCaches.Entry entry=nativeCaches.review(id,original);entries+=entry.metadata().getInt("entries");selected.add(new Selected(id,null,null,null,entry));}
            else{String[] parts=id.split(":",-1);if(parts.length!=3||!parts[0].equals("project")||!parts[1].matches("[a-f0-9-]{36}")||!DEPENDENCIES.contains(parts[2])&&!GENERATED.contains(parts[2]))throw new SecurityException("不是原项目声明的依赖或生成目录");String checkout=parts[1];checkouts.requireIdle(checkout);File target=new File(checkouts.root(checkout),parts[2]);NativeStorageTree.Snapshot snapshot=NativeStorageTree.scan(target,original);entries+=snapshot.metadata().getInt("entries");selected.add(new Selected(id,checkout,snapshot,null));}
            if(entries>NativeStorageTree.MAX_ENTRIES)throw new IllegalArgumentException("本次清理合计超过20万节点，请在原页明确分批审阅，不使用截断清单");}
        for(String id:ids)if(id.startsWith("runtime:generation:")&&!ids.contains(id.replace("runtime:generation:","runtime:links:"))||id.startsWith("runtime:links:")&&!ids.contains(id.replace("runtime:links:","runtime:generation:")))throw new SecurityException("旧rootfs和其同版PRoot后备必须一起审阅；不留下错误的链接归属");return new Review(Collections.unmodifiableList(selected),original);
    }
    public boolean issued(Confirmed confirmed){return confirmed!=null&&confirmed.review.issuer()==this;}
    public static JSONObject executeOriginal(Confirmed confirmed,dev.pocketengine.features.local.LocalModule nativeIssuer,Current original)throws Exception {if(confirmed==null||confirmed.review.issuer().nativeIssuer!=nativeIssuer)throw new SecurityException("缺少同原项目宿主的存储确认");return confirmed.review.issuer().execute(confirmed,original);}
    /** Original TaskStore metadata only; observes declared targets without
     * creating another review/confirmation or replaying the delete operation. */
    public JSONObject query(JSONObject fixed,RunControl original)throws Exception {
        JSONArray requested=fixed.getJSONArray("targets"),results=new JSONArray();if(requested.length()>12)throw new SecurityException("原清理范围超过本次上限");boolean absent=true;
        for(int i=0;i<requested.length();i++){original.checkpoint();JSONObject target=requested.getJSONObject(i);String id=target.getString("id");JSONObject observation;if(id.startsWith("runtime:"))observation=runtime.observeFromOriginal(target,original);else if(id.startsWith("native_cache:")){if(nativeCaches==null)throw new SecurityException("原缓存账本未配置");observation=nativeCaches.query(target,original);}else{String[] parts=id.split(":",-1);if(parts.length!=3||!parts[0].equals("project")||!parts[1].matches("[a-f0-9-]{36}")||!DEPENDENCIES.contains(parts[2])&&!GENERATED.contains(parts[2]))throw new SecurityException("不是原清理的native项目缓存范围");File path=new File(checkouts.root(parts[1]),parts[2]);observation=new JSONObject().put("id",id).put("targetAbsent",!Files.exists(path.toPath(),LinkOption.NOFOLLOW_LINKS)).put("observationOnly",true).put("deletionAttributionProven",false);}absent&=observation.getBoolean("targetAbsent");results.put(observation);}
        return new JSONObject().put("actionId",fixed.getString("actionId")).put("planDigest",fixed.getString("planDigest")).put("targets",results).put("allTargetsCurrentlyAbsent",absent).put("status",absent?"completed":"unknown").put("observationOnly",true).put("deletionAttributionProven",false).put("automaticRetry",false);
    }
    public JSONObject execute(Confirmed confirmed,Current nativeCurrent)throws Exception {
        if(confirmed==null||confirmed.review.issuer()!=this)throw new SecurityException("清理确认不是这个原项目宿主");synchronized(confirmed){if(confirmed.entered)throw new SecurityException("原清理已进入，只观察原结果，不重放删除");confirmed.entered=true;}Review review=confirmed.review;review.current();RunControl original=review.original;nativeCurrent.check();long before=files.getUsableSpace();JSONArray results=new JSONArray();
        try(RuntimeStorage.Maintenance maintenance=runtime.beginMaintenance()){
            for(Selected item:review.selected){original.checkpoint();nativeCurrent.check();if(item.nativeCopy!=null)item.nativeCopy.requireCurrent(review.action,original);else if(item.environment!=null)runtime.requireCurrent(item.environment,original);else{checkouts.requireIdle(item.checkout);item.project.requireCurrent(original);}}
            for(Selected item:review.selected){original.checkpoint();nativeCurrent.check();JSONObject result=item.nativeCopy!=null?item.nativeCopy.delete(review.action,original):item.environment==null?item.project.delete(original).put("id",item.id):runtime.clean(item.environment,maintenance,original);results.put(result);}
        }
        return new JSONObject().put("actionId",review.action).put("planDigest",review.digest).put("status","completed").put("targets",results).put("filesystemUsableBytesBefore",before).put("filesystemUsableBytesAfter",files.getUsableSpace()).put("crossTargetTransaction",false).put("sourceFilesDeleted",false).put("fixedArtifactsDeleted",false);
    }
}
