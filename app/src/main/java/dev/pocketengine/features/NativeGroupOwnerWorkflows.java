package dev.pocketengine.features;

import android.app.Activity;
import android.app.Application;
import android.os.Handler;
import android.os.Looper;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleEventObserver;
import androidx.lifecycle.LifecycleOwner;
import dev.pocketengine.features.groups.GroupOwnerAuthority;
import dev.pocketengine.features.groups.GroupsModule;
import java.lang.ref.WeakReference;
import java.util.Objects;
import java.util.UUID;
import org.json.JSONObject;

/**
 * The native Activity owner route, not an EngineModule or a feature/model command.
 * One current native page owns the window. Its worker call is the only place where
 * GroupsModule's owner gate is open; knowing a group/admin identity grants nothing.
 */
public final class NativeGroupOwnerWorkflows implements GroupOwnerAuthority, AutoCloseable {
    @FunctionalInterface public interface OwnerCall { JSONObject run() throws Exception; }

    private static final class Window {
        final String id=UUID.randomUUID().toString();
        final WeakReference<Activity> activity;
        final Lifecycle lifecycle;
        LifecycleEventObserver observer;
        volatile boolean revoked;
        Window(Activity activity,Lifecycle lifecycle){this.activity=new WeakReference<>(activity);this.lifecycle=lifecycle;}
    }

    private final Application application;
    private final Class<? extends Activity> activityClass;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ThreadLocal<Window> invocation=new ThreadLocal<>();
    private volatile Window active;
    private volatile GroupsModule groups;
    private volatile boolean closed;

    public NativeGroupOwnerWorkflows(Application application,Class<? extends Activity> activityClass){
        this.application=Objects.requireNonNull(application);this.activityClass=Objects.requireNonNull(activityClass);
    }

    /** Application construction only. The FeatureHost, not this gate, owns module shutdown. */
    public synchronized void bind(GroupsModule module){
        if(closed||groups!=null)throw new IllegalStateException("原生群任务宿主已经绑定或关闭");
        groups=Objects.requireNonNull(module);
    }

    /** Called directly by the native page on main, never from supplied JSON. */
    public String open(Activity activity){
        if(Looper.myLooper()!=Looper.getMainLooper())throw new SecurityException("只能从当前原生页面建立主人窗口");
        if(activity==null||activity.getClass()!=activityClass||activity.getApplication()!=application
                ||activity.isFinishing()||activity.isDestroyed()||!(activity instanceof LifecycleOwner))
            throw new SecurityException("不是本应用当前的原生主人页面");
        Lifecycle lifecycle=((LifecycleOwner)activity).getLifecycle();
        if(lifecycle.getCurrentState()!=Lifecycle.State.RESUMED)throw new SecurityException("请回到前台原生页面后重新核对");
        Window window=new Window(activity,lifecycle);
        window.observer=(source,event)->{
            if(event==Lifecycle.Event.ON_PAUSE||event==Lifecycle.Event.ON_STOP||event==Lifecycle.Event.ON_DESTROY)close(window.id);
        };
        Window previous;
        synchronized(this){
            if(closed||groups==null)throw new IllegalStateException("原生群任务宿主尚未就绪或已经关闭");
            previous=active;if(previous!=null)previous.revoked=true;active=window;
        }
        detach(previous);
        try{
            lifecycle.addObserver(window.observer);
            if(lifecycle.getCurrentState()!=Lifecycle.State.RESUMED||window.revoked)throw new SecurityException("原生页面已离开前台");
        }catch(RuntimeException failure){close(window.id);throw failure;}
        return window.id;
    }

    /** Immediate revocation is safe from page disposal, pause, detach, or a worker. */
    public void close(String windowId){
        Window removed;
        synchronized(this){
            removed=active;if(removed==null||!removed.id.equals(windowId))return;
            removed.revoked=true;active=null;
        }
        detach(removed);
    }

    public JSONObject handle(String windowId,String command,JSONObject payload)throws Exception{
        JSONObject input=new JSONObject(Objects.requireNonNull(payload).toString());
        return call(windowId,()->module().ownerCommand(command,input));
    }

    public JSONObject snapshot(String windowId)throws Exception{return call(windowId,()->module().ownerSnapshot());}

    /**
     * Native-only original-task approval/resume/stop/verification uses this same gate.
     * The Controller must capture its exact task and page window before queueing the call.
     * No window lock is held across storage, network, model, or executor work.
     */
    public JSONObject call(String windowId,OwnerCall action)throws Exception{
        if(Looper.myLooper()==Looper.getMainLooper())throw new IllegalStateException("原生群任务操作必须在现有工作队列执行");
        if(invocation.get()!=null)throw new SecurityException("不能嵌套或替换当前主人窗口");
        Window window=active;
        if(window==null||!window.id.equals(windowId))throw new SecurityException("原生主人页面已关闭，请重新打开核对");
        invocation.set(window);
        try{check();JSONObject result=Objects.requireNonNull(action).run();check();return result;}
        finally{invocation.remove();}
    }

    /** GroupsModule and native task dispatch call this before every owner-only operation. */
    @Override public void check(){
        Window window=invocation.get();
        Activity activity=window==null?null:window.activity.get();
        if(closed||window==null||window!=active||window.revoked||activity==null
                ||activity.getClass()!=activityClass||activity.getApplication()!=application
                ||activity.isFinishing()||activity.isDestroyed())
            throw new SecurityException("原生主人窗口已失效，本次没有获得主人操作权限");
    }

    private GroupsModule module(){GroupsModule module=groups;if(module==null)throw new IllegalStateException("原生群任务宿主尚未绑定");return module;}

    private void detach(Window window){
        if(window==null)return;
        Runnable remove=()->window.lifecycle.removeObserver(window.observer);
        if(Looper.myLooper()==Looper.getMainLooper())remove.run();else main.post(remove);
    }

    @Override public void close(){
        Window removed;
        synchronized(this){closed=true;removed=active;if(removed!=null)removed.revoked=true;active=null;}
        detach(removed);
    }
}
