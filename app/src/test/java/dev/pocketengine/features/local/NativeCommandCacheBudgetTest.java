package dev.pocketengine.features.local;

import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.json.*;
import org.junit.Test;

/** Synthetic historical JSON cache observations only, not a process-exit proof,
 * heap/OOM benchmark, authority restoration or physical device measurement. */
public final class NativeCommandCacheBudgetTest {
    @Test public void coldOriginalRecordsRetainOnlyBoundedPreviewsWhileExactBodyStillReadsFromTheOriginalFile()throws Exception {
        Path root=Files.createTempDirectory("legacy-original-cache-budget-");int records=20;String body="量".repeat(16384);Map<Path,byte[]> originals=new LinkedHashMap<>();
        try{for(int i=0;i<records;i++){String id="original-"+i;byte[] bytes=new JSONObject().put("id",id).put("operationId",id).put("checkoutId","own-checkout").put("fingerprint","original argv identity").put("status","completed").put("output",body).put("stdout",body).put("stderr",body).toString().getBytes(StandardCharsets.UTF_8);Path file=Files.write(root.resolve(id+".json"),bytes);originals.put(file,bytes);}
            try(var commands=new NativeCommandExecutor(root.toFile(),List.of(),event->fail("cache read dispatched or changed a task"))){
                var field=NativeCommandExecutor.class.getDeclaredField("jobs");field.setAccessible(true);Map<?,?> originalCache=(Map<?,?>)field.get(commands);long retained=0;for(Object row:originalCache.values()){JSONObject record=(JSONObject)row;for(String key:List.of("output","stdout","stderr","outputPreview"))retained+=record.optString(key).length();}
                System.out.println("synthetic original command cache records="+records+" retainedOutputUtf16Chars="+retained);
                assertTrue("cold cache must not retain every historical full body",retained<=2000L*records);
                JSONObject exact=commands.get("original-0");assertEquals(body,exact.getString("output"));assertEquals(body,exact.getString("stdout"));assertEquals(body,exact.getString("stderr"));assertEquals("completed",exact.getString("status"));assertFalse(commands.busy("own-checkout"));
                JSONArray listed=commands.list();assertEquals(records,listed.length());for(int i=0;i<listed.length();i++){JSONObject row=listed.getJSONObject(i);assertFalse(row.has("output"));assertFalse(row.has("stdout"));assertFalse(row.has("stderr"));assertEquals(body.substring(body.length()-2000),row.getString("outputPreview"));}
                for(var original:originals.entrySet())assertArrayEquals(original.getValue(),Files.readAllBytes(original.getKey()));
            }
        }finally{try(var paths=Files.walk(root)){for(Path file:paths.sorted(java.util.Comparator.reverseOrder()).toList())Files.deleteIfExists(file);}}
    }
    @Test public void aChangedOriginalBodyIsNotReturnedFromTheCacheAsTheApprovedSnapshotOrAnEmptySuccess()throws Exception {
        Path root=Files.createTempDirectory("legacy-cache-original-pin-");Path file=root.resolve("original.json");
        try{JSONObject original=new JSONObject().put("id","original").put("operationId","original").put("checkoutId","own-checkout").put("fingerprint","same args").put("status","completed").put("output","original exact body").put("stdout","original exact body").put("stderr","");Files.write(file,original.toString().getBytes(StandardCharsets.UTF_8));
            try(var commands=new NativeCommandExecutor(root.toFile(),List.of(),event->fail("readonly cache started work"))){byte[] changed=new JSONObject(original.toString()).put("output","different same-operation body").put("stdout","different same-operation body").toString().getBytes(StandardCharsets.UTF_8);Files.write(file,changed);assertThrows(java.io.IOException.class,()->commands.get("original"));assertEquals("original exact body",commands.list().getJSONObject(0).getString("outputPreview"));assertTrue(commands.busy("own-checkout"));assertTrue(commands.receiptRecovery().getBoolean("originalInventoryUnconfirmed"));assertArrayEquals(changed,Files.readAllBytes(file));assertThrows(java.io.IOException.class,()->commands.get("original"));assertEquals(1,commands.receiptRecovery().getInt("unreadableOriginalRecordsRetained"));}
        }finally{try(var paths=Files.walk(root)){for(Path path:paths.sorted(java.util.Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}
    }
    @Test public void originalNativeWorkerCompactsOnlyAfterSavingAndExactRepeatedObservationDoesNotRestartIt()throws Exception {
        Path root=Files.createTempDirectory("legacy-cache-warm-final-");String body="original warm body\n".repeat(1000);Path input=Files.write(root.resolve("input.txt"),body.getBytes(StandardCharsets.UTF_8));int[] starts={0};
        try(var commands=new NativeCommandExecutor(root.resolve("receipts").toFile(),List.of(new java.io.File("/usr/bin"),new java.io.File("/bin")),event->{if(event.optString("status").equals("running"))starts[0]++;})){
            commands.submit("own-checkout",root.toFile(),new JSONArray().put("cat").put(input.getFileName().toString()),"original",3);long until=System.nanoTime()+5_000_000_000L;JSONObject result=null;while(System.nanoTime()<until){result=commands.get("original");if(!List.of("queued","running","stopping").contains(result.optString("status")))break;Thread.sleep(10);}assertNotNull(result);assertEquals("completed",result.getString("status"));assertEquals(body,result.getString("stdout"));
            var field=NativeCommandExecutor.class.getDeclaredField("jobs");field.setAccessible(true);JSONObject cache=null;while(System.nanoTime()<until){synchronized(commands){cache=(JSONObject)((Map<?,?>)field.get(commands)).get("original");if(!cache.has("output"))break;}Thread.sleep(5);}assertNotNull(cache);assertFalse(cache.has("output"));assertFalse(cache.has("stdout"));assertFalse(cache.has("stderr"));assertEquals(2000,cache.getString("outputPreview").length());assertFalse(commands.busy("own-checkout"));
            assertEquals(body,commands.get("original").getString("stdout"));assertEquals(body,commands.submit("own-checkout",root.toFile(),new JSONArray().put("cat").put("input.txt"),"original",3).getString("stdout"));assertEquals(1,starts[0]);for(String privateKey:List.of("_originalReceiptSha256","_bodyOnDisk","_cachedOriginalSha256","_cachedFingerprintSha256"))assertFalse(commands.get("original").has(privateKey));
        }finally{try(var paths=Files.walk(root)){for(Path path:paths.sorted(java.util.Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}
    }
    @Test public void diskCacheMarkersCannotHideAnOriginalOperationOrRestoreItsColdExecutionAuthority()throws Exception {
        Path root=Files.createTempDirectory("legacy-cache-marker-not-authority-");Path file=root.resolve("original.json");byte[] raw=new JSONObject().put("id","original").put("operationId","original").put("checkoutId","own-checkout").put("status","running").put("output","actual original prefix").put("_bodyOnDisk",true).put("_cachedOriginalSha256","a".repeat(64)).put("_cachedFingerprintSha256","b".repeat(64)).toString().getBytes(StandardCharsets.UTF_8);
        try{Files.write(file,raw);try(var commands=new NativeCommandExecutor(root.toFile(),List.of(),event->fail("disk marker restored a dispatch"))){assertEquals(1,commands.list().length());JSONObject original=commands.get("original");assertEquals("unknown",original.getString("status"));assertEquals("actual original prefix",original.getString("output"));assertTrue(commands.busy("own-checkout"));assertFalse(original.has("_bodyOnDisk"));assertFalse(original.has("_cachedOriginalSha256"));assertFalse(original.has("_originalReceiptSha256"));assertArrayEquals(raw,Files.readAllBytes(file));}}
        finally{try(var paths=Files.walk(root)){for(Path path:paths.sorted(java.util.Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}
    }
    @Test(timeout=15000) public void failedFinalPersistenceKeepsTheOriginalObservedBodyInMemoryInsteadOfAFalseDiskReference()throws Exception {
        Path root=Files.createTempDirectory("legacy-cache-failed-save-"),tools=Files.createDirectory(root.resolve("tools")),release=root.resolve("allow-exit"),ready=root.resolve("tool-ready"),receipts=root.resolve("receipts"),retained=root.resolve("retained-original-receipts");
        String script="#!/bin/sh\nprintf 'complete original output\\n'\nprintf ready > tool-ready\nwhile [ ! -f allow-exit ]; do /bin/sleep 0.01; done\n";Path tool=Files.write(tools.resolve("pwd"),script.getBytes(StandardCharsets.UTF_8));assertTrue(tool.toFile().setExecutable(true));
        try(var commands=new NativeCommandExecutor(receipts.toFile(),List.of(tools.toFile()),event->{})){
            commands.submit("own-checkout",root.toFile(),new JSONArray().put("pwd"),"original",5);long until=System.nanoTime()+5_000_000_000L;while((!Files.exists(ready)||!commands.get("original").optString("status").equals("running"))&&System.nanoTime()<until)Thread.sleep(10);assertTrue(Files.exists(ready));
            byte[] original=Files.readAllBytes(receipts.resolve("original.json"));Files.move(receipts,retained);Files.write(receipts,"own fixture blocks later persistence; originals are retained beside it".getBytes(StandardCharsets.UTF_8));Files.write(release,new byte[0]);JSONObject observed=null;
            while(System.nanoTime()<until){observed=commands.get("original");if(!List.of("queued","running","stopping").contains(observed.optString("status")))break;Thread.sleep(10);}assertNotNull(observed);assertEquals("unknown",observed.getString("status"));assertEquals("complete original output\n",observed.getString("stdout"));
            var field=NativeCommandExecutor.class.getDeclaredField("jobs");field.setAccessible(true);JSONObject cache;synchronized(commands){cache=(JSONObject)((Map<?,?>)field.get(commands)).get("original");}assertTrue(cache.has("stdout"));assertFalse(cache.optBoolean("_bodyOnDisk"));assertArrayEquals(original,Files.readAllBytes(retained.resolve("original.json")));
        }finally{if(!Files.exists(release))Files.write(release,new byte[0]);try(var paths=Files.walk(root)){for(Path path:paths.sorted(java.util.Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}
    }
}
