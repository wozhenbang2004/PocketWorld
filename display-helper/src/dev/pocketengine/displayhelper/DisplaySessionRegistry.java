package dev.pocketengine.displayhelper;

import dev.pocketengine.phone.display.PhoneParallelCapabilities;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** Routes authenticated requests to their original display. It never elects a default run. */
final class DisplaySessionRegistry implements AutoCloseable {
    interface Backend extends AutoCloseable {
        JSONObject handle(JSONObject request)throws Exception;
        void tick();
        @Override void close();
    }
    @FunctionalInterface interface Factory { Backend create()throws Exception; }
    private record Owner(String task,String run,int epoch) {
        static Owner read(JSONObject request)throws Exception {
            boolean present=request.has("ownerTaskId")||request.has("ownerRunId")||request.has("ownerEpoch");
            if(!present)return null;
            Object task=request.opt("ownerTaskId"),run=request.opt("ownerRunId"),epoch=request.opt("ownerEpoch");
            if(!(task instanceof String)||!(run instanceof String)||!(epoch instanceof Number))throw failure("original_run_required");
            String taskId=(String)task,runId=(String)run;int generation;
            try{generation=new java.math.BigDecimal(epoch.toString()).intValueExact();}
            catch(RuntimeException invalid){throw failure("original_run_required");}
            if(taskId.isBlank()||taskId.length()>200||runId.isBlank()||runId.length()>200||generation<1)throw failure("original_run_required");
            return new Owner(taskId,runId,generation);
        }
        JSONObject write(JSONObject target)throws Exception {
            return target.put("ownerTaskId",task).put("ownerRunId",run).put("ownerEpoch",epoch);
        }
    }
    private static final class Entry {
        final Owner owner;
        final Backend backend;
        volatile String session="";
        volatile int display=-1;
        volatile boolean creating=true,ready,retired;
        Entry(Owner owner,Backend backend){this.owner=owner;this.backend=backend;}
    }
    private record Retired(Owner owner,String session,int display) { }
    private final Factory factory;
    private final Backend metadata;
    private final List<Entry> entries=new ArrayList<>();
    // Exact tombstones make a lost release response idempotent; they grant no execution rights.
    private final Map<String,Retired> retired=new LinkedHashMap<>();
    private boolean closed;

    DisplaySessionRegistry(Factory factory,Backend metadata){this.factory=factory;this.metadata=metadata;}

    JSONObject handle(JSONObject request)throws Exception {
        String command=request.optString("command");Owner owner=Owner.read(request);
        synchronized(this){if(closed)throw failure("helper_closed");}
        if("hello".equals(command)||"status".equals(command)){
            if(hasSessionIdentity(request))return scopedStatus(request,owner);
            if(owner!=null){
                Entry entry=findOwner(owner);
                return entry==null?owner.write(inactive()):readStatus(entry);
            }
            return inventory();
        }
        if("primary_ownership".equals(command))return metadata.handle(request);
        if(command.startsWith("audio_recovery.")){
            synchronized(this){
                // Also blocks entries whose creation or physical release has not been reconciled.
                if(!entries.isEmpty())throw failure("audio_recovery_busy");
                return metadata.handle(request);
            }
        }
        if("create".equals(command))return create(request,owner);
        Entry entry=findSession(request,owner);
        if(entry==null){
            Retired known=retired(request,owner);
            if(known!=null&&"release".equals(command))return released(known);
            throw failure("invalid_lease");
        }
        if(!entry.ready)throw failure("display_creation_unconfirmed");
        JSONObject result=entry.backend.handle(request);
        if(entry.owner!=null)entry.owner.write(result);
        if("release".equals(command)&&result.optBoolean("ok")&&result.optBoolean("released"))retire(entry);
        return result;
    }

