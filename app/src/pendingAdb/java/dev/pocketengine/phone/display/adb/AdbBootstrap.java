package dev.pocketengine.phone.display.adb;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;
import dev.pocketengine.store.SecretVault;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.json.JSONObject;

/** Main-process-only owner setup adapter. Pair/connect never constitute display readiness. */
public final class AdbBootstrap implements AdbBootstrapClient {
    public interface AuthenticatedStatus { void check()throws Exception; }
    private final Context context;
    private final SharedPreferences prefs;
    private final AdbIdentity identity;
    private final Consumer<JSONObject> changed;
    private final LocalAdbDiscovery discovery;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor(),control=Executors.newSingleThreadExecutor();
    private final Object lock=new Object();
    private volatile Connection connection;
    private volatile Attempt attempt;
    private volatile long generation;
    private volatile boolean closed,terminating,forgetPending,ready;
    private volatile String phase="idle",message="先在系统开发者选项开启无线调试，再输入本机端口与当前配对码。";

    public AdbBootstrap(Context context,SecretVault secrets,Consumer<JSONObject> changed){
        this.context=context.getApplicationContext();this.changed=changed;identity=new AdbIdentity(secrets);
        if((this.context.getPackageName()+":adb_bootstrap").equals(android.app.Application.getProcessName()))throw new SecurityException("ADB身份只允许主进程管理");
        prefs=this.context.getSharedPreferences("display_adb_bootstrap",Context.MODE_PRIVATE);generation=prefs.getLong("generation",0);
        if(!prefs.getString("pendingOperation","").isEmpty()){
            phase=unknownPhase(prefs.getString("pendingAction","connect"));message="上次无线ADB结果未确认，请先连接并核对原后台助手，不能自动重复配对。";
        }
        discovery=new LocalAdbDiscovery(this.context,value->publish());
    }

