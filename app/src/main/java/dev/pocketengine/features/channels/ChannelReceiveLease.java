package dev.pocketengine.features.channels;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Only foreground-receiver lifecycle metadata. Never a message queue, task store or restart plan. */
final class ChannelReceiveLease {
    private final File file;
    private JSONObject state;
    ChannelReceiveLease(File directory)throws Exception {
        if(!directory.exists()&&!directory.mkdirs())throw new IllegalStateException("无法创建渠道状态目录");file=new File(directory,"background-receiver.json");
        if(file.isFile()){
            if(file.length()>16_384)throw new IllegalStateException("后台接收状态过大");
            state=new JSONObject(new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8));
        }else state=new JSONObject().put("status","stopped").put("sessionId","").put("profiles",new JSONArray());
        if(active())finish(state.getString("sessionId"),"interrupted","上次接收进程已结束；未自动恢复，请在本机重新开启");
    }
    synchronized JSONObject begin(JSONArray ids,ChannelCore core,boolean confirmed)throws Exception {
        if(!confirmed)throw new SecurityException("请在本机明确确认后台接收及耗电提示");if(active())throw new IllegalStateException("后台接收正在开启、运行或停止，请先停止原会话");
        if(ids==null||ids.length()<1||ids.length()>6)throw new IllegalArgumentException("请选择1至6个QQ或微信连接");
        java.util.Set<String> unique=new java.util.HashSet<>();JSONArray selected=new JSONArray();int qq=0,wx=0;
        for(int i=0;i<ids.length();i++){
            String id=ChannelCore.identifier(new JSONObject().put("id",ids.get(i)),"id");if(!unique.add(id))throw new IllegalArgumentException("不能重复选择连接");
            JSONObject profile=core.profile(id);String provider=profile.getString("provider");if(!profile.optBoolean("enabled")||!provider.matches("qq|weixin"))throw new SecurityException("后台接收仅支持已启用的QQ官方机器人或微信iLink连接");
            if(provider.equals("qq"))qq++;else wx++;
            selected.put(ChannelReceiveProfileIdentity.capture(profile));
        }
        if(qq>3||wx>3)throw new IllegalArgumentException("QQ与微信分别最多同时接收3个连接");
        JSONObject next=new JSONObject().put("sessionId",UUID.randomUUID().toString()).put("status","starting").put("profiles",selected).put("startedAt",System.currentTimeMillis()).put("message","已请求前台接收服务，尚未确认服务或平台在线");
        persist(next);return snapshot();
    }
    synchronized boolean canClaim(String session){return session!=null&&session.equals(state.optString("sessionId"))&&state.optString("status").equals("starting");}
    synchronized void foreground(String session)throws Exception {
        if(!canClaim(session))throw new SecurityException("此后台接收请求已失效，未启动新连接");
        JSONObject next=copy(state).put("status","foreground").put("message","前台服务已建立；各连接认证状态单独核对");persist(next);
    }
    synchronized void check(String session,ChannelCore core)throws Exception {
        if(!session.equals(state.optString("sessionId"))||!state.optString("status").equals("foreground"))throw new SecurityException("后台接收会话已结束");
        JSONArray profiles=state.getJSONArray("profiles");for(int i=0;i<profiles.length();i++){
            JSONObject selected=profiles.getJSONObject(i),current=core.profile(selected.getString("id"));
            ChannelReceiveProfileIdentity.check(selected,current);
        }
    }
    synchronized boolean finish(String session,String status,String message)throws Exception {
        if(session==null||!session.equals(state.optString("sessionId"))||!active())return false;
        if(!status.matches("stopped|interrupted|blocked"))throw new IllegalArgumentException("无效接收终态");
        JSONObject next=copy(state).put("status",status).put("message",message).put("stoppedAt",System.currentTimeMillis());
        try{persist(next);}catch(Exception failed){state=next.put("persistenceFailed",true);throw failed;}return true;
    }
    synchronized boolean owns(String id){JSONArray selected=state.optJSONArray("profiles");if(!active()||selected==null)return false;for(int i=0;i<selected.length();i++)if(id.equals(selected.optJSONObject(i).optString("id")))return true;return false;}
    synchronized boolean active(){return state.optString("status").matches("starting|foreground");}
    synchronized void cleanupFailed(String session)throws Exception {
        if(!session.equals(state.optString("sessionId")))return;
        JSONObject next=copy(state).put("status","blocked").put("cleanupFailed",true).put("message","后台接收清理未完全确认，请核对系统通知及连接状态");
        try{persist(next);}catch(Exception failed){state=next.put("persistenceFailed",true);throw failed;}
    }
    synchronized JSONObject snapshot()throws Exception{return copy(state).put("foregroundActive",state.optString("status").equals("foreground")).put("restartAfterProcessDeath",false);}
    private void persist(JSONObject next)throws Exception {
        next.put("updatedAt",System.currentTimeMillis());byte[] bytes=next.toString().getBytes(StandardCharsets.UTF_8);if(bytes.length>16_384)throw new IllegalStateException("后台接收状态过大");File pending=new File(file.getParentFile(),"background-receiver.pending");
        try(FileOutputStream output=new FileOutputStream(pending)){output.write(bytes);output.getFD().sync();}
        Files.move(pending.toPath(),file.toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);state=next;
    }
    private static JSONObject copy(JSONObject value)throws Exception{return new JSONObject(value.toString());}
}