    private JSONObject create(JSONObject request,Owner owner)throws Exception {
        PhoneParallelCapabilities.require(request);
        if(hasSessionIdentity(request))throw failure("create_has_existing_session_identity");
        Entry entry;boolean reuse;
        synchronized(this){
            if(closed)throw failure("helper_closed");
            Entry existing=findOwner(owner);
            if(existing!=null){
                if(!existing.ready)throw failure("display_creation_unconfirmed");
                entry=existing;reuse=true;
            }else{
                if(owner==null){
                    if(!entries.isEmpty()||request.optBoolean("parallel")||request.optInt("concurrentSessions",1)>1)
                        throw failure("original_run_required");
                }else {
                    for(Retired previous:retired.values())if(owner.equals(previous.owner))throw failure("original_session_ended");
                    for(Entry active:entries)if(active.owner==null||active.owner.task.equals(owner.task))throw failure("original_task_busy");
                }
                if(entries.size()>=PhoneParallelCapabilities.MAXIMUM_ACTIVE_PHONE_RUNS)throw failure("display_capacity_reached");
                entry=new Entry(owner,factory.create());entries.add(entry);reuse=false;
            }
        }
        if(reuse){
            // A slow original status never holds the routing monitor for other displays.
            JSONObject current=readStatus(entry);
            if(!current.optBoolean("sessionActive"))throw failure("original_session_ended");
            return current.put("reused",true);
        }
        try{
            JSONObject result=entry.backend.handle(request);
            if(!result.optBoolean("ok")){entry.creating=false;readStatus(entry);return result;}
            remember(entry,result);
            entry.creating=false;
            if(owner!=null)owner.write(result);
            return result.put("sessionActive",true);
        }catch(Exception failed){
            // A create reply may be lost after Android created the display. Keep the entry
            // until authenticated status proves absence; do not replay the mutation.
            entry.creating=false;
            try{readStatus(entry);}catch(Exception unknown){/* Recovery retains the capacity slot. */}
            throw failed;
        }
    }

    private JSONObject scopedStatus(JSONObject request,Owner owner)throws Exception {
        Entry entry=findSession(request,owner);
        if(entry!=null)return readStatus(entry);
        Retired known=retired(request,owner);
        if(known==null)throw failure("invalid_lease");
        JSONObject result=inactive().put("releaseConfirmed",true).put("releasedSessionId",known.session).put("releasedDisplayId",known.display);
        return known.owner==null?result:known.owner.write(result);
    }

    private JSONObject inventory()throws Exception {
        JSONObject result=inactive();JSONArray sessions=new JSONArray();List<Entry> copy;
        synchronized(this){copy=new ArrayList<>(entries);}
        for(Entry entry:copy){
            JSONObject state=readStatus(entry);
            if(state.optBoolean("sessionActive"))sessions.put(state);
            else if(!entry.retired)sessions.put(state.put("recoveryRequired",true));
        }
        // Only an explicitly unowned legacy display remains visible through the old API.
        // A sole native-run display is still not the default target of an unscoped request.
        if(sessions.length()==1&&!sessions.getJSONObject(0).has("ownerRunId"))result=new JSONObject(sessions.getJSONObject(0).toString());
        return result.put("sessions",sessions).put("activeSessionCount",sessions.length())
                .put("parallelPhone",PhoneParallelCapabilities.current()).put("sessionRouting",2);
    }

    private JSONObject inactive()throws Exception {
        JSONObject result=metadata.handle(new JSONObject().put("command","status"));
        return result.put("sessionActive",false).put("displayId",JSONObject.NULL).put("sessionId",JSONObject.NULL)
                .put("parallelPhone",PhoneParallelCapabilities.current()).put("sessionRouting",2);
    }

    private JSONObject readStatus(Entry entry)throws Exception {
        if(entry.creating){
            JSONObject pending=inactive().put("creationPending",true).put("recoveryRequired",true);
            return entry.owner==null?pending:entry.owner.write(pending);
        }
        if(entry.retired){
            JSONObject result=inactive().put("releaseConfirmed",true).put("releasedSessionId",entry.session).put("releasedDisplayId",entry.display);
            return entry.owner==null?result:entry.owner.write(result);
        }
        JSONObject result=entry.backend.handle(new JSONObject().put("command","status"));
        if(result.optBoolean("sessionActive"))remember(entry,result);
        else if(result.optBoolean("ok"))retire(entry);
        if(entry.owner!=null)entry.owner.write(result);
        return result.put("sessionRouting",2);
    }

