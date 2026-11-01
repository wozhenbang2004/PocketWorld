package dev.pocketengine.features.local.language;

import android.content.Context;
import android.os.ParcelFileDescriptor;
import com.google.common.util.concurrent.ListenableFuture;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.local.project.ProjectSources;
import dev.pocketengine.features.skills.AndroidJavascriptSandboxRuntime;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

/** Native owner editor analysis on the app's ONE original JavaScript binding. The dedicated
 * input consists of a pinned compiler/driver and JSON data, never executable project scripts.
 * Each query leases one bounded isolate; closing this consumer never closes the app binding. */
public final class AndroidProjectLanguageService implements AutoCloseable {
    private static final String ROOT="project-language/",BUNDLE=ROOT+"typescript-5.9.3/";
    private static final java.util.concurrent.Semaphore OWNER_QUERY=new java.util.concurrent.Semaphore(1,true);
    private final Context context;private final AndroidJavascriptSandboxRuntime shared;
    private final AtomicBoolean closed=new AtomicBoolean(),busy=new AtomicBoolean();
    private volatile AndroidJavascriptSandboxRuntime.BuiltinReadSession active;private volatile ListenableFuture<String> evaluating;
    public AndroidProjectLanguageService(Context context,AndroidJavascriptSandboxRuntime originalBinding){this.context=context.getApplicationContext();shared=java.util.Objects.requireNonNull(originalBinding);}
    public JSONObject query(ProjectLanguageSnapshot snapshot,String action,int position,String newName,RunControl original)throws Exception {
        if(android.os.Looper.myLooper()==android.os.Looper.getMainLooper())throw new IllegalStateException("语言分析不能阻塞界面线程");
        if(!busy.compareAndSet(false,true))throw new IllegalStateException("上一项语言分析尚未结束，请等待或停止它");
        if(!OWNER_QUERY.tryAcquire()){busy.set(false);throw new IllegalStateException("另一个编辑窗口正在等待或分析；当前文件未派发，请稍后明确重试");}
        Path file=null;AndroidJavascriptSandboxRuntime.BuiltinReadSession lease=null;
        try {
            current(original);JSONObject request=snapshot.request(action,position,newName);
            File folder=new File(context.getCacheDir(),"project-language-evaluations");if(Files.isSymbolicLink(folder.toPath())||!folder.isDirectory()&&!folder.mkdirs())throw new IOException("无法准备语言分析临时输入");
            File[] retained=folder.listFiles();if(retained==null)throw new IOException("无法核对语言分析临时输入");long retainedBytes=0;int retainedFiles=0;
            for(File prior:retained){if(Files.isSymbolicLink(prior.toPath())||!prior.isFile()||!prior.getName().matches("language-.*\\.js"))throw new IOException("语言缓存有不可核对内容，未删除或覆盖");retainedBytes+=prior.length();retainedFiles++;}
            if(retainedFiles>=4||retainedBytes+AndroidJavascriptSandboxRuntime.BUILTIN_MAX_SOURCE_BYTES>64L*1024*1024)throw new IOException("语言分析临时缓存尚未释放，请从存储管理明确清理后重试；未自动删稿或项目");
            file=Files.createTempFile(folder.toPath(),"language-",".js");
            try(FileOutputStream raw=new FileOutputStream(file.toFile())){
                LimitedOutput output=new LimitedOutput(raw,original);
                output.write("if(['fetch','XMLHttpRequest','WebSocket','document','require','process','Java','Packages','readFile','writeFile'].some(n=>typeof globalThis[n]!=='undefined'))throw Error('Unexpected host authority');\n".getBytes(StandardCharsets.UTF_8));
                JSONObject lock=new JSONObject(utf8(asset(BUNDLE+"source-lock.json",65536,original)));
                if(!"5.9.3".equals(lock.getString("version"))||!"Apache-2.0".equals(lock.getString("license")))throw new SecurityException("捆绑语言编译器来源身份不符");
                JSONObject pins=lock.getJSONObject("bundledAssets");byte[] compiler=asset(BUNDLE+"typescript.js",10*1024*1024,original),libraries=asset(BUNDLE+"libraries.json",4*1024*1024,original);
                if(!ProjectSources.sha256(compiler).equals(pins.getJSONObject("typescript.js").getString("sha256"))||!ProjectSources.sha256(libraries).equals(pins.getJSONObject("libraries.json").getString("sha256")))throw new SecurityException("捆绑语言组件字节与固定来源不同");
                output.write(compiler);output.write("\n;globalThis.pwLanguageLibraries=JSON.parse(".getBytes(StandardCharsets.UTF_8));
                // JSON.parse preserves __proto__ as data in both bundled and owner virtual files.
                output.write(ProjectLanguageSnapshot.encode(JSONObject.quote(utf8(libraries))));output.write(");\n".getBytes(StandardCharsets.UTF_8));
                output.write(asset(ROOT+"language-service.js",65536,original));output.write("\n;pwProjectLanguage.run(JSON.parse(".getBytes(StandardCharsets.UTF_8));
                output.write(ProjectLanguageSnapshot.encode(JSONObject.quote(request.toString())));output.write("));".getBytes(StandardCharsets.UTF_8));output.flush();raw.getFD().sync();
            }
            current(original);lease=shared.openBuiltinReadSession(AndroidJavascriptSandboxRuntime.BuiltinProgram.PROJECT_TYPESCRIPT_5_9_3,original);active=lease;
            try(ParcelFileDescriptor descriptor=ParcelFileDescriptor.open(file.toFile(),ParcelFileDescriptor.MODE_READ_ONLY)){
                current(original);ListenableFuture<String> pending=lease.evaluate(descriptor);evaluating=pending;String result=pending.get(20,TimeUnit.SECONDS);
                current(original);if(ProjectLanguageSnapshot.encode(result).length>AndroidJavascriptSandboxRuntime.BUILTIN_MAX_RESULT_BYTES)throw new IllegalStateException("完整语言结果超出128KiB，请缩小查询；未应用部分修改");
                return snapshot.accept(new JSONObject(result),action,position);
            }
        }catch(java.util.concurrent.TimeoutException timeout){throw new IllegalStateException("本次语言分析超时，已请求关闭该分析实例；源文件和草稿未修改",timeout);}
        finally{
            try{if(lease!=null)lease.close();}finally{active=null;evaluating=null;busy.set(false);OWNER_QUERY.release();if(file!=null)Files.deleteIfExists(file);}
        }
    }
    private void current(RunControl control)throws Exception{control.check();if(closed.get()||Thread.currentThread().isInterrupted())throw new InterruptedIOException("原编辑器分析已取消或关闭");}
    private byte[] asset(String path,int max,RunControl original)throws Exception{
        try(InputStream in=context.getAssets().open(path);ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] buffer=new byte[8192];for(int n;(n=in.read(buffer))>=0;){current(original);if(n==0)continue;if(out.size()+n>max)throw new IOException("固定语言资源超过边界");out.write(buffer,0,n);}return out.toByteArray();}
    }
    private static String utf8(byte[] value)throws Exception{return StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(value)).toString();}
    private final class LimitedOutput extends FilterOutputStream {
        private long count;private final RunControl control;LimitedOutput(OutputStream output,RunControl control){super(output);this.control=control;}
        @Override public void write(int value)throws IOException{write(new byte[]{(byte)value},0,1);}
        @Override public void write(byte[] value,int start,int length)throws IOException{try{current(control);}catch(Exception stop){throw new InterruptedIOException("语言输入准备已停止");}if((count+=length)>AndroidJavascriptSandboxRuntime.BUILTIN_MAX_SOURCE_BYTES)throw new IOException("完整语言分析输入超过16MiB；请缩小源范围");out.write(value,start,length);}
    }
    @Override public void close(){if(!closed.compareAndSet(false,true))return;var pending=evaluating;if(pending!=null)pending.cancel(true);var lease=active;if(lease!=null)lease.close();}
}
