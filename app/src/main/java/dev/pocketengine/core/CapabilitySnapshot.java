package dev.pocketengine.core;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** Native read-only availability for one exact request. It never grants or dispatches a tool. */
public final class CapabilitySnapshot {
    @FunctionalInterface public interface CurrentCheck {void check()throws Exception;}
    private final String taskId,runId;
    private final int epoch;
    private final RunControl original;
    private final long pauseRevision;
    private final Set<String> capabilities;
    private final CurrentCheck current;
    private final java.util.List<CurrentCheck> routeChecks=new java.util.concurrent.CopyOnWriteArrayList<>();
    private final AtomicBoolean valid=new AtomicBoolean(true);
    private final Map<String,ScopedToolCatalog.SourcePin> sources=new LinkedHashMap<>();
    private boolean sourcesSealed;
    private record CatalogAdmission(Set<String> names,String digest,CurrentCheck current) { }
    private volatile CatalogAdmission catalog;
    public CapabilitySnapshot(String taskId,String runId,int epoch,RunControl original,Set<String> capabilities,CurrentCheck current){
        this.taskId=java.util.Objects.requireNonNull(taskId);this.runId=java.util.Objects.requireNonNull(runId);this.epoch=epoch;
        this.original=java.util.Objects.requireNonNull(original);this.current=java.util.Objects.requireNonNull(current);this.capabilities=Set.copyOf(capabilities);pauseRevision=original.pauseRevision();
        if(!original.hasHostRun()||!original.matchesHostRun(taskId,runId,epoch)||capabilities.size()>512)throw new SecurityException("能力快照缺少原任务身份或超过范围");
        for(String name:capabilities)if(!name.matches("[A-Za-z][A-Za-z0-9_.:-]{0,79}"))throw new IllegalArgumentException("能力快照名称无效");
    }
    public Set<String> current(String task,String run,int generation,RunControl control)throws Exception {
        if(control!=original||!taskId.equals(task)||!runId.equals(run)||epoch!=generation)throw new SecurityException("不能跨任务或执行代次使用能力快照");
        check();return capabilities;
    }
    public void check()throws Exception {
        if(Thread.holdsLock(this))throw new SecurityException("capability_snapshot_check_requires_unlocked_snapshot");
        original.check();if(!valid.get()||original.isPaused()||original.pauseRevision()!=pauseRevision)throw new SecurityException("原请求的能力快照已失效");
        current.check();for(CurrentCheck route:routeChecks)route.check();CatalogAdmission admitted=catalog;if(admitted!=null)admitted.current.check();
        original.check();if(!valid.get()||original.isPaused()||original.pauseRevision()!=pauseRevision)throw new SecurityException("原请求的能力快照已失效");
    }
    /** Original native routes register immutable source identities before their first read. */
    public void retainSources(Map<String,ScopedToolCatalog.SourcePin> supplied)throws Exception {
        Map<String,ScopedToolCatalog.SourcePin> copied=Map.copyOf(supplied);check();
        synchronized(this){
            if(!valid.get()||sourcesSealed)throw new SecurityException("tool_sources_closed_or_sealed");
            for(String name:copied.keySet())if(!capabilities.contains(name)||sources.containsKey(name))throw new SecurityException("tool_source_outside_snapshot_or_duplicate");
            sources.putAll(copied);
        }
        check();
    }
    /** Source checks run outside this monitor, including the check after the frozen copy. */
    public Map<String,ScopedToolCatalog.SourcePin> sourcePinsFor(String task,String run,int generation,RunControl control,Set<String> admittedNames)throws Exception {
        Set<String> names=Set.copyOf(admittedNames);current(task,run,generation,control);Map<String,ScopedToolCatalog.SourcePin> result;
        synchronized(this){
            if(!valid.get()||!capabilities.containsAll(names))throw new SecurityException("tool_sources_outside_original_snapshot");
            sourcesSealed=true;Map<String,ScopedToolCatalog.SourcePin> selected=new LinkedHashMap<>();
            for(String name:names)selected.put(name,sources.getOrDefault(name,ScopedToolCatalog.SourcePin.unknown()));
            result=Map.copyOf(selected);
        }
        current(task,run,generation,control);return result;
    }
    /** One native SQL admission, never an extra grant. Registration calls no external checks. */
    public void admitToolCatalog(Set<String> names,String nativeScopeDigest,CurrentCheck scopeCheck){
        Set<String> admitted=Set.copyOf(names);java.util.Objects.requireNonNull(scopeCheck);
        if(admitted.size()>100||!capabilities.containsAll(admitted)||admitted.contains(ScopedToolCatalog.SEARCH_TOOL)
                ||nativeScopeDigest==null||!nativeScopeDigest.matches("[a-f0-9]{64}"))throw new SecurityException("tool_catalog_native_admission_invalid");
        for(String name:admitted)if(!name.matches("[A-Za-z][A-Za-z0-9_]{0,63}"))throw new SecurityException("tool_catalog_native_name_invalid");
        synchronized(this){
            if(!valid.get()||catalog!=null)throw new SecurityException("tool_catalog_already_admitted_or_closed");
            catalog=new CatalogAdmission(admitted,nativeScopeDigest,scopeCheck);
        }
    }
    public Set<String> catalogTools(String task,String run,int generation,RunControl control)throws Exception {
        current(task,run,generation,control);CatalogAdmission admitted=catalog;
        if(admitted==null)throw new SecurityException("tool_catalog_native_admission_required");
        current(task,run,generation,control);return admitted.names;
    }
    public String catalogScopeDigest()throws Exception {
        check();CatalogAdmission admitted=catalog;if(admitted==null)throw new SecurityException("tool_catalog_native_admission_required");
        check();return admitted.digest;
    }
    /** Native additive refusal checks. A semantic alias never replaces an exact route identity. */
    public synchronized void retainRouteCheck(CurrentCheck check){if(!valid.get()||routeChecks.size()>=8)throw new SecurityException("原能力快照已关闭或路由检查过多");routeChecks.add(java.util.Objects.requireNonNull(check));}
    public void invalidate(){valid.set(false);}
}
