package dev.pocketengine.features.local.runtime;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.local.project.NativeStorageTree;
import dev.pocketengine.features.local.project.ProjectSources;
import java.io.File;
import java.util.*;
import org.json.*;

/** Owner storage adapter over this original managed runtime. Active roots,
 * homes, jobs/receipts and unknown generations are never generic cache targets. */
public final class RuntimeStorage {
    static final class Target {
        final String id,label,note;final File file;final boolean cleanable;
        Target(String id,String label,String note,File file,boolean cleanable){this.id=id;this.label=label;this.note=note;this.file=file;this.cleanable=cleanable;}
    }
    private final ManagedProjectRuntime runtime;private final AndroidRuntimePreparation android;private final RuntimeEnvironment environments;private final ManagedProcessJobs jobs;private final File home,tmp;
    RuntimeStorage(ManagedProjectRuntime runtime,AndroidRuntimePreparation android,RuntimeEnvironment environments,ManagedProcessJobs jobs,File home,File tmp){this.runtime=runtime;this.android=android;this.environments=environments;this.jobs=jobs;this.home=home;this.tmp=tmp;}
    public static final class Entry {
        private final RuntimeStorage issuer;private final Target target;private final NativeStorageTree.Snapshot snapshot;private final String runtimeIdentity;
        private Entry(RuntimeStorage issuer,Target target,NativeStorageTree.Snapshot snapshot,String identity){this.issuer=issuer;this.target=target;this.snapshot=snapshot;runtimeIdentity=identity;}
        public JSONObject metadata()throws Exception{JSONObject metadata=snapshot.metadata().put("id",target.id).put("label",target.label).put("note",target.note).put("runtimeIdentity",runtimeIdentity).put("cleanable",true);if(target.id.startsWith("runtime:links:"))metadata.put("nativeBackingId",target.file.getName());return metadata;}
    }
    private List<Target> targets()throws Exception {if(android!=null)return android.ownerStorageTargets();return List.of(new Target("runtime:home","开发环境 HOME","用户数据保留；不把整个HOME当缓存删除",home,false),new Target("runtime:temporary","运行临时文件","确认不再需要后清理，原任务日志和收据保留",tmp,true));}
    public JSONArray inventory(RunControl original)throws Exception {
        JSONArray rows=new JSONArray();for(Target target:targets()){original.checkpoint();JSONObject row=new JSONObject().put("id",target.id).put("label",target.label).put("note",target.note).put("cleanable",target.cleanable).put("runtimeIdentity",runtime.identity());try{row.put("usage",NativeStorageTree.measure(target.file,original));}catch(Exception unmeasured){original.checkpoint();row.put("usage",new JSONObject().put("complete",false).put("logicalBytes",JSONObject.NULL).put("reason","原目录无法完整读取；没有以0字节或截断清单授予清理")).put("cleanable",false);}rows.put(row);}rows.put(new JSONObject().put("id","runtime:original-receipts").put("label","原执行日志、回执与输入ACK").put("note","保留原操作核对和单调运行顺序，不把原任务证据当缓存删除").put("cleanable",false).put("usage",jobs.ownerStorageUsage(original)));return rows;
    }
    public Entry review(String targetId,RunControl original)throws Exception {for(Target target:targets())if(target.id.equals(targetId)){if(!target.cleanable)throw new SecurityException("这个原运行环境范围保留，不能作为普通缓存删除");return new Entry(this,target,NativeStorageTree.scan(target.file,original),runtime.identity());}throw new SecurityException("不是本运行环境明确声明的存储范围");}
    /** Kept until all selected native deletions finish. Existing leases and any
     * live/unknown original jobs block it; new prepares/exec acquire cannot enter. */
    public static final class Maintenance implements AutoCloseable {private final RuntimeStorage issuer;private final AutoCloseable original;private boolean closed;private Maintenance(RuntimeStorage issuer,AutoCloseable original){this.issuer=issuer;this.original=original;}@Override public synchronized void close()throws Exception{if(!closed){closed=true;original.close();}}}
    public Maintenance beginMaintenance()throws Exception {return new Maintenance(this,android==null?environments.beginOwnerMaintenance():android.beginOwnerStorageMaintenance());}
    public void requireCurrent(Entry entry,RunControl original)throws Exception {if(entry==null||entry.issuer!=this||!entry.runtimeIdentity.equals(runtime.identity()))throw new SecurityException("原存储审阅不是当前运行环境");boolean retained=false;for(Target current:targets())if(current.id.equals(entry.target.id)&&current.cleanable&&current.file.equals(entry.target.file))retained=true;if(!retained)throw new SecurityException("原存储范围已成为当前环境或身份变化");entry.snapshot.requireCurrent(original);}
    public JSONObject clean(Entry entry,Maintenance held,RunControl original)throws Exception {if(entry==null||entry.issuer!=this||held==null||held.issuer!=this||held.closed||!entry.runtimeIdentity.equals(runtime.identity()))throw new SecurityException("没有原运行环境的当前清理租约");
        // Do not repeat the full pair inventory here: an earlier member of this
        // same approved pair may already be gone. Recheck only its independent
        // active publication fence before the original exact-snapshot deletion.
        if(android!=null&&(entry.target.id.startsWith("runtime:generation:")||entry.target.id.startsWith("runtime:links:")))android.requireOwnerStoragePublication();
        return entry.snapshot.delete(original).put("id",entry.target.id).put("projectFilesDeleted",false).put("runtimeHomeDeleted",false);}
    public JSONObject observeFromOriginal(JSONObject nativeScope,RunControl original)throws Exception {
        original.checkpoint();String id=nativeScope.getString("id");File target;
        if(id.equals("runtime:temporary"))target=tmp;
        else if(android!=null)target=android.ownerStorageObservationTarget(nativeScope);
        else throw new SecurityException("原运行环境没有声明这个存储查询范围");
        return new JSONObject().put("id",id).put("targetAbsent",!java.nio.file.Files.exists(target.toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS)).put("observationOnly",true).put("deletionAttributionProven",false).put("replayAllowed",false);
    }
    public JSONObject observe(Entry entry)throws Exception{if(entry==null||entry.issuer!=this)throw new SecurityException("不是原存储审阅");return entry.snapshot.observe().put("id",entry.target.id);}
}
