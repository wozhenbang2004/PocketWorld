package dev.pocketengine.phone.display;

import dev.pocketengine.phone.access.AppCopyKey;
import dev.pocketengine.phone.setup.PhoneAudioRecovery;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.json.JSONArray;
import org.json.JSONObject;

/** A short-lived native owner review over the existing restoration ledger, not an audio queue. */
final class OwnerTaskAudio {
    interface Current { void check() throws Exception; JSONObject ownership() throws Exception; }
    private static final long REVIEW_LIFETIME=TimeUnit.SECONDS.toNanos(30);
    private final String owner;
    private final int display;
    private final PhoneAudioRecovery recovery;
    private final LongSupplier clock;
    private final Set<AppCopyKey> restoredByOwner=new HashSet<>();
    private Review review;
    private Target dispatchTarget;

    OwnerTaskAudio(String owner,int display,PhoneAudioRecovery recovery){this(owner,display,recovery,System::nanoTime);}
    OwnerTaskAudio(String owner,int display,PhoneAudioRecovery recovery,LongSupplier clock){
        if(owner==null||owner.isBlank()||display<=0)throw new IllegalArgumentException("original_audio_owner_required");
        this.owner=owner;this.display=display;this.recovery=Objects.requireNonNull(recovery);this.clock=Objects.requireNonNull(clock);
    }
    synchronized boolean automaticMuteAllowed(AppCopyKey app){return !restoredByOwner.contains(app);}
    synchronized void invalidate(){review=null;}

    synchronized JSONObject review(long pauseRevision,boolean paused,Current current)throws Exception {
        review=null;current.check();Target target=target(current.ownership(),display);
        JSONObject state=recovery.inspectOwner(owner,target.app);current.check();
        Target after=target(current.ownership(),display);if(!target.equals(after))throw new SecurityException("目标 App 已变化，请重新核对声音状态");
        Review next=new Review(UUID.randomUUID().toString(),target,new JSONObject(state.toString()),pauseRevision,paused,clock.getAsLong());
        review=next;return view(next,state).put("reviewId",next.id).put("reviewExpiresInSeconds",30).put("stateVerified",true);
    }

    synchronized JSONObject confirm(String id,boolean muted,long pauseRevision,boolean paused,Current current)throws Exception {
        Review selected=review;review=null; // Never repeat an unknown native write with the same confirmation.
        long age=selected==null?-1:clock.getAsLong()-selected.at;
        if(selected==null||!selected.id.equals(id)||age<0||age>REVIEW_LIFETIME
                ||selected.pauseRevision!=pauseRevision||selected.paused!=paused)
            throw new SecurityException("原音频审阅已过期或任务曾暂停，请重新读取；没有重复派发");
        current.check();requireTarget(selected.target,current.ownership());
        JSONObject actual=recovery.inspectOwner(owner,selected.target.app);current.check();
        if(!sameState(selected.state,actual))throw new SecurityException("原 App 的声音或恢复记录已变化，请重新核对");
        if(!(muted?actual.getBoolean("canMute"):actual.getBoolean("canRestore")))
            throw new SecurityException(muted?"此状态没有新的静音准入；原未知记录只可核对或恢复":"没有这个原任务的恢复记录，不改动 App 当前声音");
        requireTarget(selected.target,current.ownership());current.check();dispatchTarget=selected.target;
        if(muted)restoredByOwner.remove(selected.target.app);else restoredByOwner.add(selected.target.app);
        JSONObject result;
        try {
            JSONObject effect=muted?recovery.silence(owner,selected.target.app,true):recovery.setOriginalForOwner(owner,selected.target.app);
            result=base(selected.target).put("reviewId",id).put("requestedMuted",muted).put("receipt",effect)
                    .put("automaticReplayAllowed",false).put("requiresFreshReview",true);
            try {
                current.check();requireTarget(selected.target,current.ownership());
                JSONObject readback=recovery.inspectOwner(owner,selected.target.app);current.check();
                boolean confirmed=muted?"silenced_verified".equals(effect.optString("outcome"))&&readback.getBoolean("muted")
                        :effect.optLong("pending",1)==0&&!readback.getBoolean("restorationRecorded");
                result.put("state",readback).put("stateVerified",true).put("confirmed",confirmed)
                        .put("outcome",confirmed?(muted?"silenced_verified":effect.optInt("supersededByOwner")>0?"owner_mode_preserved":"restored_verified"):"audio_unconfirmed");
            } catch(Exception unknown) {
                result.put("stateVerified",false).put("confirmed",false).put("outcome","audio_unconfirmed");
            }
        } finally {dispatchTarget=null;}
        return result;
    }

