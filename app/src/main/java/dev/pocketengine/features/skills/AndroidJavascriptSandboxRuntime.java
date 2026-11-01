package dev.pocketengine.features.skills;

import android.content.Context;
import android.content.ComponentName;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.webkit.WebView;
import androidx.javascriptengine.IsolateStartupParameters;
import androidx.javascriptengine.JavaScriptIsolate;
import androidx.javascriptengine.JavaScriptSandbox;
import com.google.common.util.concurrent.ListenableFuture;
import java.io.File;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Phone-only V8 evaluation through AndroidX's out-of-process JavaScriptSandbox.
 * This is an interpreter adapter, not a task authority, shell, WebView or Java/Android bridge.
 * The native host supplies approval/journaling, the worker and the original-operation deadline.
 */
public final class AndroidJavascriptSandboxRuntime implements AutoCloseable {
    public static final String RUNTIME_ID="androidx-js";
    public static final String LIBRARY_VERSION="1.1.1";
    public static final String POLICY_ID="pure-js-no-host-bridge-v1";
    public static final long MAX_HEAP_BYTES=32L*1024*1024;
    public static final int MAX_PACKAGE_BYTES=1024*1024;
    private static final String TERMINATION="JS_FEATURE_ISOLATE_TERMINATION";
    private static final String HEAP="JS_FEATURE_ISOLATE_MAX_HEAP_SIZE";
    private static final String RESULT_LIMIT="JS_FEATURE_EVALUATE_WITHOUT_TRANSACTION_LIMIT";
    private static final String PROMISE_RETURN="JS_FEATURE_PROMISE_RETURN";
    private static final List<String> REQUIRED=List.of(TERMINATION,HEAP,RESULT_LIMIT,PROMISE_RETURN);
    private static final List<String> FORBIDDEN_GLOBALS=List.of("fetch","XMLHttpRequest","WebSocket","WebTransport","document","navigator","require","process","Deno","Java","Packages","load","read","readFile","writeFile");
    private static final java.util.concurrent.Executor DIRECT=Runnable::run;

    /** Actual installed WebView/runtime artifact identity. This is not an executable path or cwd. */
    public static final class Identity {
        public final String installationId,runtimeVersion,runtimeSha256;
        private final String generation;
        Identity(String generation,String version,String sha)throws Exception {
            if(generation==null||generation.isEmpty()||version==null||version.isEmpty()||sha==null||!sha.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("JavaScript运行时身份无效");
            this.generation=generation;this.installationId="webview-"+sha256(generation.getBytes(StandardCharsets.UTF_8)).substring(0,40);
            this.runtimeVersion=version;this.runtimeSha256=sha;
        }
        public JSONObject review()throws Exception {
            return new JSONObject().put("runtimeId",RUNTIME_ID).put("runtimeVersion",runtimeVersion).put("runtimeSha256",runtimeSha256)
                    .put("runtimeHashKind","sha256-installed-artifact-inventory")
                    .put("installationId",installationId).put("libraryVersion",LIBRARY_VERSION).put("enforcementId",POLICY_ID)
                    .put("invocationKind","JAVASCRIPT_EVALUATION").put("filesystem","none").put("network","deny")
                    .put("androidBridge",false).put("nodeCompatible",false).put("maxHeapBytes",MAX_HEAP_BYTES)
                    .put("requiredFeatures",new JSONArray(REQUIRED));
        }
    }
    public static final class Unavailable extends IllegalStateException {
        public Unavailable(String message){super(message);}
    }
    interface Provider {
        String generation()throws Exception;
        Identity fingerprint()throws Exception;
        Engine connect()throws Exception;
    }
    interface Engine extends AutoCloseable {
        boolean supports(String feature);
        Isolate isolate(long heap,int maxReturnBytes)throws Exception;
        @Override void close();
    }
    interface Isolate extends AutoCloseable {
        ListenableFuture<String> evaluate(String source)throws Exception;
        @Override void close();
    }
    private final Provider provider;
    private Engine engine;
    private Identity identity;
    private Session active;
    private boolean connecting,closed;

    public AndroidJavascriptSandboxRuntime(Context context){this(new AndroidProvider(context.getApplicationContext()));}
    AndroidJavascriptSandboxRuntime(Provider provider){this.provider=java.util.Objects.requireNonNull(provider);}

