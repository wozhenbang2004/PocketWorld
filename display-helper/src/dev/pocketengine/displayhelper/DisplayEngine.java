package dev.pocketengine.displayhelper;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.ComponentName;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Base64;
import android.view.InputEvent;
import android.view.KeyEvent;
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

/** The shell process owns every display it exposes. No command can address display zero. */
final class DisplayEngine {
    private final Context context;
    private final Handler main;
    private final int authenticatedOwnerUid;
    private final DisplayGesturePaths paths;
    private VirtualDisplay display;
    private ImageReader reader;
    private volatile LeasePolicy lease;
    private int width,height,density;
    private int requiredDisplayFlags;
    private String packageName="";
    private String imeState="unknown",imeReason="display_not_created";
    private final AtomicLong frameGeneration=new AtomicLong();
    private final Object frameLock=new Object();
    private volatile byte[] latestPixels;
    private volatile long frameAt;
    private long lastFrameConsumed;
    private volatile boolean framePending;
    private ImageReader scheduledFrameReader;
    private final Runnable trailingFrame=()->{
        ImageReader source=scheduledFrameReader;scheduledFrameReader=null;
        if(source!=null)consumeFrame(source);
    };
    private Object inputManager;
    private Method inject,setDisplay;
    // Owned only on main, including cleanup. A stale worker cannot cancel a replacement sequence.
    private TouchSequence activeTouch;
    private static final class TouchSequence {
        final LeasePolicy lease;final VirtualDisplay display;final long downTime;
        volatile boolean downAccepted,upAccepted,cancelAccepted;
        float x,y;
        TouchSequence(LeasePolicy lease,VirtualDisplay display,long downTime){this.lease=lease;this.display=display;this.downTime=downTime;}
    }
    private static final ThreadLocal<Long> DISPATCH_DEADLINE=new ThreadLocal<>();
    DisplayEngine(Context context,Handler main){this(context,main,-1);}
    DisplayEngine(Context context,Handler main,int authenticatedOwnerUid){this.context=context;this.main=main;this.authenticatedOwnerUid=authenticatedOwnerUid;paths=new DisplayGesturePaths(main);requirePrivilegedEnvironment();}

    private void requirePrivilegedEnvironment(){
        int uid=android.os.Process.myUid();
        if(uid!=0&&uid!=2000)throw new SecurityException("DisplayEngine requires a dedicated shell/root helper process");
        if(Build.VERSION.SDK_INT<34)throw new SecurityException("Isolated display focus requires Android14 or newer");
        if(context==null||!"com.android.shell".equals(context.getPackageName())||context.getApplicationInfo().uid!=2000)
            throw new SecurityException("DisplayEngine requires explicit shell package attribution");
    }

