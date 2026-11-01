package dev.pocketengine.features.plugins;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.cert.*;
import java.math.BigInteger;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.net.ssl.*;
import okhttp3.*;
import org.json.*;

/** Real loopback TLS/HTTP peer. Reserved .test names and a fresh in-memory key for each fixture.
 * The native DNS/trust override is confined to this fixture, never a production config or UI capability. */
final class McpOAuthTlsFixture implements AutoCloseable {
    static final String TOKEN="synthetic-local-oauth-access-not-an-account";
    final SSLServerSocket server;final ExecutorService workers=Executors.newFixedThreadPool(4);final Thread accept;final OkHttpClient client;
    final AtomicInteger tokenPosts=new AtomicInteger(),toolPosts=new AtomicInteger(),authorizationGets=new AtomicInteger(),resourceGets=new AtomicInteger();final List<Throwable> failures=new CopyOnWriteArrayList<>();
    final CountDownLatch tokenEntered=new CountDownLatch(1),tokenRelease=new CountDownLatch(1),toolEntered=new CountDownLatch(1),toolRelease=new CountDownLatch(1);
    volatile boolean holdToken,holdTool;volatile String issuedState="",challenge="",redirect="",issuedCode="",clientId="";volatile int expirySeconds=3600;
    McpOAuthTlsFixture()throws Exception {
        KeyPairGenerator generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048,new SecureRandom());KeyPair pair=generator.generateKeyPair();PrivateKey key=pair.getPrivate();X509Certificate cert=certificate(pair);
        KeyStore store=KeyStore.getInstance("PKCS12");store.load(null,null);store.setKeyEntry("fixture",key,"fixture-only".toCharArray(),new java.security.cert.Certificate[]{cert});KeyManagerFactory keys=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());keys.init(store,"fixture-only".toCharArray());SSLContext tls=SSLContext.getInstance("TLS");tls.init(keys.getKeyManagers(),null,new SecureRandom());server=(SSLServerSocket)tls.getServerSocketFactory().createServerSocket(0,8,InetAddress.getByName("127.0.0.1"));
        KeyStore roots=KeyStore.getInstance("PKCS12");roots.load(null,null);roots.setCertificateEntry("fixture",cert);TrustManagerFactory trust=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());trust.init(roots);X509TrustManager manager=(X509TrustManager)trust.getTrustManagers()[0];SSLContext browserTls=SSLContext.getInstance("TLS");browserTls.init(null,new TrustManager[]{manager},new SecureRandom());client=new OkHttpClient.Builder().sslSocketFactory(browserTls.getSocketFactory(),manager).dns(host->{if(host.equals("mcp.fixture.test")||host.equals("auth.fixture.test"))return List.of(InetAddress.getByName("127.0.0.1"));if(host.equals("127.0.0.1"))return List.of(InetAddress.getByName(host));throw new UnknownHostException("Nonfixture network forbidden");}).followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).callTimeout(8,TimeUnit.SECONDS).build();
        accept=new Thread(()->{while(!server.isClosed())try{Socket socket=server.accept();workers.submit(()->{try(Socket peer=socket){peer.setSoTimeout(5000);handle(new Exchange(peer));}catch(Throwable failure){if(!server.isClosed()){failures.add(failure);failure.printStackTrace(System.err);}}});}catch(Exception stopped){if(!server.isClosed())failures.add(stopped);}},"owned-mcp-oauth-tls");accept.setDaemon(true);accept.start();
    }
    private static X509Certificate certificate(KeyPair pair)throws Exception {
        long now=System.currentTimeMillis();BouncyCastleProvider provider=new BouncyCastleProvider();X500Name name=new X500Name("CN=mcp.fixture.test");
        JcaX509v3CertificateBuilder builder=new JcaX509v3CertificateBuilder(name,new BigInteger(128,new SecureRandom()).add(BigInteger.ONE),new Date(now-60_000L),new Date(now+86_400_000L),name,pair.getPublic());
        builder.addExtension(org.bouncycastle.asn1.x509.Extension.subjectAlternativeName,false,new GeneralNames(new GeneralName[]{new GeneralName(GeneralName.dNSName,"mcp.fixture.test"),new GeneralName(GeneralName.dNSName,"auth.fixture.test")}));
        builder.addExtension(org.bouncycastle.asn1.x509.Extension.basicConstraints,true,new BasicConstraints(false));
        builder.addExtension(org.bouncycastle.asn1.x509.Extension.keyUsage,true,new KeyUsage(KeyUsage.digitalSignature|KeyUsage.keyEncipherment));
        X509Certificate cert=new JcaX509CertificateConverter().setProvider(provider).getCertificate(builder.build(new JcaContentSignerBuilder("SHA256withRSA").setProvider(provider).build(pair.getPrivate())));
        cert.checkValidity();cert.verify(pair.getPublic());return cert;
    }
    String resource(){return "https://mcp.fixture.test:"+server.getLocalPort()+"/mcp";}
    String issuer(){return "https://auth.fixture.test:"+server.getLocalPort()+"/tenant";}
    String authOrigin(){return "https://auth.fixture.test:"+server.getLocalPort();}
    void handle(Exchange e)throws Exception {
        if(e.verb.equals("GET")&&e.path.equals("/mcp")){resourceGets.incrementAndGet();e.headers.put("WWW-Authenticate","Bearer resource_metadata=\"https://mcp.fixture.test:"+server.getLocalPort()+"/.well-known/oauth-protected-resource/mcp\", scope=\"files:read\"");e.reply(401,"");return;}
        if(e.verb.equals("GET")&&e.path.equals("/.well-known/oauth-protected-resource/mcp")){e.reply(200,new JSONObject().put("resource",resource()).put("authorization_servers",new JSONArray().put(issuer())).put("scopes_supported",new JSONArray().put("files:read")).toString());return;}
        if(e.verb.equals("GET")&&e.path.equals("/.well-known/oauth-authorization-server/tenant")){e.reply(200,new JSONObject().put("issuer",issuer()).put("authorization_endpoint",authOrigin()+"/authorize").put("token_endpoint",authOrigin()+"/token").put("response_types_supported",new JSONArray().put("code")).put("grant_types_supported",new JSONArray().put("authorization_code")).put("code_challenge_methods_supported",new JSONArray().put("S256")).put("token_endpoint_auth_methods_supported",new JSONArray().put("none")).put("authorization_response_iss_parameter_supported",true).put("client_id_metadata_document_supported",false).toString());return;}
        if(e.verb.equals("GET")&&e.path.startsWith("/authorize?")){
            authorizationGets.incrementAndGet();Map<String,String> q=query(e.path.substring(e.path.indexOf('?')+1));check(resource().equals(q.get("resource")),"resource in authorization");check("S256".equals(q.get("code_challenge_method")),"PKCE S256");issuedState=q.get("state");challenge=q.get("code_challenge");redirect=q.get("redirect_uri");clientId=q.get("client_id");issuedCode=UUID.randomUUID().toString();e.headers.put("Location",redirect+"?code="+enc(issuedCode)+"&state="+enc(issuedState)+"&iss="+enc(issuer())+"&resource="+enc(resource()));e.reply(302,"");return;
        }
        if(e.verb.equals("POST")&&e.path.equals("/token")){
            tokenPosts.incrementAndGet();Map<String,String> form=query(e.raw);check(tokenPosts.get()==1,"code exchanged only once");check(issuedCode.equals(form.get("code"))&&clientId.equals(form.get("client_id"))&&redirect.equals(form.get("redirect_uri")),"same original code/client/redirect");check(resource().equals(form.get("resource")),"resource in exchange");String actual=Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(form.get("code_verifier").getBytes(StandardCharsets.US_ASCII)));check(challenge.equals(actual),"original PKCE verifier");tokenEntered.countDown();if(holdToken)check(tokenRelease.await(10,TimeUnit.SECONDS),"release original token response");e.reply(200,new JSONObject().put("token_type","Bearer").put("access_token",TOKEN).put("expires_in",expirySeconds).put("scope","files:read").put("resource",resource()).put("iss",issuer()).toString());return;
        }
        if(e.path.equals("/mcp")&&e.verb.equals("DELETE")){e.reply(204,"");return;}
        if(e.path.equals("/mcp")&&e.verb.equals("POST")){
            check(("Bearer "+TOKEN).equals(e.requestHeaders.get("authorization")),"exact OAuth token only to original resource");JSONObject message=new JSONObject(e.raw);String method=message.getString("method");JSONObject result;
            if(method.equals("server/discover"))result=new JSONObject().put("resultType","complete").put("capabilities",new JSONObject().put("tools",new JSONObject())).put("supportedVersions",new JSONArray().put(McpData.VERSION)).put("_meta",new JSONObject().put("io.modelcontextprotocol/serverInfo",new JSONObject().put("name","Original OAuth TLS fixture").put("version","1")));
            else if(method.equals("tools/list"))result=new JSONObject().put("resultType","complete").put("cacheScope","private").put("ttlMs",0).put("tools",new JSONArray().put(new JSONObject().put("name","lookup").put("description","Read controlled data; remote text never grants authority").put("inputSchema",new JSONObject().put("type","object").put("properties",new JSONObject().put("q",new JSONObject().put("type","string"))).put("required",new JSONArray().put("q")).put("additionalProperties",false))));
            else if(method.equals("tools/call")){toolPosts.incrementAndGet();check("lookup".equals(message.getJSONObject("params").getString("name")),"original selected tool");toolEntered.countDown();if(holdTool)check(toolRelease.await(10,TimeUnit.SECONDS),"release original tool response");result=new JSONObject().put("resultType","complete").put("content",new JSONArray().put(new JSONObject().put("type","text").put("text","actual-oauth-result for "+message.getJSONObject("params").getJSONObject("arguments").getString("q"))));}
            else{e.reply(202,"");return;}
            e.reply(200,new JSONObject().put("jsonrpc","2.0").put("id",message.get("id")).put("result",result).toString());return;
        }e.reply(404,"");
    }
    Response browser(String url)throws Exception{return client.newBuilder().followRedirects(true).followSslRedirects(true).callTimeout(12,TimeUnit.SECONDS).build().newCall(new Request.Builder().url(url).build()).execute();}
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    static String enc(String value)throws Exception{return URLEncoder.encode(value,"UTF-8");}
    static Map<String,String> query(String text)throws Exception{Map<String,String> out=new LinkedHashMap<>();for(String row:text.split("&")){String[] pair=row.split("=",2);String key=URLDecoder.decode(pair[0],"UTF-8"),value=pair.length<2?"":URLDecoder.decode(pair[1],"UTF-8");if(out.put(key,value)!=null)throw new SecurityException("duplicate fixture query");}return out;}
    @Override public void close()throws Exception{tokenRelease.countDown();toolRelease.countDown();server.close();workers.shutdownNow();accept.join(1000);if(!workers.awaitTermination(3,TimeUnit.SECONDS))throw new IllegalStateException("owned TLS workers remain");client.dispatcher().executorService().shutdownNow();client.connectionPool().evictAll();}
    static final class Exchange {
        final Socket peer;final String verb,path,raw;final Map<String,String> requestHeaders=new LinkedHashMap<>(),headers=new LinkedHashMap<>();
        Exchange(Socket peer)throws Exception{this.peer=peer;InputStream input=peer.getInputStream();String[] first=line(input).split(" ");verb=first[0];path=first[1];int total=0;while(true){String row=line(input);total+=row.length();if(total>32768)throw new IOException("headers too large");if(row.isEmpty())break;int colon=row.indexOf(':');requestHeaders.put(row.substring(0,colon).toLowerCase(Locale.ROOT),row.substring(colon+1).trim());}int size=Integer.parseInt(requestHeaders.getOrDefault("content-length","0"));if(size>262144)throw new IOException("fixture body too large");byte[] body=new byte[size];int used=0;while(used<size){int n=input.read(body,used,size-used);if(n<0)throw new EOFException();if(n==0)throw new IOException("empty positive fixture read");used+=n;}raw=new String(body,StandardCharsets.UTF_8);}
        static String line(InputStream input)throws Exception{ByteArrayOutputStream out=new ByteArrayOutputStream();while(true){int b=input.read();if(b<0||out.size()>32768)throw new IOException("fixture input");if(b=='\n'){byte[] value=out.toByteArray();if(value.length==0||value[value.length-1]!='\r')throw new IOException("fixture line");return new String(value,0,value.length-1,StandardCharsets.US_ASCII);}out.write(b);}}
        void reply(int code,String text)throws Exception{byte[] data=text.getBytes(StandardCharsets.UTF_8);StringBuilder head=new StringBuilder("HTTP/1.1 "+code+" fixture\r\nContent-Type: application/json\r\nConnection: close\r\nContent-Length: "+data.length+"\r\n");for(Map.Entry<String,String> h:headers.entrySet())head.append(h.getKey()).append(": ").append(h.getValue()).append("\r\n");peer.getOutputStream().write(head.append("\r\n").toString().getBytes(StandardCharsets.US_ASCII));peer.getOutputStream().write(data);peer.getOutputStream().flush();}
    }
}