    /** Native warm-up, on the host's bounded worker, before constructing an owner review. */
    public Identity connect()throws Exception {
        synchronized(this){if(closed)throw new Unavailable("JavaScript沙箱已关闭");if(identity!=null)return describe();if(connecting)throw new Unavailable("JavaScript沙箱正在准备");connecting=true;}
        Engine candidate=null;Isolate probe=null;
        try {
            String before=provider.generation();Identity measured=provider.fingerprint();
            if(!before.equals(measured.generation)||!before.equals(provider.generation()))throw new Unavailable("WebView安装在探测期间改变，请重新准备");
            candidate=provider.connect();
            for(String feature:REQUIRED)if(!candidate.supports(feature))throw new Unavailable("当前WebView缺少JavaScript取消、内存或返回上限能力");
            probe=candidate.isolate(MAX_HEAP_BYTES,4096);
            String check=probe.evaluate(probeSource()).get(5,TimeUnit.SECONDS);
            JSONObject globals=new JSONObject(check);
            if(globals.length()!=FORBIDDEN_GLOBALS.size())throw new Unavailable("JavaScript沙箱环境探测不匹配");
            for(String name:FORBIDDEN_GLOBALS)if(!"undefined".equals(globals.optString(name)))throw new Unavailable("执行环境暴露了不支持的宿主能力");
            probe.close();probe=null;
            if(!before.equals(provider.generation()))throw new Unavailable("WebView安装在连接期间改变，请重新准备");
            synchronized(this){if(closed)throw new Unavailable("JavaScript沙箱已关闭");engine=candidate;candidate=null;identity=measured;return measured;}
        } catch(Unavailable known){throw known;}
        catch(Exception failure){throw new Unavailable("无法建立可验证的本机JavaScript沙箱；没有执行技能脚本");}
        finally{try{if(probe!=null)probe.close();}finally{try{if(candidate!=null)candidate.close();}finally{synchronized(this){connecting=false;}}}}
    }

    /** Short installed-provider check; expensive artifact hashing only occurs during native warm-up. */
    public synchronized Identity describe()throws Exception {
        if(closed||engine==null||identity==null)throw new Unavailable("请先准备本机JavaScript沙箱");
        if(!identity.generation.equals(provider.generation()))throw new Unavailable("WebView运行时已改变，请停止原操作并重新审阅");
        return identity;
    }

    /** One isolate at a time; another user's data is never placed in a sibling live isolate. */
    public synchronized Session createSession(int maxOutputBytes)throws Exception {
        describe();if(maxOutputBytes<1||maxOutputBytes>65536)throw new IllegalArgumentException("JavaScript返回上限无效");
        if(active!=null)throw new Unavailable("原JavaScript操作尚未释放，请先核对原回执");
        active=new Session(engine.isolate(MAX_HEAP_BYTES,maxOutputBytes));return active;
    }

    public final class Session implements AutoCloseable {
        private final Isolate isolate;
        private final AtomicBoolean initialized=new AtomicBoolean(),evaluated=new AtomicBoolean(),stopped=new AtomicBoolean();
        private volatile ListenableFuture<String> initialization;
        private boolean closeComplete;
        private Session(Isolate isolate){this.isolate=isolate;}

        /** Data only: no source interpolation, named file descriptors, message ports or callbacks. */
        public ListenableFuture<String> initialize(List<String> arguments,Map<String,byte[]> packageFiles)throws Exception {
            if(stopped.get()||!initialized.compareAndSet(false,true))throw new IllegalStateException("此JavaScript隔离实例不能重新初始化");
            String bootstrap=bootstrap(arguments,packageFiles);initialization=isolate.evaluate(bootstrap);return initialization;
        }
        /** The unmodified, strictly decoded approved script bytes; no eval/string-command wrapper. */
        public ListenableFuture<String> evaluateExact(byte[] approvedScript)throws Exception {
            if(stopped.get()||initialization==null||!initialization.isDone())throw new IllegalStateException("JavaScript数据初始化尚未完成");
            initialization.get(); // Do not execute if initialization failed or was cancelled.
            if(!evaluated.compareAndSet(false,true))throw new SecurityException("同一隔离实例只能派发一次脚本");
            describe();if(stopped.get())throw new InterruptedException("原JavaScript操作已停止");
            return isolate.evaluate(utf8(approvedScript));
        }
        /** The feature gate guarantees this terminates active/queued evaluation, not just its Future. */
        @Override public synchronized void close(){
            if(closeComplete)return;stopped.set(true);
            isolate.close();closeComplete=true;
            synchronized(AndroidJavascriptSandboxRuntime.this){if(active==this)active=null;}
        }
    }

