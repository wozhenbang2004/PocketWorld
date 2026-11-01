package dev.pocketengine.media;

import dev.pocketengine.testutil.HttpServer;
import dev.pocketengine.core.RunControl;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import static org.junit.Assert.*;

/** Real protocol exchange using explicitly labelled test media; no live account or generation claim. */
public final class MediaTransportTest {
    private HttpServer first,second;
    private String base(HttpServer server){return "http://127.0.0.1:"+server.getAddress().getPort()+"/v1";}
    private HttpServer server()throws Exception{return HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);}
    @After public void close(){if(first!=null)first.stop(0);if(second!=null)second.stop(0);}
    @Test public void imageRequestTransmitsRealConfiguredPayloadAndDecodesActualBytes()throws Exception{
        byte[] png=java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jJu0AAAAASUVORK5CYII=");AtomicReference<String> auth=new AtomicReference<>(),received=new AtomicReference<>();byte[] response=new JSONObject().put("data",new JSONArray().put(new JSONObject().put("b64_json",java.util.Base64.getEncoder().encodeToString(png)))).toString().getBytes(StandardCharsets.UTF_8);first=server();first.createContext("/v1/images/generations",exchange->{auth.set(exchange.getRequestHeaders().getFirst("Authorization"));received.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));exchange.sendResponseHeaders(200,response.length);exchange.getResponseBody().write(response);exchange.close();});first.start();
        MediaTransport.Payload result=MediaTransport.request(base(first),"private-key","image","configured-image-model","","a blue mug",false,new RunControl());assertArrayEquals(png,result.bytes);assertEquals("image/png",result.mimeType);assertEquals("Bearer private-key",auth.get());JSONObject body=new JSONObject(received.get());assertEquals("configured-image-model",body.getString("model"));assertEquals("a blue mug",body.getString("prompt"));assertEquals("png",body.getString("output_format"));assertFalse(body.has("response_format"));
    }
    @Test public void speechReturnsWaveBytesAndUsesConfiguredVoice()throws Exception{
        byte[] wave=java.nio.ByteBuffer.allocate(46).order(java.nio.ByteOrder.LITTLE_ENDIAN).put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(38).put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16).putShort((short)1).putShort((short)1).putInt(16000).putInt(32000).putShort((short)2).putShort((short)16).put("data".getBytes(StandardCharsets.US_ASCII)).putInt(2).putShort((short)0).array();AtomicReference<String> received=new AtomicReference<>();first=server();first.createContext("/v1/audio/speech",exchange->{received.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));exchange.getResponseHeaders().add("Content-Type","audio/wav");exchange.sendResponseHeaders(200,wave.length);exchange.getResponseBody().write(wave);exchange.close();});first.start();MediaTransport.Payload result=MediaTransport.request(base(first),"","speech","configured-tts","configured-voice","你好",false,new RunControl());assertArrayEquals(wave,result.bytes);assertEquals("audio/wav",result.mimeType);JSONObject body=new JSONObject(received.get());assertEquals("wav",body.getString("response_format"));assertEquals("configured-voice",body.getString("voice"));assertEquals("你好",body.getString("input"));
    }
    @Test public void redirectsNeverReceiveTheConfiguredApiCredential()throws Exception{
        AtomicInteger reached=new AtomicInteger();second=server();second.createContext("/stolen",exchange->{reached.incrementAndGet();exchange.sendResponseHeaders(200,0);exchange.close();});second.start();first=server();first.createContext("/v1/images/generations",exchange->{exchange.getResponseHeaders().add("Location","http://127.0.0.1:"+second.getAddress().getPort()+"/stolen");exchange.sendResponseHeaders(302,-1);exchange.close();});first.start();assertThrows(MediaTransport.Rejected.class,()->MediaTransport.request(base(first),"secret","image","m","","test",false,new RunControl()));assertEquals(0,reached.get());
    }
    @Test public void canceledOrInvalidRequestsCannotReachTheService()throws Exception{
        AtomicInteger reached=new AtomicInteger();first=server();first.createContext("/v1/images/generations",exchange->{reached.incrementAndGet();exchange.sendResponseHeaders(200,0);exchange.close();});first.start();RunControl cancelled=new RunControl();cancelled.cancel();assertThrows(InterruptedException.class,()->MediaTransport.request(base(first),"","image","m","","test",false,cancelled));assertThrows(IllegalArgumentException.class,()->MediaTransport.request(base(first),"","image","m","","x".repeat(8001),false,new RunControl()));assertEquals(0,reached.get());assertThrows(IllegalArgumentException.class,()->MediaTransport.base("https://user:password@example.com/v1"));assertFalse(MediaTransport.sameOrigin("https://example.com/v1","https://other.example/v1"));assertTrue(MediaTransport.sameOrigin("https://example.com:443/v1","https://example.com/api"));
    }
    @Test public void truncatedAndOversizeResponsesCannotBecomeSavedPayloads()throws Exception{
        first=server();first.createContext("/truncated",exchange->{exchange.sendResponseHeaders(200,100);exchange.getResponseBody().write(new byte[]{1,2,3});exchange.close();});first.createContext("/oversize",exchange->{exchange.sendResponseHeaders(200,MediaTransport.MAX_MEDIA_BYTES+1L);exchange.close();});first.start();
        String root="http://127.0.0.1:"+first.getAddress().getPort();
        assertThrows(java.io.EOFException.class,()->MediaTransport.exchange(java.net.URI.create(root+"/truncated"),"","GET",null,MediaTransport.MAX_MEDIA_BYTES,new RunControl()));
        assertThrows(IllegalStateException.class,()->MediaTransport.exchange(java.net.URI.create(root+"/oversize"),"","GET",null,MediaTransport.MAX_MEDIA_BYTES,new RunControl()));
    }
    @Test public void validationRejectionsAndAmbiguousServerResponsesAreDistinguished(){
        assertTrue(new MediaTransport.Rejected(400).definitive);assertTrue(new MediaTransport.Rejected(429).definitive);
        assertFalse(new MediaTransport.Rejected(500).definitive);assertFalse(new MediaTransport.Rejected(408).definitive);assertFalse(new MediaTransport.Rejected(302).definitive);
    }
}
