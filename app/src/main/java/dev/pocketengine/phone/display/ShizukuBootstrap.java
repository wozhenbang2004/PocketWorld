package dev.pocketengine.phone.display;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.json.JSONObject;
import rikka.shizuku.Shizuku;

/** Official API integration only; actual readiness is established by our socket handshake. */
final class ShizukuBootstrap implements AutoCloseable {
    private static final int PERMISSION_REQUEST=18431;
    private final Context context;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService bootstrap=Executors.newSingleThreadExecutor();
    private final AtomicLong generation=new AtomicLong();
    private final Consumer<JSONObject> changed;
    private final Shizuku.UserServiceArgs args;
    private volatile ServiceConnection connection;
    private volatile IBinder remote;
    private volatile boolean starting,permissionPending,closed;
    private volatile String status="not_connected",message="在手机上安装并启动Shizuku，然后授权口袋世界。";
    private final Shizuku.OnBinderReceivedListener received=this::publish;
    private final Shizuku.OnBinderDeadListener dead=()->{
        generation.incrementAndGet();remote=null;starting=false;permissionPending=false;
        status="backend_unavailable";message="Shizuku连接已断开，后台操作已停止。";publish();
    };
    private final Shizuku.OnRequestPermissionResultListener permission=(code,result)->{
        if(code!=PERMISSION_REQUEST)return;
        permissionPending=false;status=result==PackageManager.PERMISSION_GRANTED?"authorized":"permission_denied";
        message=result==PackageManager.PERMISSION_GRANTED?"授权已获得，可以启动后台助手。":"Shizuku授权未获准，未启动后台助手。";publish();
    };

    ShizukuBootstrap(Context context,Consumer<JSONObject> changed){
        this.context=context.getApplicationContext();this.changed=changed;
        args=new Shizuku.UserServiceArgs(new ComponentName(context,ShizukuDisplayService.class))
                .tag("pocket-world-display-v1").version(2).daemon(false).processNameSuffix("display").debuggable(false);
        Shizuku.addBinderReceivedListenerSticky(received);
        Shizuku.addBinderDeadListener(dead);Shizuku.addRequestPermissionResultListener(permission);
    }

    JSONObject snapshot(){
        JSONObject value=new JSONObject();boolean binder=false,granted=false,installed=false;int version=-1;
        try{context.getPackageManager().getApplicationInfo("moe.shizuku.privileged.api",0);installed=true;}catch(Exception absent){}
        try{binder=Shizuku.pingBinder();if(binder){version=Shizuku.getVersion();granted=!Shizuku.isPreV11()&&Shizuku.checkSelfPermission()==PackageManager.PERMISSION_GRANTED;}}catch(RuntimeException unavailable){}
        try{return value.put("apiAvailable",true).put("managerInstalled",installed).put("binderAvailable",binder)
                .put("serverVersion",version).put("permissionGranted",granted).put("permissionRequestPending",permissionPending)
                .put("userServiceBound",remote!=null&&remote.isBinderAlive()).put("starting",starting).put("status",status).put("message",message);}
        catch(Exception impossible){return value;}
    }

    JSONObject requestPermission(){
        JSONObject current=snapshot();
        if(!supported(current))return snapshot();
        if(current.optBoolean("permissionGranted")){status="authorized";message="Shizuku已授权，可以启动后台助手。";return snapshot();}
        if(Shizuku.shouldShowRequestPermissionRationale()){
            status="permission_denied";message="请在Shizuku的已授权应用中允许口袋世界，再返回连接。";return snapshot();
        }
        permissionPending=true;status="permission_requested";message="等待Shizuku授权，请在手机上处理授权对话框。";
        main.post(()->{try{if(!closed)Shizuku.requestPermission(PERMISSION_REQUEST);}catch(RuntimeException unavailable){permissionPending=false;status="permission_unavailable";message="无法请求Shizuku授权，请检查其运行状态。";publish();}});
        return snapshot();
    }

