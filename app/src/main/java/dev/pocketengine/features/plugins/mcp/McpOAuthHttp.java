package dev.pocketengine.features.plugins.mcp;

import dev.pocketengine.core.RunControl;
import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import okhttp3.Call;
import okhttp3.Dns;
import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.json.JSONObject;

/** No redirects, retry, cookies, client secret, Bearer header or credential-bearing metadata fetch. */
public final class McpOAuthHttp implements McpOAuth.Http {
    private static final OkHttpClient HTTP=new OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .dns(host->{List<InetAddress> addresses=Dns.SYSTEM.lookup(host);for(InetAddress address:addresses)if(!publicAddress(address))throw new UnknownHostException("Nonpublic OAuth address");return addresses;})
            // Surface non-200 responses before OkHttp's HTTP-status follow-up logic (e.g. 503 Retry-After: 0).
            .addNetworkInterceptor(chain->{Response response=chain.proceed(chain.request());if(response.code()==200)return response;int status=response.code();response.close();throw new HttpStatus(status,response.headers("WWW-Authenticate"));})
            .connectTimeout(5,TimeUnit.SECONDS).readTimeout(10,TimeUnit.SECONDS).callTimeout(15,TimeUnit.SECONDS).build();
    private final OkHttpClient http;
    public McpOAuthHttp(){http=HTTP;}
    /** Native controlled TLS fixture seam. JSON/config cannot replace production DNS/trust policy. */
    public McpOAuthHttp(OkHttpClient nativeTransport){http=java.util.Objects.requireNonNull(nativeTransport).newBuilder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).connectTimeout(5,TimeUnit.SECONDS).readTimeout(10,TimeUnit.SECONDS).callTimeout(15,TimeUnit.SECONDS).addNetworkInterceptor(chain->{Response response=chain.proceed(chain.request());if(response.code()==200)return response;int status=response.code();java.util.List<String> challenges=response.headers("WWW-Authenticate");response.close();throw new HttpStatus(status,challenges);}).build();}
    private static final class HttpStatus extends java.io.IOException {final int status;final List<String> challenges;HttpStatus(int status,List<String> challenges){this.status=status;this.challenges=new java.util.ArrayList<>(challenges);}}
    static boolean publicAddress(InetAddress address){
        byte[] raw=address.getAddress();
        return !address.isAnyLocalAddress()&&!address.isLoopbackAddress()&&!address.isLinkLocalAddress()&&!address.isSiteLocalAddress()&&!address.isMulticastAddress()
                &&!(raw.length==16&&(raw[0]&0xfe)==0xfc)&&!(raw.length==4&&((raw[0]&255)==0||(raw[0]&255)>=240||(raw[0]&255)==100&&(raw[1]&255)>=64&&(raw[1]&255)<=127));
    }
    @Override public List<String> authenticationChallenge(String exactResource,RunControl control)throws Exception {
        McpOAuthMetadata.https(exactResource);control.check();Call call=http.newCall(new Request.Builder().url(exactResource).header("Accept","application/json").header("Cache-Control","no-store").get().build());Runnable cancel=call::cancel;control.onCancel(cancel);
        try(Response response=call.execute()){control.check();return checkedChallenges(response.headers("WWW-Authenticate"));}
        catch(HttpStatus status){control.check();if(status.status==401)return checkedChallenges(status.challenges);if(status.status==404||status.status==405)return java.util.Collections.emptyList();throw new IllegalStateException("MCP授权探测未取得有效响应，未发送凭据或调用工具");}
        finally{control.removeCancelAction(cancel);}
    }
    private static List<String> checkedChallenges(List<String> values)throws Exception{if(values.size()>16)throw new SecurityException("授权challenge过多");int total=0;for(String value:values){total+=value.length();if(total>8192)throw new SecurityException("授权challenge过大");}return java.util.Collections.unmodifiableList(new java.util.ArrayList<>(values));}
    @Override public McpOAuth.Reply request(String url,Map<String,String> form,RunControl control)throws Exception {
        McpOAuthMetadata.https(url);control.check();Request.Builder builder=new Request.Builder().url(url).header("Accept","application/json").header("Cache-Control","no-store");
        if(form!=null){
            FormBody.Builder encoded=new FormBody.Builder();for(Map.Entry<String,String> entry:form.entrySet())encoded.add(entry.getKey(),entry.getValue());FormBody body=encoded.build();
            builder.post(new RequestBody(){
                @Override public okhttp3.MediaType contentType(){return body.contentType();}
                @Override public long contentLength(){return body.contentLength();}
                @Override public boolean isOneShot(){return true;}
                @Override public void writeTo(okio.BufferedSink sink)throws java.io.IOException{body.writeTo(sink);}
            });
        }
        Call call=http.newCall(builder.build());Runnable cancel=call::cancel;control.onCancel(cancel);
        try(Response response=call.execute()){
            control.check();if(response.code()!=200)return new McpOAuth.Reply(response.code(),new JSONObject());
            if(response.body()==null||!response.header("Content-Type","").split(";",2)[0].trim().equalsIgnoreCase("application/json"))throw new IllegalArgumentException("Invalid OAuth JSON response");
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[4096];int n;
            try(java.io.InputStream in=response.body().byteStream()){while((n=in.read(buffer))!=-1){control.check();if(n>65536-bytes.size())throw new IllegalArgumentException("OAuth response limit");bytes.write(buffer,0,n);}}
            String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
            control.check();return new McpOAuth.Reply(200,McpJson.parse(text));
        }catch(Exception failure){if(control.isCancelled())throw new InterruptedException("Authorization stopped");if(failure instanceof HttpStatus)return new McpOAuth.Reply(((HttpStatus)failure).status,new JSONObject());throw new IllegalStateException("OAuth request failed; no automatic retry");}
        finally{control.removeCancelAction(cancel);}
    }
}
