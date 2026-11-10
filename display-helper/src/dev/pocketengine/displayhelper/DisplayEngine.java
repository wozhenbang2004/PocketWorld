package dev.pocketengine.displayhelper;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.ComponentName;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.hardware.input.InputManager;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.KeyCharacterMap;
import android.view.InputDevice;
import android.view.MotionEvent;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** The shell process owns every display it exposes. Only an explicit pinned owner transfer may move its own task to display zero; input never targets zero. */
final class DisplayEngine {
    private final Context context;
    private final Handler main;
    private final DisplayManager displayManager;
    private VirtualDisplay display;
    private volatile boolean closed;
    private ImageReader reader;
    private volatile LeasePolicy lease;
    private OwnerTaskTransfer ownerTransfer;
    private final DisplayTaskTopology taskTopology=new DisplayTaskTopology();
    private final DisplayAppControl appControl;
    private final DisplayGesturePaths gesturePaths;
    private int width,height,density;
    private volatile DisplayGeometry geometry;
    private volatile boolean geometryReady;
    private final AtomicLong geometryGeneration=new AtomicLong();
    private boolean geometryListenerRegistered;
    private final DisplayManager.DisplayListener geometryListener=new DisplayManager.DisplayListener(){
        public void onDisplayAdded(int id) { }
        public void onDisplayRemoved(int id) {
            LeasePolicy original=lease;if(original!=null&&id==original.display())release();
        }
        public void onDisplayChanged(int id) {
            LeasePolicy original=lease;if(original==null||id!=original.display())return;
            // Even a round trip with equal final dimensions invalidates old references.
            // Replacing one non-null surface with another does not change display state.
            try{synchronizeGeometry(original,true);}catch(Exception unavailable){blockGeometry();}
        }
    };
    private int requiredDisplayFlags;
    private String packageName="";
    private String imeState="unknown",imeReason="display_not_created";
    private final DisplayKeepAlive keepAlive=new DisplayKeepAlive();
    private final AtomicLong frameGeneration=new AtomicLong();
    private final FrameVisualEvidence visualFrames=new FrameVisualEvidence();
    private final Object frameLock=new Object();
    private volatile byte[] latestPixels;
    private volatile long frameAt;
    private volatile long frameTimestampNanos;
    private volatile long frameNotBeforeNanos;
    private long lastFrameConsumed;
    private volatile boolean framePending;
    private ImageReader scheduledFrameReader;
    private final Runnable trailingFrame=()->{
        ImageReader source=scheduledFrameReader;scheduledFrameReader=null;
        if(source!=null)consumeFrame(source);
    };
    private Object inputManager;
    private Method inject,injectTarget,setDisplay;
    // Owned only on main, including cleanup. A stale worker cannot cancel a replacement sequence.
    private TouchSequence activeTouch;
    private volatile KeySequence activeKey;
    private static final class KeySequence {
        final LeasePolicy lease;final VirtualDisplay display;final int displayId,uid,code,source;final long downTime;
        boolean downAttempted,downAccepted,upAttempted,upAccepted;
        KeySequence(LeasePolicy lease,VirtualDisplay display,int uid,int code,int source,long downTime){
            this.lease=lease;this.display=display;this.displayId=lease.display();this.uid=uid;this.code=code;this.source=source;this.downTime=downTime;
        }
    }
    private static final class TouchSequence {
        final LeasePolicy lease;final VirtualDisplay display;final long downTime;final DisplayGeometry geometry;
        volatile boolean downAttempted,downAccepted,upAccepted,cancelAccepted,cancelAttempted;
        float x,y;
        TouchSequence(LeasePolicy lease,VirtualDisplay display,long downTime,DisplayGeometry geometry){this.lease=lease;this.display=display;this.downTime=downTime;this.geometry=geometry;}
    }
    private static final ThreadLocal<Long> DISPATCH_DEADLINE=new ThreadLocal<>();
    DisplayEngine(Context context,Handler main){this.context=context;this.main=main;this.gesturePaths=new DisplayGesturePaths(main);requirePrivilegedEnvironment();this.displayManager=context.getSystemService(DisplayManager.class);this.appControl=new DisplayAppControl(context);}

    private void requirePrivilegedEnvironment(){
        int uid=android.os.Process.myUid();
        if(uid!=0&&uid!=2000)throw new SecurityException("DisplayEngine requires a dedicated shell/root helper process");
        if(Build.VERSION.SDK_INT<34)throw new SecurityException("Isolated display focus requires Android14 or newer");
        if(context==null||!"com.android.shell".equals(context.getPackageName())||context.getApplicationInfo().uid!=2000)
            throw new SecurityException("DisplayEngine requires explicit shell package attribution");
    }