    private synchronized void remember(Entry entry,JSONObject response)throws Exception {
        String session=response.optString("sessionId","");int display=response.optInt("displayId",-1);
        if(session.isBlank()||response.isNull("sessionId")||display<=0)throw failure("invalid_display_identity");
        if(entry.retired||!entries.contains(entry))throw failure("original_session_ended");
        if(!entry.session.isEmpty()&&(!entry.session.equals(session)||entry.display!=display))throw failure("display_identity_changed");
        for(Entry other:entries)if(other!=entry&&(session.equals(other.session)||display==other.display))throw failure("duplicate_display_identity");
        entry.session=session;entry.display=display;entry.ready=true;
    }

    private synchronized Entry findOwner(Owner owner){
        for(Entry entry:entries)if(java.util.Objects.equals(entry.owner,owner))return entry;
        return null;
    }

    private synchronized Entry findSession(JSONObject request,Owner owner)throws Exception {
        String session=exactSession(request);int display=exactDisplay(request);
        for(Entry entry:entries)if(session.equals(entry.session)&&display==entry.display){
            if(!java.util.Objects.equals(entry.owner,owner))throw failure("original_run_mismatch");
            return entry;
        }
        return null;
    }

    private synchronized Retired retired(JSONObject request,Owner owner)throws Exception {
        Retired previous=retired.get(exactSession(request));
        return previous!=null&&previous.display==exactDisplay(request)&&java.util.Objects.equals(previous.owner,owner)?previous:null;
    }

    private void retire(Entry entry){
        synchronized(this){
            if(entry.retired)return;
            entry.retired=true;entries.remove(entry);
            if(!entry.session.isEmpty()){
                retired.put(entry.session,new Retired(entry.owner,entry.session,entry.display));
                while(retired.size()>256)retired.remove(retired.keySet().iterator().next());
            }
        }
        entry.backend.close();
    }

    private JSONObject released(Retired previous)throws Exception {
        JSONObject result=new JSONObject().put("ok",true).put("released",true).put("releaseConfirmed",true)
                .put("sessionId",previous.session).put("displayId",previous.display);
        return previous.owner==null?result:previous.owner.write(result);
    }

    private static boolean hasSessionIdentity(JSONObject request){return request.has("sessionId")||request.has("displayId");}
    private static String exactSession(JSONObject request)throws Failure {
        Object value=request.opt("sessionId");
        if(!(value instanceof String)||((String)value).isBlank()||((String)value).length()>200)throw failure("exact_session_required");
        return (String)value;
    }
    private static int exactDisplay(JSONObject request)throws Failure {
        Object value=request.opt("displayId");
        if(!(value instanceof Number))throw failure("exact_display_required");
        try{int id=new java.math.BigDecimal(value.toString()).intValueExact();if(id>0)return id;}
        catch(RuntimeException invalid){/* Refuse fractions and overflow. */}
        throw failure("exact_display_required");
    }
    void tick(){
        List<Entry> copy;synchronized(this){if(closed)return;copy=new ArrayList<>(entries);}
        // Queue every display's own cleanup before waiting for any status reconciliation.
        for(Entry entry:copy)entry.backend.tick();
        for(Entry entry:copy){
            if(!entry.creating)try{readStatus(entry);}catch(Exception unknown){/* Keep unconfirmed resources owned. */}
        }
    }
    @Override public void close(){
        List<Entry> copy;synchronized(this){if(closed)return;closed=true;copy=new ArrayList<>(entries);entries.clear();}
        for(Entry entry:copy)entry.backend.close();metadata.close();
    }
    static Failure failure(String code){return new Failure(code);}
    static final class Failure extends Exception {
        final String code;
        Failure(String code){super("原后台显示身份或资源状态不满足请求，未改用其他屏幕。");this.code=code;}
    }
}