    @Override public void close(){
        Engine current;Session session;
        synchronized(this){if(closed)return;closed=true;session=active;current=engine;engine=null;identity=null;}
        try{if(session!=null)session.close();}finally{if(current!=null)current.close();}
    }

    static String bootstrap(List<String> args,Map<String,byte[]> files)throws Exception {
        if(args==null||args.size()>40||files==null||files.size()>128)throw new IllegalArgumentException("JavaScript输入超限");
        JSONArray arguments=new JSONArray();int argumentChars=0;
        for(String value:args){if(value==null||value.length()>2048||value.chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException("JavaScript参数无效");StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(value));argumentChars+=value.length();if(argumentChars>8192)throw new IllegalArgumentException("JavaScript参数超限");arguments.put(value);}
        JSONObject resources=new JSONObject();long bytes=0;
        for(Map.Entry<String,byte[]> item:new TreeMap<>(files).entrySet()){
            String path=item.getKey();if(path==null||path.isEmpty()||path.length()>240||path.startsWith("/")||path.contains("\\")||path.contains(":"))throw new IllegalArgumentException("JavaScript包资源路径无效");
            for(String part:path.split("/",-1))if(part.isEmpty()||part.equals(".")||part.equals(".."))throw new IllegalArgumentException("JavaScript包资源路径无效");
            byte[] content=item.getValue();if(content==null||(bytes+=content.length)>MAX_PACKAGE_BYTES)throw new IllegalArgumentException("JavaScript包资源超限");resources.put(path,utf8(content));
        }
        // JSON.parse avoids object-literal __proto__ semantics. The source is separately evaluated.
        return "(()=>{const a=JSON.parse("+JSONObject.quote(arguments.toString())+");const r=JSON.parse("+JSONObject.quote(resources.toString())+");Object.freeze(a);Object.freeze(r);Object.defineProperty(globalThis,'pocket',{value:Object.freeze({args:a,resources:r}),writable:false,configurable:false});return 'ready';})()";
    }
    private static String probeSource()throws Exception {
        StringBuilder out=new StringBuilder("JSON.stringify({");
        for(String name:FORBIDDEN_GLOBALS){if(out.length()>16)out.append(',');out.append(JSONObject.quote(name)).append(":typeof globalThis[").append(JSONObject.quote(name)).append(']');}
        return out.append("})").toString();
    }
    static String utf8(byte[] value)throws Exception {
        if(value==null||value.length>MAX_PACKAGE_BYTES)throw new IllegalArgumentException("JavaScript源码无效");
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(value)).toString();
    }
    private static String sha256(byte[] bytes)throws Exception {MessageDigest digest=MessageDigest.getInstance("SHA-256");return hex(digest.digest(bytes));}
    private static String hex(byte[] bytes){StringBuilder result=new StringBuilder();for(byte value:bytes)result.append(String.format(java.util.Locale.ROOT,"%02x",value&255));return result.toString();}

