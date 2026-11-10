package dev.pocketengine.features.semantic;

import dev.pocketengine.core.RunControl;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;
import org.json.JSONObject;

/** Bounded in-memory discovery/re-entry state of ONE original control; TaskStore owns all authority. */
final class SemanticRunSession {
    final RunControl originalControl;
    final LongSupplier clock;
    private final Map<String,SemanticCapabilityPort.Discovery> discoveries=new LinkedHashMap<>();
    private final Map<String,CalendarReference> calendarReferences=new LinkedHashMap<>();
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
    synchronized String retainCalendarEvent(SemanticPlatform.Target source) {
        requireLive(originalControl);long now=clock.getAsLong(),pause=originalControl.pauseRevision();
        calendarReferences.entrySet().removeIf(entry->now-entry.getValue().created>=SemanticCapabilityPort.DISCOVERY_TTL_MS||entry.getValue().pauseRevision!=pause);
        while(calendarReferences.size()>=256)calendarReferences.remove(calendarReferences.keySet().iterator().next());
        String id=java.util.UUID.randomUUID().toString();calendarReferences.put(id,new CalendarReference(source,now,pause));return id;
    }
    synchronized SemanticPlatform.Target calendarEvent(String reference) {
        requireLive(originalControl);CalendarReference value=calendarReferences.get(reference);
        if(value==null||clock.getAsLong()-value.created>=SemanticCapabilityPort.DISCOVERY_TTL_MS||value.pauseRevision!=originalControl.pauseRevision()){
            calendarReferences.remove(reference);throw new SecurityException("日历事件引用已过期、经历暂停或不属于当前执行，请重新列出事件");
        }
        return value.source;
    }
    synchronized void rememberCalendarApproval(JSONObject args,SemanticCapabilityPort.Discovery discovery,JSONObject capability,JSONObject approval)throws Exception {
        requireLive(originalControl);
        if(reconciliationRequired||discovery.consumed||find(args.getString("discoveryId"))!=discovery)return;
        discovery.calendarApproval=null;
        if(!"pending".equals(approval.optString("state"))||!discovery.request.calendarWrite()||!capability.optBoolean("requiresOwnerConsent"))return;
        String id=approval.getString("approvalId");SemanticJson.uuid(id);
        discovery.calendarApproval=new SemanticCapabilityPort.PendingCalendarApproval(id,args.getString("candidateId"),SemanticJson.hash(args),SemanticJson.hash(capability));
    }
    synchronized Map<String,SemanticCapabilityPort.Discovery> calendarContinuations(SemanticRequest request)throws Exception {
        requireLive(originalControl);Map<String,SemanticCapabilityPort.Discovery> out=new LinkedHashMap<>();
        if(reconciliationRequired||!request.calendarWrite())return out;
        for(Map.Entry<String,SemanticCapabilityPort.Discovery> entry:discoveries.entrySet()){
            SemanticCapabilityPort.Discovery value=entry.getValue();
            if(!value.consumed&&value.calendarApproval!=null&&clock.getAsLong()-value.created<SemanticCapabilityPort.DISCOVERY_TTL_MS
                    &&value.request.digest().equals(request.digest()))out.put(entry.getKey(),value);
        }
        return out;
    }
    synchronized SemanticCapabilityPort.PendingCalendarApproval calendarApproval(String id,SemanticCapabilityPort.Discovery discovery) {
        requireLive(originalControl);
        return !reconciliationRequired&&!discovery.consumed&&find(id)==discovery?discovery.calendarApproval:null;
    }
    /** Rebind just this reviewed discovery, never the listed eventRef or its expiry. */
    synchronized void rebindCalendar(String id,SemanticCapabilityPort.Discovery discovery,SemanticCapabilityPort.PendingCalendarApproval pending,long pause) {
        if(calendarApproval(id,discovery)!=pending||pending==null||originalControl.isPaused()||originalControl.isCancelled()||pause!=originalControl.pauseRevision())
            throw new SecurityException("原日历审批对象在重新核对期间已失效");
        discovery.pauseRevision=pause;
    }
    /** Short local claim only; no lock survives into authority callbacks, main queue or Android I/O. */
    synchronized boolean claim(String operationId,String discoveryId,SemanticCapabilityPort.Discovery discovery) {
        requireLive(originalControl);
        if(reconciliationRequired||entered.contains(operationId))return false;
        if(discoveries.get(discoveryId)!=discovery||discovery.consumed||clock.getAsLong()-discovery.created>=SemanticCapabilityPort.DISCOVERY_TTL_MS)
            throw new SecurityException("语义发现已使用、过期或失效");
        if(discovery.request.calendarWrite()&&discovery.pauseRevision!=originalControl.pauseRevision())throw new SecurityException("日历审批对象尚未在当前暂停代次重新核对");
        if(entered.size()>=1000)throw new IllegalStateException("本次执行语义派发已达上限");
        entered.add(operationId);discovery.consumed=true;discovery.calendarApproval=null;return true;
    }
    void markUncertain(){reconciliationRequired=true;}
    void close() {
        ended=true;
        synchronized(this){discoveries.clear();calendarReferences.clear();entered.clear();}
        originalControl.removeCancelAction(cancelAction);
    }
    private static final class CalendarReference {
        final SemanticPlatform.Target source;final long created,pauseRevision;
        CalendarReference(SemanticPlatform.Target source,long created,long pauseRevision){this.source=source;this.created=created;this.pauseRevision=pauseRevision;}
    }
}