    synchronized JSONObject start(JSONObject configuration,Runnable authenticatedReady){
        JSONObject current=snapshot();
        if(closed){status="closed";message="后台授权组件已关闭。";return snapshot();}
        if(!supported(current))return snapshot();
        if(!current.optBoolean("permissionGranted")){status="permission_required";message="请先授权Shizuku，再启动后台助手。";return snapshot();}
        if(starting)return snapshot();
        final long epoch=generation.incrementAndGet();starting=true;status="starting";message="正在以已授权的Shizuku启动后台助手。";
        IBinder existing=remote;
        if(existing!=null&&existing.isBinderAlive()){
            initializeRemote(existing,configuration,authenticatedReady,epoch);watchdog(epoch);return snapshot();
        }
        ServiceConnection next=new ServiceConnection(){
            @Override public void onServiceConnected(ComponentName name,IBinder service){
                if(closed||generation.get()!=epoch)return;
                remote=service;
                initializeRemote(service,configuration,authenticatedReady,epoch);
            }
            @Override public void onServiceDisconnected(ComponentName name){
                if(generation.get()!=epoch)return;remote=null;starting=false;status="service_disconnected";message="后台UserService已断开，未回落到主屏。";publish();
            }
        };
        connection=next;
        main.post(()->{try{if(!closed&&generation.get()==epoch)Shizuku.bindUserService(args,next);}
            catch(RuntimeException failed){if(generation.get()==epoch){starting=false;status="bind_failed";message="Shizuku无法绑定后台UserService。";publish();}}});
        watchdog(epoch);return snapshot();
    }
    private void watchdog(long epoch){
        main.postDelayed(()->{if(generation.get()==epoch&&starting){generation.incrementAndGet();starting=false;status="bind_timeout";message="后台UserService启动或握手超时，请检查Shizuku后重试。";publish();}},15_000);
    }
    private void initializeRemote(IBinder service,JSONObject configuration,Runnable authenticatedReady,long epoch){
        if(closed)return;
        try{bootstrap.execute(()->{
            Parcel input=Parcel.obtain(),output=Parcel.obtain();
            try{
                if(closed||generation.get()!=epoch||!authorized())return;
                input.writeInterfaceToken(ShizukuDisplayService.DESCRIPTOR);input.writeString(configuration.toString());
                if(!service.transact(ShizukuDisplayService.START,input,output,0))throw new IllegalStateException();
                output.readException();JSONObject result=new JSONObject(output.readString());
                if(!result.optBoolean("ok"))throw new IllegalStateException();
                if(closed||generation.get()!=epoch||!authorized())return;
                authenticatedReady.run();
                if(generation.get()==epoch){status="connected";message="Shizuku后台助手已通过真实连接核验。";}
            }catch(Exception unavailable){if(generation.get()==epoch){status="start_failed";message="后台助手启动或握手失败，请检查Shizuku和系统支持。";}}
            finally{input.recycle();output.recycle();if(generation.get()==epoch){starting=false;publish();}}
        });}catch(java.util.concurrent.RejectedExecutionException closedWorker){starting=false;}
    }

    boolean authorized(){
        try{return !closed&&Shizuku.pingBinder()&&!Shizuku.isPreV11()&&Shizuku.getVersion()>=13
                &&Shizuku.checkSelfPermission()==PackageManager.PERMISSION_GRANTED;}
        catch(RuntimeException unavailable){return false;}
    }
    synchronized JSONObject stop(){
        generation.incrementAndGet();starting=false;remote=null;ServiceConnection old=connection;connection=null;
        status="stopping";message="正在停止Shizuku后台助手。";
        main.post(()->{
            try{if(old!=null&&Shizuku.pingBinder())Shizuku.unbindUserService(args,old,true);status="stopped";message="Shizuku后台助手已请求停止。";}
            catch(RuntimeException unknown){status="stop_unknown";message="后台助手停止结果未确认，请检查Shizuku运行状态。";}
            publish();
        });return snapshot();
    }
    private boolean supported(JSONObject current){
        if(!current.optBoolean("binderAvailable")){status=current.optBoolean("managerInstalled")?"backend_not_running":"manager_missing";message="请先在手机上安装并启动Shizuku；Android11以上可使用无线调试启动。";return false;}
        if(current.optInt("serverVersion",-1)<13){status="unsupported_version";message="需要Shizuku API13或更新的服务端。";return false;}
        return true;
    }
    private void publish(){if(!closed)changed.accept(snapshot());}
    @Override public void close(){
        if(closed)return;stop();closed=true;
        Shizuku.removeBinderReceivedListener(received);Shizuku.removeBinderDeadListener(dead);Shizuku.removeRequestPermissionResultListener(permission);
        bootstrap.shutdownNow();
    }
}