    private static final class AndroidProvider implements Provider {
        private final Context context;
        AndroidProvider(Context context){this.context=context;}
        private PackageInfo current()throws Exception {
            PackageInfo selected=WebView.getCurrentWebViewPackage();
            if(selected==null)throw new Unavailable("手机没有可用的WebView运行时");
            ServiceInfo service=context.getPackageManager().getServiceInfo(new ComponentName(selected.packageName,"org.chromium.android_webview.js_sandbox.service.JsSandboxService0"),0);
            if((service.flags&ServiceInfo.FLAG_ISOLATED_PROCESS)==0||(service.flags&ServiceInfo.FLAG_EXTERNAL_SERVICE)==0)throw new Unavailable("WebView未声明外部隔离JavaScript服务");
            return context.getPackageManager().getPackageInfo(selected.packageName,PackageManager.GET_SHARED_LIBRARY_FILES|PackageManager.GET_SIGNING_CERTIFICATES);
        }
        private List<File> artifacts(PackageInfo info)throws Exception {
            ApplicationInfo app=info.applicationInfo;if(app==null||app.sourceDir==null)throw new Unavailable("WebView安装信息不完整");
            TreeMap<String,File> result=new TreeMap<>();List<String> paths=new ArrayList<>();paths.add(app.sourceDir);
            if(app.splitSourceDirs!=null)paths.addAll(Arrays.asList(app.splitSourceDirs));if(app.sharedLibraryFiles!=null)paths.addAll(Arrays.asList(app.sharedLibraryFiles));
            if(app.nativeLibraryDir!=null){File[] nativeFiles=new File(app.nativeLibraryDir).listFiles((dir,name)->name.endsWith(".so"));if(nativeFiles!=null)for(File file:nativeFiles)paths.add(file.getPath());}
            for(String path:paths){File file=new File(path).getCanonicalFile();if(!file.isFile()||!file.canRead())throw new Unavailable("无法核验WebView运行时文件");result.put(file.getPath(),file);}
            return new ArrayList<>(result.values());
        }
        private String generation(PackageInfo info)throws Exception {
            StringBuilder out=new StringBuilder(info.packageName).append('\n').append(info.getLongVersionCode()).append('\n').append(info.lastUpdateTime).append('\n').append(Build.FINGERPRINT).append('\n').append(String.join(",",Build.SUPPORTED_ABIS));
            if(info.signingInfo==null)throw new Unavailable("WebView签名信息不可用");
            for(android.content.pm.Signature signer:info.signingInfo.getApkContentsSigners())out.append('\n').append(sha256(signer.toByteArray()));
            for(File file:artifacts(info))out.append('\n').append(file.getPath()).append(':').append(file.length()).append(':').append(file.lastModified());
            return out.toString();
        }
        @Override public String generation()throws Exception{return generation(current());}
        @Override public Identity fingerprint()throws Exception {
            PackageInfo info=current();String before=generation(info);MessageDigest digest=MessageDigest.getInstance("SHA-256");digest.update(before.getBytes(StandardCharsets.UTF_8));digest.update((byte)0);long total=0;
            for(File file:artifacts(info)){
                digest.update(file.getPath().getBytes(StandardCharsets.UTF_8));digest.update((byte)0);
                try(InputStream input=Files.newInputStream(file.toPath())){byte[] chunk=new byte[32768];int count;while((count=input.read(chunk))!=-1){if(Thread.currentThread().isInterrupted())throw new InterruptedException("运行时核验已停止");if((total+=count)>512L*1024*1024)throw new Unavailable("WebView运行时文件超过核验上限");digest.update(chunk,0,count);}}
            }
            if(!before.equals(generation()))throw new Unavailable("WebView安装在核验期间改变");
            return new Identity(before,info.packageName+" / "+info.versionName,hex(digest.digest()));
        }
        @Override public Engine connect()throws Exception {
            if(!JavaScriptSandbox.isSupported())throw new Unavailable("当前WebView不支持本机JavaScript沙箱");
            ListenableFuture<JavaScriptSandbox> pending=JavaScriptSandbox.createConnectedInstanceAsync(context);JavaScriptSandbox sandbox;
            try{sandbox=pending.get(5,TimeUnit.SECONDS);}
            catch(Exception failure){pending.addListener(()->{try{pending.get().close();}catch(Exception ignored){}},DIRECT);throw failure;}
            return new Engine(){
                @Override public boolean supports(String feature){return sandbox.isFeatureSupported(feature);}
                @Override public Isolate isolate(long heap,int maxReturnBytes){
                    IsolateStartupParameters settings=new IsolateStartupParameters();settings.setMaxHeapSizeBytes(heap);settings.setMaxEvaluationReturnSizeBytes(maxReturnBytes);
                    JavaScriptIsolate value=sandbox.createIsolate(settings);
                    return new Isolate(){@Override public ListenableFuture<String> evaluate(String source){return value.evaluateJavaScriptAsync(source);}@Override public void close(){value.close();}};
                }
                @Override public void close(){sandbox.close();}
            };
        }
    }
}
