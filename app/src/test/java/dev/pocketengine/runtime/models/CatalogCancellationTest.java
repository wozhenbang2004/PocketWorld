package dev.pocketengine.runtime.models;

import dev.pocketengine.core.RunControl;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.Test;
import static org.junit.Assert.*;

/** Actual blocked sockets and the existing catalog ticket; no Android or live-provider claim. */
public final class CatalogCancellationTest {
    private static ModelBinding binding(URI endpoint,long revision) {
        return new ModelBinding("fixture-connection","fixture-provider",ModelBinding.Protocol.OPENAI_CHAT,
                ModelBinding.Format.OPENAI,endpoint,"fixture-credential",revision,"fixture-installation",revision,false);
    }
    private static ModelCatalog catalog(Path file,ModelBinding binding)throws Exception {
        ModelCatalog catalog=new ModelCatalog(file,Clock.systemUTC());catalog.bind(binding);
        ModelCatalog.Ticket initial=catalog.beginRefresh(binding);long at=System.currentTimeMillis();
        var page=CatalogParser.parse(binding,"{\"data\":[{\"id\":\"retained-model\"}]}",at,1);
        catalog.commitRefresh(initial,new CatalogHttpClient.Snapshot(binding,at,page.models()));
        catalog.updateOwner(new ModelCatalog.ModelKey(binding,"retained-model"),0,
                Map.of(ModelFacts.Field.TOOL_CALLS,ModelFacts.OwnerValue.known(true)),Set.of());
        return catalog;
    }
    private static final class StalledServer implements AutoCloseable {
        final ServerSocket server;final Thread worker;final CountDownLatch entered=new CountDownLatch(1),disconnected=new CountDownLatch(1);
        final AtomicInteger requests=new AtomicInteger();volatile Socket client;volatile Throwable failure;volatile boolean closing;
        StalledServer(boolean bodyStarted)throws Exception {
            server=new ServerSocket();server.bind(new InetSocketAddress("127.0.0.1",0));
            worker=new Thread(()->{
                try(Socket accepted=server.accept()){
                    client=accepted;accepted.setSoTimeout(4000);InputStream input=accepted.getInputStream();
                    String first=line(input);assertTrue(first.startsWith("GET /v1/models "));
                    while(!line(input).isEmpty()){}requests.incrementAndGet();
                    if(bodyStarted){
                        accepted.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 10000\r\n\r\n{\"data\":[")
                                .getBytes(StandardCharsets.US_ASCII));accepted.getOutputStream().flush();
                    }
                    entered.countDown();if(input.read()==-1)disconnected.countDown();
                }catch(Throwable error){if(!closing)failure=error;}
            },"fixture-catalog-stall");worker.setDaemon(true);worker.start();
        }
        URI endpoint(){return URI.create("http://127.0.0.1:"+server.getLocalPort()+"/v1/models");}
        public void close()throws Exception {closing=true;server.close();Socket open=client;if(open!=null)open.close();worker.join(1000);}
    }
    private static String line(InputStream input)throws Exception {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();int previous=-1,next;
        while((next=input.read())>=0){
            if(previous=='\r'&&next=='\n'){byte[] raw=bytes.toByteArray();return new String(raw,0,raw.length-1,StandardCharsets.US_ASCII);}
            bytes.write(next);previous=next;if(bytes.size()>16384)throw new AssertionError("Fixture header exceeded bound");
        }
        throw new java.io.IOException("Fixture request ended before headers");
    }
    private static void interruptedFetch(boolean bodyStarted,boolean changeBinding)throws Exception {
        Path directory=Files.createTempDirectory("catalog-cancel-");Path file=directory.resolve("catalog.json");
        var caller=Executors.newSingleThreadExecutor();RunControl original=new RunControl();
        try(StalledServer server=new StalledServer(bodyStarted)){
            ModelBinding selected=binding(server.endpoint(),1);ModelCatalog catalog=catalog(file,selected);byte[] before=Files.readAllBytes(file);
            ModelCatalog.Ticket ticket=catalog.beginRefresh(selected);
            // This is the existing native model page's source: the same RunControl plus current ticket.
            BooleanSupplier current=()->!original.isCancelled()&&catalog.isCurrent(ticket);
            var pending=caller.submit(()->{
                var fetched=new CatalogHttpClient(Clock.systemUTC(),5000).fetch(selected,CatalogHttpClient.Headers.none(),current);
                return catalog.commitRefresh(ticket,fetched);
            });
            assertTrue("The original catalog GET must actually be blocked",server.entered.await(2,TimeUnit.SECONDS));
            if(changeBinding)catalog.bind(binding(server.endpoint(),2));else original.cancel();
            ExecutionException ended=assertThrows("Invalidated catalog GET must finish before its 5s deadline",ExecutionException.class,
                    ()->pending.get(1200,TimeUnit.MILLISECONDS));
            assertTrue(ended.getCause() instanceof CatalogException);
            assertEquals(CatalogException.Code.CANCELLED,((CatalogException)ended.getCause()).code);
            assertTrue("Cancellation must close the original socket",server.disconnected.await(1,TimeUnit.SECONDS));
            assertArrayEquals("No partial catalog or owner-fact change may be persisted",before,Files.readAllBytes(file));
            assertEquals("No retry or next page may be requested",1,server.requests.get());assertNull(server.failure);
            if(!changeBinding){
                var retained=catalog.get(new ModelCatalog.ModelKey(selected,"retained-model"),60000);
                assertTrue(retained.facts().toolCalls().known);assertTrue(retained.facts().toolCalls().value);
            }
        }finally{
            original.cancel();caller.shutdownNow();caller.awaitTermination(2,TimeUnit.SECONDS);
            try(var paths=Files.walk(directory)){for(Path path:paths.sorted(java.util.Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}
        }
    }
    @Test public void modelPageCancelInterruptsStalledResponseHeaders()throws Exception {interruptedFetch(false,false);}
    @Test public void modelPageCancelInterruptsStalledBodyWithoutPublishingAPartialCatalog()throws Exception {interruptedFetch(true,false);}
    @Test public void changingTheExactBindingInterruptsTheOriginalStalledGet()throws Exception {interruptedFetch(false,true);}
}