    JSONObject handle(JSONObject request) throws Exception {
        String cmd=request.optString("command");
        if("hello".equals(cmd)||"status".equals(cmd))return status();
        if("primary_ownership".equals(cmd)){
            requirePrivilegedEnvironment();android.app.KeyguardManager keyguard=context.getSystemService(android.app.KeyguardManager.class);
            if(keyguard==null||keyguard.isDeviceLocked()||keyguard.isKeyguardLocked())throw new Failure("screen_locked","请先由机主解锁手机。",false);
            return ok().put("ownership",DisplayAppOwnership.primary(taskDump()));
        }
        if("create".equals(cmd))return create(request);
        LeasePolicy current=lease;
        if(current==null||!current.accepts(request.optString("sessionId"),request.optInt("displayId",-1),SystemClock.uptimeMillis()))
            throw new Failure("invalid_lease","后台显示会话已结束，请重新建立。",false);
        JSONObject result;
        if("release".equals(cmd)){onMain(()->{paths.cancelLease(current,event->injectPath(current,event,-1));return null;});current.release();onMain(()->{if(lease==current)release();return null;});result=ok().put("released",true);}
        else if("heartbeat".equals(cmd)){long lifetime=integer(request,"lifetimeMs",1,30_000,30_000);if(!current.renew(request.optString("sessionId"),request.optInt("displayId",-1),SystemClock.uptimeMillis(),lifetime))throw new Failure("invalid_lease","原后台显示不能续期。",false);result=ok().put("renewed",true).put("leaseExpiresAtUptimeMs",current.expiresAt());}
        else if("frame".equals(cmd))result=frame(current,request);
        else if("launch".equals(cmd))result=launch(current,request);
        else if("apps".equals(cmd))result=apps();
        else if("ownership".equals(cmd)){valid(current);String dump=taskDump();result=ok().put("ownership",DisplayAppOwnership.attach(DisplayOwnershipSnapshot.parse(dump,current.display()),dump));}
        else if("intent".equals(cmd))result=onMain(()->DisplayTypedIntent.dispatch(context,current.display(),request,typedGate(current)));
        else if("launch_copy".equals(cmd))result=onMain(()->DisplayAppCopies.launch(context,current.display(),request,typedGate(current)));
        else if("practice".equals(cmd))result=onMain(()->DisplayAppCopies.practice(context,current.display(),authenticatedOwnerUid,request,typedGate(current)));
        else if("audio.get".equals(cmd)||"audio.set".equals(cmd)){valid(current);result=DisplayAudioCommands.handle(cmd,request);}
        else if("gesture".equals(cmd))result=gesturePaths(current,request);
        else if("tap".equals(cmd)||"swipe".equals(cmd))result=gesture(current,request,"swipe".equals(cmd));
        else if("key".equals(cmd))result=key(current,request);
        else throw new Failure("unsupported_command","后台助手不支持该命令。",false);
        return result.put("sessionId",current.session()).put("displayId",current.display());
    }
    private DisplayTypedIntent.Gate typedGate(LeasePolicy expected){return new DisplayTypedIntent.Gate(){public void check()throws Exception{valid(expected);}public void requireAppFree(String pkg,int user)throws Exception{valid(expected);DisplayAppOwnership.requireAppFree(taskDump(),pkg,user,expected.display());valid(expected);}};}
    private JSONObject gesturePaths(LeasePolicy expected,JSONObject request)throws Exception {
        long frame=request.optLong("frameGeneration",-1);valid(expected);
        if(framePending||frame<0||frame!=frameGeneration.get())throw new Failure("stale_observation","后台画面已变化，请重新观察。",false);
        return paths.run(expected,request,width,height,()->{valid(expected);if(activeTouch!=null)throw new Failure("gesture_in_flight","原触摸尚未结束。",false);},event->injectPath(expected,event,event.getActionMasked()==MotionEvent.ACTION_DOWN?frame:-1));
    }
    private boolean injectPath(LeasePolicy expected,MotionEvent event,long expectedFrame)throws Exception {
        valid(expected);if(expectedFrame>=0&&(framePending||expectedFrame!=frameGeneration.get()))throw new Failure("stale_observation","后台画面在派发前已变化。",false);
        setDisplay.invoke(event,expected.display());dispatchDeadline();final Object result;
        try{result=inject.invoke(inputManager,event,2);}catch(java.lang.reflect.InvocationTargetException unknown){throw new Failure("action_unknown","系统输入回执异常，请核对原操作。",true);}
        if(!Boolean.TRUE.equals(result))throw new Failure("action_unknown","系统输入结果未确认，不会自动重发。",true);return true;
    }
    private JSONObject status()throws Exception {
        return onMain(()->{
            LeasePolicy current=lease;
            if(current!=null&&(current.expired(SystemClock.uptimeMillis())||!displayIsValid(current)))release();
            if(lease!=null)probeIme(lease.display(),false);
            return ok().put("protocol",1).put("uid",android.os.Process.myUid()).put("sdk",Build.VERSION.SDK_INT)
                    .put("frameMetadataOnly",true).put("backgroundOnly",true).put("minimumSdk",34).put("sessionActive",lease!=null)
                    .put("displayId",lease==null?JSONObject.NULL:lease.display()).put("sessionId",lease==null?JSONObject.NULL:lease.session())
                    .put("width",width).put("height",height).put("density",density).put("requestedPackageName",packageName)
                    .put("focusIsolationRequested",lease!=null).put("displayFlagsConfirmed",lease!=null&&displayIsValid(lease))
                    .put("imeIsolation",imeState).put("imeReason",imeReason).put("inputInjectionAvailable",inject!=null&&setDisplay!=null).put("captureAvailable",lease==null?JSONObject.NULL:latestPixels!=null).put("deviceVerified",false);
        });
    }
    private JSONObject create(JSONObject request)throws Exception {
        if(Build.VERSION.SDK_INT<34)throw new Failure("android_version","后台隔离焦点模式需要 Android14 或以上。",false);
        int w=integer(request,"width",320,1600,720),h=integer(request,"height",320,2400,1280),d=integer(request,"density",120,640,240);
        if((long)w*h>2_400_000)throw new Failure("invalid_dimensions","后台屏尺寸过大。",false);
        return onMain(()->{
            if(lease!=null&&!lease.expired(SystemClock.uptimeMillis()))throw new Failure("display_busy","后台屏已有会话，请先释放。",false);
            release();
            try {
                prepareInput();
                int flags=DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC|DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
                        |flag("VIRTUAL_DISPLAY_FLAG_TRUSTED")|flag("VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH")
                        |flag("VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL")|flag("VIRTUAL_DISPLAY_FLAG_OWN_FOCUS")|flag("VIRTUAL_DISPLAY_FLAG_STEAL_TOP_FOCUS_DISABLED");
                reader=ImageReader.newInstance(w,h,PixelFormat.RGBA_8888,3);
                width=w;height=h;density=d;
                reader.setOnImageAvailableListener(r->consumeFrame(r),main);
                DisplayManager manager=context.getSystemService(DisplayManager.class);
                dispatchDeadline();
                display=manager.createVirtualDisplay("PocketEngine background",w,h,d,reader.getSurface(),flags);
                if(display==null||display.getDisplay()==null||display.getDisplay().getDisplayId()<=0)throw new Failure("display_creation_failed","系统未创建独立后台屏。",false);
                requiredDisplayFlags=requiredIsolationFlags();
                if(!display.getDisplay().isValid()||(display.getDisplay().getFlags()&requiredDisplayFlags)!=requiredDisplayFlags||!destroysContentOnRemoval(display.getDisplay()))throw new Failure("focus_isolation_unavailable","系统未提供所需的后台隔离，不启动后台任务。",false);
                configureIme(display.getDisplay().getDisplayId());
                lease=new LeasePolicy(UUID.randomUUID().toString(),display.getDisplay().getDisplayId(),SystemClock.uptimeMillis(),600_000);
                return ok().put("sessionId",lease.session()).put("displayId",lease.display()).put("width",w).put("height",h)
                        .put("density",d).put("frameMetadataOnly",true).put("backgroundOnly",true).put("focusIsolationRequested",true).put("displayFlagsConfirmed",true).put("imeIsolation",imeState).put("imeReason",imeReason).put("inputInjectionAvailable",inject!=null&&setDisplay!=null).put("captureAvailable",JSONObject.NULL).put("deviceVerified",false);
            }catch(Exception failure){release();throw failure;}
        });
    }
    private void consumeFrame(ImageReader source){
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
        boolean acquired=false,published=false;
        try(Image image=source.acquireLatestImage()){
            if(image==null)return;acquired=true;lastFrameConsumed=now;
            Image.Plane[] planes=image.getPlanes();if(planes.length!=1)return;
            Image.Plane plane=planes[0];int pixelStride=plane.getPixelStride(),rowStride=plane.getRowStride();
            if(pixelStride<4||image.getWidth()!=width||image.getHeight()!=height)return;
            ByteBuffer data=plane.getBuffer();byte[] pixels=new byte[width*height*4];
            for(int y=0;y<height;y++)for(int x=0;x<width;x++){
                int offset=y*rowStride+x*pixelStride,dest=(y*width+x)*4;
                if(offset+3>=data.limit())return;
                pixels[dest]=data.get(offset);pixels[dest+1]=data.get(offset+1);pixels[dest+2]=data.get(offset+2);pixels[dest+3]=data.get(offset+3);
            }
            synchronized(frameLock){if(!java.util.Arrays.equals(latestPixels,pixels)){latestPixels=pixels;frameGeneration.incrementAndGet();}frameAt=SystemClock.uptimeMillis();published=true;}
        }catch(RuntimeException ignored){acquired=true;}
        finally{
            if(acquired&&!published)synchronized(frameLock){latestPixels=null;frameAt=0;frameGeneration.incrementAndGet();}
            framePending=false;
        }
    }
    private JSONObject frame(LeasePolicy expected,JSONObject request)throws Exception {
        if(request.has("includeImage")&&!(request.opt("includeImage") instanceof Boolean))
            throw new Failure("invalid_arguments","includeImage 必须是布尔值。",false);
        if(!request.optBoolean("includeImage",true)){
            valid(expected);
            JSONObject result;
            synchronized(frameLock){
                result=ok().put("displayId",expected.display()).put("sessionId",expected.session()).put("width",width).put("height",height)
                        .put("frameGeneration",frameGeneration.get()).put("capturedAt",frameAt).put("framePending",framePending)
                        .put("empty",latestPixels==null).put("imageIncluded",false).put("frameMetadataOnly",true).put("requestedPackageName",packageName);
            }
            valid(expected);return result;
        }
        long until=SystemClock.uptimeMillis()+3000;
        while((latestPixels==null||framePending)&&SystemClock.uptimeMillis()<until){valid(expected);Thread.sleep(40);}
        valid(expected);byte[] pixels;long frame,captured;int frameWidth=width,frameHeight=height;
        synchronized(frameLock){if(framePending)throw new Failure("frame_pending","后台画面正在更新，请重新观察。",false);pixels=latestPixels;frame=frameGeneration.get();captured=frameAt;}
        if(pixels==null)throw new Failure("frame_unavailable","后台屏暂未产生画面，请先打开应用再观察。",false);
        
        Bitmap bitmap=Bitmap.createBitmap(frameWidth,frameHeight,Bitmap.Config.ARGB_8888);
        try{
            bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(pixels));ByteArrayOutputStream jpeg=new ByteArrayOutputStream();
            if(!bitmap.compress(Bitmap.CompressFormat.JPEG,75,jpeg))throw new Failure("frame_encoding","后台屏图片编码失败。",false);
            valid(expected);return ok().put("displayId",expected.display()).put("sessionId",expected.session()).put("width",frameWidth).put("height",frameHeight)
                    .put("imageBase64",Base64.encodeToString(jpeg.toByteArray(),Base64.NO_WRAP)).put("imageMimeType","image/jpeg")
                    .put("frameGeneration",frame).put("capturedAt",captured).put("framePending",false).put("empty",false)
                    .put("imageIncluded",true).put("requestedPackageName",packageName);
        }finally{bitmap.recycle();}
    }
    private JSONObject launch(LeasePolicy expected,JSONObject request)throws Exception {
        String pkg=request.optString("packageName");
        if(!pkg.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")||pkg.length()>250)throw new Failure("invalid_package","应用包名无效。",false);
        // Only the caller-provided known main-screen package is a hint; shell independently checks foreground tasks.
        if(mainScreenUses(pkg))throw new Failure("main_screen_conflict","该应用正在主屏使用，后台不会抢占。",false);
        Intent intent=context.getPackageManager().getLaunchIntentForPackage(pkg);
        if(intent==null)throw new Failure("app_unavailable","没有找到可启动的应用。",false);
        android.content.pm.ActivityInfo info=intent.resolveActivityInfo(context.getPackageManager(),0);
        if(info==null||info.launchMode>android.content.pm.ActivityInfo.LAUNCH_SINGLE_TOP)throw new Failure("app_launch_mode","此应用使用单实例任务，后台模式不会移动现有界面。",false);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
        onMain(()->{
            valid(expected);
            android.app.ActivityOptions options=android.app.ActivityOptions.makeBasic().setLaunchDisplayId(expected.display());
            context.startActivity(intent,options.toBundle());packageName=pkg;return null;
        });
        return receipt().put("packageName",pkg);
    }
    private boolean mainScreenUses(String pkg)throws Exception {
        String dump=taskDump();
        try{return PrimaryAppGuard.uses(dump,pkg);}
        catch(IllegalArgumentException unsupported){throw new Failure("task_probe_failed","当前系统任务格式不受支持，未启动应用。",false);}
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
        if(framePending||expectedFrame<0||expectedFrame!=frameGeneration.get())throw new Failure("stale_observation","后台画面已变化，请重新观察。",false);
        int x=integer(request,swipe?"x1":"x",0,1000,-1),y=integer(request,swipe?"y1":"y",0,1000,-1);
        int x2=swipe?integer(request,"x2",0,1000,-1):x,y2=swipe?integer(request,"y2",0,1000,-1):y;
        int duration=swipe?integer(request,"durationMs",100,2000,400):60;
        long start=SystemClock.uptimeMillis();
        TouchSequence touch=onMain(()->{
            valid(expected);
            if(activeTouch!=null||paths.active())throw new Failure("gesture_in_flight","原触摸尚未结束，请核对或释放原副屏后再操作。",false);
            activeTouch=new TouchSequence(expected,display,start);return activeTouch;
        });
        try{
            int steps=swipe?Math.max(2,duration/25):1;
            for(int step=0;step<=steps;step++){
                valid(expected);long eventTime=SystemClock.uptimeMillis();
                float f=step/(float)steps;
                MotionEvent e=MotionEvent.obtain(start,eventTime,step==0?MotionEvent.ACTION_DOWN:step==steps?MotionEvent.ACTION_UP:MotionEvent.ACTION_MOVE,
                        (x+(x2-x)*f)*(width-1)/1000f,(y+(y2-y)*f)*(height-1)/1000f,0);
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
            if(!touch.downAccepted){if(!uncertain)activeTouch=null;return uncertain?"not_attempted_down_unconfirmed":"not_needed_down_not_accepted";}
            valid(touch.lease);
            MotionEvent cancel=MotionEvent.obtain(touch.downTime,SystemClock.uptimeMillis(),MotionEvent.ACTION_CANCEL,touch.x,touch.y,0);
            cancel.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
            try{inject(touch.lease,cancel,-1,touch);return "cancel_accepted_not_business_proof";}finally{cancel.recycle();}
        });}catch(InterruptedException unconfirmed){Thread.currentThread().interrupt();return "cancel_unconfirmed";}
        catch(Exception unconfirmed){return "cancel_unconfirmed";}
    }
    private JSONObject key(LeasePolicy expected,JSONObject request)throws Exception {
        if(framePending||request.optLong("frameGeneration",-1)<0||request.optLong("frameGeneration",-1)!=frameGeneration.get())throw new Failure("stale_observation","后台画面已变化，请重新观察。",false);
        int code=request.optInt("keyCode",-1);
        if(code!=KeyEvent.KEYCODE_BACK)throw new Failure("unsupported_key","后台模式仅支持定向返回键，桌面操作不受支持。",false);
        valid(expected);long time=SystemClock.uptimeMillis();
        inject(expected,new KeyEvent(time,time,KeyEvent.ACTION_DOWN,code,0),request.getLong("frameGeneration"));
        try{inject(expected,new KeyEvent(time,SystemClock.uptimeMillis(),KeyEvent.ACTION_UP,code,0),-1);return receipt();}
        catch(Exception failure){throw new Failure("action_unknown","返回键已部分派发，请重新观察。",true);}
    }
    // Only Main's app_process or our Shizuku UserService instantiates this class;
    // neither is the normal app process. UID/SDK/shell attribution are checked
    // again before these privileged lookups. OEM/ART denial remains a real failure;
    // no exemption or main-display fallback is assumed or installed.
    @SuppressLint("BlockedPrivateApi")
    private void prepareInput()throws Exception {
        requirePrivilegedEnvironment();
        Class<?> type=Class.forName("android.hardware.input.InputManagerGlobal");
        inputManager=type.getDeclaredMethod("getInstance").invoke(null);
        inject=type.getDeclaredMethod("injectInputEvent",InputEvent.class,int.class);
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
        final MotionEvent motion=touch==null?null:(MotionEvent)event;
        if(motion!=null&&motion.getPointerCount()!=1)throw new Failure("unsupported_motion","此后台入口只接受原单指触摸，尚未派发。",false);
        final int touchAction=motion==null?-1:motion.getActionMasked(),nativeAction=motion==null?0:motion.getAction();
        final float touchX=motion==null?0:motion.getX(),touchY=motion==null?0:motion.getY();
        final long downTime=motion==null?0:motion.getDownTime(),eventTime=motion==null?0:motion.getEventTime();
        final int metaState=motion==null?0:motion.getMetaState(),source=motion==null?0:motion.getSource();
        final boolean queuedTouch=motion!=null&&Looper.myLooper()!=main.getLooper();
        onMain(()->{valid(expected);
            if(touch!=null&&(activeTouch!=touch||lease!=touch.lease||display!=touch.display))throw new Failure("invalid_lease","原触摸或独立显示已变化，未继续输入。",false);
            if(expectedFrame>=0&&(framePending||expectedFrame!=frameGeneration.get()))throw new Failure("stale_observation","后台画面在派发前已变化，请重新观察。",false);
            // Only these private single-pointer constructors cross queues. The worker may
            // recycle its original after an interrupted wait; the admitted main callback owns its copy.
            MotionEvent owned=queuedTouch?MotionEvent.obtain(downTime,eventTime,nativeAction,touchX,touchY,metaState):null;
            try{
                if(owned!=null)owned.setSource(source);InputEvent actual=owned==null?event:owned;
                setDisplay.invoke(actual,expected.display());
                final Object result;
                dispatchDeadline();
                try{result=inject.invoke(inputManager,actual,2);}
                catch(java.lang.reflect.InvocationTargetException unknown){throw new Failure("action_unknown","系统输入回执异常，结果尚未确认，请核对后继续。",true);}
                // WAIT_FOR_FINISHED false also represents Android TIMED_OUT, not proof of no input.
                if(!Boolean.TRUE.equals(result))throw new Failure("action_unknown","系统输入结果未确认，请核对原操作；不会自动重发。",true);
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
    boolean releaseConfirmed()throws Exception{return onMain(()->lease==null&&display==null&&reader==null&&activeTouch==null&&!paths.active());}
    void tick(){LeasePolicy current=lease;if(current!=null)try{onMain(()->{if(lease==current&&(current.expired(SystemClock.uptimeMillis())||!displayIsValid(current)))release();return null;});}catch(Exception ignored){}}
    void close(){try{onMain(()->{release();return null;});}catch(Exception ignored){ /* Process teardown also revokes its display token. */ }}
    private void release(){
        LeasePolicy closing=lease;if(closing!=null)paths.cancelLease(closing,event->injectPath(closing,event,-1));
        main.removeCallbacks(trailingFrame);scheduledFrameReader=null;framePending=false;lastFrameConsumed=0;
        LeasePolicy old=lease;lease=null;activeTouch=null;if(old!=null)old.release();latestPixels=null;frameAt=0;packageName="";requiredDisplayFlags=0;
        if(display!=null){display.release();display=null;}if(reader!=null){reader.close();reader=null;}
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
    static final class Failure extends Exception {final String code;final boolean dispatched;final String pointerCleanup;
        Failure(String code,String message,boolean dispatched){this(code,message,dispatched,null);}
        Failure(String code,String message,boolean dispatched,String pointerCleanup){super(message);this.code=code;this.dispatched=dispatched;this.pointerCleanup=pointerCleanup;}}
}
