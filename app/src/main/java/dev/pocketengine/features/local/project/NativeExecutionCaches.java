package dev.pocketengine.features.local.project;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.store.TaskStore;
import java.io.File;
import java.nio.file.*;
import java.util.*;
import org.json.*;

/** Owner-reviewed cleanup of two existing, finite native execution-copy roots.
 * TaskStore remains the only authority; this class has no persistent ledger,
 * job runner, auto eviction or ability to select an arbitrary native path. */
public final class NativeExecutionCaches {
    private static final Map<String,String> ROOTS=Map.of("git_intake","git-source-intakes","skill_copy","skill-execution-copies");
    private final File localRoot;private final TaskStore tasks;
    public NativeExecutionCaches(File originalLocalRoot,TaskStore originalTasks){localRoot=Objects.requireNonNull(originalLocalRoot).getAbsoluteFile();tasks=Objects.requireNonNull(originalTasks);}
    private File path(String kind,String operation)throws Exception {
        String folder=ROOTS.get(kind);if(folder==null||operation==null||!operation.matches("[a-f0-9-]{36}"))throw new SecurityException("不是本机原生执行缓存范围");
        File parent=new File(localRoot,folder),target=new File(parent,operation);if(Files.isSymbolicLink(localRoot.toPath())||Files.isSymbolicLink(parent.toPath())||!target.getCanonicalFile().equals(target))throw new SecurityException("原执行缓存路径身份已变化");return target;
    }
    public JSONArray inventory(RunControl original)throws Exception {
        JSONArray rows=new JSONArray();
        for(String kind:new TreeSet<>(ROOTS.keySet())){
            File parent=new File(localRoot,ROOTS.get(kind));String label=kind.equals("git_intake")?"Git读取暂存":"本机Skill执行副本";
            if(Files.isSymbolicLink(parent.toPath())){rows.put(new JSONObject().put("id","native_cache:"+kind+":unknown").put("label",label).put("cleanable",false).put("usage",new JSONObject().put("complete",false).put("reason","原目录身份不可核对，保留未知范围")));continue;}
            File[] candidates=parent.listFiles();if(candidates==null){if(parent.exists())rows.put(new JSONObject().put("id","native_cache:"+kind+":unknown").put("label",label).put("cleanable",false).put("usage",new JSONObject().put("complete",false).put("reason","原缓存目录不可完整读取，未按0字节或空目录处理")));continue;}
            if(candidates.length>64){rows.put(new JSONObject().put("id","native_cache:"+kind+":unknown").put("label",label).put("cleanable",false).put("usage",new JSONObject().put("complete",false).put("reason","缓存目录超过64个原生条目，保留并核对，不用截断列表授予删除")));continue;}
            Arrays.sort(candidates,Comparator.comparing(File::getName));
            for(File candidate:candidates){original.checkpoint();String id=candidate.getName();JSONObject row=new JSONObject().put("id","native_cache:"+kind+":"+id).put("label",label+" · "+id.substring(0,Math.min(8,id.length()))).put("cleanable",false).put("note",kind.equals("git_intake")?"删除本次下载暂存及尚未发布的源码、历史；已创建项目和原任务回执保留。确认即撤回这份缓存的后续发布资格。":"删除原脚本、输入与未固定输出的执行副本；项目、原日志和已固定成果保留。不会重新执行Skill。");
                try{TaskStore.NativeCacheDisposition authority=tasks.nativeExecutionCacheDispositionForOwner(kind,id);row.put("nativeCache",authority.metadata()).put("cleanable",authority.disposable).put("usage",NativeStorageTree.measure(path(kind,id),original));if(!row.getJSONObject("usage").optBoolean("complete"))row.put("cleanable",false);}
                catch(Exception unknown){original.checkpoint();row.put("usage",new JSONObject().put("complete",false).put("reason","没有同原账本的完整终态、回收或文件身份，缓存继续保留"));}rows.put(row);
            }
        }return rows;
    }
    public final class Entry {
        private final String kind,operation;private final TaskStore.NativeCacheDisposition authority;private final NativeStorageTree.Snapshot files;
        private Entry(String kind,String operation,TaskStore.NativeCacheDisposition authority,NativeStorageTree.Snapshot files){this.kind=kind;this.operation=operation;this.authority=authority;this.files=files;}
        public JSONObject metadata()throws Exception{return files.metadata().put("id","native_cache:"+kind+":"+operation).put("nativeCache",authority.metadata());}
        public void requireCurrent(String cleanup,RunControl original)throws Exception{tasks.requireNativeExecutionCacheForCleanup(kind,operation,authority.digest,cleanup);files.requireCurrent(original);}
        public JSONObject delete(String cleanup,RunControl original)throws Exception{requireCurrent(cleanup,original);return (kind.equals("skill_copy")?files.deleteOwnerReadOnlyCopy(original):files.delete(original)).put("id","native_cache:"+kind+":"+operation).put("originalTaskAndFixedArtifactsRetained",true);}
    }
    public Entry review(String id,RunControl original)throws Exception {
        String[] parts=id.split(":",-1);if(parts.length!=3||!parts[0].equals("native_cache"))throw new SecurityException("缺少准确本机缓存定位");TaskStore.NativeCacheDisposition authority=tasks.nativeExecutionCacheDispositionForOwner(parts[1],parts[2]);if(!authority.disposable)throw new SecurityException(authority.metadata().optString("reason","原执行缓存仍需保留"));return new Entry(parts[1],parts[2],authority,NativeStorageTree.scan(path(parts[1],parts[2]),original));
    }
    /** Observes only the exact roots previously retained in the original scope.
     * Absence is not evidence of who deleted files, or permission to retry. */
    public JSONObject query(JSONObject originalTarget,RunControl original)throws Exception{String[] parts=originalTarget.getString("id").split(":",-1);if(parts.length!=3||!parts[0].equals("native_cache"))throw new SecurityException("不是原执行缓存定位");original.checkpoint();return new JSONObject().put("id",originalTarget.getString("id")).put("targetAbsent",!Files.exists(path(parts[1],parts[2]).toPath(),LinkOption.NOFOLLOW_LINKS)).put("observationOnly",true).put("deletionAttributionProven",false);}
}
