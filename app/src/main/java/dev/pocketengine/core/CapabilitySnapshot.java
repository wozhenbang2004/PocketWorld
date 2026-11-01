package dev.pocketengine.core;

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
        original.check();if(!valid.get()||original.isPaused()||original.pauseRevision()!=pauseRevision)throw new SecurityException("原请求的能力快照已失效");
        current.check();for(CurrentCheck route:routeChecks)route.check();original.check();if(!valid.get()||original.isPaused()||original.pauseRevision()!=pauseRevision)throw new SecurityException("原请求的能力快照已失效");
    }
    /** Native additive refusal checks. A semantic alias never replaces an exact route identity. */
    public synchronized void retainRouteCheck(CurrentCheck check){if(!valid.get()||routeChecks.size()>=8)throw new SecurityException("原能力快照已关闭或路由检查过多");routeChecks.add(java.util.Objects.requireNonNull(check));}
    public void invalidate(){valid.set(false);}
}
