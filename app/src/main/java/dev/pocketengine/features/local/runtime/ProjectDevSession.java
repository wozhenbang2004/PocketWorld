package dev.pocketengine.features.local.runtime;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.local.project.ProjectLiveEditPolicy;
import dev.pocketengine.features.local.project.ProjectSources;
import java.io.File;
import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.*;
import org.json.JSONArray;
import org.json.JSONObject;

/** One original job's cooperative loopback protocol, never a grant or stdout-derived server address. */
public final class ProjectDevSession {
    public static final String FORMAT="pocketworld.dev-session.v1",PROTOCOL="pocketworld.vite-dev.v1";
    private final RuntimeExecutionRef reference;
    private final JSONObject policy,marker;
    private final File markerFile;
    private final String markerHash,relativeMarker,base;
    private final int port;
    private ProjectDevSession(RuntimeInvocation invocation,JSONObject actualPolicy)throws Exception {
        reference=invocation.executionRef();policy=ProjectLiveEditPolicy.verified(actualPolicy);ProjectLiveEditPolicy.requireStartup(policy,invocation.sourceSnapshot());
        JSONObject payload=invocation.exact().getJSONObject("payload");JSONArray argv=payload.getJSONArray("argv");
        if(reference.phase()!=RuntimeExecutionRef.Phase.COMMAND||!"owned_service".equals(payload.optString("runMode"))||!"node".equals(payload.optString("executable"))||argv.length()!=1||!"tools/dev.mjs".equals(argv.getString(0))||!PROTOCOL.equals(payload.optString("devWatchProtocol"))||!".".equals(payload.optString("workingDirectory",".")))throw new SecurityException("热改服务只能使用原审阅的 Node 开发入口");
        byte[] random=new byte[32];new java.security.SecureRandom().nextBytes(random);StringBuilder token=new StringBuilder();for(byte value:random)token.append(String.format(java.util.Locale.ROOT,"%02x",value&255));
        try(ServerSocket reservation=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))){port=reservation.getLocalPort();}
        if(port<1024)throw new SecurityException("开发服务未取得可用非特权本机端口");
        base="http://127.0.0.1:"+port+"/__pw_dev/"+token+"/";relativeMarker=".pocketworld/dev-"+reference.operationId()+".json";
        File parent=invocation.workspace().resolve(".pocketworld",false);if(!parent.isDirectory()&&!parent.mkdir())throw new IllegalStateException("无法建立原开发会话目录");markerFile=invocation.workspace().resolve(relativeMarker,false);
        JSONObject binding=reference.binding();marker=new JSONObject().put("format",FORMAT).put("protocol",PROTOCOL).put("projectId",reference.projectId()).put("checkoutId",reference.checkoutId()).put("taskId",binding.getString("taskId")).put("runId",binding.getString("runId")).put("operationId",reference.operationId()).put("epoch",binding.getInt("coordinatorEpoch")).put("policyDigest",policy.getString("policyDigest")).put("token",token.toString()).put("port",port);
        byte[] bytes=marker.toString().getBytes(StandardCharsets.UTF_8);if(bytes.length>8192)throw new IllegalArgumentException("开发会话描述超限");
        boolean created=false,written=false;try{var channel=java.nio.channels.FileChannel.open(markerFile.toPath(),StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);created=true;try(var output=channel){java.nio.ByteBuffer input=java.nio.ByteBuffer.wrap(bytes);while(input.hasRemaining())output.write(input);output.force(true);}written=true;}finally{if(created&&!written)Files.deleteIfExists(markerFile.toPath());}markerHash=ProjectSources.sha256(bytes);
    }
    static ProjectDevSession create(RuntimeInvocation invocation)throws Exception {JSONObject policy=ProjectLiveEditPolicy.fromExact(invocation.exact());return policy==null?null:new ProjectDevSession(invocation,policy);}
    String markerArgument(){return relativeMarker;}
    JSONObject policy()throws Exception{return ProjectSources.copy(policy);}
    JSONObject startupMetadata()throws Exception{return new JSONObject().put("protocol",PROTOCOL).put("policyDigest",policy.getString("policyDigest")).put("editablePaths",ProjectLiveEditPolicy.selectedPaths(policy)).put("nativeSessionCreated",true).put("httpObserved",false).put("websocketObserved",false).put("businessVerified",false).put("portOwnershipIsOsSandbox",false);}
    boolean cleanupMarker(){try{if(!Files.exists(markerFile.toPath(),LinkOption.NOFOLLOW_LINKS))return true;if(Files.isSymbolicLink(markerFile.toPath())||markerFile.length()>8192||!markerHash.equals(RuntimeFiles.sha256(markerFile)))return false;Files.delete(markerFile.toPath());return true;}catch(Exception unconfirmed){return false;}}

    /** Only this native object can mint a page description, after real HTTP and WS observations. */
    public static final class Observed {
        public final RuntimeExecutionRef reference;
        public final String baseUrl,entryUrl,identity,policyDigest;
        public final long observedAt;
        private Observed(ProjectDevSession session)throws Exception {
            reference=session.reference;baseUrl=session.base;entryUrl=baseUrl+"index.html";policyDigest=session.policy.getString("policyDigest");observedAt=System.currentTimeMillis();
            identity=ProjectSources.digest(new JSONObject().put("execution",reference.executionLink()).put("base",baseUrl).put("policyDigest",policyDigest));
        }
        public JSONObject metadata()throws Exception{return new JSONObject().put("format","pocketworld.live-project-preview.v1").put("previewId",identity).put("executionId",reference.operationId()).put("projectId",reference.projectId()).put("checkoutId",reference.checkoutId()).put("sourceDigest",reference.sourceDigest()).put("policyDigest",policyDigest).put("observedAt",observedAt).put("httpObserved",true).put("websocketObserved",true).put("businessVerified",false).put("persisted",false).put("sameOriginalHostRunOnly",true).put("baseUrl",baseUrl).put("entryUrl",entryUrl);}
        public boolean allows(String url){try{if(url==null||url.length()>8192)return false;URI actual=URI.create(url),allowed=URI.create(baseUrl);String path=actual.getPath();if(path==null||path.indexOf('\\')>=0||path.indexOf('\0')>=0)return false;for(String part:path.split("/",-1))if(part.equals(".")||part.equals(".."))return false;return "http".equals(actual.getScheme())&&actual.getRawUserInfo()==null&&allowed.getHost().equals(actual.getHost())&&actual.getPort()==allowed.getPort()&&path.startsWith(allowed.getPath())&&actual.normalize().getRawPath().equals(actual.getRawPath());}catch(Exception invalid){return false;}}
    }
    Observed probe(RunControl observation,ProjectRuntime.LiveGate current)throws Exception {
        observation.check();current.check();OkHttpClient client=new OkHttpClient.Builder().proxy(Proxy.NO_PROXY).followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).connectTimeout(1500,TimeUnit.MILLISECONDS).readTimeout(1500,TimeUnit.MILLISECONDS).callTimeout(4000,TimeUnit.MILLISECONDS).build();
        try{
            Call call=client.newCall(new Request.Builder().url(base+"__health").get().header("Accept","application/json").build());Runnable cancel=call::cancel;observation.onCancel(cancel);
            try(Response response=call.execute()){
                if(response.code()!=200||response.body()==null||!response.header("Content-Type","").startsWith("application/json"))throw new IllegalStateException("原开发服务尚未返回可核对的实际HTTP响应");byte[] bytes=bounded(response.body().byteStream(),8192,observation);JSONObject actual=new JSONObject(StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString());
                for(String key:new String[]{"format","protocol","projectId","checkoutId","taskId","runId","operationId","epoch","policyDigest","token"})if(!String.valueOf(marker.get(key)).equals(String.valueOf(actual.get(key))))throw new SecurityException("本机响应不是原开发会话的挑战回执");
            }finally{observation.removeCancelAction(cancel);}
            observation.check();current.check();CountDownLatch connected=new CountDownLatch(1);AtomicReference<String> error=new AtomicReference<>();java.util.concurrent.atomic.AtomicBoolean seen=new java.util.concurrent.atomic.AtomicBoolean();
            Request request=new Request.Builder().url(base.replace("http://","ws://")+"hmr").header("Sec-WebSocket-Protocol","vite-hmr").build();
            // Native diagnostic has no browser Origin. Vite's own per-process token remains required for browser clients.
            WebSocket socket=client.newWebSocket(request,new WebSocketListener(){
                @Override public void onMessage(WebSocket ws,String text){try{if(text.length()<=8192&&"connected".equals(new JSONObject(text).optString("type"))){seen.set(true);connected.countDown();}}catch(Exception ignored){}}
                @Override public void onFailure(WebSocket ws,Throwable failure,Response response){error.set("原开发服务的WebSocket握手未确认");connected.countDown();}
                @Override public void onClosed(WebSocket ws,int code,String reason){connected.countDown();}
            });Runnable close=socket::cancel;observation.onCancel(close);
            try{if(!connected.await(2500,TimeUnit.MILLISECONDS)||!seen.get())throw new IllegalStateException(error.get()==null?"原开发服务尚未确认WebSocket协议":error.get());observation.check();current.check();return new Observed(this);}
            finally{observation.removeCancelAction(close);socket.cancel();}
        }finally{client.connectionPool().evictAll();client.dispatcher().executorService().shutdown();}
    }
    private static byte[] bounded(java.io.InputStream input,int max,RunControl control)throws Exception {ByteArrayOutputStream output=new ByteArrayOutputStream();byte[] bytes=new byte[2048];for(int n;(n=input.read(bytes))>=0;){control.check();if(n==0)continue;if(output.size()+n>max)throw new IllegalArgumentException("原开发服务响应超限，未当作就绪");output.write(bytes,0,n);}return output.toByteArray();}
}