    /** Called immediately before each admitted audio.set, after its original durable claim. */
    synchronized void requireDispatchTarget(JSONObject payload,JSONObject ownership)throws Exception {
        Target expected=dispatchTarget;
        if(expected==null||!expected.app.packageName().equals(payload.optString("packageName"))
                ||exactInt(payload,"userId")!=expected.app.userId())throw new SecurityException("音频写入不属于刚才核对的原 App");
        requireTarget(expected,ownership);
    }
    private void requireTarget(Target expected,JSONObject ownership)throws Exception {
        if(!expected.equals(target(ownership,display)))throw new SecurityException("原目标 App 或显示身份已变化，未对新目标重放音频操作");
    }
    private static boolean sameState(JSONObject a,JSONObject b)throws Exception {
        for(String key:new String[]{"mode","restorationRecorded","originalMode","canMute","canRestore"})if(!Objects.equals(a.get(key),b.get(key)))return false;
        return true;
    }
    private JSONObject view(Review value,JSONObject state)throws Exception {
        return base(value.target).put("state",new JSONObject(state.toString())).put("paused",value.paused).put("pauseRevision",value.pauseRevision)
                .put("restoreActionLabel","将此 App 设为原记录的声音模式")
                .put("restoreActionNotice","这是现在明确发出的声音设置；若其他程序同时更改，可能以本次设置为准。不会自动执行或重复派发。")
                .put("scope","background_current_app_user0").put("mainScreenSupported",false).put("otherProfilesSupported",false);
    }
    private JSONObject base(Target target)throws Exception {
        return new JSONObject().put("packageName",target.app.packageName()).put("userId",target.app.userId()).put("displayId",display)
                .put("globalVolumeChanged",false).put("automaticReplayAllowed",false);
    }

    /** Resolve exactly one resumed App from complete helper-native identities. Never guess a user. */
    static Target target(JSONObject facts,int display)throws Exception {
        String unavailable=DisplayLandingSafety.failure(null,facts,display);
        if(unavailable!=null)throw new SecurityException("目标 App 的完整显示归属尚不可核对："+unavailable);
        JSONArray owned=facts.getJSONArray("owned"),tasks=facts.getJSONObject("taskTopology").getJSONArray("tasks");
        if(owned.length()==0)throw new SecurityException("后台屏还没有可核对的当前 App");
        AppCopyKey app=null;List<String> identities=new ArrayList<>();Set<Integer> seen=new HashSet<>();
        for(int i=0;i<owned.length();i++){
            JSONObject row=owned.getJSONObject(i);int taskId=exactInt(row,"taskId"),user=exactInt(row,"userId");
            AppCopyKey found=new AppCopyKey(row.getString("packageName"),user);
            if(user!=0)throw new SecurityException("当前 helper 只可核对 user 0 的目标音频；这个资料不支持此入口");
            if(app!=null&&!app.equals(found))throw new SecurityException("后台屏有多个当前 App，请先回原任务明确目标");
            if(!seen.add(taskId))throw new SecurityException("目标 App 身份重复，未选择音频目标");
            app=found;JSONObject matched=null;
            for(int j=0;j<tasks.length();j++){JSONObject candidate=tasks.getJSONObject(j);if(exactInt(candidate,"taskId")==taskId){matched=candidate;break;}}
            if(matched==null||exactInt(matched,"androidUserId")!=user||exactInt(matched,"displayId")!=display
                    ||!found.packageName().equals(matched.getString("packageName")))throw new SecurityException("目标 App 资料与原生任务身份不一致");
            identities.add(taskId+"/"+matched.getInt("rootTaskId")+"/"+matched.getString("component")+"/"+matched.getString("rootIdentity"));
        }
        for(int i=0;i<tasks.length();i++){JSONObject task=tasks.getJSONObject(i);
            if(app.packageName().equals(task.getString("packageName"))&&app.userId()==exactInt(task,"androidUserId")&&exactInt(task,"displayId")!=display)
                throw new SecurityException("同一 App 资料还在其他屏幕，按 App 静音会影响那里，未改动声音");
        }
        identities.sort(String::compareTo);return new Target(app,String.join("\n",identities));
    }
    private static int exactInt(JSONObject value,String key)throws Exception {
        Object raw=value.get(key);if(!(raw instanceof Number n)||!Double.isFinite(n.doubleValue())||n.doubleValue()!=Math.rint(n.doubleValue())||n.doubleValue()<0||n.doubleValue()>Integer.MAX_VALUE)
            throw new SecurityException("目标 App 没有可核对的原生整数身份");
        return((Number)raw).intValue();
    }
    record Target(AppCopyKey app,String fingerprint) { }
    private record Review(String id,Target target,JSONObject state,long pauseRevision,boolean paused,long at) { }
}