    public JSONObject snapshot(){
        JSONObject value=new JSONObject();Attempt current=attempt;
        try{return value.put("supportedAndroid",Build.VERSION.SDK_INT>=34).put("identityPresent",identity.exists())
                .put("busy",current!=null||terminating||forgetPending).put("phase",phase).put("message",message)
                .put("generation",generation).put("operationId",current==null?JSONObject.NULL:current.id)
                .put("helperAuthenticated",ready).put("recoveryRequired",!prefs.getString("pendingOperation","").isEmpty())
                .put("processTerminationPending",terminating).put("discovery",discovery.snapshot()).put("deviceVerified",false);}
        catch(Exception impossible){return value;}
    }
    public JSONObject discover(){requireOpen();discovery.start();return snapshot();}
    public JSONObject pair(JSONObject input)throws Exception {
        String host=LocalAdbEndpoints.current().check(input.optString("host","127.0.0.1"),input.getInt("pairingPort"));
        String code=LocalAdbEndpoints.pairingCode(input.optString("pairingCode"));
        if(!prefs.getString("pendingOperation","").isEmpty())throw new IllegalStateException("上次配对或启动结果未确认，请先连接核对原助手");
        Attempt current=begin("pair",new JSONObject().put("host",host).put("pairingPort",input.getInt("pairingPort")).put("pairingCode",code),null,null);
        bind(current);return snapshot();
    }
    public JSONObject connect(JSONObject input,JSONObject bootstrap,AuthenticatedStatus authenticatedStatus)throws Exception {
        String host=LocalAdbEndpoints.current().check(input.optString("host","127.0.0.1"),input.getInt("connectPort"));
        LocalAdbEndpoints.distinctPorts(input.optInt("pairingPort",-1),input.getInt("connectPort"));
        if(authenticatedStatus==null)throw new IllegalArgumentException("后台助手必须提供真实status核验");
        JSONObject config=new JSONObject(bootstrap.toString());
        if(config.optInt("ownerUid",-1)!=Process.myUid()||!config.optString("socketName").matches("pocketengine\\.display\\.[a-f0-9]{32}")||!config.optString("token").matches("[a-f0-9]{64}"))throw new SecurityException("后台助手身份配置无效");
        Attempt current=begin("connect",new JSONObject().put("host",host).put("connectPort",input.getInt("connectPort")).put("pairingPort",input.optInt("pairingPort",-1)),config,authenticatedStatus);
        bind(current);return snapshot();
    }
    private Attempt begin(String action,JSONObject request,JSONObject config,AuthenticatedStatus status)throws Exception {
        synchronized(lock){
            requireOpen();if(Build.VERSION.SDK_INT<34)throw new IllegalStateException("后台独立显示需要Android14或以上");
            if(attempt!=null||terminating||forgetPending)throw new IllegalStateException("已有无线ADB操作或进程回收尚未结束");
            long next=Math.addExact(generation,1);if(!prefs.edit().putLong("generation",next).commit())throw new IllegalStateException("无线ADB代次保存失败");
            generation=next;ready=false;Attempt current=new Attempt(UUID.randomUUID().toString(),next,action,request,config,status);attempt=current;
            phase="binding";message="正在连接本应用的独立无线ADB启动器。";return current;
        }
    }
    private void bind(Attempt current){
        Connection existing,next;
        synchronized(lock){
            if(closed){current.clearTransient();if(attempt==current)attempt=null;return;}
            existing=connection;
            if(existing!=null&&existing.binder!=null&&existing.binder.isBinderAlive())next=null;
            else{next=new Connection();connection=next;}
        }
        if(next==null){submit(existing,current);publish();return;}
        main.post(()->{
            if(connection!=next)return;
            try{
                next.bound=context.bindService(new Intent(context,AdbBootstrapService.class),next,Context.BIND_AUTO_CREATE);
                if(!next.bound)bindUnavailable(next,current);
            }catch(RuntimeException unavailable){bindUnavailable(next,current);}
        });
        main.postDelayed(()->{if(current(current)&&phase.equals("binding"))terminate("bind_timeout","独立启动器连接超时；未把配对或连接视为成功。");},10000);
        main.postDelayed(()->{if(current(current))terminate(unknownPhase(current.action),"启动回执超时，正在结束本应用独立启动进程。");},60000);
        publish();
    }
    private void bindUnavailable(Connection remote,Attempt current){
        if(connection!=remote)return;connection=null;
        if(terminating){attempt=null;terminating=false;current.clearTransient();phase="service_unavailable";message="系统未绑定独立启动服务，当前请求未交给ADB库。";publish();}
        else finish(current,"service_unavailable","独立启动服务未注册或系统拒绝绑定。",false);
        if(closed)control.shutdown();
    }
    private void submit(Connection remote,Attempt current){
        execute(worker,()->{
            if(!current(current)||connection!=remote||remote.binder==null)return;
            try{
                phase="checking_existing";message="先核对原助手与独立启动进程，避免重放未知配对。";publish();
                ChildState child=info(remote.binder);
                if(current.action.equals("connect")){
                    try{
                        current.status.check();
                        if(!current(current))return;
                        if(child.phase.equals("helper_started")){
                            if(!prefs.getString("pendingOperation","").equals(child.operation)||!acknowledge(remote.binder,child.operation))throw new IllegalStateException("旧启动回执身份不匹配");
                        }else if(!idleChild(child))throw new IllegalStateException("旧启动操作仍在进行");
                        if(clearPendingFor(current))finish(current,"ready","原后台助手已通过认证status核验。",true);return;
                    }catch(Exception notReady){if(!current(current))return;}
                }
                if(!idleChild(child)){
                    terminate(unknownPhase(current.action),"旧启动进程尚未完成；先回收后再连接核对，未重复配对。");return;
                }
                try(AdbIdentity.Material material=identity.getOrCreate()){
                    byte[] helper=helper();
                    try{
                        if(!current(current))return;
                        JSONObject request=current.request.put("action",current.action).put("operationId",current.id).put("generation",current.generation);
                        if(current.config!=null)request.put("bootstrap",current.config);
                        // Main process is the only preferences/vault writer. A crash after
                        // this fence requires recovery, even if the Binder reply is lost.
                        if(!markPending(current))return;
                        Parcel data=Parcel.obtain(),reply=Parcel.obtain();
                        try{
                            data.writeInterfaceToken(AdbBootstrapWire.SERVICE);data.writeString(request.toString());data.writeByteArray(material.key);data.writeByteArray(material.certificate);data.writeByteArray(helper);data.writeStrongBinder(callback);
                            if(!remote.binder.transact(AdbBootstrapWire.REQUEST,data,reply,0))throw new RemoteException("私有启动协议不支持");reply.readException();
                        }finally{data.recycle();reply.recycle();request.remove("pairingCode");request.remove("bootstrap");}
                    }finally{Arrays.fill(helper,(byte)0);}
                }
            }catch(Exception unknown){if(current(current))terminate(unknownPhase(current.action),"无线ADB结果未确认；请先核对原助手，不会自动重试。");}
        });
    }
    private final Binder callback=new Binder(){
        @Override protected boolean onTransact(int code,Parcel data,Parcel reply,int flags)throws RemoteException {
            if(code==INTERFACE_TRANSACTION){if(reply!=null)reply.writeString(AdbBootstrapWire.CALLBACK);return true;}
            if(Binder.getCallingUid()!=Process.myUid())throw new SecurityException("只接受本应用启动器回执");
            if(code!=AdbBootstrapWire.EVENT)return super.onTransact(code,data,reply,flags);data.enforceInterface(AdbBootstrapWire.CALLBACK);
            String encoded=data.readString();if(encoded==null||encoded.length()>2048)return true;
            try{JSONObject event=new JSONObject(encoded);Attempt current=attempt;
                if(current==null||!current.id.equals(event.optString("operationId"))||current.generation!=event.optLong("generation")||!current(current))return true;
                String value=event.optString("phase");
                if(value.equals("helper_started"))execute(worker,()->verifyStarted(current));
                else if(value.equals("paired")){if(clearPendingFor(current)){finish(current,"paired","配对协议已确认；请重新发现连接端口并连接，尚未建立后台助手。",false);discovery.refreshConnect();}}
                else if(value.equals("needs_pairing")){if(clearPendingFor(current))finish(current,"needs_pairing","ADB身份未获授权，请使用系统当前显示的配对端口与配对码。",false);}
                else if(value.endsWith("outcome_unknown"))terminate(unknownPhase(current.action),"无线ADB结果未确认，请先连接核对原助手。");
                else if(value.matches("pairing|connecting|uploading")){phase=value;message=value.equals("pairing")?"正在校验系统当前配对码。":"正在通过本机无线调试启动既有后台助手。";publish();}
            }catch(Exception unknown){ /* Malformed or stale callbacks never become readiness. */ }return true;
        }
    };
    private void verifyStarted(Attempt current){
        if(!current(current))return;
        try{
            current.status.check();if(!current(current))return;Connection remote=connection;
            if(remote==null||!acknowledge(remote.binder,current.id))throw new IllegalStateException("启动器未接受真实核验回执");
            if(clearPendingFor(current))finish(current,"ready","后台助手已通过既有认证status核验。",true);
        }catch(Exception unknown){if(current(current))terminate("bootstrap_outcome_unknown","助手已请求启动但认证未完成，正在回收启动进程；请核对原助手。");}
    }
    public JSONObject cancel(){requireOpen();discovery.stop();if(connection==null&&attempt==null)return snapshot();terminate(attempt==null?"cancelled":unknownPhase(attempt.action),"正在结束本应用的独立ADB启动进程；配对或已发生效果不会被撤销。");return snapshot();}
    /** Rotate only after Binder death, including when recovering an earlier main-process exit. */
    public JSONObject forgetIdentity(){
        requireOpen();synchronized(lock){if(attempt!=null||terminating||forgetPending)throw new IllegalStateException("请先等待当前启动或回收结束");forgetPending=true;ready=false;phase="forgetting";message="正在确认旧启动进程退出，再删除本机ADB身份。";}
        Connection remote=connection;
        if(remote!=null&&remote.binder!=null)terminate("forgetting",message);else main.post(()->{
            if(closed)return;Connection next=new Connection();connection=next;
            try{next.bound=context.bindService(new Intent(context,AdbBootstrapService.class),next,Context.BIND_AUTO_CREATE);if(!next.bound)forgetUnavailable(next);}
            catch(RuntimeException unavailable){forgetUnavailable(next);}
        });publish();return snapshot();
    }
    private void forgetUnavailable(Connection remote){
        if(connection!=remote)return;connection=null;if(remote.bound)try{context.unbindService(remote);}catch(RuntimeException ignored){}
        forgetPending=false;phase="forget_blocked";message="未能确认旧启动进程退出，身份尚未删除。";publish();
    }
    private void terminate(String status,String explanation){
        synchronized(lock){terminating=true;ready=false;phase=status;message=explanation;}
        Connection remote=connection;if(remote!=null&&remote.binder!=null)kill(remote);publish();
    }
    private void kill(Connection remote){execute(control,()->{
        if(connection!=remote||remote.binder==null)return;
        try{ChildState child=info(remote.binder);Parcel data=Parcel.obtain(),reply=Parcel.obtain();
            try{data.writeInterfaceToken(AdbBootstrapWire.SERVICE);data.writeLong(child.generation);
                if(!remote.binder.transact(AdbBootstrapWire.CANCEL,data,reply,0))throw new RemoteException();reply.readException();if(reply.readInt()!=1)throw new IllegalStateException();
            }finally{data.recycle();reply.recycle();}
        }catch(Exception unknown){if(!remote.binder.isBinderAlive())childDied(remote);else{phase="stop_unknown";message="独立启动进程退出尚未确认，暂不接受新配对或身份轮换。";publish();}}
    });}
    private void childDied(Connection remote){
        boolean forget;
        synchronized(lock){
            if(connection!=remote||!remote.ended.compareAndSet(false,true))return;
            connection=null;Attempt previous=attempt;attempt=null;terminating=false;ready=false;if(previous!=null)previous.clearTransient();forget=forgetPending;
            if(!forget){phase=!prefs.getString("pendingOperation","").isEmpty()?unknownPhase(prefs.getString("pendingAction","connect")):"bootstrap_process_exited";message="独立启动进程已退出。已启动助手是否仍存在需重新认证核对，未自动重试。";}
        }
        main.post(()->{if(remote.bound)try{context.unbindService(remote);}catch(RuntimeException ignored){}});
        if(forget){execute(worker,()->{try{identity.forget();clearPending();phase="identity_forgotten";message="本机ADB身份已删除；系统端已授权设备仍需你在开发者选项中遗忘。";}catch(Exception unavailable){phase="forget_failed";message="本机身份删除未确认，请检查后重试。";}finally{forgetPending=false;publish();}});}
        else publish();
        if(closed)control.shutdown();
    }
    private void finish(Attempt current,String status,String explanation,boolean authenticated){
        synchronized(lock){if(!current(current))return;attempt=null;current.clearTransient();phase=status;message=explanation;ready=authenticated;}publish();
    }
    private boolean current(Attempt expected){return !closed&&!terminating&&attempt==expected&&generation==expected.generation;}
    private boolean markPending(Attempt current){synchronized(lock){if(!current(current))return false;if(!prefs.edit().putString("pendingOperation",current.id).putString("pendingAction",current.action).commit())throw new IllegalStateException("启动回执边界保存失败");return true;}}
    private boolean clearPendingFor(Attempt current){synchronized(lock){if(!current(current))return false;clearPending();return true;}}
    private void clearPending(){if(!prefs.edit().remove("pendingOperation").remove("pendingAction").commit())throw new IllegalStateException("启动结果保存失败");}
    private static boolean idleChild(ChildState child){return child.operation==null&&child.phase.matches("idle|paired|ready|needs_pairing");}
    private static ChildState info(IBinder binder)throws Exception {
        Parcel data=Parcel.obtain(),reply=Parcel.obtain();try{data.writeInterfaceToken(AdbBootstrapWire.SERVICE);if(!binder.transact(AdbBootstrapWire.INFO,data,reply,0))throw new RemoteException();reply.readException();return new ChildState(reply.readLong(),reply.readString(),reply.readString());}finally{data.recycle();reply.recycle();}
    }
    private static boolean acknowledge(IBinder binder,String operation)throws Exception {
        Parcel data=Parcel.obtain(),reply=Parcel.obtain();try{data.writeInterfaceToken(AdbBootstrapWire.SERVICE);data.writeString(operation);if(!binder.transact(AdbBootstrapWire.READY,data,reply,0))return false;reply.readException();return reply.readInt()==1;}finally{data.recycle();reply.recycle();}
    }
    private byte[] helper()throws Exception {
        try(InputStream input=context.getAssets().open("pocketengine-display-helper.jar");ByteArrayOutputStream output=new ByteArrayOutputStream()){
            byte[] buffer=new byte[8192];int count;while((count=input.read(buffer))!=-1){if(output.size()+count>524288)throw new IllegalStateException("打包后台助手超出上传边界");output.write(buffer,0,count);}return output.toByteArray();
        }
    }
    private void requireOpen(){if(closed)throw new IllegalStateException("无线ADB启动器已关闭");}
    private static String unknownPhase(String action){return action.equals("pair")?"pair_outcome_unknown":"bootstrap_outcome_unknown";}
    private void execute(ExecutorService executor,Runnable work){try{executor.execute(work);}catch(java.util.concurrent.RejectedExecutionException stopped){}}
    private void publish(){if(!closed)changed.accept(snapshot());}
    @Override public void close(){synchronized(lock){if(closed)return;closed=true;}terminate("closing","正在结束本应用独立ADB启动进程。");discovery.close();worker.shutdownNow();if(connection==null)control.shutdown();}
    private final class Connection implements ServiceConnection {
        volatile IBinder binder;boolean bound;final AtomicBoolean ended=new AtomicBoolean();
        @Override public void onServiceConnected(ComponentName name,IBinder service){
            if(connection!=this)return;binder=service;
            try{service.linkToDeath(()->childDied(this),0);}catch(RemoteException dead){childDied(this);return;}
            if(closed||terminating||forgetPending){terminating=true;kill(this);return;}Attempt current=attempt;if(current!=null)submit(this,current);
        }
        private void disconnected(){if(binder!=null&&!binder.isBinderAlive())childDied(this);else{terminating=true;ready=false;phase="stop_unknown";message="服务绑定已失效，尚未取得旧进程死亡证据；身份未轮换。";if(binder!=null)kill(this);publish();}}
        @Override public void onServiceDisconnected(ComponentName name){disconnected();}
        @Override public void onBindingDied(ComponentName name){disconnected();}
        @Override public void onNullBinding(ComponentName name){forgetUnavailable(this);Attempt current=attempt;if(current!=null)finish(current,"service_unavailable","独立启动器没有提供私有Binder接口。",false);}
    }
    private static final class Attempt {
        final String id,action;final long generation;final JSONObject request,config;final AuthenticatedStatus status;
        Attempt(String id,long generation,String action,JSONObject request,JSONObject config,AuthenticatedStatus status){this.id=id;this.generation=generation;this.action=action;this.request=request;this.config=config;this.status=status;}
        void clearTransient(){request.remove("pairingCode");if(config!=null){config.remove("token");request.remove("bootstrap");}}
    }
    private static final class ChildState {final long generation;final String phase,operation;ChildState(long generation,String phase,String operation){this.generation=generation;this.phase=phase;this.operation=operation;}}
}
