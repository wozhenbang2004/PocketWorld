package dev.pocketengine.browser;

import dev.pocketengine.core.RunControl;
import java.io.ByteArrayOutputStream;
import java.io.FilterReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;
import okhttp3.Authenticator;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.ConnectionPool;
import okhttp3.CookieJar;
import okhttp3.Dispatcher;
import okhttp3.Dns;
import okhttp3.EventListener;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.parser.Parser;
import org.jsoup.select.NodeVisitor;

/** Public, anonymous GET only. No WebView/Model/Profile cookie, header or authentication route is reachable. */
public final class PublicHttpReader {
    public static final String TOOL="browser_task_read_public";
    public static final int DEFAULT_BYTES=256*1024,MAX_BYTES=512*1024,MAX_CHARS=6000;
    private static final int MAX_HEADER_BYTES=32*1024;
    private static final Set<String> MIMES=Set.of("text/html","application/xhtml+xml","text/plain","text/markdown","application/json","application/ld+json","application/xml","text/xml");
    private static final Set<String> CREDENTIAL_KEYS=Set.of("access_token","refresh_token","id_token","api_key","apikey","password","passwd","authorization","session","sessionid","cookie","client_secret","auth","token");
    private static final OkHttpClient BASE;
    // A timed-out platform DNS call cannot be forcibly killed. Keep its permit until its
    // callback really exits, so late DNS cannot create unbounded background workers.
    private static final Semaphore IN_FLIGHT=new Semaphore(2);
    /** Native I/O only: not a task runner, model turn, grant or independent journal. No queued backlog. */
    private static final ThreadPoolExecutor NATIVE_READS=new ThreadPoolExecutor(0,2,30,TimeUnit.SECONDS,new SynchronousQueue<>(),work->{Thread thread=new Thread(work,"PocketWorld-public-http");thread.setDaemon(true);return thread;});
    static {
        Dispatcher dispatcher=new Dispatcher();dispatcher.setMaxRequests(2);dispatcher.setMaxRequestsPerHost(1);
        BASE=new OkHttpClient.Builder().dispatcher(dispatcher).connectionPool(new ConnectionPool(0,1,TimeUnit.MILLISECONDS))
                .cookieJar(CookieJar.NO_COOKIES).authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE).proxy(Proxy.NO_PROXY)
                .protocols(java.util.Collections.singletonList(Protocol.HTTP_1_1)) // bounded HTTP/1 headers; no HPACK/coalesced connection path
                .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).cache(null)
                .connectTimeout(3,TimeUnit.SECONDS).readTimeout(3,TimeUnit.SECONDS).callTimeout(8,TimeUnit.SECONDS).build();
    }
    public static final class Budget {
        private final long deadline,revision;
        private final RunControl control;
        private final AtomicBoolean nativeScopeStopped=new AtomicBoolean();
        public Budget(RunControl control){this(control,8000);}
        Budget(RunControl control,long millis){if(millis<0||millis>8000)throw new IllegalArgumentException("读取预算无效");this.control=java.util.Objects.requireNonNull(control);revision=control.pauseRevision();deadline=System.nanoTime()+millis*1_000_000L;}
        public boolean boundTo(RunControl supplied){return control==supplied;}
        void stopNativeScope(){nativeScopeStopped.set(true);}
        public void check()throws Exception{checkInsideStore();control.check();checkInsideStore();}
        void admit(BrowserTaskSpace.Gate gate)throws Exception{check();gate.check();checkInsideStore();}
        /** No UI callback or authority callback under TaskStore's monitor. Its current grant/epoch check stays separate. */
        public void checkInsideStore()throws Exception {
            if(nativeScopeStopped.get())throw new SecurityException("原只读范围已撤销");
            if(control.isCancelled()||Thread.currentThread().isInterrupted())throw new InterruptedException("公共读取已停止");
            if(control.isPaused()||control.pauseRevision()!=revision)throw new SecurityException("公共读取的原控制已变化");
            if(System.nanoTime()>=deadline)throw new TimeoutException("公共读取预算已到");
        }
        long remainingMs()throws Exception{check();return Math.max(1,(deadline-System.nanoTime())/1_000_000L);}
        long remainingWithoutCallback()throws Exception{checkInsideStore();return Math.max(1,(deadline-System.nanoTime())/1_000_000L);}
    }
    public static final class Result {
        public final JSONObject value;
        public final Budget budget;
        Result(JSONObject value,Budget budget){this.value=value;this.budget=budget;}
        public boolean readable(){return "read".equals(value.optString("status"));}
    }
    /** Even blocked platform DNS / SQLite / native authority cannot keep the calling tool waiting past its budget.
     * The same original gates run inside the worker before every effect; late workers retain their bounded slot,
     * are interrupted, and must pass the original budget again before connection, source commit or publication. */
    static JSONObject bounded(Budget budget,Callable<JSONObject> nativeRead)throws Exception {
        budget.checkInsideStore();Future<JSONObject> pending;
        try{pending=NATIVE_READS.submit(nativeRead);}catch(java.util.concurrent.RejectedExecutionException busy){throw new IllegalStateException("前次公共读取的原生调用仍待退出，未启动新的读取",busy);}
        try{for(;;){budget.checkInsideStore();try{JSONObject actual=pending.get(Math.min(50,budget.remainingWithoutCallback()),TimeUnit.MILLISECONDS);budget.checkInsideStore();return actual;}
            catch(TimeoutException waiting){budget.checkInsideStore();}catch(ExecutionException failed){Throwable cause=failed.getCause();if(cause instanceof Exception)throw(Exception)cause;throw new IllegalStateException("公共读取原生回执不可用",cause);}}}
        finally{if(!pending.isDone())pending.cancel(true);}
    }
    /** Validate before approval and again before the first request. Native identities are never accepted here. */
    public static void validate(JSONObject args)throws Exception {
        if(args==null)throw new IllegalArgumentException("需要公共URL");url(args.getString("url"));
        if(args.has("maxBytes"))integer(args,"maxBytes",4096,MAX_BYTES);
        if(args.has("maxChars"))integer(args,"maxChars",64,MAX_CHARS);
        if(args.has("allowedRedirectOrigins")){
            JSONArray origins=args.optJSONArray("allowedRedirectOrigins");if(origins==null||origins.length()>3)throw new IllegalArgumentException("最多明确批准3个重定向origin");
            for(int i=0;i<origins.length();i++){String raw=origins.getString(i);HttpUrl parsed=url(raw);if(!raw.equals(origin(parsed)))throw new IllegalArgumentException("重定向批准必须是精确scheme://host[:port] origin");}
        }
    }
    public static JSONObject approvalPolicy(JSONObject args)throws Exception {
        validate(args);HttpUrl initial=url(args.getString("url"));JSONArray allowed=args.optJSONArray("allowedRedirectOrigins");
        return new JSONObject().put("sourceScope","public_anonymous_get").put("initialOrigin",origin(initial))
                .put("requestUrl",request(initial).url().toString()).put("sameOriginRedirects",true)
                .put("approvedRedirectOrigins",allowed==null?new JSONArray():new JSONArray(allowed.toString()))
                .put("sameHostHttpsUpgrade",true).put("httpsDowngradeAllowed",false).put("maxRedirects",3)
                .put("maxBodyBytes",args.optInt("maxBytes",DEFAULT_BYTES)).put("maxProjectedChars",args.optInt("maxChars",MAX_CHARS))
                .put("deadlineMs",8000).put("maxHeaderBytes",MAX_HEADER_BYTES).put("acceptedContentEncoding","identity_only").put("profileCredentialsUsed",false).put("systemProxyCredentialsUsed",false)
                .put("privateNetworkAllowed",false).put("rendered",false);
    }
    public Result read(JSONObject args,Budget budget,BrowserTaskSpace.Gate gate,RunControl control)throws Exception {
        if(budget==null||!budget.boundTo(control))throw new SecurityException("公共读取必须使用同一个原控制与native预算");
        validate(args);HttpUrl initial=url(args.getString("url")),current=initial;Set<String> approved=new LinkedHashSet<>();approved.add(origin(initial));
        JSONArray allowed=args.optJSONArray("allowedRedirectOrigins");if(allowed!=null)for(int i=0;i<allowed.length();i++)approved.add(allowed.getString(i));
        JSONArray redirects=new JSONArray();AtomicBoolean entered=new AtomicBoolean();JSONObject facts=null;
        for(int hop=0;hop<=3;hop++){
            budget.admit(gate);final HttpUrl target=current;Set<InetAddress> validated=java.util.concurrent.ConcurrentHashMap.newKeySet();
            if(target.host().contains(":")||target.host().matches("[0-9.]+")){
                InetAddress literal=literalAddress(target.host());if(!publicAddress(literal))return failed("rejected","non_public_address",false,budget,null);
                validated.add(literal);
            }
            OkHttpClient client=BASE.newBuilder().connectionPool(new ConnectionPool(0,1,TimeUnit.MILLISECONDS)).dns(host->{
                try{budget.admit(gate);if(!host.equals(target.host()))throw new AddressRejected();
                    List<InetAddress> addresses=admitAddresses(Dns.SYSTEM.lookup(host));
                    budget.admit(gate);validated.addAll(addresses);return addresses; // OkHttp connects this very address list, not a second DNS lookup.
                }catch(UnknownHostException failed){throw failed;}catch(Exception stopped){UnknownHostException failure=new UnknownHostException("Public DNS admission ended");failure.initCause(stopped);throw failure;}
            }).eventListener(new EventListener(){
                @Override public void connectStart(Call call,InetSocketAddress address,Proxy proxy){
                    try{budget.admit(gate);if(!connectionAdmitted(address,proxy,validated))throw new SecurityException("Unvalidated public connection address");}
                    catch(Exception stopped){call.cancel();throw new SecurityException("Public connection admission ended",stopped);}
                }
            }).callTimeout(budget.remainingMs(),TimeUnit.MILLISECONDS).build();
            Request request=request(target);
            Call call=client.newCall(request);
            if(!IN_FLIGHT.tryAcquire())return failed("unavailable","public_http_native_calls_pending",false,budget,null);
            CompletableFuture<Response> response=new CompletableFuture<>();Runnable stop=call::cancel;control.onCancel(stop);
            AtomicBoolean callbackDone=new AtomicBoolean(),consumerDone=new AtomicBoolean(),released=new AtomicBoolean(),queued=new AtomicBoolean();
            Runnable release=()->{if(callbackDone.get()&&consumerDone.get()&&released.compareAndSet(false,true))IN_FLIGHT.release();};
            try{
                budget.admit(gate);call.timeout().timeout(budget.remainingMs(),TimeUnit.MILLISECONDS);
                control.dispatchAtPauseRevision(budget.revision,()->{
                    budget.checkInsideStore();gate.entered();entered.set(true);
                    call.enqueue(new Callback(){public void onFailure(Call ignored,IOException failure){try{response.completeExceptionally(failure);}finally{callbackDone.set(true);release.run();}}public void onResponse(Call ignored,Response value){try{if(!response.complete(value))value.close();}finally{callbackDone.set(true);release.run();}}});queued.set(true);
                });
                try(Response received=await(response,budget)){
                    budget.admit(gate);int status=received.code();
                    facts=facts(received,args,redirects);
                    if(!headersWithinBudget(received,budget))return failed("unavailable","response_headers_budget",true,budget,facts);
                    if(Set.of(301,302,303,307,308).contains(status)){
                        String location=received.header("Location");if(location==null||hop==3)return failed("unavailable","redirect_limit_or_missing_location",true,budget,facts);
                        try{validateLocation(location);}catch(IllegalArgumentException invalid){return failed("rejected","restricted_redirect",true,budget,facts);}
                        HttpUrl resolved=request.url().resolve(location);if(resolved==null)return failed("rejected","invalid_redirect",true,budget,facts);
                        HttpUrl next;try{next=url(resolved.toString());}catch(IllegalArgumentException invalid){return failed("rejected","restricted_redirect",true,budget,facts);}
                        if(target.isHttps()&&!next.isHttps())return failed("rejected","https_downgrade",true,budget,facts);
                        if(!redirectAllowed(target,next,approved))
                            return failed("redirect_approval_required","redirect_origin_not_approved",true,budget,facts.put("proposedOrigin",origin(next)));
                        redirects.put(new JSONObject().put("fromOrigin",origin(target)).put("toOrigin",origin(next)).put("statusCode",status));current=next;continue;
                    }
                    return consume(received,args,facts,budget,gate);
                }
            }catch(InterruptedException stopped){throw stopped;}
            catch(Exception failure){
                if(control.isCancelled()||Thread.currentThread().isInterrupted())throw new InterruptedException("公共读取已停止");
                if(hasAddressRejection(failure))return failed("rejected","non_public_address",facts==null?false:true,budget,facts);
                return failed(entered.get()?"unknown":"unavailable",entered.get()?"public_http_read_unknown":"public_http_unavailable",entered.get()?null:false,budget,facts);
            }finally{control.removeCancelAction(stop);call.cancel();if(!response.isDone())response.cancel(false);else if(!response.isCompletedExceptionally()){Response unused=response.getNow(null);if(unused!=null)unused.close();}client.connectionPool().evictAll();consumerDone.set(true);if(!queued.get())callbackDone.set(true);release.run();}
        }
        return failed("unavailable","redirect_limit",true,budget,facts);
    }
    static JSONObject facts(Response received,JSONObject args,JSONArray redirects)throws Exception {
        return new JSONObject().put("requestedUrl",args.getString("url")).put("finalUrl",received.request().url().toString()).put("statusCode",received.code())
                .put("retrievedAt",System.currentTimeMillis()).put("redirects",new JSONArray(redirects.toString())).put("bodyBytesRead",0)
                .put("bodyByteLimit",args.optInt("maxBytes",DEFAULT_BYTES)).put("bodyComplete",false).put("profileCredentialsUsed",false).put("systemProxyCredentialsUsed",false)
                .put("credentialHeadersSent",false).put("method","GET").put("rendered",false).put("connectAddressValidated",true);
    }
    /** Called only after the native public-address connection admission. Package scope supports bounded body/parser fixtures, not another transport. */
    static Result consume(Response received,JSONObject args,JSONObject facts,Budget budget,BrowserTaskSpace.Gate gate)throws Exception {
        budget.admit(gate);int status=received.code(),limit=args.optInt("maxBytes",DEFAULT_BYTES),chars=args.optInt("maxChars",MAX_CHARS);
        if(!headersWithinBudget(received,budget))return failed("unavailable","response_headers_budget",true,budget,facts);
        if(status==401||status==403)return failed("needs_webview","login_or_access_required",true,budget,facts);
        if(status<200||status>=300)return failed("unavailable","http_status",true,budget,facts);
        ResponseBody body=received.body();String contentType=received.header("Content-Type","");String mime=contentType.split(";",2)[0].trim().toLowerCase(java.util.Locale.ROOT);facts.put("mime",mime);
        if(body==null||!MIMES.contains(mime))return failed("needs_webview","unsupported_or_missing_mime",true,budget,facts);
        if(!"identity".equalsIgnoreCase(received.header("Content-Encoding","identity").trim()))return failed("needs_webview","unsupported_content_encoding",true,budget,facts);
        Charset charset=StandardCharsets.UTF_8;
        if(contentType.toLowerCase(java.util.Locale.ROOT).matches("(?s).*;\\s*charset\\s*=.*")){
            charset=body.contentType()==null?null:body.contentType().charset(null);
            if(charset==null)return failed("needs_webview","unsupported_charset",true,budget,facts);
        }
        facts.put("charset",charset.name());ByteArrayOutputStream bytes=new ByteArrayOutputStream();boolean eof=false;
        try(InputStream stream=body.byteStream()){
            byte[] block=new byte[8192];
            while(bytes.size()<limit){
                budget.admit(gate);int n=stream.read(block,0,Math.min(block.length,limit-bytes.size()));
                if(n<0){eof=true;break;}if(n==0)throw new IOException("No public response progress");bytes.write(block,0,n);
            }
        }
        budget.admit(gate);boolean complete=eof||body.contentLength()>=0&&bytes.size()==body.contentLength();
        byte[] data=bytes.toByteArray();facts.put("bodyBytesRead",data.length).put("bodyComplete",complete).put("bodySha256",sha256(data))
                .put("bodyTruncated",!complete).put("byteCounting","uncompressed_identity_response_body");
        String decoded;
        try{decoded=decode(data,charset,!complete);}catch(java.nio.charset.CharacterCodingException malformed){return failed("needs_webview","text_decode_incomplete",true,budget,facts);}
        Projection projection=project(decoded,mime,received.request().url().toString(),budget);budget.admit(gate);
        if(projection.reason!=null)return failed("needs_webview",projection.reason,true,budget,facts);
        String text=projection.text;boolean clipped=text.length()>chars;text=clip(text,chars);
        facts.put("projection",htmlMime(mime)?"server_dom_text_not_rendered":"unrendered_text_body").put("projectionTruncated",clipped).put("projectedChars",text.length());
        return new Result(new JSONObject().put("ok",true).put("accepted",true).put("status","read").put("observationId",UUID.randomUUID().toString())
                .put("title",clip(projection.title,240)).put("text",text).put("http",facts).put("verified",false).put("verifiedBusinessSuccess",false)
                .put("responseBodyComplete",complete).put("projectionTruncated",clipped).put("rendered",false)
                .put("automaticReplayAllowed",false).put("untrustedPageContent",true),budget);
    }

    static Response await(CompletableFuture<Response> future,Budget budget)throws Exception {
        while(true){budget.check();try{return future.get(Math.min(50,budget.remainingMs()),TimeUnit.MILLISECONDS);}catch(TimeoutException waiting){budget.check();}catch(ExecutionException failed){Throwable cause=failed.getCause();if(cause instanceof Exception)throw(Exception)cause;throw new IOException("Public response failed",cause);}}
    }
    static Result failed(String status,String reason,Boolean accepted,Budget budget,JSONObject facts)throws Exception {
        JSONObject out=new JSONObject().put("ok",false).put("accepted",accepted==null?JSONObject.NULL:accepted).put("status",status).put("code",reason)
                .put("readComplete",false).put("verified",false).put("verifiedBusinessSuccess",false).put("automaticReplayAllowed",false);
        if(facts!=null)out.put("http",facts);
        if(status.equals("needs_webview"))out.put("nextUrl",facts==null?JSONObject.NULL:facts.opt("finalUrl")).put("nextTool","browser_task_open").put("message","公共HTTP没有取得可引用的目标正文；请在原任务调用browser_task_open打开已核URL。需要登录时请browser_task_request_user，交还后沿原链重新观察。");
        else if(status.equals("redirect_approval_required"))out.put("message","跳转origin未在本次审批内，未请求该目的地；请明确批准新的公共URL或redirect origin。");
        else out.put("message",status.equals("unknown")?"公共读取未取得确定回执；未声称读完，也未自动重试或切换浏览后端。":"公共读取受到网络、来源或预算边界限制；没有把失败页面当成完整来源，也不会借WebView回落绕过受限地址。");
        return new Result(out,budget);
    }
    static final class Projection {final String title,text,reason;Projection(String title,String text,String reason){this.title=title;this.text=text;this.reason=reason;}}
    static Projection project(String source,String mime,String url,Budget budget)throws Exception {
        budget.check();if(!htmlMime(mime))return new Projection("",source,source.trim().isEmpty()?"empty_body":null);
        Parser parser=Parser.htmlParser();parser.setMaxDepth(128);
        Reader checked=new FilterReader(new StringReader(source)){@Override public int read(char[] b,int off,int len)throws IOException{try{budget.check();}catch(Exception stopped){throw new IOException("Public parsing admission ended",stopped);}return super.read(b,off,Math.min(len,4096));}};
        Document document=parser.parseInput(checked,url);budget.check();
        AtomicBoolean depthLimited=new AtomicBoolean();int[] visited={0};
        document.traverse(new NodeVisitor(){public void head(Node node,int depth){if(depth>=127)depthLimited.set(true);if((visited[0]++&255)==0)try{budget.check();}catch(Exception stopped){throw new java.io.UncheckedIOException(new IOException("Public DOM projection admission ended",stopped));}}});
        if(depthLimited.get())return new Projection(document.title(),"","html_structure_budget");
        if(!document.select("input[type=password],input[autocomplete=one-time-code]").isEmpty())return new Projection(document.title(),"","login_form");
        boolean scripts=!document.select("script").isEmpty();document.select("script,style,noscript,template").remove();
        Element main=document.selectFirst("main,article,[role=main]");if(main==null||main.text().trim().isEmpty())main=document.body();
        String text=main==null?"":main.text();budget.check();
        return new Projection(document.title(),text,text.trim().isEmpty()?"empty_server_dom":scripts&&text.length()<80?"low_server_text_requires_rendering":null);
    }
    static HttpUrl url(String raw){
        if(raw==null||raw.length()>2048||raw.codePoints().anyMatch(Character::isISOControl))throw new IllegalArgumentException("公共URL无效");
        if(!raw.equals(raw.trim()))throw new IllegalArgumentException("公共URL不得包含外围空白");
        try{if(new java.net.URI(raw).getRawUserInfo()!=null)throw new IllegalArgumentException("公共URL不得包含userinfo");}catch(java.net.URISyntaxException invalid){throw new IllegalArgumentException("公共URL无效",invalid);}
        HttpUrl parsed=HttpUrl.parse(raw);if(parsed==null||!parsed.username().isEmpty()||!parsed.password().isEmpty()||parsed.port()!=(parsed.isHttps()?443:80))throw new IllegalArgumentException("公共URL不得带userinfo、凭据或非标准端口");
        for(String key:parsed.queryParameterNames())if(CREDENTIAL_KEYS.contains(key.toLowerCase(java.util.Locale.ROOT)))throw new IllegalArgumentException("公共读取不接受凭据查询参数");
        if(parsed.host().equalsIgnoreCase("localhost")||parsed.host().endsWith(".localhost")||parsed.host().endsWith(".local"))throw new IllegalArgumentException("公共读取不接受本机网络名称");
        if(parsed.host().contains(":")||parsed.host().matches("[0-9.]+"))try{if(!publicAddress(literalAddress(parsed.host())))throw new IllegalArgumentException("公共URL地址不可用");}catch(UnknownHostException invalid){throw new IllegalArgumentException("公共URL地址无效",invalid);}
        return parsed;
    }
    private static InetAddress literalAddress(String host)throws UnknownHostException {
        // Never perform hostname DNS during argument validation (which precedes the timed read).
        // HttpUrl has already validated IPv6 syntax; ambiguous/legacy IPv4 spellings stay closed.
        if(host.contains(":"))return InetAddress.getByName(host);
        String[] parts=host.split("\\.",-1);if(parts.length!=4)throw new UnknownHostException("Canonical IPv4 required");byte[] address=new byte[4];
        for(int i=0;i<4;i++){if(!parts[i].matches("0|[1-9][0-9]{0,2}"))throw new UnknownHostException("Canonical IPv4 required");int value=Integer.parseInt(parts[i]);if(value>255)throw new UnknownHostException("Invalid IPv4");address[i]=(byte)value;}
        return InetAddress.getByAddress(address);
    }
    static String origin(HttpUrl url){return url.scheme()+"://"+(url.host().contains(":")?"["+url.host()+"]":url.host())+(url.port()==(url.isHttps()?443:80)?"":":"+url.port());}
    static boolean publicAddress(InetAddress address){
        if(address==null||address.isAnyLocalAddress()||address.isLoopbackAddress()||address.isLinkLocalAddress()||address.isSiteLocalAddress()||address.isMulticastAddress())return false;
        byte[] raw=address.getAddress();int a=raw[0]&255,b=raw[1]&255;
        if(raw.length==4)return a!=0&&a!=10&&a!=127&&a<224&&!(a==100&&b>=64&&b<=127)&&!(a==169&&b==254)&&!(a==172&&b>=16&&b<=31)
                &&!(a==192&&(b==168||b==0||b==2||b==88&&((raw[2]&255)==99)))&&!(a==198&&(b==18||b==19||b==51&&(raw[2]&255)==100))&&!(a==203&&b==0&&(raw[2]&255)==113);
        if(raw.length!=16||(a&0xfe)==0xfc||a==0xff)return false;
        if(a==0x20&&b==0x01&&((raw[2]&255)==0x0d&&(raw[3]&255)==0xb8||(raw[2]&0xfe)==0))return false;
        if(a==0x3f&&b==0xff&&(raw[2]&0xf0)==0)return false; // RFC9637 documentation prefix, not a public source.
        if(a==0x00&&b==0x64&&raw[2]==(byte)0xff&&raw[3]==(byte)0x9b){
            for(int i=4;i<12;i++)if(raw[i]!=0)return false;
            try{return publicAddress(InetAddress.getByAddress(java.util.Arrays.copyOfRange(raw,12,16)));}catch(UnknownHostException impossible){return false;}
        }
        if(a==0x20&&b==0x02)try{return publicAddress(InetAddress.getByAddress(java.util.Arrays.copyOfRange(raw,2,6)));}catch(UnknownHostException impossible){return false;}
        return (a&0xe0)==0x20; // Conservative global-unicast-only IPv6 policy; unsupported special ranges remain closed.
    }
    static boolean supportedMime(String mime){return MIMES.contains(mime);}
    static boolean htmlMime(String mime){return mime.equals("text/html")||mime.equals("application/xhtml+xml");}
    static Request request(HttpUrl target){
        return new Request.Builder().url(target.newBuilder().fragment(null).build()).get()
                .header("Accept","text/html, application/xhtml+xml, text/plain, text/markdown, application/json, application/xml")
                .header("Accept-Encoding","identity").header("Cache-Control","no-store").header("User-Agent","PocketWorld-PublicRead/1").build();
    }
    private static boolean headersWithinBudget(Response response,Budget budget)throws Exception {
        long bytes=0;for(int i=0;i<response.headers().size();i++){budget.check();bytes+=response.headers().name(i).getBytes(StandardCharsets.UTF_8).length+response.headers().value(i).getBytes(StandardCharsets.UTF_8).length+4;if(bytes>MAX_HEADER_BYTES)return false;}return true;
    }
    static List<InetAddress> admitAddresses(List<InetAddress> addresses)throws UnknownHostException {
        if(addresses==null||addresses.isEmpty()||addresses.size()>16)throw new AddressRejected();
        for(InetAddress address:addresses)if(!publicAddress(address))throw new AddressRejected();
        return java.util.Collections.unmodifiableList(new java.util.ArrayList<>(addresses));
    }
    static boolean connectionAdmitted(InetSocketAddress address,Proxy proxy,Set<InetAddress> validated){
        return proxy.type()==Proxy.Type.DIRECT&&!address.isUnresolved()&&publicAddress(address.getAddress())&&validated.contains(address.getAddress());
    }
    static boolean redirectAllowed(HttpUrl from,HttpUrl next,Set<String> approved){
        if(from.isHttps()&&!next.isHttps())return false;
        return approved.contains(origin(next))||origin(from).equals(origin(next))||!from.isHttps()&&next.isHttps()&&from.host().equals(next.host())&&next.port()==443;
    }
    static void requireRetainedPolicy(JSONObject args,JSONObject facts,String excerpt)throws Exception {
        validate(args);HttpUrl current=url(args.getString("url"));Set<String> approved=new LinkedHashSet<>();approved.add(origin(current));
        JSONArray allowed=args.optJSONArray("allowedRedirectOrigins");if(allowed!=null)for(int i=0;i<allowed.length();i++)approved.add(allowed.getString(i));
        if(!args.getString("url").equals(facts.getString("requestedUrl"))||facts.getInt("bodyByteLimit")!=args.optInt("maxBytes",DEFAULT_BYTES)
                ||excerpt.length()>args.optInt("maxChars",MAX_CHARS))throw new SecurityException("公共来源不属于原已批URL与读取预算");
        JSONArray redirects=facts.getJSONArray("redirects");if(redirects.length()>3)throw new SecurityException("公共来源重定向过多");
        for(int i=0;i<redirects.length();i++){
            JSONObject hop=redirects.getJSONObject(i);if(hop.length()!=3||!hop.has("fromOrigin")||!hop.has("toOrigin")||!hop.has("statusCode")
                    ||!(hop.get("statusCode") instanceof Number)||!Set.of(301,302,303,307,308).contains(hop.get("statusCode")))throw new SecurityException("公共来源重定向字段无效");
            HttpUrl from=url(hop.getString("fromOrigin")),next=url(hop.getString("toOrigin"));
            if(!origin(from).equals(hop.getString("fromOrigin"))||!origin(next).equals(hop.getString("toOrigin"))||!origin(current).equals(origin(from))||!redirectAllowed(from,next,approved))throw new SecurityException("公共来源跳转不属于原已批origin链");
            current=next;
        }
        if(!origin(current).equals(origin(url(facts.getString("finalUrl")))))throw new SecurityException("公共来源最终地址与原跳转链不符");
    }
    private static void validateLocation(String location){
        if(location.length()>2048||location.codePoints().anyMatch(Character::isISOControl))throw new IllegalArgumentException("Invalid redirect");
        try{if(new java.net.URI(location).getRawUserInfo()!=null)throw new IllegalArgumentException("Redirect userinfo");}catch(java.net.URISyntaxException invalid){throw new IllegalArgumentException("Invalid redirect",invalid);}
    }
    private static final class AddressRejected extends UnknownHostException {AddressRejected(){super("Public address required");}}
    private static boolean hasAddressRejection(Throwable failure){for(Throwable f=failure;f!=null;f=f.getCause())if(f instanceof AddressRejected)return true;return false;}
    static String sha256(byte[] value)throws Exception{StringBuilder out=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(value))out.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return out.toString();}
    private static String decode(byte[] bytes,Charset charset,boolean partial)throws Exception {
        int trims=partial&&charset.equals(StandardCharsets.UTF_8)?Math.min(3,bytes.length):0;
        for(int trim=0;;trim++)try{return charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes,0,bytes.length-trim)).toString();}
        catch(java.nio.charset.CharacterCodingException invalid){if(trim>=trims)throw invalid;}
    }
    static String clip(String value,int maximum){String out=value.substring(0,Math.min(value.length(),maximum));return !out.isEmpty()&&Character.isHighSurrogate(out.charAt(out.length()-1))?out.substring(0,out.length()-1):out;}
    private static int integer(JSONObject input,String key,int min,int max){Object v=input.opt(key);if(!(v instanceof Number)||!Double.isFinite(((Number)v).doubleValue())||((Number)v).doubleValue()!=Math.rint(((Number)v).doubleValue())||((Number)v).doubleValue()<min||((Number)v).doubleValue()>max)throw new IllegalArgumentException("公共读取预算无效");return((Number)v).intValue();}
}