    JSONObject handle(JSONObject request) throws Exception {
        if(closed)throw new Failure("helper_session_closed","原后台显示已关闭。",false);
        String cmd=request.optString("command");
        if("hello".equals(cmd)||"status".equals(cmd))return status();
        if("audio_recovery.get".equals(cmd)||"audio_recovery.set".equals(cmd))return onMain(()->{
            requirePrivilegedEnvironment();android.app.KeyguardManager keyguard=context.getSystemService(android.app.KeyguardManager.class);
            if(keyguard==null||keyguard.isDeviceLocked()||keyguard.isKeyguardLocked())throw new Failure("screen_locked","请先由机主解锁手机。",false);
            // Expiry is not release. The original native cleanup must have removed every
            // retained lease/display before a cold owner undo can touch appops.
            if(lease!=null||display!=null||reader!=null)throw new Failure("audio_recovery_busy","原后台屏尚未证实释放，音频恢复记录已保留。",false);
            return DisplayAudioCommands.handle(cmd.endsWith(".get")?"audio.get":"audio.set",request);
        });
        if("primary_ownership".equals(cmd)){
            requirePrivilegedEnvironment();JSONObject facts=DisplayAppOwnership.enrich(DisplayOwnershipSnapshot.parse(taskDump(),0),taskTopology.read(),0);
            return ok().put("ownership",facts);
        }
        if(DisplayAppControl.supports(cmd)&&request.optInt("displayId",-1)==0){
            if(lease!=null||display!=null)throw new Failure("app_control_display_mismatch","原副屏操作不能转向主屏。",false);
            return appControl(null,request,cmd);
        }
        if("create".equals(cmd))return create(request);
        LeasePolicy current=lease;
        if(current==null||!current.accepts(request.optString("sessionId"),request.optInt("displayId",-1),SystemClock.uptimeMillis()))
            throw new Failure("invalid_lease","后台显示会话已结束，请重新建立。",false);
        if(activeKey!=null&&cmd.matches("launch|launch_copy|intent|gesture|tap|swipe|key|owner_handoff_.*|app_restart_stop|app_restart_launch"))
            throw new Failure("key_release_unconfirmed","原按键释放尚未确认，请核对或释放原副屏，未派发后续输入。",false);
        JSONObject result;
        if(ownerTransfer!=null&&ownerTransfer.blocksInput()&&cmd.matches("launch|launch_copy|intent|gesture|tap|swipe|key|app_restart_stop|app_restart_launch"))throw new Failure("owner_handoff_active","应用由主人在主屏接管，未派发副屏输入。",false);
        if(cmd.startsWith("owner_handoff_"))result=ownerHandoff(current,request,cmd);
        else if("release".equals(cmd)){current.release();onMain(()->{if(lease==current)release();return null;});result=ok().put("released",true);}
        else if("heartbeat".equals(cmd)){long lifetime=integer(request,"lifetimeMs",1,30_000,30_000);if(!current.renew(request.optString("sessionId"),request.optInt("displayId",-1),SystemClock.uptimeMillis(),lifetime))throw new Failure("invalid_lease","原后台显示不能续期。",false);result=ok().put("renewed",true).put("leaseExpiresAtUptimeMs",current.expiresAt());}
        else if("frame".equals(cmd))result=frame(current,request);
        else if("preview_frame".equals(cmd))result=previewFrame(current,request);
        else if("launch".equals(cmd))result=launch(current,request);
        else if(DisplayAppControl.supports(cmd))result=appControl(current,request,cmd);
        else if("intent".equals(cmd)||"launch_copy".equals(cmd))result=onMain(()->{
            valid(current);if(activeTouch!=null||activeKey!=null||gesturePaths.active())throw new Failure("gesture_in_flight","原输入尚未结束，未打开应用。",false);
            DisplayTypedIntent.Gate gate=new DisplayTypedIntent.Gate(){public void check()throws Exception{valid(current);dispatchDeadline();}
                public void entering(){markFrameBoundary();}
                public void requireAppFree(String pkg,int user)throws Exception{if(!DisplayAppOwnership.free(taskTopology.read(),pkg,user,current.display()))throw new Failure("app_user_busy_or_unknown","同一App资料的完整占用不能排除，未抢占其他屏幕。",false);}};
            long previousBoundary=frameNotBeforeNanos;
            JSONObject answer="intent".equals(cmd)?DisplayTypedIntent.dispatch(context,current.display(),request,gate):DisplayAppCopies.launch(context,current.display(),request,gate);
            if(Boolean.FALSE.equals(answer.opt("accepted")))frameNotBeforeNanos=previousBoundary;
            if(Boolean.TRUE.equals(answer.opt("accepted")))packageName=request.has("target")?request.getJSONObject("target").getString("packageName"):request.getString("packageName");return answer;
        });
        else if("gesture".equals(cmd)){
            long generation=request.optLong("frameGeneration",-1);
            DisplayGeometry original=inputGeometry(current,request);
            result=gesturePaths.run(current,request,original.width,original.height,()->{valid(current);requireGeometry(current,original);if(activeTouch!=null||activeKey!=null)throw new Failure("gesture_in_flight","原输入动作未结束。",false);},event->{inject(current,event,event.getActionMasked()==MotionEvent.ACTION_DOWN?generation:-1,null,original);return true;});
        }
        else if("apps".equals(cmd))result=apps();
        else if("audio.get".equals(cmd)||"audio.set".equals(cmd)){
            valid(current);if(request.optInt("userId",-1)!=context.getApplicationInfo().uid/100000)throw new Failure("audio_profile_mismatch","音频资料不是原本机启动用户。",false);
            result=DisplayAudioCommands.handle(cmd,request);
        }
        else if("ownership".equals(cmd)){valid(current);JSONObject facts=DisplayAppOwnership.enrich(DisplayOwnershipSnapshot.parse(taskDump(),current.display()),taskTopology.read(),current.display());
            valid(current);result=ok().put("ownership",facts);}
        else if("tap".equals(cmd)||"swipe".equals(cmd))result=gesture(current,request,"swipe".equals(cmd));
        else if("key".equals(cmd))result=key(current,request);
        else throw new Failure("unsupported_command","后台助手不支持该命令。",false);
        return result.put("sessionId",current.session()).put("displayId",current.display());
    }
    private JSONObject appControl(LeasePolicy original,JSONObject request,String command)throws Exception {
        return onMain(()->appControl.handle(command,request,new DisplayAppControl.Gate(){
            public void check()throws Exception {
                requirePrivilegedEnvironment();dispatchDeadline();
                android.app.KeyguardManager lock=context.getSystemService(android.app.KeyguardManager.class);
                if(closed||lock==null||lock.isDeviceLocked()||lock.isKeyguardLocked())throw new Failure("screen_locked","原手机已锁定，未继续冷启动。",false);
                if(original!=null){valid(original);if(original.display()!=request.getInt("displayId")||!original.session().equals(request.getString("sessionId")))throw new Failure("app_control_display_mismatch","原应用控制显示会话已变化。",false);}
                else if(lease!=null||display!=null||request.getInt("displayId")!=0)throw new Failure("app_control_display_mismatch","当前请求不属于原主屏。",false);
                if(command.equals("app_restart_stop")||command.equals("app_restart_launch")){
                    if(activeTouch!=null||activeKey!=null||gesturePaths.active())throw new Failure("gesture_in_flight","原输入尚未结束，未冷启动应用。",false);
                    if(ownerTransfer!=null&&ownerTransfer.blocksInput())throw new Failure("owner_handoff_active","原应用尚未交还，未冷启动。",false);
                }
            }
            public JSONObject topology()throws Exception {check();JSONObject facts=taskTopology.read();check();return facts;}
            public void entering(){if(original!=null)markFrameBoundary();}
        }));
    }
    private JSONObject ownerHandoff(LeasePolicy current,JSONObject request,String command)throws Exception {
        return onMain(()->{
            valid(current);
            if(ownerTransfer==null)ownerTransfer=new OwnerTaskTransfer(new OwnerTaskTransfer.Backend(){
                private Object service()throws Exception{return Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null);}
                public java.util.List<OwnerTaskTransfer.Task> tasks()throws Exception {
                    Class<?> api=Class.forName("android.app.IActivityTaskManager");
                    java.util.List<?> roots=(java.util.List<?>)api.getMethod("getAllRootTaskInfos").invoke(service());
                    java.util.List<OwnerTaskTransfer.Task> result=new java.util.ArrayList<>();
                    for(Object root:roots){Class<?> type=root.getClass();int at=type.getField("displayId").getInt(root);if(at!=0&&at!=current.display())continue;
                        Object base=type.getField("baseActivity").get(root);Object token=type.getField("token").get(root);
                        if(base==null||token==null)continue;
                        int id=type.getField("taskId").getInt(root),user=type.getField("userId").getInt(root);
                        result.add(new OwnerTaskTransfer.Task(id,at,user,((ComponentName)base).flattenToString(),token,(int[])type.getField("childTaskIds").get(root)));
                    }return result;
                }
                public void move(int root,int target)throws Exception {
                    valid(current);dispatchDeadline();
                    Method move=Class.forName("android.app.IActivityTaskManager").getMethod("moveRootTaskToDisplay",int.class,int.class);
                    Object manager=service();markFrameBoundary();move.invoke(manager,root,target);
                }
            },current.display());
            try{
                if(command.equals("owner_handoff_prepare"))return ownerTransfer.prepare(SystemClock.uptimeMillis());
                if(command.equals("owner_handoff_status"))return ownerTransfer.status();
                if(command.equals("owner_handoff_main")||command.equals("owner_handoff_return"))return ownerTransfer.transfer(request.getString("handoffId"),command.equals("owner_handoff_return"),SystemClock.uptimeMillis());
                throw new Failure("unsupported_command","不支持的接管操作。",false);
            }catch(Failure known){throw known;}catch(Exception unavailable){throw new Failure("owner_handoff_unavailable","系统未确认原任务身份或不支持接管，请保留副屏并核对。",false);}
        });
    }
    private JSONObject status()throws Exception {
        return onMain(()->{
            LeasePolicy current=lease;
            if(current!=null&&(current.expired(SystemClock.uptimeMillis())||!displayIsValid(current)))release();
            if(lease!=null){probeIme(lease.display(),false);try{synchronizeGeometry(lease,false);}catch(Exception unavailable){blockGeometry();}}
            JSONObject result=ok().put("protocol",1).put("uid",android.os.Process.myUid()).put("sdk",Build.VERSION.SDK_INT)
                    .put("parallelPhone",dev.pocketengine.phone.display.PhoneParallelCapabilities.current()).put("frameMetadataOnly",true).put("backgroundOnly",true).put("minimumSdk",34).put("sessionActive",lease!=null)
                    .put("displayId",lease==null?JSONObject.NULL:lease.display()).put("sessionId",lease==null?JSONObject.NULL:lease.session())
                    .put("width",width).put("height",height).put("density",density).put("requestedPackageName",packageName)
                    .put("focusIsolationRequested",lease!=null).put("displayFlagsConfirmed",lease!=null&&displayIsValid(lease))
                    .put("imeIsolation",imeState).put("imeReason",imeReason).put("imePolicy","local_display")
                    .put("displayKeepAlive",keepAlive.state()).put("displayKeepAliveReason",keepAlive.reason())
                    .put("frameTimestampSupported",true).put("inputInjectionAvailable",inject!=null&&setDisplay!=null).put("captureAvailable",lease==null?JSONObject.NULL:frameReady(0)).put("deviceVerified",false);
            return geometry==null?result:geometry.writeTo(result,geometryReady);
        });
    }
    private JSONObject create(JSONObject request)throws Exception {
        dev.pocketengine.phone.display.PhoneParallelCapabilities.require(request);
        if(Build.VERSION.SDK_INT<34)throw new Failure("android_version","后台隔离焦点模式需要 Android14 或以上。",false);
        int w=integer(request,"width",320,1600,720),h=integer(request,"height",320,2400,1280),d=integer(request,"density",120,640,240);
        if((long)w*h>2_400_000)throw new Failure("invalid_dimensions","后台屏尺寸过大。",false);
        return onMain(()->{
            if(closed)throw new Failure("helper_session_closed","原后台显示已关闭。",false);
            if(lease!=null&&!lease.expired(SystemClock.uptimeMillis()))throw new Failure("display_busy","后台屏已有会话，请先释放。",false);
            release();
            try {
                prepareInput();
                int flags=DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC|DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
                        |flag("VIRTUAL_DISPLAY_FLAG_TRUSTED")|flag("VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH")
                        |flag("VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP")
                        |flag("VIRTUAL_DISPLAY_FLAG_ROTATES_WITH_CONTENT")
                        |flag("VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL")|flag("VIRTUAL_DISPLAY_FLAG_OWN_FOCUS")|flag("VIRTUAL_DISPLAY_FLAG_STEAL_TOP_FOCUS_DISABLED");
                reader=ImageReader.newInstance(w,h,PixelFormat.RGBA_8888,3);
                width=w;height=h;density=d;
                reader.setOnImageAvailableListener(r->consumeFrame(r),main);
                dispatchDeadline();
                display=displayManager.createVirtualDisplay("PocketEngine background "+UUID.randomUUID(),w,h,d,reader.getSurface(),flags);
                if(display==null||display.getDisplay()==null||display.getDisplay().getDisplayId()<=0)throw new Failure("display_creation_failed","系统未创建独立后台屏。",false);
                requiredDisplayFlags=requiredIsolationFlags();
                if(!display.getDisplay().isValid()||(display.getDisplay().getFlags()&requiredDisplayFlags)!=requiredDisplayFlags||!destroysContentOnRemoval(display.getDisplay()))throw new Failure("focus_isolation_unavailable","系统未提供所需的后台隔离，不启动后台任务。",false);
                configureIme(display.getDisplay().getDisplayId());
                lease=new LeasePolicy(UUID.randomUUID().toString(),display.getDisplay().getDisplayId(),SystemClock.uptimeMillis(),600_000);
                keepAlive.configure(display.getDisplay());
                synchronizeGeometry(lease,false);
                displayManager.registerDisplayListener(geometryListener,main);geometryListenerRegistered=true;
                return geometry.writeTo(ok().put("sessionId",lease.session()).put("displayId",lease.display())
                        .put("frameMetadataOnly",true).put("frameTimestampSupported",true).put("backgroundOnly",true).put("focusIsolationRequested",true).put("displayFlagsConfirmed",true).put("imeIsolation",imeState).put("imeReason",imeReason).put("imePolicy","local_display")
                        .put("displayKeepAlive",keepAlive.state()).put("displayKeepAliveReason",keepAlive.reason())
                        .put("inputInjectionAvailable",inject!=null&&setDisplay!=null).put("captureAvailable",JSONObject.NULL).put("deviceVerified",false),geometryReady);
            }catch(Exception failure){release();throw failure;}
        });
    }
    /** Main owns both geometry and the capture surface. No replacement display/lease is created. */
    private DisplayGeometry synchronizeGeometry(LeasePolicy expected,boolean changedEvent)throws Exception {
        valid(expected);
        final DisplayGeometry actual;
        try{actual=readOwnedGeometry(Math.max(1,geometryGeneration.get()));}
        catch(Exception unavailable){blockGeometry();throw unavailable;}
        DisplayGeometry previous=geometry;
        if(!changedEvent&&geometryReady&&actual.sameConfiguration(previous))return previous;
        long generation=previous==null||changedEvent||geometryReady||!actual.sameConfiguration(previous)
                ?geometryGeneration.incrementAndGet():previous.generation;
        DisplayGeometry next=actual.withGeneration(generation);
        synchronized(frameLock){geometry=next;geometryReady=false;latestPixels=null;frameAt=0;frameTimestampNanos=0;frameNotBeforeNanos=System.nanoTime();framePending=true;frameGeneration.incrementAndGet();}
        main.removeCallbacks(trailingFrame);scheduledFrameReader=null;lastFrameConsumed=0;
        cancelForGeometry(expected);
        if(activeTouch!=null||activeKey!=null||gesturePaths.active())throw new Failure("geometry_unavailable","旋转后原输入结束尚未确认，请核对或释放原副屏；不会继续输入。",false);
        ImageReader replacement=null;
        try{
            replacement=ImageReader.newInstance(next.captureWidth,next.captureHeight,PixelFormat.RGBA_8888,3);
            replacement.setOnImageAvailableListener(this::consumeFrame,main);
            // Rotation changes logical coordinates, not the display's natural surface size.
            // Resize only when that physical extent/density really changed; never swap twice.
            if(reader==null||reader.getWidth()!=next.captureWidth||reader.getHeight()!=next.captureHeight||density!=next.density)
                display.resize(next.captureWidth,next.captureHeight,next.density);
            display.setSurface(replacement.getSurface());
            ImageReader old=reader;reader=replacement;replacement=null;
            if(old!=null){old.setOnImageAvailableListener(null,null);old.close();}
            width=next.width;height=next.height;density=next.density;
            if(!next.sameConfiguration(readOwnedGeometry(next.generation)))throw new Failure("geometry_pending","副屏尺寸仍在变化，请重新观察。",false);
            geometryReady=true;
            return next;
        }catch(Exception unavailable){blockGeometry();throw unavailable;}
        finally{if(replacement!=null)replacement.close();}
    }
    private DisplayGeometry readOwnedGeometry(long generation)throws Exception {
        android.view.Display owned=display==null?null:display.getDisplay();
        if(owned==null||lease==null||owned.getDisplayId()!=lease.display()||!owned.isValid())
            throw new Failure("display_lost","原副屏已失效。",false);
        Point first=new Point(),second=new Point();DisplayMetrics a=new DisplayMetrics(),b=new DisplayMetrics();
        int firstRotation=owned.getRotation();owned.getRealSize(first);owned.getRealMetrics(a);
        int secondRotation=owned.getRotation();owned.getRealSize(second);owned.getRealMetrics(b);
        if(firstRotation!=secondRotation||!first.equals(second)||a.densityDpi!=b.densityDpi)
            throw new Failure("geometry_pending","副屏尺寸仍在变化，请重新观察。",false);
        try{return new DisplayGeometry(generation,first.x,first.y,a.densityDpi,firstRotation);}
        catch(IllegalArgumentException unsupported){throw new Failure("geometry_unavailable","系统返回的副屏尺寸不可安全捕获，未继续输入。",false);}
    }
    private void cancelForGeometry(LeasePolicy original) {
        KeySequence key=activeKey;if(key!=null&&key.lease==original)releaseKey(key);
        TouchSequence touch=activeTouch;
        if(touch!=null&&touch.lease==original)cancelTouch(touch,false);
        gesturePaths.cancelLease(original,event->{inject(original,event,-1);return true;});
    }
    private void blockGeometry() {
        synchronized(frameLock){
            if(geometryReady&&geometry!=null)geometry=geometry.withGeneration(geometryGeneration.incrementAndGet());
            if(geometryReady||latestPixels!=null)frameGeneration.incrementAndGet();
            geometryReady=false;latestPixels=null;frameAt=0;frameTimestampNanos=0;framePending=true;
        }
        LeasePolicy original=lease;if(original!=null)cancelForGeometry(original);
    }
    private DisplayGeometry inputGeometry(LeasePolicy original,JSONObject request)throws Exception {
        return onMain(()->{
            DisplayGeometry current=synchronizeGeometry(original,false);
            long expectedFrame=request.optLong("frameGeneration",-1);
            if(!frameReady(0)||expectedFrame<0||expectedFrame!=frameGeneration.get())throw new Failure("stale_observation","后台画面已变化，请重新观察。",false);
            if(request.has("geometryGeneration")&&request.optLong("geometryGeneration",-1)!=current.generation)
                throw new Failure("stale_observation","后台屏已旋转或改变尺寸，请重新观察。",false);
            return current;
        });
    }
    private void requireGeometry(LeasePolicy original,DisplayGeometry expected)throws Exception {
        onMain(()->{
            DisplayGeometry actual=synchronizeGeometry(original,false);
            if(expected==null||!expected.sameObservation(actual))throw new Failure("stale_observation","原后台几何已失效，未继续旧坐标。",false);
            return null;
        });
    }
    /** AOSP virtual-display sinks queue an untransformed full buffer. Never rotate unknown producer metadata twice. */
    @SuppressLint("BlockedPrivateApi")
    private int captureTransform(Image image)throws Exception {
        requirePrivilegedEnvironment();
        return ((Number)Image.class.getDeclaredMethod("getTransform").invoke(image)).intValue();
    }
    private void consumeFrame(ImageReader source){
        if(source!=reader||lease==null)return;
        final DisplayGeometry capturedGeometry;
        try{capturedGeometry=synchronizeGeometry(lease,false);}catch(Exception unavailable){blockGeometry();return;}
        if(source!=reader)return;
        framePending=true;
        long now=SystemClock.uptimeMillis(),delay=150-(now-lastFrameConsumed);
        if(delay>0){
            // Leave the newest image queued. Acquiring and then discarding it would
            // lose a final stable frame when there is no later compositor event.
            if(scheduledFrameReader!=source){main.removeCallbacks(trailingFrame);scheduledFrameReader=source;main.postDelayed(trailingFrame,delay);}
            return;
        }
        main.removeCallbacks(trailingFrame);scheduledFrameReader=null;
        boolean acquired=false,published=false,olderFrame=false;
        try(Image image=source.acquireLatestImage()){
            if(image==null)return;acquired=true;lastFrameConsumed=now;
            long timestamp=image.getTimestamp();
            if(timestamp<=0)return;
            var crop=image.getCropRect();
            if(crop==null||crop.left!=0||crop.top!=0||crop.right!=capturedGeometry.captureWidth
                    ||crop.bottom!=capturedGeometry.captureHeight||captureTransform(image)!=0)return;
            Image.Plane[] planes=image.getPlanes();if(planes.length!=1)return;
            Image.Plane plane=planes[0];
            byte[] pixels=capturedGeometry.logicalPixels(plane.getBuffer(),plane.getPixelStride(),plane.getRowStride(),image.getWidth(),image.getHeight());
            if(!capturedGeometry.sameConfiguration(readOwnedGeometry(capturedGeometry.generation))){synchronizeGeometry(lease,false);return;}
            synchronized(frameLock){
                if(source!=reader||!geometryReady||!capturedGeometry.sameObservation(geometry))return;
                if(timestamp<frameTimestampNanos){olderFrame=true;return;}
                visualFrames.record(lease.session()+":"+capturedGeometry.generation,capturedGeometry.width,capturedGeometry.height,timestamp,(x,y)->{
                    int at=(y*capturedGeometry.width+x)*4;
                    return (pixels[at+3]&255)<<24|(pixels[at]&255)<<16|(pixels[at+1]&255)<<8|(pixels[at+2]&255);
                });
                boolean afterAction=frameTimestampNanos<=frameNotBeforeNanos&&timestamp>frameNotBeforeNanos;
                if(afterAction||!java.util.Arrays.equals(latestPixels,pixels)){latestPixels=pixels;frameGeneration.incrementAndGet();}
                frameTimestampNanos=timestamp;frameAt=TimeUnit.NANOSECONDS.toMillis(timestamp);published=true;
            }
        }catch(Exception ignored){acquired=true;}
        finally{
            if(source==reader)synchronized(frameLock){
                if(acquired&&!published&&!olderFrame){latestPixels=null;frameAt=0;frameTimestampNanos=0;frameGeneration.incrementAndGet();}
                framePending=!geometryReady;
            }
        }
    }
    private JSONObject frame(LeasePolicy expected,JSONObject request)throws Exception {
        if(request.has("includeImage")&&!(request.opt("includeImage") instanceof Boolean))
            throw new Failure("invalid_arguments","includeImage 必须是布尔值。",false);
        long after=frameBoundary(request);
        DisplayGeometry original=onMain(()->synchronizeGeometry(expected,false));
        if(!request.optBoolean("includeImage",true)){
            valid(expected);
            JSONObject result=frameMetadata(expected,original,after);
            requireGeometry(expected,original);return result;
        }
        long until=SystemClock.uptimeMillis()+3000;
        while(!frameReady(after)&&SystemClock.uptimeMillis()<until){valid(expected);Thread.sleep(40);}
        requireGeometry(expected,original);byte[] pixels;long frame,captured,timestamp;JSONObject visual;
        synchronized(frameLock){
            if(latestPixels==null&&geometryReady&&original.sameObservation(geometry))throw new Failure("frame_unavailable","后台屏暂未产生画面，请先打开应用再观察。",false);
            if(!geometryReady||!original.sameObservation(geometry))throw new Failure("frame_pending","后台画面正在更新，请重新观察。",false);
            if(!frameReady(after))return frameMetadata(expected,original,after).put("imageUnavailableReason","waiting_for_post_action_frame");
            pixels=latestPixels;frame=frameGeneration.get();captured=frameAt;timestamp=frameTimestampNanos;
            visual=visualFrames.snapshot();
        }
        if(pixels==null)throw new Failure("frame_unavailable","后台屏暂未产生画面，请先打开应用再观察。",false);
        
        Bitmap bitmap=Bitmap.createBitmap(original.width,original.height,Bitmap.Config.ARGB_8888);
        try{
            bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(pixels));ByteArrayOutputStream jpeg=new ByteArrayOutputStream();
            if(blackFrame(bitmap)){
                requireGeometry(expected,original);
                // Black pixels are not proof of FLAG_SECURE. Keep the real tree/metadata,
                // but do not offer a content-free raster to OCR or coordinate selection.
                synchronized(frameLock){
                    if(latestPixels!=pixels||frameTimestampNanos!=timestamp||frameGeneration.get()!=frame||!frameReady(after))
                        return frameMetadata(expected,original,after).put("framePending",true).put("imageUnavailableReason","frame_pending");
                    JSONObject result=frameMetadata(expected,original,after).put("imageUnavailableReason","frame_blank");
                    JSONObject facts=result.optJSONObject("visualEvidence");if(facts!=null)facts.put("allPixelsBlack",true);return result;
                }
            }
            if(!bitmap.compress(Bitmap.CompressFormat.JPEG,75,jpeg))throw new Failure("frame_encoding","后台屏图片编码失败。",false);
            requireGeometry(expected,original);
            synchronized(frameLock){
                if(timestamp<=Math.max(after,frameNotBeforeNanos))return frameMetadata(expected,original,after).put("framePending",true).put("imageUnavailableReason","action_during_capture");
                return original.writeTo(ok().put("displayId",expected.display()).put("sessionId",expected.session())
                    .put("imageBase64",Base64.encodeToString(jpeg.toByteArray(),Base64.NO_WRAP)).put("imageMimeType","image/jpeg")
                    .put("frameGeneration",frame).put("capturedAt",captured).put("frameTimestampNanos",timestamp)
                    .put("frameTimestampSupported",true).put("framePending",false).put("empty",false)
                    .put("imageIncluded",true).put("requestedPackageName",packageName).put("visualEvidence",visual),true);
            }
        }finally{bitmap.recycle();}
    }
    private JSONObject frameMetadata(LeasePolicy expected,DisplayGeometry original,long after)throws Exception {
        synchronized(frameLock){
            if(!geometryReady||!original.sameObservation(geometry))throw new Failure("stale_observation","后台几何在读取期间变化，请重新观察。",false);
            JSONObject result=original.writeTo(ok().put("displayId",expected.display()).put("sessionId",expected.session())
                    .put("frameGeneration",frameGeneration.get()).put("capturedAt",frameAt).put("frameTimestampNanos",frameTimestampNanos)
                    .put("frameTimestampSupported",true).put("framePending",!frameReady(after))
                    .put("empty",latestPixels==null).put("imageIncluded",false).put("frameMetadataOnly",true).put("requestedPackageName",packageName),true);
            JSONObject visual=visualFrames.snapshot();
            if(latestPixels!=null&&visual!=null&&visual.optLong("frameTimestampNanos",-1)==frameTimestampNanos)
                result.put("visualEvidence",visual.put("sourceFrameFresh",frameReady(after)).put("framePending",!frameReady(after)).put("source","display_surface_frame"));
            return result;
        }
    }
    private void markFrameBoundary(){synchronized(frameLock){frameNotBeforeNanos=System.nanoTime();}}
    private static boolean blackFrame(Bitmap bitmap){
        int[] row=new int[bitmap.getWidth()];
        for(int y=0;y<bitmap.getHeight();y++){
            bitmap.getPixels(row,0,row.length,0,y,row.length,1);
            for(int pixel:row)if((pixel&0x00ffffff)!=0)return false;
        }
        return true;
    }
    private boolean frameReady(long after){
        synchronized(frameLock){return geometryReady&&!framePending&&latestPixels!=null
                &&frameTimestampNanos>Math.max(after,frameNotBeforeNanos);}
    }
    private static long frameBoundary(JSONObject request)throws Failure {
        if(!request.has("afterTimestampNanos"))return 0;
        Object raw=request.opt("afterTimestampNanos");
        try{
            if(!(raw instanceof Number))throw new ArithmeticException();
            long value=new java.math.BigDecimal(raw.toString()).longValueExact();
            if(value<0)throw new ArithmeticException();return value;
        }catch(NumberFormatException|ArithmeticException invalid){throw new Failure("invalid_arguments","画面源时间戳必须是非负整数。",false);}
    }
    /** Latest compositor pixels only. Unlike observation, this read never waits for a new frame
     * or synchronizes/recreates geometry, cancels a gesture, changes a lease, or visits display zero. */
    private JSONObject previewFrame(LeasePolicy expected,JSONObject request)throws Exception {
        Object raw=request.opt("afterFrameGeneration");long after=-1;
        if(request.has("afterFrameGeneration"))try{
            if(!(raw instanceof Number))throw new ArithmeticException();
            after=new java.math.BigDecimal(raw.toString()).longValueExact();
            if(after< -1||after>9_007_199_254_740_991L)throw new ArithmeticException();
        }catch(NumberFormatException|ArithmeticException invalid){throw new Failure("invalid_arguments","原画面代次必须为整数。",false);}
        DisplayGeometry original=onMain(()->{
            valid(expected);DisplayGeometry value=geometry;
            return value!=null&&geometryReady&&value.sameConfiguration(readOwnedGeometry(value.generation))?value:null;
        });
        byte[] pixels;long generation,captured,timestamp;DisplayGeometry metadata;
        synchronized(frameLock){
            generation=frameGeneration.get();captured=frameAt;timestamp=frameTimestampNanos;metadata=geometry;
            pixels=original!=null&&frameReady(0)&&original.sameObservation(metadata)?latestPixels:null;
        }
        if(pixels==null)return previewMetadata(metadata,generation,captured,"pending");
        if(generation==after){
            if(!previewFrameCurrent(expected,original,timestamp))return previewPending(expected);
            return previewMetadata(original,generation,captured,"unchanged");
        }
        int[] size=previewSize(original);
        Bitmap source=Bitmap.createBitmap(original.width,original.height,Bitmap.Config.ARGB_8888),preview=null;
        try{
            source.copyPixelsFromBuffer(ByteBuffer.wrap(pixels));
            preview=Bitmap.createScaledBitmap(source,size[0],size[1],true);
            ByteArrayOutputStream jpeg=new ByteArrayOutputStream();
            if(!preview.compress(Bitmap.CompressFormat.JPEG,65,jpeg))throw new Failure("frame_encoding","后台屏预览编码失败。",false);
            if(!previewFrameCurrent(expected,original,timestamp))return previewPending(expected);
            return previewMetadata(original,generation,captured,"ready").put("imageBase64",Base64.encodeToString(jpeg.toByteArray(),Base64.NO_WRAP));
        }finally{if(preview!=null&&preview!=source)preview.recycle();source.recycle();}
    }
    private boolean previewFrameCurrent(LeasePolicy expected,DisplayGeometry original,long timestamp)throws Exception {
        return onMain(()->{
            valid(expected);
            if(!geometryReady||!original.sameObservation(geometry)||!original.sameConfiguration(readOwnedGeometry(original.generation)))return false;
            synchronized(frameLock){return timestamp>frameNotBeforeNanos;}
        });
    }
    private JSONObject previewPending(LeasePolicy expected)throws Exception {
        valid(expected);
        synchronized(frameLock){return previewMetadata(geometry,frameGeneration.get(),frameAt,"pending");}
    }
    private static int[] previewSize(DisplayGeometry value){
        if(value==null)return new int[]{0,0};
        double scale=Math.min(1d,960d/Math.max(value.width,value.height));
        return new int[]{Math.max(1,(int)Math.round(value.width*scale)),Math.max(1,(int)Math.round(value.height*scale))};
    }
    private static JSONObject previewMetadata(DisplayGeometry value,long generation,long captured,String state)throws Exception {
        int[] size=previewSize(value);
        return ok().put("state",state).put("frameGeneration",generation).put("capturedAt",captured)
                .put("sourceWidth",value==null?0:value.width).put("sourceHeight",value==null?0:value.height)
                .put("previewWidth",size[0]).put("previewHeight",size[1]).put("mime","image/jpeg");
    }
    private JSONObject launch(LeasePolicy expected,JSONObject request)throws Exception {
        String pkg=request.optString("packageName");
        if(!pkg.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")||pkg.length()>250)throw new Failure("invalid_package","应用包名无效。",false);
        int user=context.getApplicationInfo().uid/100000;
        if(integer(request,"userId",0,21474,user)!=user)throw new Failure("unsupported_host_profile","普通启动的宿主资料与原助手不匹配，未回落到其他账号。",false);
        Intent intent=context.getPackageManager().getLaunchIntentForPackage(pkg);
        if(intent==null)throw new Failure("app_unavailable","没有找到可启动的应用。",false);
        android.content.pm.ActivityInfo info=intent.resolveActivityInfo(context.getPackageManager(),0);
        if(info==null||info.launchMode>android.content.pm.ActivityInfo.LAUNCH_SINGLE_TOP)throw new Failure("app_launch_mode","此应用使用单实例任务，后台模式不会移动现有界面。",false);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
        onMain(()->{
            valid(expected);
            if(activeTouch!=null||activeKey!=null||gesturePaths.active())throw new Failure("gesture_in_flight","原输入尚未结束，未打开应用。",false);
            android.app.ActivityOptions options=android.app.ActivityOptions.makeBasic().setLaunchDisplayId(expected.display());
            if(!DisplayAppOwnership.free(taskTopology.read(),pkg,user,expected.display()))throw new Failure("app_user_busy_or_unknown","同一App资料的完整任务占用不能排除，未移动或打开应用。",false);
            dispatchDeadline();long previousBoundary=frameNotBeforeNanos;markFrameBoundary();
            try{context.startActivity(intent,options.toBundle());}
            catch(android.content.ActivityNotFoundException|SecurityException refused){frameNotBeforeNanos=previousBoundary;throw refused;}
            catch(Exception unknown){throw new Failure("action_unknown","应用启动回执未确认，请核对原副屏，不会自动重复启动。",true);}
            packageName=pkg;return null;
        });
        return receipt().put("packageName",pkg).put("requestedUserId",user).put("automaticProfileFallback",false);
    }
    /** Configure only the helper-owned display. Unsupported/unknown IME never inherits focus readiness. */
    private void configureIme(int displayId){probeIme(displayId,true);}
    private void probeIme(int displayId,boolean configure){
        imeState="unknown";imeReason="policy_not_verified";
        try{Object wm=Class.forName("android.view.WindowManagerGlobal").getMethod("getWindowManagerService").invoke(null);
            Class<?> contract=Class.forName("android.view.IWindowManager");
            Method get=contract.getMethod("getDisplayImePolicy",int.class);
            // Invoke the public Binder interface, not its private implementation class.
            // Android DISPLAY_IME_POLICY_LOCAL = 0; FALLBACK_DISPLAY is expressly not accepted.
            if(configure)contract.getMethod("setDisplayImePolicy",int.class,int.class).invoke(wm,displayId,0);
            Object actual=get.invoke(wm,displayId);
            imeState=Integer.valueOf(0).equals(actual)?"available":"unavailable";imeReason=Integer.valueOf(0).equals(actual)?"local_display_policy_read_back":"local_policy_not_applied";
        }catch(NoSuchMethodException unsupported){imeState="unavailable";imeReason="display_ime_policy_unsupported";}
        catch(Exception unknown){imeState="unknown";imeReason="display_ime_policy_probe_failed";}
    }
    private String taskDump()throws Exception {
        Process p=new ProcessBuilder("/system/bin/dumpsys","activity","activities").redirectErrorStream(true).start();
        java.util.concurrent.ExecutorService reading=java.util.concurrent.Executors.newSingleThreadExecutor();
        java.util.concurrent.Future<String> output=reading.submit(()->{
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] chunk=new byte[4096];
            try(java.io.InputStream input=p.getInputStream()){
                int n;while((n=input.read(chunk))!=-1){if(bytes.size()+n>2_000_000)throw new IllegalStateException("Task dump exceeds limit");bytes.write(chunk,0,n);}
            }
            return bytes.toString("UTF-8");
        });
        String dump;
        try{dump=output.get(3,TimeUnit.SECONDS);if(!p.waitFor(500,TimeUnit.MILLISECONDS)||p.exitValue()!=0)throw new IllegalStateException();}
        catch(Exception unknown){throw new Failure("task_probe_failed","无法确认显示任务归属。",false);}
        finally{p.destroy();output.cancel(true);reading.shutdownNow();}
        return dump;
    }
    private JSONObject apps()throws Exception {
        List<ResolveInfo> list=context.getPackageManager().queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),0);
        JSONArray result=new JSONArray();java.util.HashSet<String> packages=new java.util.HashSet<>();
        for(ResolveInfo item:list){if(item.activityInfo==null||result.length()>=200)continue;String pkg=item.activityInfo.packageName;if(packages.add(pkg))result.put(new JSONObject().put("packageName",pkg).put("label",String.valueOf(item.loadLabel(context.getPackageManager()))));}
        return ok().put("apps",result);
    }
    private JSONObject gesture(LeasePolicy expected,JSONObject request,boolean swipe)throws Exception {
        long expectedFrame=request.optLong("frameGeneration",-1);
        DisplayGeometry original=inputGeometry(expected,request);
        int x=integer(request,swipe?"x1":"x",0,1000,-1),y=integer(request,swipe?"y1":"y",0,1000,-1);
        int x2=swipe?integer(request,"x2",0,1000,-1):x,y2=swipe?integer(request,"y2",0,1000,-1):y;
        int duration=swipe?integer(request,"durationMs",100,2000,400):60;
        long start=SystemClock.uptimeMillis();
        TouchSequence touch=onMain(()->{
            valid(expected);requireGeometry(expected,original);
            if(activeTouch!=null||activeKey!=null||gesturePaths.active())throw new Failure("gesture_in_flight","原输入尚未结束，请核对或释放原副屏后再操作。",false);
            activeTouch=new TouchSequence(expected,display,start,original);return activeTouch;
        });
        try{
            int steps=swipe?Math.max(2,duration/25):1;
            for(int step=0;step<=steps;step++){
                valid(expected);long eventTime=SystemClock.uptimeMillis();
                float f=step/(float)steps;
                MotionEvent e=MotionEvent.obtain(start,eventTime,step==0?MotionEvent.ACTION_DOWN:step==steps?MotionEvent.ACTION_UP:MotionEvent.ACTION_MOVE,
                        (x+(x2-x)*f)*(original.width-1)/1000f,(y+(y2-y)*f)*(original.height-1)/1000f,0);
                e.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
                try{inject(expected,e,step==0?expectedFrame:-1,touch);}finally{e.recycle();}
                if(step<steps)Thread.sleep(Math.max(1,duration/steps));
            }
            return receipt();
        }catch(Exception failure){
            boolean uncertain=failure instanceof Failure&&((Failure)failure).dispatched;
            boolean interrupted=Thread.interrupted()||failure instanceof InterruptedException;
            final String cleanup;
            try{cleanup=cancelTouch(touch,uncertain);}finally{if(interrupted)Thread.currentThread().interrupt();}
            if(touch.downAccepted||uncertain)throw new Failure("action_unknown",touch.cancelAccepted
                    ?"系统已接收取消残余触摸；原操作结果仍未确认，请核对，勿直接重放。"
                    :touch.upAccepted?"触摸已抬起；原操作结果仍未确认，请核对，勿直接重放。"
                    :"残余触摸结束未确认，请先核对或释放原副屏；勿直接重放。",true,cleanup);
            throw failure;
        }
    }
    /** No new business action, frame requirement or global cancellation. The original display must still be owned. */
    private String cancelTouch(TouchSequence touch,boolean uncertain){
        try{return onMain(()->{
            if(activeTouch!=touch||lease!=touch.lease||display!=touch.display)return "not_attempted_original_sequence_or_display_gone";
            if(touch.upAccepted||touch.cancelAccepted){activeTouch=null;return "not_needed_already_ended";}
            if(!touch.downAccepted){boolean unknown=uncertain||touch.downAttempted;if(!unknown)activeTouch=null;return unknown?"not_attempted_down_unconfirmed":"not_needed_down_not_accepted";}
            if(touch.cancelAttempted)return "cancel_unconfirmed";
            valid(touch.lease);
            MotionEvent cancel=MotionEvent.obtain(touch.downTime,SystemClock.uptimeMillis(),MotionEvent.ACTION_CANCEL,touch.x,touch.y,0);
            cancel.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
            try{touch.cancelAttempted=true;inject(touch.lease,cancel,-1,touch);return "cancel_accepted_not_business_proof";}finally{cancel.recycle();}
        });}catch(InterruptedException unconfirmed){Thread.currentThread().interrupt();return "cancel_unconfirmed";}
        catch(Exception unconfirmed){return "cancel_unconfirmed";}
    }
    private JSONObject key(LeasePolicy expected,JSONObject request)throws Exception {
        if(request.has("key"))return focusedKey(expected,request);
        int code=request.optInt("keyCode",-1);
        if(code!=KeyEvent.KEYCODE_BACK)throw new Failure("unsupported_key","后台模式仅支持定向返回键，桌面操作不受支持。",false);
        DisplayGeometry original=onMain(()->{
            DisplayGeometry current=synchronizeGeometry(expected,false);
            if(activeTouch!=null||activeKey!=null||gesturePaths.active())throw new Failure("gesture_in_flight","原输入尚未结束，未派发返回键。",false);
            if(request.has("geometryGeneration")&&request.optLong("geometryGeneration",-1)!=current.generation)
                throw new Failure("stale_observation","后台屏已旋转或改变尺寸，请重新观察。",false);
            return current;
        });
        valid(expected);long time=SystemClock.uptimeMillis();
        inject(expected,new KeyEvent(time,time,KeyEvent.ACTION_DOWN,code,0),-1,null,original);
        // A key UP has no coordinates and only ends this already admitted original key.
        try{inject(expected,new KeyEvent(time,SystemClock.uptimeMillis(),KeyEvent.ACTION_UP,code,0),-1);return receipt();}
        catch(Exception failure){throw new Failure("action_unknown","返回键已部分派发，请重新观察。",true);}
    }

    private JSONObject focusedKey(LeasePolicy expected,JSONObject request)throws Exception {
        final int code;
        try{code=PhoneKeyCodes.require(request.getString("key"));}
        catch(Exception invalid){throw new Failure("unsupported_key","按键不在固定键盘范围内，未派发。",false);}
        if(integer(request,"keyCode",0,1000,-1)!=code)throw new Failure("invalid_argument","按键名称与原生键码不一致。",false);
        DisplayGeometry original=inputGeometry(expected,request);long frame=request.getLong("frameGeneration");
        return onMain(()->{
            valid(expected);requireGeometry(expected,original);
            if(activeTouch!=null||activeKey!=null||gesturePaths.active())throw new Failure("gesture_in_flight","原输入尚未结束，未派发按键。",false);
            int uid=keyTargetUid(expected,request);
            long time=SystemClock.uptimeMillis();int source=InputDevice.SOURCE_KEYBOARD;
            if(code>=KeyEvent.KEYCODE_DPAD_UP&&code<=KeyEvent.KEYCODE_DPAD_CENTER)source|=InputDevice.SOURCE_DPAD;
            KeySequence sequence=new KeySequence(expected,display,uid,code,source,time);activeKey=sequence;
            Exception failure=null;String cleanup;
            try{
                KeyEvent down=new KeyEvent(time,time,KeyEvent.ACTION_DOWN,code,0,0,KeyCharacterMap.VIRTUAL_KEYBOARD,0,KeyEvent.FLAG_FROM_SYSTEM,source);
                inject(expected,down,frame,null,original,uid,sequence);
            }catch(Exception unavailable){failure=unavailable;}
            finally{cleanup=releaseKey(sequence);}
            if(failure!=null&&!sequence.downAttempted)throw failure;
            if(failure!=null||!sequence.downAccepted||!sequence.upAccepted)
                throw new Failure("action_unknown","原按键已尝试派发，结果尚未确认；释放只结束原按键，不证明业务成功。",true,null,cleanup);
            return receipt().put("key",request.getString("key")).put("keyCode",code).put("backend","display_targeted_key")
                    .put("targetPackageName",request.getString("targetPackageName")).put("targetUserId",request.getInt("targetUserId"))
                    .put("keyCleanup",cleanup).put("ackKind","native_input_finished").put("processingConfirmed",false)
                    .put("requiresPostconditionCheck",true).put("automaticReplayAllowed",false);
        });
    }

    /** At most one UP for a DOWN attempt, including lease cancellation. This cannot admit a new key or display. */
    private String releaseKey(KeySequence original){
        if(Looper.myLooper()!=main.getLooper())throw new IllegalStateException("key_cleanup_requires_main");
        if(original.upAccepted)return "up_accepted_not_business_proof";
        if(activeKey!=original||lease!=original.lease||display!=original.display)return "not_attempted_original_sequence_or_display_gone";
        if(!original.downAttempted){activeKey=null;return "not_needed_down_not_attempted";}
        if(original.upAttempted)return "up_unconfirmed";
        try{
            android.view.Display physical=original.display.getDisplay();
            if(physical==null||!physical.isValid()||physical.getDisplayId()!=original.displayId)return "not_attempted_original_display_gone";
            KeyEvent up=new KeyEvent(original.downTime,SystemClock.uptimeMillis(),KeyEvent.ACTION_UP,original.code,0,0,
                    KeyCharacterMap.VIRTUAL_KEYBOARD,0,KeyEvent.FLAG_FROM_SYSTEM,original.source);
            setDisplay.invoke(up,original.displayId);
            // Expiry/stop forbids new input, but must not strand a key already attempted on this exact lease.
            original.upAttempted=true;markFrameBoundary();
            original.upAccepted=Boolean.TRUE.equals(injectTarget.invoke(inputManager,up,2,original.uid));
            if(original.upAccepted){activeKey=null;return "up_accepted_not_business_proof";}
        }catch(Exception unconfirmed){ /* Retain the occupied sequence until its original display is released. */ }
        return "up_unconfirmed";
    }

    /** The host supplies an observed task/profile; the privileged endpoint resolves and enforces its actual UID. */
    @SuppressLint("BlockedPrivateApi")
    private int keyTargetUid(LeasePolicy expected,JSONObject request)throws Exception {
        String pkg=request.optString("targetPackageName");int user=integer(request,"targetUserId",0,21474,-1),task=integer(request,"targetTaskId",0,Integer.MAX_VALUE,-1);
        if(!pkg.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)*"))throw new Failure("key_target_unavailable","原按键目标身份无效，未派发。",false);
        JSONObject topology=taskTopology.read();
        if(!"observed".equals(topology.optString("status"))||!topology.optBoolean("complete"))throw new Failure("key_target_unavailable","不能确认原应用任务身份，未派发。",false);
        JSONArray tasks=topology.optJSONArray("tasks");boolean found=false;
        for(int i=0;tasks!=null&&i<tasks.length();i++){JSONObject row=tasks.getJSONObject(i);
            if(row.optInt("taskId",-1)==task){
                if(found||row.optInt("displayId",-1)!=expected.display()||row.optInt("androidUserId",-1)!=user||!pkg.equals(row.optString("packageName")))throw new Failure("key_target_changed","原按键应用或Android资料已变化，未派发。",false);
                found=true;
            }
        }
        if(!found||!DisplayAppOwnership.free(topology,pkg,user,expected.display()))throw new Failure("key_target_changed","原按键任务已迁移或资料占用不能确认，未派发。",false);
        try{
            if(injectTarget==null)injectTarget=InputManager.class.getMethod("injectInputEvent",InputEvent.class,int.class,int.class);
            Object resolved=android.content.pm.PackageManager.class.getMethod("getPackageUidAsUser",String.class,int.class).invoke(context.getPackageManager(),pkg,user);
            if(!(resolved instanceof Integer)||((Integer)resolved)<0||((Integer)resolved)/100000!=user)throw new IllegalStateException("profile_uid_mismatch");
            return(Integer)resolved;
        }catch(Exception unsupported){throw new Failure("key_target_unsupported","当前系统没有可核验的定向按键入口，未回退到全局输入。",false);}
    }
    // Only Main's app_process or our Shizuku UserService instantiates this class;
    // neither is the normal app process. UID/SDK/shell attribution are checked
    // again before these privileged lookups. OEM/ART denial remains a real failure;
    // no exemption or main-display fallback is assumed or installed.
    @SuppressLint("BlockedPrivateApi")
    private void prepareInput()throws Exception {
        requirePrivilegedEnvironment();
        inputManager=context.getSystemService(InputManager.class);
        if(inputManager==null)throw new Failure("input_unavailable","系统未提供副屏输入服务。",false);
        inject=InputManager.class.getMethod("injectInputEvent",InputEvent.class,int.class);
        setDisplay=InputEvent.class.getDeclaredMethod("setDisplayId",int.class);
    }
    // Focus uses actual Display flags; destroy-on-removal is a separate remove mode.
    // This narrow helper-only lookup fails create/release if the platform hides it.
    @SuppressLint("BlockedPrivateApi")
    private int requiredIsolationFlags()throws Exception {
        requirePrivilegedEnvironment();
        return android.view.Display.class.getDeclaredField("FLAG_TRUSTED").getInt(null)
                |android.view.Display.class.getDeclaredField("FLAG_OWN_FOCUS").getInt(null)
                |android.view.Display.class.getDeclaredField("FLAG_STEAL_TOP_FOCUS_DISABLED").getInt(null);
    }
    @SuppressLint("BlockedPrivateApi")
    private boolean destroysContentOnRemoval(android.view.Display observed){
        try{
            int destroy=android.view.Display.class.getDeclaredField("REMOVE_MODE_DESTROY_CONTENT").getInt(null);
            return ((Integer)android.view.Display.class.getDeclaredMethod("getRemoveMode").invoke(observed)).intValue()==destroy;
        }catch(Exception unavailable){return false;}
    }
    private void inject(LeasePolicy expected,InputEvent event,long expectedFrame)throws Exception {
        inject(expected,event,expectedFrame,null);
    }
    private void inject(LeasePolicy expected,InputEvent event,long expectedFrame,TouchSequence touch)throws Exception {
        inject(expected,event,expectedFrame,touch,touch==null?null:touch.geometry);
    }
    private void inject(LeasePolicy expected,InputEvent event,long expectedFrame,TouchSequence touch,DisplayGeometry originalGeometry)throws Exception {
        inject(expected,event,expectedFrame,touch,originalGeometry,-1,null);
    }
    private void inject(LeasePolicy expected,InputEvent event,long expectedFrame,TouchSequence touch,DisplayGeometry originalGeometry,int targetUid,KeySequence key)throws Exception {
        final MotionEvent motion=touch==null?null:(MotionEvent)event;
        if(motion!=null&&motion.getPointerCount()!=1)throw new Failure("unsupported_motion","此后台入口只接受原单指触摸，尚未派发。",false);
        final int touchAction=motion==null?-1:motion.getActionMasked(),nativeAction=motion==null?0:motion.getAction();
        final float touchX=motion==null?0:motion.getX(),touchY=motion==null?0:motion.getY();
        final long downTime=motion==null?0:motion.getDownTime(),eventTime=motion==null?0:motion.getEventTime();
        final int metaState=motion==null?0:motion.getMetaState(),source=motion==null?0:motion.getSource();
        final boolean queuedTouch=motion!=null&&Looper.myLooper()!=main.getLooper();
        final boolean cleanup=event instanceof MotionEvent&&((MotionEvent)event).getActionMasked()==MotionEvent.ACTION_CANCEL;
        onMain(()->{valid(expected);
            if(!cleanup&&originalGeometry!=null)requireGeometry(expected,originalGeometry);
            if(touch!=null&&(activeTouch!=touch||lease!=touch.lease||display!=touch.display))throw new Failure("invalid_lease","原触摸或独立显示已变化，未继续输入。",false);
            if(key!=null&&(activeKey!=key||lease!=key.lease||display!=key.display))throw new Failure("invalid_lease","原按键或独立显示已变化，未继续输入。",false);
            if(activeKey!=null&&activeKey!=key&&!cleanup)throw new Failure("key_release_unconfirmed","原按键释放尚未确认，未派发后续输入。",false);
            if(expectedFrame>=0&&(!frameReady(0)||expectedFrame!=frameGeneration.get()))throw new Failure("stale_observation","后台画面在派发前已变化，请重新观察。",false);
            // Only these private single-pointer constructors cross queues. The worker may
            // recycle its original after an interrupted wait; the admitted main callback owns its copy.
            MotionEvent owned=queuedTouch?MotionEvent.obtain(downTime,eventTime,nativeAction,touchX,touchY,metaState):null;
            try{
                if(owned!=null)owned.setSource(source);InputEvent actual=owned==null?event:owned;
                setDisplay.invoke(actual,expected.display());
                final Object result;
                dispatchDeadline();
                if(touch!=null&&touchAction==MotionEvent.ACTION_DOWN)touch.downAttempted=true;
                markFrameBoundary();
                if(key!=null)key.downAttempted=true;
                try{result=targetUid<0?inject.invoke(inputManager,actual,2):injectTarget.invoke(inputManager,actual,2,targetUid);}
                catch(java.lang.reflect.InvocationTargetException unknown){throw new Failure("action_unknown","系统输入回执异常，结果尚未确认，请核对后继续。",true);}
                // WAIT_FOR_FINISHED false also represents Android TIMED_OUT, not proof of no input.
                if(!Boolean.TRUE.equals(result))throw new Failure("action_unknown","系统输入结果未确认，请核对原操作；不会自动重发。",true);
                if(key!=null)key.downAccepted=true;
                if(touch!=null){
                    touch.x=touchX;touch.y=touchY;
                    if(touchAction==MotionEvent.ACTION_DOWN)touch.downAccepted=true;
                    else if(touchAction==MotionEvent.ACTION_UP){touch.upAccepted=true;activeTouch=null;}
                    else if(touchAction==MotionEvent.ACTION_CANCEL){touch.cancelAccepted=true;activeTouch=null;}
                }
                return null;
            }finally{if(owned!=null)owned.recycle();}
        });
    }
    private void valid(LeasePolicy expected)throws Failure {
        dispatchDeadline();
        android.app.KeyguardManager keyguard=context.getSystemService(android.app.KeyguardManager.class);
        if(keyguard==null||keyguard.isDeviceLocked()||keyguard.isKeyguardLocked())throw new Failure("screen_locked","请先由机主解锁手机。",false);
        if(lease!=expected||expected.expired(SystemClock.uptimeMillis())||expected.display()<=0)throw new Failure("invalid_lease","后台会话已结束。",false);
        if(Looper.myLooper()==main.getLooper()&&!displayIsValid(expected))throw new Failure("display_lost","独立显示或焦点隔离已失效，未继续输入。",false);
    }
    private boolean displayIsValid(LeasePolicy expected){
        return display!=null&&display.getDisplay()!=null&&display.getDisplay().isValid()
                &&display.getDisplay().getDisplayId()==expected.display()&&requiredDisplayFlags!=0
                &&(display.getDisplay().getFlags()&requiredDisplayFlags)==requiredDisplayFlags
                &&destroysContentOnRemoval(display.getDisplay());
    }
    void tick(){
        LeasePolicy current=lease;
        if(current==null)return;
        try{onMain(()->{
            if(lease!=current)return null;
            if(current.expired(SystemClock.uptimeMillis())||!displayIsValid(current)){release();return null;}
            try{
                synchronizeGeometry(current,false);
                android.app.KeyguardManager keyguard=context.getSystemService(android.app.KeyguardManager.class);
                if(keyguard!=null&&!keyguard.isDeviceLocked()&&!keyguard.isKeyguardLocked())keepAlive.tick(display.getDisplay());
            }catch(Exception unavailable){blockGeometry();}
            return null;
        });}catch(Exception ignored){}
    }
    void close(){closed=true;try{onMain(()->{release();return null;});}catch(Exception ignored){ /* Process teardown also revokes its display token. */ }}
    private void release(){
        KeySequence key=activeKey;if(key!=null)releaseKey(key);
        if(geometryListenerRegistered){displayManager.unregisterDisplayListener(geometryListener);geometryListenerRegistered=false;}
        LeasePolicy cancelling=lease;if(cancelling!=null)gesturePaths.cancelLease(cancelling,event->{inject(cancelling,event,-1);return true;});
        ownerTransfer=null;taskTopology.clear();appControl.close();
        keepAlive.clear();
        main.removeCallbacks(trailingFrame);scheduledFrameReader=null;framePending=false;lastFrameConsumed=0;
        LeasePolicy old=lease;activeTouch=null;if(old!=null)old.release();latestPixels=null;frameAt=0;frameTimestampNanos=0;frameNotBeforeNanos=0;packageName="";requiredDisplayFlags=0;geometry=null;geometryReady=false;
        if(display!=null){display.release();display=null;}if(reader!=null){reader.close();reader=null;}
        // An unconfirmed CANCEL stays occupied until physical display teardown succeeds.
        // Keep the stopped original lease on a teardown failure so cleanup can be reconciled.
        if(old!=null)gesturePaths.releaseLease(old);activeKey=null;lease=null;
    }
    private int flag(String name)throws Exception {return DisplayManager.class.getDeclaredField(name).getInt(null);}
    private <T>T onMain(java.util.concurrent.Callable<T> call)throws Exception {
        if(Looper.myLooper()==main.getLooper())return call.call();
        long deadline=SystemClock.uptimeMillis()+5000;
        // 0=queued, 1=entered, 2=cancelled before entry. One CAS decides whether call may run.
        java.util.concurrent.atomic.AtomicInteger entry=new java.util.concurrent.atomic.AtomicInteger();
        FutureTask<T> future=new FutureTask<>(()->{
            DISPATCH_DEADLINE.set(deadline);
            try{dispatchDeadline();if(!entry.compareAndSet(0,1))throw new Failure("dispatch_cancelled","本次后台界面请求已在开始前撤回，请核对原会话状态。",false);return call.call();}finally{DISPATCH_DEADLINE.remove();}
        });main.post(future);
        try{return future.get(5,TimeUnit.SECONDS);}
        catch(java.util.concurrent.ExecutionException wrapped){
            Throwable cause=wrapped.getCause();if(cause instanceof Exception)throw(Exception)cause;throw wrapped;
        }catch(java.util.concurrent.TimeoutException timeout){
            boolean cancelledBeforeEntry=entry.compareAndSet(0,2);
            throw new Failure(cancelledBeforeEntry?"dispatch_timeout":"action_unknown",cancelledBeforeEntry?"本次后台界面请求未及时开始，请核对原会话状态。":"后台请求超时，结果尚未确认，请核对后继续。",!cancelledBeforeEntry);
        }catch(InterruptedException stopped){
            boolean cancelledBeforeEntry=entry.compareAndSet(0,2);Thread.currentThread().interrupt();
            throw new Failure(cancelledBeforeEntry?"dispatch_cancelled":"action_unknown",cancelledBeforeEntry?"本次后台界面请求已在开始前撤回，请核对原会话状态。":"后台等待已中断，已入场操作的结果尚未确认，请核对后继续。",!cancelledBeforeEntry);
        }finally{future.cancel(false);}
    }
    private static void dispatchDeadline()throws Failure {
        Long deadline=DISPATCH_DEADLINE.get();if(deadline!=null&&SystemClock.uptimeMillis()>=deadline)
            throw new Failure("dispatch_timeout","后台请求已过期，未继续派发。",false);
    }
    private static int integer(JSONObject j,String key,int min,int max,int fallback)throws Failure {
        Object raw=j.has(key)?j.opt(key):fallback;if(!(raw instanceof Number))throw new Failure("invalid_argument","参数无效："+key,false);
        double value=((Number)raw).doubleValue();if(value!=Math.rint(value)||value<min||value>max)throw new Failure("invalid_argument","参数超出范围："+key,false);return(int)value;
    }
    static JSONObject ok()throws Exception{return new JSONObject().put("ok",true);}
    private JSONObject receipt()throws Exception{return ok().put("accepted",true).put("verified",false).put("displayId",lease==null?-1:lease.display()).put("message","后台输入请求已接收，请重新观察结果。");}
    static final class Failure extends Exception {final String code;final boolean dispatched;final String pointerCleanup,keyCleanup;
        Failure(String code,String message,boolean dispatched){this(code,message,dispatched,null);}
        Failure(String code,String message,boolean dispatched,String pointerCleanup){this(code,message,dispatched,pointerCleanup,null);}
        Failure(String code,String message,boolean dispatched,String pointerCleanup,String keyCleanup){super(message);this.code=code;this.dispatched=dispatched;this.pointerCleanup=pointerCleanup;this.keyCleanup=keyCleanup;}}
}
