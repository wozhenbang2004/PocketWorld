package dev.pocketengine.features.proxy;

import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.runtime.models.InferenceAudiencePolicy;
import dev.pocketengine.runtime.models.NativeInferenceScope;
import dev.pocketengine.features.proxy.accounts.GatewayCall;
import dev.pocketengine.features.proxy.accounts.GatewayFailure;
import dev.pocketengine.features.proxy.providers.ChatGptProvider;
import dev.pocketengine.features.proxy.providers.ResponsesRequest;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;

/** Authenticated phone-local model API. A client token is not a phone/tool grant. */
public final class LoopbackRelay implements AutoCloseable {
    public interface ConfigSource {
        ModelConfig get()throws Exception;
        /** Native settings selection CAS, including A -> B -> A; 0 is the explicit legacy host. */
        default long selectionRevision()throws Exception{return 0;}
    }
    /** Native original receipt binding. HTTP headers/body never choose a task or usage target. */
    public interface InferenceFactory {dev.pocketengine.runtime.usage.NativeInferenceWork open(ModelConfig captured,dev.pocketengine.core.RunControl original,String nativeSessionId)throws Exception;}
    /** Installed only by the native host, using its existing account singleton and version CAS. */
    public interface AccountRoute {
        JSONArray models(ModelConfig.AccountModelRef reference,GatewayCall call)throws GatewayFailure;
        void stream(ModelConfig.AccountModelRef reference,ResponsesRequest request,GatewayCall call,ChatGptProvider.EventSink sink)throws GatewayFailure;
    }
    private static final int MAX_BODY=2*1024*1024;
    private final String sessionId=java.util.UUID.randomUUID().toString();
    private final ConfigSource configurations;
    private volatile RelayRouteBinding routeBinding;
    private final AccountRoute accounts;
    private final InferenceFactory inferenceFactory;
    private final String token;
    private final ServerSocket listener;
    private final ThreadPoolExecutor workers;
    private final Set<Socket> clients=ConcurrentHashMap.newKeySet();
    private final Set<dev.pocketengine.core.RunControl> apiControls=ConcurrentHashMap.newKeySet();
    private final Set<GatewayCall> accountCalls=ConcurrentHashMap.newKeySet();
    private final AtomicInteger requests=new AtomicInteger();
    private final Thread acceptor;
    private volatile boolean closed;
    private volatile boolean routeInvalidated;
    private volatile long selectionRevision=-1;
    public LoopbackRelay(int port,String token,ConfigSource configurations)throws Exception {
        this(port,token,configurations,null);
    }
    public LoopbackRelay(int port,String token,ConfigSource configurations,AccountRoute accounts)throws Exception {
        this(port,token,configurations,accounts,null);
    }
    /** Native module passes its already reviewed startup selection; public ports bind after authentication. */
    LoopbackRelay(int port,String token,ConfigSource configurations,AccountRoute accounts,ModelConfig selected)throws Exception {this(port,token,configurations,accounts,selected,null);}
    LoopbackRelay(int port,String token,ConfigSource configurations,AccountRoute accounts,ModelConfig selected,InferenceFactory inferenceFactory)throws Exception {
        if((port!=0&&port<1024)||port>65535)throw new IllegalArgumentException("反代端口需为0或1024至65535");
        if(token==null||!token.matches("[A-Za-z0-9_-]{24,256}"))throw new IllegalArgumentException("本地反代令牌需24至256位字母、数字、下划线或短横线");
        this.token=token;this.configurations=configurations;this.accounts=accounts;this.inferenceFactory=inferenceFactory;
        if(selected!=null)InferenceAudiencePolicy.require(selected,NativeInferenceScope.externalRelay(),null);
        routeBinding=selected==null?null:new RelayRouteBinding(selected);
        if(selected!=null)selectionRevision=configurations.selectionRevision();
        listener=new ServerSocket();listener.setReuseAddress(true);listener.bind(new InetSocketAddress("127.0.0.1",port),8);listener.setSoTimeout(500);
        workers=new ThreadPoolExecutor(4,4,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(8),r->{Thread t=new Thread(r,"pocket-proxy-request");t.setDaemon(true);return t;});
        acceptor=new Thread(this::accept,"pocket-proxy-listen");acceptor.setDaemon(true);acceptor.start();
    }
    public String sessionId(){return sessionId;}
    public int port(){return listener.getLocalPort();}
    public boolean running(){return !closed&&!listener.isClosed();}
    public int requests(){return requests.get();}
    public boolean routeCurrent(){try{RelayRouteBinding bound=routeBinding;if(bound==null)return false;if(!bound.matches(configurations.get())||selectionRevision!=configurations.selectionRevision())invalidateRoute();return !routeInvalidated;}catch(Exception unavailable){return false;}}
    private ModelConfig currentConfiguration()throws Exception{
        ModelConfig current=configurations.get();requireCurrent(current);return current;
    }
    private void requireCurrent(ModelConfig captured)throws Exception{
        // Every public constructor and every authenticated request uses the same external audience.
        // Client headers/body never select an owner scope, including after configuration changes.
        InferenceAudiencePolicy.require(captured,NativeInferenceScope.externalRelay(),null);
        synchronized(this){if(routeBinding==null){routeBinding=new RelayRouteBinding(captured);selectionRevision=configurations.selectionRevision();}}
        if(!routeBinding.matches(captured)||!routeBinding.matches(configurations.get())||selectionRevision!=configurations.selectionRevision())invalidateRoute();
        if(routeInvalidated)throw new RequestFailure(503,"模型连接或请求设置已改变；请停止本机模型服务后按新选择重新启用，没有自动切换连接或模型");
    }
    private void invalidateRoute(){routeInvalidated=true;for(dev.pocketengine.core.RunControl control:apiControls)control.cancel();for(GatewayCall call:accountCalls)call.cancel();}
    private void requireInferenceCurrent(ModelConfig captured,URL target)throws Exception{
        requireCurrent(captured);
        try{configurations.get().requireTransportConsent(target);}
        catch(IllegalArgumentException revoked){invalidateRoute();throw new RequestFailure(503,"上游HTTP传输确认已撤销；旧回复未作为完整结果返回，请核对模型设置");}
    }
    private void accept() {
        while(!closed){try{Socket client=listener.accept();client.setSoTimeout(10_000);clients.add(client);try{workers.execute(()->serve(client));}catch(java.util.concurrent.RejectedExecutionException busy){try{respond(client,503,error("本地反代并发已满"));}catch(Exception ignored){}closeClient(client);}}
        catch(java.net.SocketTimeoutException next){}catch(Exception error){if(!closed)close();}}
    }
    private void serve(Socket socket) {
        java.util.concurrent.atomic.AtomicBoolean parsed=new java.util.concurrent.atomic.AtomicBoolean();
        Thread deadline=new Thread(()->{try{Thread.sleep(10_000);if(!parsed.get())closeClient(socket);}catch(InterruptedException complete){}},"pocket-proxy-request-deadline");deadline.setDaemon(true);deadline.start();
        Response response = new Response(socket);
        try {
            if(!socket.getInetAddress().isLoopbackAddress())throw new RequestFailure(403,"仅允许本机连接");
            InputStream in=socket.getInputStream();String start=line(in,4096);String[] request=start.split(" ");if(request.length!=3||!request[2].matches("HTTP/1\\.[01]"))throw new RequestFailure(400,"HTTP请求行无效");
            Map<String,String> headers=new LinkedHashMap<>();int bytes=start.length();
            while(true){String header=line(in,4096);bytes+=header.length()+2;if(bytes>16384)throw new RequestFailure(431,"HTTP头过大");if(header.isEmpty())break;int colon=header.indexOf(':');if(colon<1)throw new RequestFailure(400,"HTTP头无效");String key=header.substring(0,colon).toLowerCase(Locale.ROOT),value=header.substring(colon+1).trim();if(headers.put(key,value)!=null)throw new RequestFailure(400,"重复HTTP头被拒绝");}
            if(headers.containsKey("origin"))throw new RequestFailure(403,"浏览器跨来源调用被禁用");
            String authorization=headers.get("authorization"),apiToken=headers.get("x-api-key"),googleToken=headers.get("x-goog-api-key");
            int credentials=(authorization==null?0:1)+(apiToken==null?0:1)+(googleToken==null?0:1);
            String supplied=authorization!=null&&authorization.startsWith("Bearer ")?authorization.substring(7):apiToken!=null?apiToken:googleToken;
            if(credentials!=1||supplied==null||!MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),supplied.getBytes(StandardCharsets.UTF_8)))throw new RequestFailure(401,"本地反代令牌无效或认证头冲突");
            if(request[0].equals("GET")&&request[1].equals("/v1/models")){
                if(headers.containsKey("transfer-encoding")||!headers.getOrDefault("content-length","0").equals("0"))throw new RequestFailure(400,"模型目录请求不能包含请求体");
                parsed.set(true);deadline.interrupt();ModelConfig config=currentConfiguration();
                if(config.usesAccount()){accountModels(socket,response,config);return;}
                JSONObject model=new JSONObject().put("id",config.routePlan==null?config.model:config.routePlan.requestedAlias).put("object","model").put("owned_by","configured-upstream");response.json(200,ChatCompletionStream.scrub(new JSONObject().put("object","list").put("data",new JSONArray().put(model)),config.apiKey).toString().getBytes(StandardCharsets.UTF_8));return;
            }
            if(!request[0].equals("POST")||!NativeRelayPolicy.acceptsPath(request[1]))throw new RequestFailure(404,"请使用本机已选模型协议的明确路径，可在本机服务页查看");
            if(headers.containsKey("transfer-encoding"))throw new RequestFailure(400,"当前不支持分块请求");
            if(headers.containsKey("content-encoding"))throw new RequestFailure(415,"当前不支持压缩请求体");
            int length;try{length=Integer.parseInt(headers.getOrDefault("content-length","-1"));}catch(NumberFormatException invalid){throw new RequestFailure(400,"Content-Length无效");}
            if(length<1)throw new RequestFailure(411,"请提供Content-Length");if(length>MAX_BODY)throw new RequestFailure(413,"请求超过2MiB");
            if(!headers.getOrDefault("content-type","").split(";",2)[0].trim().equalsIgnoreCase("application/json"))throw new RequestFailure(415,"请求需使用application/json");
            byte[] body=new byte[length];int offset=0;while(offset<length){int count=in.read(body,offset,length-offset);if(count<0)throw new RequestFailure(400,"请求体未传完");offset+=count;}
            JSONObject json;try{json=ChatCompletionStream.parseObject(StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(body)).toString());}catch(Exception invalid){throw new RequestFailure(400,"请求JSON或UTF-8无效");}
            if(json.has("stream")&&!(json.get("stream") instanceof Boolean))throw new RequestFailure(400,"stream需为布尔值");
            boolean streaming=json.optBoolean("stream",false);
            parsed.set(true);deadline.interrupt();ModelConfig config=currentConfiguration();
            if(config.usesAccount()){
                if(!request[1].equals("/v1/responses"))throw new RequestFailure(400,"账号路线请使用/v1/responses；不进行有损Chat Completions转换");
                if(length>ResponsesRequest.MAX_REQUEST_BYTES)throw new RequestFailure(413,"账号请求超过256KiB");
                accountResponse(socket,response,config,json);return;
            }
            NativeRelayPolicy.Prepared prepared;
            try{prepared=NativeRelayPolicy.prepare(config,request[1],json);}
            catch(NativeRelayPolicy.Rejected|ProxyRequestPolicy.Rejected|dev.pocketengine.runtime.providers.ProtocolCodec.Rejected|dev.pocketengine.runtime.models.ModelRequestPolicy.Rejected invalid){throw new RequestFailure(400,invalid.getMessage());}
            catch(org.json.JSONException|IllegalArgumentException malformed){throw new RequestFailure(400,"客户端协议字段或参数无法核对，未向上游发送");}
            preventSelf(prepared.endpoint);gatewayRelay(socket,response,config,prepared.gateway);
        }catch(InferenceAudiencePolicy.Rejected denied){try{response.failure(403,denied.getMessage());}catch(Exception ignored){}}
        catch(GatewayFailure failure){try{response.accountFailure(failure);}catch(Exception ignored){}}
        catch(dev.pocketengine.runtime.models.ModelRequestPolicy.Rejected changed){try{response.failure(409,"当前模型能力已变化；旧回复未作为完整结果返回，没有自动重试");}catch(Exception ignored){}}
        catch(RequestFailure failure){try{response.failure(failure.status,failure.getMessage());}catch(Exception ignored){}}
        catch(Exception failure){try{response.failure(failure instanceof java.io.InterruptedIOException?504:502,"反代请求失败、已停止或超时；未自动重试");}catch(Exception ignored){}}
        finally{deadline.interrupt();closeClient(socket);}
    }
    private void accountModels(Socket client,Response response,ModelConfig config)throws Exception {
        if(accounts==null)throw new GatewayFailure("ACCOUNT_ROUTE_UNAVAILABLE");
        try(AccountLifetime life=new AccountLifetime(client,config.requestTimeoutSeconds)){
            life.check();JSONArray catalog=accounts.models(config.accountModel,life.call);life.check();requireCurrent(config);
            JSONArray data=new JSONArray();
            for(int i=0;i<catalog.length();i++){
                JSONObject model=catalog.getJSONObject(i);
                if(config.model.equals(model.getString("id")))data.put(new JSONObject().put("id",config.model).put("object","model").put("created",0).put("owned_by",ModelConfig.ACCOUNT_PROVIDER));
            }
            if(data.length()!=1)throw new GatewayFailure("ACCOUNT_MODEL_UNAVAILABLE");
            byte[] result=new JSONObject().put("object","list").put("data",data).toString().getBytes(StandardCharsets.UTF_8);
            life.write(()->response.json(200,result));
        }
    }
    private void accountResponse(Socket client,Response response,ModelConfig config,JSONObject json)throws Exception {
        LoopbackResponses.Input input=LoopbackResponses.input(json,config.model);
        if(accounts==null)throw new GatewayFailure("ACCOUNT_ROUTE_UNAVAILABLE");
        try(AccountLifetime life=new AccountLifetime(client,config.requestTimeoutSeconds)){
            response.accountProtocol=new LoopbackResponses(config.model,input.streaming,new LoopbackResponses.Output(){
                public void event(JSONObject event)throws Exception {life.write(()->{if(!response.started)response.startStream();response.namedEvent(event);});}
                public void complete(JSONObject result)throws Exception {life.write(()->response.json(200,result.toString().getBytes(StandardCharsets.UTF_8)));}
            });
            try{
                life.check();requests.incrementAndGet();
                // Native port performs existing singleflight renewal/version persistence before inference.
                // There is no fallback, account selection, retry, phone dispatcher or tool executor here.
                accounts.stream(config.accountModel,input.request,life.call,event->{life.check();requireCurrent(config);response.accountProtocol.accept(event);});
                life.check();response.accountProtocol.requireCompleted();
            }catch(GatewayFailure failure){
                // Keep the same watchdog alive while sending an error; an unwritable client must
                // not strand a worker or hold the native account monitor through a network write.
                life.failure(response,failure);
            }catch(Exception failure){life.failure(response,new GatewayFailure("PROVIDER_STREAM_INTERRUPTED"));}
        }
    }
    private interface SocketWrite {void run()throws Exception;}
    /** Same operation reaches refresh and inference; downstream close also unblocks a stalled writer. */
    private final class AccountLifetime implements AutoCloseable {
        final GatewayCall call;final Socket client;final long expires;final Thread watcher;
        volatile boolean complete;volatile long writeStarted;
        AccountLifetime(Socket client,int seconds)throws Exception {
            this.client=client;call=new GatewayCall(seconds*1000L);expires=System.nanoTime()+seconds*1_000_000_000L;
            accountCalls.add(call);client.setSoTimeout(250);client.setSendBufferSize(16*1024);
            watcher=new Thread(()->{
                try{InputStream input=client.getInputStream();while(!complete&&!closed&&System.nanoTime()<expires){
                        long writing=writeStarted;if(writing!=0&&System.nanoTime()-writing>=TimeUnit.SECONDS.toNanos(2))break;
                        try{input.read();break;}catch(java.net.SocketTimeoutException waiting){}}
                    if(!complete){closeClient(client);call.cancel();}}
                catch(Exception cancelled){if(!complete){closeClient(client);call.cancel();}}
            },"pocket-proxy-account-lifetime");watcher.setDaemon(true);watcher.start();
        }
        void check()throws GatewayFailure {call.check();if(closed||client.isClosed())throw new GatewayFailure("CANCELLED");}
        void write(SocketWrite send)throws Exception {check();writeStarted=System.nanoTime();try{send.run();}finally{writeStarted=0;}}
        void failure(Response response,GatewayFailure failure){
            if(closed||client.isClosed()||System.nanoTime()>=expires){closeClient(client);return;}
            // Notification of a cancelled call is allowed; it cannot generate another model request.
            writeStarted=System.nanoTime();try{response.accountFailure(failure);}catch(Exception unwritable){closeClient(client);}finally{writeStarted=0;}
        }
        @Override public void close(){complete=true;watcher.interrupt();accountCalls.remove(call);call.cancel();}
    }
    private void gatewayRelay(Socket client,Response response,ModelConfig selected,dev.pocketengine.runtime.providers.GatewayRequest request)throws Exception {
        final dev.pocketengine.core.RunControl control=new dev.pocketengine.core.RunControl();
        final java.util.concurrent.atomic.AtomicBoolean complete=new java.util.concurrent.atomic.AtomicBoolean();
        long expires=System.nanoTime()+selected.requestTimeoutSeconds*1_000_000_000L;client.setSoTimeout(250);apiControls.add(control);
        Thread watchdog=new Thread(()->{try{InputStream input=client.getInputStream();while(!complete.get()&&!closed&&System.nanoTime()<expires){try{input.read();break;}catch(java.net.SocketTimeoutException waiting){}}
            if(!complete.get()){control.cancel();closeClient(client);}}catch(Exception stopped){if(!complete.get()){control.cancel();closeClient(client);}}},"pocket-gateway-client-lifetime");watchdog.setDaemon(true);watchdog.start();
        dev.pocketengine.runtime.usage.NativeInferenceWork work=null;
        try{
            requireInferenceCurrent(selected,request.endpoint);request.revalidate();
            if(inferenceFactory!=null)work=inferenceFactory.open(selected,control,sessionId+":"+java.util.UUID.randomUUID());
            var target=work==null?dev.pocketengine.runtime.usage.InferenceCapture.unattributed():work.target();
            // Admission and every physical receipt are still owned by the original native target.
            var observed=dev.pocketengine.runtime.usage.InferenceCapture.observe(target,new dev.pocketengine.runtime.usage.InferenceCapture.Sink(){
                public void started(dev.pocketengine.runtime.usage.InferenceReceipt receipt){requests.incrementAndGet();}
                public void finished(dev.pocketengine.runtime.usage.InferenceReceipt receipt){}
            });
            var admitted=dev.pocketengine.runtime.usage.InferenceCapture.withAdmission(observed,()->requireInferenceCurrent(selected,request.endpoint));
            JSONObject result=dev.pocketengine.runtime.ModelGateway.completeNative(selected,request,control,admitted,
                (callId,seq)->{if(closed||client.isClosed()||System.nanoTime()>=expires)throw new java.io.IOException("relay stopped");requireInferenceCurrent(selected,request.endpoint);},
                (event,data)->{if(closed||client.isClosed()||System.nanoTime()>=expires)throw new java.io.IOException("relay stopped");requireInferenceCurrent(selected,request.endpoint);
                    if(!response.started){response.nativeProtocol=request.ingressProtocol;response.startStream();}
                    response.nativeFrame(event,data);
                });
            if(request.stream)response.nativeCompleted=true;else{requireInferenceCurrent(selected,request.endpoint);response.json(200,result.toString().getBytes(StandardCharsets.UTF_8));}
            if(work!=null)work.completed();
        }catch(dev.pocketengine.runtime.providers.ProtocolGateway.Failure failure){if(work!=null)work.failed(failure);throw new RequestFailure(failure.observation().httpStatus>=400&&failure.observation().httpStatus<=599?failure.observation().httpStatus:502,"上游请求失败；没有自动换号或重放");}
        catch(dev.pocketengine.runtime.ModelGateway.GatewayException failure){if(work!=null)work.failed(failure);var observed=failure.observation();if(observed!=null&&observed.httpStatus>=400&&observed.httpStatus<=599)throw new RequestFailure(observed.httpStatus,"上游请求失败；没有自动换号或重放");throw failure;}
        catch(Exception failure){if(work!=null)try{work.failed(failure);}catch(Exception storage){failure.addSuppressed(storage);}throw failure;}
        finally{complete.set(true);watchdog.interrupt();apiControls.remove(control);control.cancel();if(work!=null)work.close();}
    }
    private static final class Response {
        private final Socket socket;
        private boolean started,streaming;
        private LoopbackResponses accountProtocol;
        private String nativeProtocol="";private boolean nativeCompleted;
        Response(Socket socket){this.socket=socket;}
        void json(int status,byte[] body)throws Exception{if(started)return;started=true;respond(socket,status,body);}
        void startStream()throws Exception{
            started=true;streaming=true;
            String headers="HTTP/1.1 200 OK\r\nContent-Type: text/event-stream; charset=utf-8\r\nConnection: close\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nX-Accel-Buffering: no\r\n\r\n";
            OutputStream out=socket.getOutputStream();out.write(headers.getBytes(StandardCharsets.US_ASCII));out.flush();
        }
        void data(String json)throws Exception{OutputStream out=socket.getOutputStream();out.write(("data: "+json+"\n\n").getBytes(StandardCharsets.UTF_8));out.flush();}
        void nativeFrame(String event,String data)throws Exception{OutputStream out=socket.getOutputStream();out.write(((event.isEmpty()?"":"event: "+event+"\n")+"data: "+data+"\n\n").getBytes(StandardCharsets.UTF_8));out.flush();}
        void nativeEvent(String event,JSONObject data)throws Exception{nativeFrame(event,data.toString());}
        void namedEvent(JSONObject event)throws Exception{OutputStream out=socket.getOutputStream();out.write(("event: "+event.getString("type")+"\ndata: "+event+"\n\n").getBytes(StandardCharsets.UTF_8));out.flush();}
        void accountFailure(GatewayFailure failure)throws Exception {
            if(!started)json(LoopbackResponses.status(failure),LoopbackResponses.error(failure).toString().getBytes(StandardCharsets.UTF_8));
            else if(streaming&&accountProtocol!=null&&!accountProtocol.isCompleted())namedEvent(accountProtocol.failure(failure));
        }
        void failure(int status,String message)throws Exception{
            if(!started)json(status,error(message));
            else if(streaming&&!nativeCompleted&&(accountProtocol==null||!accountProtocol.isCompleted())){
                JSONObject failure=new JSONObject(new String(error(message),StandardCharsets.UTF_8));
                if(nativeProtocol.equals("anthropic"))failure=new JSONObject().put("type","error").put("error",new JSONObject().put("type","api_error").put("message",message));
                else if(nativeProtocol.equals("openai_responses"))failure.put("type","error");
                nativeEvent(nativeProtocol.equals("anthropic")||nativeProtocol.equals("openai_responses")?"error":"",failure);
            }
            // Never emit [DONE] on failure and never write a second HTTP response.
        }
    }
    private void preventSelf(URL target)throws Exception{String host=target.getHost();if((host.equals("127.0.0.1")||host.equalsIgnoreCase("localhost")||host.equals("[::1]"))&&target.getPort()==port())throw new RequestFailure(508,"不能将本机服务指向自身");}
    private static URL endpoint(String base)throws Exception {URI uri=new URI(base);if(uri.getHost()==null||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null||(!"https".equalsIgnoreCase(uri.getScheme())&&!"http".equalsIgnoreCase(uri.getScheme())))throw new RequestFailure(503,"上游地址无效");String path=uri.getPath()==null?"":uri.getPath();while(path.endsWith("/"))path=path.substring(0,path.length()-1);if(path.isEmpty())path="/v1";if(!path.endsWith("/chat/completions"))path+="/chat/completions";return new URI(uri.getScheme(),null,uri.getHost(),uri.getPort(),path,null,null).toURL();}
    private static String line(InputStream in,int limit)throws Exception {ByteArrayOutputStream result=new ByteArrayOutputStream();int previous=-1;while(result.size()<=limit){int next=in.read();if(next<0)throw new RequestFailure(400,"HTTP请求未完成");if(previous=='\r'&&next=='\n'){byte[] bytes=result.toByteArray();return new String(bytes,0,bytes.length-1,StandardCharsets.US_ASCII);}if(next=='\n')throw new RequestFailure(400,"HTTP行格式无效");result.write(next);previous=next;}throw new RequestFailure(431,"HTTP行过长");}
    private static byte[] error(String text)throws Exception{return new JSONObject().put("error",new JSONObject().put("message",text).put("type","pocketengine_proxy_error")).toString().getBytes(StandardCharsets.UTF_8);}
    private static void respond(Socket socket,int status,byte[] body)throws Exception {OutputStream out=socket.getOutputStream();String header="HTTP/1.1 "+status+" "+(status==200?"OK":"Error")+"\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: "+body.length+"\r\nConnection: close\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\n\r\n";out.write(header.getBytes(StandardCharsets.US_ASCII));out.write(body);out.flush();}
    private void closeClient(Socket client){clients.remove(client);try{client.close();}catch(Exception ignored){}}
    @Override public void close(){closed=true;try{listener.close();}catch(Exception ignored){}for(Socket client:clients)closeClient(client);for(dev.pocketengine.core.RunControl control:apiControls)control.cancel();for(GatewayCall call:accountCalls)call.cancel();workers.shutdownNow();}
    private static final class RequestFailure extends Exception {final int status;RequestFailure(int status,String text){super(text);this.status=status;}}
}
