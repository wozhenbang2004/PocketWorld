package dev.pocketengine.features.semantic;

import dev.pocketengine.core.RunControl;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

/** Bounded in-memory discovery/re-entry state of ONE original control; TaskStore owns all authority. */
final class SemanticRunSession {
    final RunControl originalControl;
    final LongSupplier clock;
    private final Map<String,SemanticCapabilityPort.Discovery> discoveries=new LinkedHashMap<>();
    private final Set<String> entered=new HashSet<>();
    private final Runnable cancelAction=this::close;
    private volatile boolean ended,reconciliationRequired;
    SemanticRunSession(RunControl originalControl,LongSupplier clock) {
        this.originalControl=java.util.Objects.requireNonNull(originalControl);this.clock=java.util.Objects.requireNonNull(clock);
        if(!originalControl.hasHostRun())throw new SecurityException("语义发现需要原任务执行控制");
        originalControl.onCancel(cancelAction);
    }
    boolean ended(){return ended;}
    void requireLive(RunControl supplied) {
        if(ended||supplied!=originalControl)throw new SecurityException("语义发现会话不属于原任务控制或已结束");
    }
    synchronized boolean uncertain(String operationId){return reconciliationRequired||entered.contains(operationId);}
    synchronized SemanticCapabilityPort.Discovery find(String id) {
        if(ended)return null;SemanticCapabilityPort.Discovery value=discoveries.get(id);
        if(value!=null&&clock.getAsLong()-value.created>=SemanticCapabilityPort.DISCOVERY_TTL_MS){discoveries.remove(id);return null;}
        return value;
    }
    synchronized void put(String id,SemanticCapabilityPort.Discovery discovery) {
        requireLive(originalControl);long now=clock.getAsLong();
        discoveries.entrySet().removeIf(entry->now-entry.getValue().created>=SemanticCapabilityPort.DISCOVERY_TTL_MS);
        while(discoveries.size()>=16)discoveries.remove(discoveries.keySet().iterator().next());
        discoveries.put(id,discovery);
    }
    /** Short local claim only; no lock survives into authority callbacks, main queue or Android I/O. */
    synchronized boolean claim(String operationId,String discoveryId,SemanticCapabilityPort.Discovery discovery) {
        requireLive(originalControl);
        if(reconciliationRequired||entered.contains(operationId))return false;
        if(discoveries.get(discoveryId)!=discovery||discovery.consumed||clock.getAsLong()-discovery.created>=SemanticCapabilityPort.DISCOVERY_TTL_MS)
            throw new SecurityException("语义发现已使用、过期或失效");
        if(entered.size()>=1000)throw new IllegalStateException("本次执行语义派发已达上限");
        entered.add(operationId);discovery.consumed=true;return true;
    }
    void markUncertain(){reconciliationRequired=true;}
    void close() {
        ended=true;
        synchronized(this){discoveries.clear();entered.clear();}
        originalControl.removeCancelAction(cancelAction);
    }
}
