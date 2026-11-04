package dev.pocketengine.features.workspace;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.pocketengine.core.RunControl;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

/** Real loopback HTTP; only the package-local target constructor permits cleartext fixtures. */
public final class OwnerBackupWebDavClientTest {
    private static final String ACTION="51f862bd-47b9-4300-8902-ff951409a6c9";
    @Test public void oneConditionalPutCreatesVersionAndExactReadbackChecksBytes()throws Exception {
        try(Fixture f=new Fixture()){
            AtomicInteger entered=new AtomicInteger();var reply=f.client.put(f.target,ACTION,f.file,f.bytes.length,f.sha,"",new RunControl(),entered::incrementAndGet);
            assertEquals("accepted",reply.status);assertEquals(201,reply.httpStatus);assertEquals(1,entered.get());assertArrayEquals(f.bytes,f.saved);
            ByteArrayOutputStream copy=new ByteArrayOutputStream();f.client.readBack(f.target,ACTION,f.bytes.length,f.sha,"",copy,new RunControl());assertArrayEquals(f.bytes,copy.toByteArray());
            var conflict=f.client.put(f.target,ACTION,f.file,f.bytes.length,f.sha,"",new RunControl(),()->{});assertEquals(412,conflict.httpStatus);assertEquals("rejected",conflict.status);assertEquals(1,f.effects.get());assertArrayEquals(f.bytes,f.saved);f.checked();
        }
    }
    @Test public void lostAckIsNotRetriedAndOnlyExactGetCanObserveTheStoredFile()throws Exception {
        try(Fixture f=new Fixture()){
            f.drop=true;assertThrows(IOException.class,()->f.client.put(f.target,ACTION,f.file,f.bytes.length,f.sha,"",new RunControl(),()->{}));assertEquals(1,f.puts.get());assertEquals(1,f.effects.get());
            f.drop=false;ByteArrayOutputStream actual=new ByteArrayOutputStream();new OwnerBackupWebDavClient().readBack(f.target,ACTION,f.bytes.length,f.sha,"",actual,new RunControl());assertArrayEquals(f.bytes,actual.toByteArray());assertEquals(1,f.puts.get());
            f.saved=f.saved.clone();f.saved[30]^=1;assertThrows(SecurityException.class,()->f.client.readBack(f.target,ACTION,f.bytes.length,f.sha,"",new ByteArrayOutputStream(),new RunControl()));assertEquals(1,f.puts.get());f.checked();
        }
    }
    @Test public void redirectNeverForwardsCredentialsOrRepeatsTheBody()throws Exception {
        try(Fixture f=new Fixture()){
            f.redirect=true;var result=f.client.put(f.target,ACTION,f.file,f.bytes.length,f.sha,"Basic Zml4dHVyZTpzZWNyZXQ=",new RunControl(),()->{});
            assertEquals("unknown",result.status);assertEquals(307,result.httpStatus);assertEquals(1,f.puts.get());assertEquals(0,f.followed.get());assertEquals(0,f.effects.get());f.checked();
        }
    }
    @Test public void stoppedControlNeverEntersPutAndChangedFileFailsBeforeTheGate()throws Exception {
        try(Fixture f=new Fixture()){
            AtomicInteger entries=new AtomicInteger();RunControl stopped=new RunControl();stopped.cancel();
            assertThrows(Exception.class,()->f.client.put(f.target,ACTION,f.file,f.bytes.length,f.sha,"",stopped,entries::incrementAndGet));
            Files.write(f.file.toPath(),new byte[]{1,2,3});assertThrows(SecurityException.class,()->f.client.put(f.target,ACTION,f.file,f.bytes.length,f.sha,"",new RunControl(),entries::incrementAndGet));
            assertEquals(0,entries.get());assertEquals(0,f.puts.get());
        }
    }
    private static final class Fixture implements AutoCloseable {
        final HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);final ExecutorService workers=Executors.newCachedThreadPool();
        final OwnerBackupWebDavClient client=new OwnerBackupWebDavClient();final OwnerBackupWebDavTarget target;
        final File file;final byte[] bytes;final String sha;final AtomicInteger puts=new AtomicInteger(),effects=new AtomicInteger(),followed=new AtomicInteger();
        volatile byte[] saved;volatile boolean drop,redirect;volatile Throwable failed;
        Fixture()throws Exception {
            byte[] source=new byte[65540];for(int i=0;i<source.length;i++)source[i]=(byte)(i*7);
            ByteArrayOutputStream encrypted=new ByteArrayOutputStream();OwnerBackupEnvelope.encrypt(new ByteArrayInputStream(source),encrypted,source.length,OwnerBackupWebDavTarget.hex(MessageDigest.getInstance("SHA-256").digest(source)),"synthetic transport passphrase".toCharArray(),new RunControl());
            bytes=encrypted.toByteArray();sha=OwnerBackupWebDavTarget.hex(MessageDigest.getInstance("SHA-256").digest(bytes));file=Files.createTempFile("webdav-cipher-",".pwbackup").toFile();Files.write(file.toPath(),bytes);
            server.createContext("/",this::handle);server.setExecutor(workers);server.start();target=OwnerBackupWebDavTarget.loopback(server.getAddress().getPort());
        }
        private void handle(HttpExchange exchange)throws IOException {
            try{
                if(exchange.getRequestURI().getPath().equals("/redirected")){followed.incrementAndGet();exchange.sendResponseHeaders(200,-1);return;}
                assertEquals("/backups/PocketWorld-"+ACTION+".pwbackup",exchange.getRequestURI().getPath());
                if(exchange.getRequestMethod().equals("PUT")){
                    puts.incrementAndGet();assertEquals("*",exchange.getRequestHeaders().getFirst("If-None-Match"));byte[] body=exchange.getRequestBody().readAllBytes();
                    if(redirect){exchange.getResponseHeaders().set("Location","http://127.0.0.1:"+server.getAddress().getPort()+"/redirected");exchange.sendResponseHeaders(307,-1);return;}
                    if(saved!=null){exchange.sendResponseHeaders(412,-1);return;}
                    saved=body;effects.incrementAndGet();if(drop)return;exchange.sendResponseHeaders(201,-1);
                }else{assertEquals("GET",exchange.getRequestMethod());assertEquals("identity",exchange.getRequestHeaders().getFirst("Accept-Encoding"));exchange.sendResponseHeaders(200,saved.length);exchange.getResponseBody().write(saved);}
            }catch(Throwable failure){failed=failure;if(failure instanceof IOException io)throw io;}
            finally{exchange.close();}
        }
        void checked(){if(failed!=null)throw new AssertionError("Loopback protocol failed",failed);}
        @Override public void close()throws Exception {server.stop(0);workers.shutdownNow();Files.deleteIfExists(file.toPath());}
    }
}
