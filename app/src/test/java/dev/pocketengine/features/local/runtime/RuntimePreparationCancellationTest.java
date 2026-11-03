package dev.pocketengine.features.local.runtime;

import static org.junit.Assert.*;
import dev.pocketengine.features.local.project.ProjectSources;
import dev.pocketengine.testutil.HttpServer;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.*;
import org.junit.Test;

/** Actual loopback HTTP and original preparation Job. No remote server, download retry,
 * executable dispatch, second worker or physical Android-network claim. */
public final class RuntimePreparationCancellationTest {
    private static final byte[] ARCHIVE="exact pinned bytes for original cancellation fixture".getBytes(StandardCharsets.UTF_8);
    private static final int PREFIX=8;
    private static final class HeldResponse implements AutoCloseable {
        final ServerSocket listener=new ServerSocket(0,1,InetAddress.getLoopbackAddress());
        final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);final Thread worker;
        volatile Socket client;volatile Throwable failure;
        HeldResponse(boolean body)throws Exception {
            worker=new Thread(()->{try(Socket owned=listener.accept()) {
                client=owned;owned.setSoTimeout(5000);InputStream input=owned.getInputStream();int matched=0,total=0;
                while(matched<4){int value=input.read();if(value<0||++total>16384)throw new IOException("own fixture request incomplete");int expected="\r\n\r\n".charAt(matched);matched=value==expected?matched+1:value=='\r'?1:0;}
                if(body){OutputStream out=owned.getOutputStream();out.write(("HTTP/1.1 200 OK\r\nContent-Length: "+ARCHIVE.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));out.write(ARCHIVE,0,PREFIX);out.flush();}
                entered.countDown();release.await(20,TimeUnit.SECONDS);
            }catch(Exception stopped){if(!listener.isClosed())failure=stopped;}},"own-held-runtime-download");worker.setDaemon(true);worker.start();
        }
        okhttp3.Call open(okhttp3.Request original){return AndroidRuntimePreparation.downloadCall(original.newBuilder().url("http://127.0.0.1:"+listener.getLocalPort()+"/original-archive").build());}
        @Override public void close()throws Exception {release.countDown();listener.close();Socket owned=client;if(owned!=null)owned.close();worker.join(2000);assertFalse("Only original fixture server may remain",worker.isAlive());}
    }
    private static RuntimeInvocation invocation(Path directory,String operation)throws Exception {
        JSONObject source=RuntimeContractsTest.source();Files.write(directory.resolve("Main.java"),"class Main {}\n".getBytes(StandardCharsets.UTF_8));
        return new RuntimeInvocation(RuntimeContractsTest.exact(source,"termux.prepare",null),RuntimeContractsTest.binding(operation),source,RuntimeContractsTest.original(),()->{},new RuntimeWorkspace(directory.toFile(),"project",RuntimeContractsTest.CHECKOUT));
    }
    private static RuntimeReceipt await(ManagedProcessJobs jobs,RuntimeInvocation original,long millis)throws Exception {
        long end=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(millis);
        do{RuntimeReceipt row=jobs.query(original.executionRef());if(row.json().has("finishedAt"))return row;Thread.sleep(5);}while(System.nanoTime()<end);
        throw new AssertionError("Stopped original preparation still owns blocked download/lease after "+millis+"ms");
    }
    private static void cancelled(boolean body,int stop,boolean resume)throws Exception {
        Path root=Files.createTempDirectory("runtime-download-cancel-");HeldResponse response=null;ManagedProcessJobs jobs=null;RuntimeInvocation original=null;
        try {
            Path workspace=Files.createDirectory(root.resolve("workspace")),home=Files.createDirectory(root.resolve("home")),tmp=Files.createDirectory(root.resolve("tmp")),nativeLib=Files.createDirectory(root.resolve("native"));Files.write(home.resolve("user.txt"),"retain original HOME".getBytes(StandardCharsets.UTF_8));
            Path source=Path.of(Objects.requireNonNull(System.getProperty("pocket.gateway.source")));JSONObject descriptor=new JSONObject(new String(Files.readAllBytes(source.resolve("app/src/main/assets/runtime/catalog.json")),StandardCharsets.UTF_8));JSONObject artifact=descriptor.getJSONObject("rootfs").put("archiveBytes",ARCHIVE.length).put("archiveSha256",ProjectSources.sha256(ARCHIVE));
            jobs=new ManagedProcessJobs(root.toFile(),new NativeProcessSupervisor());RuntimeEnvironment environment=new RuntimeEnvironment(root.toFile(),jobs::requireEnvironmentIdle);response=new HeldResponse(body);HeldResponse exact=response;
            AndroidRuntimePreparation preparation=new AndroidRuntimePreparation(root.toFile(),nativeLib.toFile(),new AndroidRuntimeCatalog(descriptor),environment,home.toFile(),tmp.toFile(),exact::open);
            original=invocation(workspace,"blocked-"+body+"-"+stop);AtomicInteger released=new AtomicInteger();
            jobs.startPreparation(original,"a".repeat(64),context->{preparation.download(artifact,context);return new JSONObject();},released::incrementAndGet);
            assertTrue("Original loopback request not observed",response.entered.await(2,TimeUnit.SECONDS));Path cache=root.resolve("android-runtime/downloads"),partial=cache.resolve(artifact.getString("archiveSha256")+".part");
            if(body){long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);while((!Files.isRegularFile(partial)||Files.size(partial)<PREFIX)&&System.nanoTime()<end)Thread.sleep(5);assertArrayEquals(Arrays.copyOf(ARCHIVE,PREFIX),Files.readAllBytes(partial));}
            assertThrows(IllegalStateException.class,()->environment.requireGenerationIdle("a".repeat(64)));assertEquals(0,released.get());
            long cancelAt=System.nanoTime();if(stop==1)original.originalControl().cancel();else if(stop==2)jobs.close();else jobs.requestCancel(original.executionRef());
            assertTrue("The stop caller itself blocked on download I/O",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-cancelAt)<600);
            RuntimeReceipt stopped=await(jobs,original,1200);JSONObject actual=stopped.json();assertEquals("failed",stopped.status());assertTrue(actual.getBoolean("cancelRequested"));assertTrue(actual.getBoolean("receiptReceived"));assertTrue(actual.getBoolean("resourcesReleased"));assertFalse(actual.getBoolean("processLaunched"));assertFalse(actual.getBoolean("processDispatchAttempted"));assertEquals("not_dispatched",actual.getString("effectStatus"));assertEquals("interrupted",actual.getString("preparationPhase"));assertEquals(1,released.get());environment.requireGenerationIdle("a".repeat(64));
            assertFalse(Files.exists(cache.resolve(artifact.getString("archiveSha256")+".archive")));assertFalse(Files.exists(root.resolve("android-runtime/active.json")));assertEquals("retain original HOME",new String(Files.readAllBytes(home.resolve("user.txt")),StandardCharsets.UTF_8));if(body)assertArrayEquals(Arrays.copyOf(ARCHIVE,PREFIX),Files.readAllBytes(partial));
            assertNull(response.failure);
            if(resume){
                // A distinct original invocation/control consumes the retained prefix. The old
                // cancellation is never reset and cannot authorize or cancel this new call.
                response.close();response=null;AtomicInteger requests=new AtomicInteger();String[] range={null};HttpServer recovered=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
                recovered.createContext("/archive",exchange->{requests.incrementAndGet();range[0]=exchange.getRequestHeaders().getFirst("Range");exchange.getResponseHeaders().add("Content-Range","bytes "+PREFIX+"-"+(ARCHIVE.length-1)+"/"+ARCHIVE.length);exchange.sendResponseHeaders(206,ARCHIVE.length-PREFIX);exchange.getResponseBody().write(ARCHIVE,PREFIX,ARCHIVE.length-PREFIX);exchange.close();});recovered.start();
                try{
                    AndroidRuntimePreparation fresh=new AndroidRuntimePreparation(root.toFile(),nativeLib.toFile(),new AndroidRuntimeCatalog(descriptor),environment,home.toFile(),tmp.toFile(),request->AndroidRuntimePreparation.downloadCall(request.newBuilder().url("http://127.0.0.1:"+recovered.getAddress().getPort()+"/archive").build()));
                    RuntimeInvocation replacement=invocation(workspace,"fresh-original-preparation");original.originalControl().cancel();assertFalse(replacement.originalControl().isCancelled());
                    jobs.startPreparation(replacement,"a".repeat(64),context->{File complete=fresh.download(artifact,context);assertArrayEquals(ARCHIVE,Files.readAllBytes(complete.toPath()));return new JSONObject();},released::incrementAndGet);
                    assertEquals("completed",await(jobs,replacement,2000).status());assertEquals("bytes="+PREFIX+"-",range[0]);assertEquals(1,requests.get());assertEquals(2,released.get());assertArrayEquals(ARCHIVE,Files.readAllBytes(cache.resolve(artifact.getString("archiveSha256")+".archive")));assertFalse(Files.exists(partial));assertFalse(Files.exists(root.resolve("android-runtime/active.json")));
                }finally{recovered.stop(0);}
            }
        }finally{
            if(response!=null)response.close();if(jobs!=null){if(original!=null)try{await(jobs,original,3000);}catch(Exception|AssertionError settling){}jobs.close();}
            try(var paths=Files.walk(root)){for(Path path:paths.sorted(Comparator.reverseOrder()).collect(java.util.stream.Collectors.toList()))Files.deleteIfExists(path);}
        }
    }
    @Test public void originalJobStopInterruptsBlockedResponseHeaders()throws Exception{cancelled(false,0,false);}
    @Test public void originalRunStopInterruptsBlockedResponseHeaders()throws Exception{cancelled(false,1,false);}
    @Test public void originalJobStopPreservesExactPrefixAndInterruptsBlockedBody()throws Exception{cancelled(true,0,false);}
    @Test public void originalRunStopPreservesExactPrefixAndInterruptsBlockedBody()throws Exception{cancelled(true,1,false);}
    @Test public void originalJobsCloseCancelsOnlyItsOwnBlockedDownloadBeforeJoining()throws Exception{cancelled(true,2,false);}
    @Test public void retainedExactPrefixResumesWithOneRangeRequestThroughFreshOriginalInvocation()throws Exception{cancelled(true,0,true);}
    @Test public void retiredScopeDoesNotReceiveLaterOriginalCancellation()throws Exception {
        Path root=Files.createTempDirectory("runtime-io-scope-retired-");try(ManagedProcessJobs jobs=new ManagedProcessJobs(root.toFile(),new NativeProcessSupervisor())){
            Path workspace=Files.createDirectory(root.resolve("workspace"));RuntimeInvocation original=invocation(workspace,"retired-scope");AtomicInteger calls=new AtomicInteger();CountDownLatch retired=new CountDownLatch(1);
            jobs.startPreparation(original,"a".repeat(64),context->{try(AutoCloseable scope=context.cancelIoOnStop(calls::incrementAndGet)){context.check();}retired.countDown();while(true){context.check();Thread.sleep(5);}},()->{});
            assertTrue(retired.await(2,TimeUnit.SECONDS));original.originalControl().cancel();assertTrue(await(jobs,original,1200).json().getBoolean("resourcesReleased"));assertEquals(0,calls.get());
        }finally{try(var paths=Files.walk(root)){for(Path path:paths.sorted(Comparator.reverseOrder()).collect(java.util.stream.Collectors.toList()))Files.deleteIfExists(path);}}
    }
    @Test public void oneOriginalJobStopCannotCancelAnotherJobsScopeOrReinvokeAnOldScope()throws Exception {
        Path root=Files.createTempDirectory("runtime-io-scope-isolation-");try(ManagedProcessJobs jobs=new ManagedProcessJobs(root.toFile(),new NativeProcessSupervisor())){
            Path workspace=Files.createDirectory(root.resolve("workspace"));RuntimeInvocation first=invocation(workspace,"scope-first"),second=invocation(workspace,"scope-second");AtomicInteger firstCalls=new AtomicInteger(),secondCalls=new AtomicInteger();CountDownLatch entered=new CountDownLatch(2);
            jobs.startPreparation(first,"a".repeat(64),context->{try(AutoCloseable scope=context.cancelIoOnStop(firstCalls::incrementAndGet)){entered.countDown();while(true){context.check();Thread.sleep(5);}}},()->{});
            jobs.startPreparation(second,"b".repeat(64),context->{try(AutoCloseable scope=context.cancelIoOnStop(secondCalls::incrementAndGet)){entered.countDown();while(true){context.check();Thread.sleep(5);}}},()->{});
            assertTrue(entered.await(2,TimeUnit.SECONDS));jobs.requestCancel(first.executionRef());assertTrue(await(jobs,first,1200).json().getBoolean("resourcesReleased"));assertEquals(1,firstCalls.get());assertEquals(0,secondCalls.get());assertFalse(second.originalControl().isCancelled());assertFalse(jobs.query(second.executionRef()).json().getBoolean("resourcesReleased"));
            first.originalControl().cancel();assertEquals(1,firstCalls.get());jobs.requestCancel(second.executionRef());assertTrue(await(jobs,second,1200).json().getBoolean("resourcesReleased"));assertEquals(1,secondCalls.get());
        }finally{try(var paths=Files.walk(root)){for(Path path:paths.sorted(Comparator.reverseOrder()).collect(java.util.stream.Collectors.toList()))Files.deleteIfExists(path);}}
    }
}
