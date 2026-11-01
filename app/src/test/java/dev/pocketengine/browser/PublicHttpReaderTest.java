package dev.pocketengine.browser;

import dev.pocketengine.core.RunControl;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import okhttp3.Authenticator;
import okhttp3.CookieJar;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.BufferedSource;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Real URL/address policy, body reader and jsoup parser; response bodies are explicit fixtures, not network/platform passes. */
public final class PublicHttpReaderTest {
    static JSONObject obj(Object... pairs)throws Exception {JSONObject value=new JSONObject();for(int i=0;i<pairs.length;i+=2)value.put((String)pairs[i],pairs[i+1]);return value;}
    static JSONObject args()throws Exception{return obj("url","https://public.example/article?edition=2026","maxBytes",4096,"maxChars",6000);}
    static final class Body extends ResponseBody {
        final Buffer bytes=new Buffer();final long length;final MediaType type;boolean closed;
        Body(byte[] data,String type,boolean known){bytes.write(data);length=known?data.length:-1;this.type=MediaType.parse(type);}
        public MediaType contentType(){return type;}public long contentLength(){return length;}public BufferedSource source(){return bytes;}
        public void close(){closed=true;}
    }
    static Response response(Body body,String type,int code)throws Exception{return new Response.Builder().request(new Request.Builder().url(args().getString("url")).build()).protocol(Protocol.HTTP_1_1).code(code).message("fixture").header("Content-Type",type).body(body).build();}
    static PublicHttpReader.Result consume(Response response,JSONObject args,PublicHttpReader.Budget budget)throws Exception{return PublicHttpReader.consume(response,args,PublicHttpReader.facts(response,args,new JSONArray()),budget,()->{});}
    static PublicHttpReader.Result text(String text,String mime)throws Exception {Body body=new Body(text.getBytes(StandardCharsets.UTF_8),mime,true);try(Response response=response(body,mime,200)){return consume(response,args(),new PublicHttpReader.Budget(new RunControl()));}}

