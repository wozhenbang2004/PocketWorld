package dev.pocketengine.displayhelper;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** Optional routing of original engines, after native TaskStore/session admission is enabled.
 * This is not a task runner, grant, operation journal, or authority to create another phone run. */
final class DisplaySessionDirectory implements AutoCloseable {
    private static final int MAX_SESSIONS=4;
    private final Context context;private final Handler main;private final int ownerUid;
    private final Map<String,Entry> entries=new LinkedHashMap<>();private boolean closed;
    private static final class Entry {
        final String owner;final DisplayEngine engine;volatile boolean creating=true,closed;
        Entry(String owner,DisplayEngine engine){this.owner=owner;this.engine=engine;}
    }
    DisplaySessionDirectory(Context context,Handler main,int authenticatedOwnerUid){
        if(authenticatedOwnerUid<10000||authenticatedOwnerUid>=100000)throw new SecurityException("authenticated_app_owner_required");
        this.context=java.util.Objects.requireNonNull(context);this.main=java.util.Objects.requireNonNull(main);ownerUid=authenticatedOwnerUid;
    }
    JSONObject handle(JSONObject request)throws Exception {
        String command=request.optString("command");synchronized(this){if(closed)throw failure("helper_closed","原后台助手已关闭。");}
        if("hello".equals(command)||"status".equals(command)){
            String owner=request.optString("ownerRunToken");return owner.isEmpty()?status():status(required(owner));
        }
        if("primary_ownership".equals(command)||"audio_recovery.get".equals(command)||"audio_recovery.set".equals(command))return new DisplayEngine(context,main,ownerUid).handle(request);
        if("create".equals(command))return create(request);
        Entry entry=required(request.optString("ownerRunToken"));
        if("release_owner".equals(command)){
            close(entry);try{if(!entry.engine.releaseConfirmed())throw new DisplayEngine.Failure("cleanup_unknown","原显示关闭尚未确认，请继续只读核对原会话。",true);}
            catch(DisplayEngine.Failure known){throw known;}catch(Exception unknown){throw new DisplayEngine.Failure("cleanup_unknown","原显示关闭尚未确认，请继续只读核对原会话。",true);}
            synchronized(this){entries.remove(entry.owner,entry);}
            return new JSONObject().put("ok",true).put("released",true).put("releaseConfirmed",true).put("sessionActive",false).put("ownerRunToken",entry.owner).put("releaseProof","original_engine_resources_released");
        }
        if(entry.closed)throw failure("release_pending","原显示关闭尚未核实，未继续派发。");
        if(request.optInt("displayId",-1)<=0||request.optString("sessionId").isEmpty())throw failure("invalid_lease","缺少原后台显示身份。");
        JSONObject result;
        try{result=entry.engine.handle(request).put("ownerRunToken",entry.owner);}
        catch(DisplayEngine.Failure known){throw known;}
        catch(Exception unknown){if(!java.util.Set.of("frame","apps","ownership","audio.get").contains(command))throw new DisplayEngine.Failure("action_unknown","原显示派发结果尚未确认，不会自动重放。",true);throw unknown;}
        if("release".equals(command)&&result.optBoolean("ok")&&result.optBoolean("released")){
            synchronized(this){entries.remove(entry.owner,entry);}close(entry);
        }
        return result;
    }
    private JSONObject create(JSONObject request)throws Exception {
        String owner=owner(request.optString("ownerRunToken"));
        synchronized(this){if(closed)throw failure("helper_closed","原后台助手已关闭。");if(entries.containsKey(owner))throw failure("display_owner_exists","原执行已有创建请求，请只读核对原会话，不会重复创建。");if(entries.size()>=MAX_SESSIONS)throw failure("display_capacity","独立屏资源已满，原任务应等待，不会抢占现有会话。");}
        // The same original engine enforces shell attribution, platform flags, lease, IME and PH020 ownership.
        Entry entry=new Entry(owner,new DisplayEngine(context,main,ownerUid));
        boolean rejected;synchronized(this){rejected=closed||entries.containsKey(owner)||entries.size()>=MAX_SESSIONS;if(!rejected)entries.put(owner,entry);}
        if(rejected){close(entry);throw failure("display_capacity","后台资源已变化，未取得第二屏。");}
        synchronized(entry){
            synchronized(this){if(closed||entry.closed||entries.get(owner)!=entry){entry.creating=false;throw failure("helper_closed","原后台助手已关闭，未继续创建。");}}
            try{return entry.engine.handle(request).put("ownerRunToken",owner).put("multiSession",true);}
            catch(DisplayEngine.Failure failed){
                if(!failed.dispatched){synchronized(this){entries.remove(owner,entry);}close(entry);}
                // An entered/uncertain create remains addressable by its original native token for read-only reconciliation.
                throw failed;
            }catch(Exception unknown){throw new DisplayEngine.Failure("create_outcome_unknown","独立屏创建结果尚未确认，请只读核对这个原执行；不会重复创建。",true);}
            finally{entry.creating=false;}
        }
    }
    private JSONObject status(Entry entry)throws Exception {return entry.engine.handle(new JSONObject().put("command","status")).put("ownerRunToken",entry.owner).put("creationPending",entry.creating).put("multiSession",true);}
    private JSONObject status()throws Exception {
        JSONArray sessions=new JSONArray();for(Entry entry:snapshot())sessions.put(status(entry));
        // A legacy client must never adopt the first other run's display as its own session.
        return new JSONObject().put("ok",true).put("protocol",1).put("uid",android.os.Process.myUid()).put("sdk",Build.VERSION.SDK_INT)
                .put("minimumSdk",34).put("backgroundOnly",true).put("frameMetadataOnly",true).put("multiSession",true)
                .put("sessionActive",false).put("displayId",JSONObject.NULL).put("sessionId",JSONObject.NULL).put("sessions",sessions)
                .put("capacity",MAX_SESSIONS).put("deviceVerified",false);
    }
    private synchronized Entry required(String token)throws Exception {owner(token);if(closed)throw failure("helper_closed","原后台助手已关闭。");Entry entry=entries.get(token);if(entry==null)throw failure("invalid_owner","没有这个原执行的显示会话。");return entry;}
    private synchronized List<Entry> snapshot(){return new ArrayList<>(entries.values());}
    void tick(){for(Entry entry:snapshot())try{entry.engine.tick();}catch(Exception unknown){/* Retain the exact entry until its native owner confirms release; never free it based on silence. */}}
    @Override public void close(){List<Entry> closing;synchronized(this){if(closed)return;closed=true;closing=new ArrayList<>(entries.values());entries.clear();}for(Entry entry:closing)try{close(entry);}catch(Exception ignored){/* Close every original engine even if one platform cleanup fails. */}}
    private void close(Entry entry){synchronized(entry){entry.closed=true;entry.engine.close();}}
    private static String owner(String token){if(token==null||!token.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))throw new SecurityException("native_owner_run_token_required");return token;}
    private static DisplayEngine.Failure failure(String code,String message){return new DisplayEngine.Failure(code,message,false);}
}
