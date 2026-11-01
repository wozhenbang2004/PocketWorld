package dev.pocketengine.media;

import static org.junit.Assert.*;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.media.jobs.*;
import dev.pocketengine.testutil.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.json.*;
import org.junit.*;

/** Real HTTP requests to self-owned fixtures; no live provider, TLS exception, paid call or codec claim. */
public final class MediaExtensionTransportTest {
    private HttpServer server;
    private static final byte[] PNG=Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jJu0AAAAASUVORK5CYII=");
    @After public void close(){if(server!=null)server.stop(0);}
    private static boolean contains(byte[] body,byte[] exact){outer:for(int i=0;i+exact.length<=body.length;i++){for(int j=0;j<exact.length;j++)if(body[i+j]!=exact[j])continue outer;return true;}return false;}
    @Test public void actualEditPostsExactNativeImageAndMaskAndNoInternalReferences()throws Exception{
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);AtomicReference<byte[]> posted=new AtomicReference<>();AtomicReference<String> contentType=new AtomicReference<>(),auth=new AtomicReference<>();AtomicInteger calls=new AtomicInteger();
        server.createContext("/v1/images/edits",exchange->{calls.incrementAndGet();posted.set(exchange.getRequestBody().readAllBytes());contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));auth.set(exchange.getRequestHeaders().getFirst("Authorization"));byte[] reply=new JSONObject().put("data",new JSONArray().put(new JSONObject().put("b64_json",Base64.getEncoder().encodeToString(PNG)))).toString().getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,reply.length);exchange.getResponseBody().write(reply);exchange.close();});server.start();String base="http://127.0.0.1:"+server.getAddress().getPort()+"/v1";
        String id=UUID.randomUUID().toString();JSONObject pins=new JSONObject().put("inputArtifacts",new JSONArray().put(new JSONObject().put("id",id).put("sha256","a".repeat(64))));AtomicInteger checked=new AtomicInteger();
        try(var image=MediaTransport.editInput(MediaTransport.imagePayload(PNG.clone()),MediaTransport.imagePayload(PNG.clone()),pins,c->checked.incrementAndGet())){
            var result=MediaTransport.editResponse(base,"test-only-fixture-key","configured-edit-model","把蓝色杯子换成红色",false,image,new RunControl());assertArrayEquals(PNG,result.payload.bytes);assertTrue(result.resultUrl.isEmpty());assertTrue(result.responseDigest.matches("[a-f0-9]{64}"));
        }
        assertEquals(1,calls.get());assertEquals("Bearer test-only-fixture-key",auth.get());assertTrue(contentType.get().startsWith("multipart/form-data; boundary=pw-image-"));String text=new String(posted.get(),StandardCharsets.ISO_8859_1);assertTrue(text.contains("name=\"image[]\""));assertTrue(text.contains("name=\"mask\""));assertTrue(text.contains("name=\"n\"\r\n\r\n1\r\n"));assertTrue(contains(posted.get(),PNG));assertTrue(contains(posted.get(),"把蓝色杯子换成红色".getBytes(StandardCharsets.UTF_8)));assertFalse(text.contains(id));assertFalse(text.contains("inputArtifacts"));assertTrue(checked.get()>=2);
        RunControl cancelled=new RunControl();cancelled.cancel();try(var image=MediaTransport.editInput(MediaTransport.imagePayload(PNG.clone()),null,pins,c->{})){assertThrows(InterruptedException.class,()->MediaTransport.editResponse(base,"","m","no dispatch",false,image,cancelled));}assertEquals(1,calls.get());
    }
    @Test public void returnedUrlIsDurableAndColdOriginalFetchCannotRepeatPaidPostOrFetchPrivateAddress()throws Exception{
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);AtomicInteger posts=new AtomicInteger(),stolen=new AtomicInteger();String[] returned=new String[1];
        server.createContext("/v1/images/generations",exchange->{posts.incrementAndGet();exchange.getRequestBody().readAllBytes();byte[] body=new JSONObject().put("data",new JSONArray().put(new JSONObject().put("url",returned[0]))).toString().getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();});server.createContext("/private-result",exchange->{stolen.incrementAndGet();exchange.close();});server.start();returned[0]="https://127.0.0.1:"+server.getAddress().getPort()+"/private-result?secret_signed=do-not-expose";
        Path root=Files.createTempDirectory("c2-media-url-");Path journal=root.resolve("journal.json");var config=new JSONObject().put("provider","openai_compatible").put("baseUrl","http://127.0.0.1:"+server.getAddress().getPort()+"/v1").put("revision","fixed-source").put("imageModel","fixture").put("allowResultUrls",true);String operation=UUID.randomUUID().toString(),task=UUID.randomUUID().toString(),run=UUID.randomUUID().toString();JSONObject input=new JSONObject().put("_operationId",operation).put("_taskId",task).put("_runId",run).put("prompt","labelled fixture");MediaJobs.ArtifactSink noPublication=(payload,source,c)->{throw new AssertionError("non-public result must not become an attachment");};
        var jobs=new MediaJobs(new MediaJobJournal(journal.toFile()),noPublication,r->{});JSONObject first=jobs.execute("generateImage",input,config,"private-fixture-only",new RunControl());assertEquals("response_received",first.getString("status"));assertTrue(first.getBoolean("resultDescriptorConfirmed"));assertEquals("unavailable",first.getString("resultStatus"));assertFalse(first.toString().contains("do-not-expose"));assertFalse(first.toString().contains("private-result"));assertEquals(1,posts.get());assertEquals(0,stolen.get());
        JSONObject persisted=new JSONObject(Files.readString(journal));assertEquals(returned[0],persisted.getJSONObject("receipts").getJSONObject(operation).getString("_resultUrl"));var cold=new MediaJobs(new MediaJobJournal(journal.toFile()),noPublication,r->{});assertTrue(cold.execute("generateImage",input,config,"private-fixture-only",new RunControl()).getBoolean("knownOperation"));JSONObject fetched=cold.execute("fetchResults",new JSONObject().put("operationId",operation),config,"private-fixture-only",new RunControl());assertEquals("response_received",fetched.getString("status"));assertEquals(1,posts.get());assertEquals(0,stolen.get());assertFalse(fetched.toString().contains("do-not-expose"));
    }
    @Test public void urlPolicyUsesExactHttpsOriginAndRejectsLiteralPrivateAndCredentialTargets()throws Exception{
        assertThrows(Exception.class,()->MediaResultDownload.validate("http://example.com/a"));assertThrows(Exception.class,()->MediaResultDownload.validate("https://user:password@example.com/a"));assertThrows(Exception.class,()->MediaResultDownload.validate("https://example.com/a#fragment"));assertThrows(Exception.class,()->MediaResultDownload.validate("https://127.0.0.1/a"));assertThrows(Exception.class,()->MediaResultDownload.validate("https://169.254.169.254/latest/meta-data"));assertThrows(Exception.class,()->MediaResultDownload.validate("https://[::1]/a"));assertFalse(MediaResultDownload.sameOrigin(URI.create("https://cdn.example.com/a?signature=x"),URI.create("https://api.example.com/v1")));assertFalse(MediaResultDownload.sameOrigin(URI.create("https://api.example.com:444/a"),URI.create("https://api.example.com/v1")));assertTrue(MediaResultDownload.sameOrigin(URI.create("https://api.example.com:443/a?signature=x"),URI.create("https://api.example.com/v1")));
    }
    @Test public void mp4ClassificationPreservesActualBytesButDoesNotClaimContainerValidation()throws Exception{
        byte[] header=new byte[]{0,0,0,20,'f','t','y','p','i','s','o','m',0,0,0,0,'i','s','o','m'};var payload=MediaTransport.outputPayload(header);assertArrayEquals(header,payload.bytes);assertEquals("video/mp4",payload.mimeType);assertEquals("mp4",payload.extension);assertThrows(Exception.class,()->MediaTransport.outputPayload(new byte[]{'n','o','t','v','i','d','e','o'}));assertEquals("image/png",MediaTransport.outputPayload(PNG.clone()).mimeType);
    }
}