    @Test public void urlRejectsCredentialsPrivateTargetsAndNonstandardPortsBeforeApproval()throws Exception {
        for(String bad:List.of("https://user:pass@public.example/a","https://@public.example/a","https://public.example:444/a","http://127.0.0.1/a","http://2130706433/a","http://127.1/a","http://0177.0.0.1/a","https://[::1]/a","http://10.2.3.4/a","https://localhost/a","https://a.local/a","https://public.example/?%61ccess_token=secret","https://public.example/?Cookie=x","https://public.example/a\n"," https://public.example/a","file:///tmp/a"))assertThrows(bad,IllegalArgumentException.class,()->PublicHttpReader.validate(obj("url",bad)));
        PublicHttpReader.validate(args());assertEquals("https://public.example",PublicHttpReader.origin(PublicHttpReader.url(args().getString("url"))));
    }
    @Test public void redirectApprovalIsExactOriginNotAnUnreviewedPrefix()throws Exception {
        PublicHttpReader.validate(args().put("allowedRedirectOrigins",new JSONArray().put("https://cdn.example")));
        for(String bad:List.of("https://cdn.example/","https://cdn.example/path","https://cdn.example?next=x","https://user@cdn.example","https://cdn.example:444"))assertThrows(Exception.class,()->PublicHttpReader.validate(args().put("allowedRedirectOrigins",new JSONArray().put(bad))));
        assertThrows(Exception.class,()->PublicHttpReader.validate(args().put("maxBytes",4096.5)));assertThrows(Exception.class,()->PublicHttpReader.validate(args().put("maxChars",6001)));
    }
    @Test public void publicAddressPolicyClosesLocalSpecialAndEmbeddedPrivateRanges()throws Exception {
        for(String bad:List.of("0.0.0.0","10.0.0.1","127.0.0.1","100.64.1.1","169.254.169.254","172.31.1.1","192.168.1.1","192.0.2.2","198.18.0.1","198.51.100.1","203.0.113.1","224.0.0.1","255.255.255.255","::","::1","fc00::1","fe80::1","2001:db8::1","2001:20::1","3fff::1","64:ff9b::a00:1","2002:0a00:0001::1","::ffff:127.0.0.1"))assertFalse(bad,PublicHttpReader.publicAddress(InetAddress.getByName(bad)));
        for(String good:List.of("8.8.8.8","1.1.1.1","2606:4700::1111","2001:4860:4860::8888","64:ff9b::808:808","2002:0808:0808::1"))assertTrue(good,PublicHttpReader.publicAddress(InetAddress.getByName(good)));
    }
    @Test public void mixedDnsAnswersAreDeniedAndTheAdmittedListCannotBeReplaced()throws Exception {
        InetAddress a=InetAddress.getByName("8.8.8.8"),b=InetAddress.getByName("1.1.1.1");List<InetAddress> original=new ArrayList<>(List.of(a,b));List<InetAddress> pinned=PublicHttpReader.admitAddresses(original);original.clear();assertEquals(List.of(a,b),pinned);assertThrows(UnsupportedOperationException.class,()->pinned.add(a));
        assertThrows(Exception.class,()->PublicHttpReader.admitAddresses(List.of(a,InetAddress.getByName("127.0.0.1"))));assertThrows(Exception.class,()->PublicHttpReader.admitAddresses(List.of()));
        assertTrue(PublicHttpReader.connectionAdmitted(new InetSocketAddress(a,443),Proxy.NO_PROXY,Set.of(a)));
        assertFalse(PublicHttpReader.connectionAdmitted(new InetSocketAddress(b,443),Proxy.NO_PROXY,Set.of(a)));
        assertFalse(PublicHttpReader.connectionAdmitted(InetSocketAddress.createUnresolved("public.example",443),Proxy.NO_PROXY,Set.of(a)));
        assertFalse(PublicHttpReader.connectionAdmitted(new InetSocketAddress(a,443),new Proxy(Proxy.Type.HTTP,new InetSocketAddress(b,8080)),Set.of(a)));
    }
    @Test public void redirectPolicyNeverDowngradesEvenAfterAnApprovedHttpUpgrade()throws Exception {
        HttpUrl plain=PublicHttpReader.url("http://public.example/a"),tls=PublicHttpReader.url("https://public.example/a"),other=PublicHttpReader.url("https://cdn.example/b");Set<String> approved=Set.of("http://public.example","https://cdn.example");
        assertTrue(PublicHttpReader.redirectAllowed(plain,tls,approved));assertTrue(PublicHttpReader.redirectAllowed(tls,other,approved));assertFalse(PublicHttpReader.redirectAllowed(tls,plain,approved));assertFalse(PublicHttpReader.redirectAllowed(tls,PublicHttpReader.url("https://unapproved.example/a"),approved));
    }
    @Test public void anonymousClientHasNoCookieAuthProxyOrAutomaticRedirectInheritance()throws Exception {
        Field field=PublicHttpReader.class.getDeclaredField("BASE");field.setAccessible(true);OkHttpClient client=(OkHttpClient)field.get(null);assertEquals(List.of(Protocol.HTTP_1_1),client.protocols());assertSame(CookieJar.NO_COOKIES,client.cookieJar());assertSame(Authenticator.NONE,client.authenticator());assertSame(Authenticator.NONE,client.proxyAuthenticator());assertEquals(Proxy.NO_PROXY,client.proxy());assertFalse(client.followRedirects());assertFalse(client.followSslRedirects());assertFalse(client.retryOnConnectionFailure());assertNull(client.cache());assertTrue(client.interceptors().isEmpty());assertTrue(client.networkInterceptors().isEmpty());
        Request request=PublicHttpReader.request(PublicHttpReader.url("https://public.example/a#fragment"));assertNull(request.header("Cookie"));assertNull(request.header("Authorization"));assertNull(request.header("Proxy-Authorization"));assertNull(request.header("Referer"));assertEquals("identity",request.header("Accept-Encoding"));assertNull(request.url().fragment());assertEquals("GET",request.method());
    }
    @Test public void knownBodyLengthAndProjectionClippingAreIndependentCoverageFacts()throws Exception {
        String body="公开正文 ".repeat(200);Body nativeBody=new Body(body.getBytes(StandardCharsets.UTF_8),"text/plain; charset=utf-8",true);try(Response response=response(nativeBody,"text/plain; charset=utf-8",200)){
            PublicHttpReader.Result result=consume(response,args().put("maxChars",64),new PublicHttpReader.Budget(new RunControl()));assertTrue(result.readable());JSONObject facts=result.value.getJSONObject("http");assertTrue(facts.getBoolean("bodyComplete"));assertFalse(facts.getBoolean("bodyTruncated"));assertTrue(facts.getBoolean("projectionTruncated"));assertEquals(64,result.value.getString("text").length());assertEquals("unrendered_text_body",facts.getString("projection"));assertFalse(result.value.getBoolean("verifiedBusinessSuccess"));assertEquals(PublicHttpReader.sha256(body.getBytes(StandardCharsets.UTF_8)),facts.getString("bodySha256"));
        }
    }
    @Test public void unknownLengthAtTheLimitIsPartialWithoutAPlusOneBodyRead()throws Exception {
        byte[] bytes="a".repeat(6000).getBytes(StandardCharsets.UTF_8);Body body=new Body(bytes,"text/plain",false);try(Response response=response(body,"text/plain",200)){
            PublicHttpReader.Result result=consume(response,args(),new PublicHttpReader.Budget(new RunControl()));assertTrue(result.readable());assertEquals(4096,result.value.getJSONObject("http").getInt("bodyBytesRead"));assertTrue(result.value.getJSONObject("http").getBoolean("bodyTruncated"));assertEquals(1904,body.bytes.size());assertEquals(4096,result.value.getString("text").length());
        }
    }
    @Test public void malformedUnicodeCannotBecomeACompleteSavedArticle()throws Exception {
        Body body=new Body(new byte[]{(byte)0xff,65},"text/plain; charset=utf-8",true);try(Response response=response(body,"text/plain; charset=utf-8",200)){PublicHttpReader.Result result=consume(response,args(),new PublicHttpReader.Budget(new RunControl()));assertFalse(result.readable());assertEquals("text_decode_incomplete",result.value.getString("code"));assertFalse(result.value.has("text"));}
    }
    @Test public void realHtmlParserNormalizesMalformedMarkupAndDoesNotIncludeScriptStyleOrNavigation()throws Exception {
        String more="更多正文 ".repeat(20).trim();PublicHttpReader.Result result=text("<title>Research &amp; source</title><nav>unrelated navigation</nav><main><p>正文 &amp; 依据 <b>第一段<p>第二段 "+more+"</main><script>secret_js_content</script><style>hidden_style</style>","text/html");assertTrue(result.value.toString(),result.readable());assertEquals("Research & source",result.value.getString("title"));assertEquals("正文 & 依据 第一段 第二段 "+more,result.value.getString("text"));assertEquals("server_dom_text_not_rendered",result.value.getJSONObject("http").getString("projection"));
    }
    @Test public void parserDepthLimitDoesNotMasqueradeAsCompleteServerDom()throws Exception {PublicHttpReader.Result result=text("<div>".repeat(200)+"deep public text"+"</div>".repeat(200),"text/html");assertEquals("html_structure_budget",result.value.getString("code"));assertFalse(result.readable());}
    @Test public void loginJsShellAndUnsupportedMimeExplicitlyRequireTheOriginalWebView()throws Exception {
        for(PublicHttpReader.Result result:List.of(text("<input type=password><p>Please sign in</p>","text/html"),text("<div id=app>Loading</div><script src=app.js></script>","text/html"),text("binary placeholder","application/pdf"))){assertEquals("needs_webview",result.value.getString("status"));assertEquals("browser_task_open",result.value.getString("nextTool"));assertFalse(result.value.has("text"));assertFalse(result.value.getBoolean("automaticReplayAllowed"));assertEquals(args().getString("url"),result.value.getString("nextUrl"));}
    }
    @Test public void compressionAndUnknownCharsetAreRejectedBeforeBodyConsumption()throws Exception {
        for(String mime:List.of("text/plain; charset=x-unknown-charset","text/plain")){Body body=new Body("read must not happen".getBytes(StandardCharsets.UTF_8),mime,true);Response.Builder builder=response(body,mime,200).newBuilder();if(mime.equals("text/plain"))builder.header("Content-Encoding","gzip");try(Response response=builder.build()){PublicHttpReader.Result result=consume(response,args(),new PublicHttpReader.Budget(new RunControl()));assertFalse(result.readable());assertEquals(20,body.bytes.size());}}
    }
    @Test public void oversizedResponseHeadersDoNotProduceASource()throws Exception {Body body=new Body("text".getBytes(StandardCharsets.UTF_8),"text/plain",true);try(Response response=response(body,"text/plain",200).newBuilder().header("X-Large","a".repeat(32768)).build()){PublicHttpReader.Result result=consume(response,args(),new PublicHttpReader.Budget(new RunControl()));assertEquals("response_headers_budget",result.value.getString("code"));assertEquals(4,body.bytes.size());}}
    @Test public void originalControlCancellationPauseRevisionAndDeadlineDiscardLateReads()throws Exception {
        RunControl control=new RunControl();PublicHttpReader.Budget budget=new PublicHttpReader.Budget(control);assertTrue(budget.boundTo(control));assertFalse(budget.boundTo(new RunControl()));control.pause();control.resume();assertThrows(SecurityException.class,budget::check);
        RunControl cancelled=new RunControl();PublicHttpReader.Budget cancelledBudget=new PublicHttpReader.Budget(cancelled);cancelled.cancel();assertThrows(InterruptedException.class,cancelledBudget::check);
        assertThrows(TimeoutException.class,()->PublicHttpReader.await(new CompletableFuture<>(),new PublicHttpReader.Budget(new RunControl(),0)));
        RunControl pausedDuringAuthority=new RunControl();pausedDuringAuthority.setAuthorityCheck(pausedDuringAuthority::pause);assertThrows(SecurityException.class,()->new PublicHttpReader.Budget(pausedDuringAuthority).check());
        java.util.concurrent.CountDownLatch release=new java.util.concurrent.CountDownLatch(1),exited=new java.util.concurrent.CountDownLatch(1);java.util.concurrent.atomic.AtomicBoolean published=new java.util.concurrent.atomic.AtomicBoolean();PublicHttpReader.Budget nativeBudget=new PublicHttpReader.Budget(new RunControl(),250);
        try{assertThrows(TimeoutException.class,()->PublicHttpReader.bounded(nativeBudget,()->{try{while(release.getCount()>0)try{release.await();}catch(InterruptedException uninterruptibleFixture){/* Simulate a platform call that cannot be killed. */}nativeBudget.checkInsideStore();published.set(true);return new JSONObject();}finally{exited.countDown();}}));}
        finally{release.countDown();assertTrue(exited.await(2,java.util.concurrent.TimeUnit.SECONDS));}assertFalse(published.get());
    }
    @Test public void retainedPolicyChecksTheOriginalUrlBudgetsAndEveryRedirectOrigin()throws Exception {
        PublicHttpReader.Result result=text("public article","text/plain");JSONObject facts=result.value.getJSONObject("http");PublicHttpReader.requireRetainedPolicy(args(),facts,"public article");assertThrows(SecurityException.class,()->PublicHttpReader.requireRetainedPolicy(args().put("url","https://different.example/a"),facts,"public article"));assertThrows(SecurityException.class,()->PublicHttpReader.requireRetainedPolicy(args().put("maxBytes",8192),facts,"public article"));
        JSONObject tampered=new JSONObject(facts.toString()).put("finalUrl","https://unapproved.example/a").put("redirects",new JSONArray().put(obj("fromOrigin","https://public.example","toOrigin","https://unapproved.example","statusCode",302)));assertThrows(SecurityException.class,()->PublicHttpReader.requireRetainedPolicy(args(),tampered,"public article"));PublicHttpReader.requireRetainedPolicy(args().put("allowedRedirectOrigins",new JSONArray().put("https://unapproved.example")),tampered,"public article");
    }
    @Test public void publicPinDeclaresAnonymousUnrenderedProvenanceNotPrivateDom()throws Exception {try(BrowserTaskSpaceTest.Harness h=new BrowserTaskSpaceTest.Harness(true)){
        JSONObject args=args();JSONObject pin=h.ui.call(()->h.space.publicHttpPin(args));assertEquals("public_anonymous_response",pin.getJSONObject("dataProvenance").getString("sourceScope"));assertFalse(pin.getJSONObject("publicHttpPolicy").getBoolean("rendered"));assertFalse(pin.has("tabId"));h.ui.call(()->{h.space.requirePublicHttpPin(pin,args);return null;});assertThrows(Exception.class,()->h.ui.call(()->{h.space.requirePublicHttpPin(pin,args().put("maxBytes",8192));return null;}));
    }}
}
