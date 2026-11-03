package dev.pocketengine.features.local;

import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.*;
import org.junit.Test;

/** Cold original legacy read-only command records, never a replacement managed
 * process ledger or evidence that an unobserved old process has exited. */
public final class NativeCommandReceiptRecoveryTest {
    private static byte[] record(String id,String status)throws Exception{return new JSONObject().put("id",id).put("operationId",id).put("checkoutId","own-checkout").put("status",status).put("output","original output").put("stdout","original output").put("stderr","").put("verified",false).toString().getBytes(StandardCharsets.UTF_8);}
    private static NativeCommandExecutor open(Path receipts,AtomicInteger events)throws Exception{return new NativeCommandExecutor(receipts.toFile(),List.of(),event->events.incrementAndGet());}
    private static void clean(Path root)throws Exception{try(var files=Files.walk(root)){for(Path path:files.sorted(java.util.Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}
    @Test public void aTornOriginalReceiptCannotDisableReadableOriginalRecordsOrRewriteTheirBytes()throws Exception {
        Path root=Files.createTempDirectory("legacy-receipt-torn-");AtomicInteger events=new AtomicInteger();byte[] damaged="{\"id\":\"unfinished".getBytes(StandardCharsets.UTF_8),valid=record("good","completed");
        try{Files.write(root.resolve("damaged.json"),damaged);Files.write(root.resolve("good.json"),valid);try(var commands=open(root,events)){
            assertEquals("completed",commands.get("good").getString("status"));assertEquals(1,commands.list().length());assertEquals(0,events.get());assertArrayEquals(damaged,Files.readAllBytes(root.resolve("damaged.json")));assertArrayEquals(valid,Files.readAllBytes(root.resolve("good.json")));
        }}finally{clean(root);}
    }
    @Test public void coldRunningIsUnconfirmedRatherThanInterruptedAndNeverChangesTheOriginalReceipt()throws Exception {
        Path root=Files.createTempDirectory("legacy-receipt-cold-");AtomicInteger events=new AtomicInteger();byte[] bytes=record("original","running");
        try{for(String status:List.of("queued","running","stopping","unknown","interrupted")){bytes=record("original",status);Files.write(root.resolve("original.json"),bytes);try(var commands=open(root,events)){
            assertEquals("unknown",commands.get("original").getString("status"));assertTrue(commands.busy("own-checkout"));assertFalse(commands.busy("unrelated-checkout"));assertEquals("unknown",commands.cancel("original").getString("status"));assertArrayEquals(bytes,Files.readAllBytes(root.resolve("original.json")));assertEquals(0,events.get());assertEquals("recorded_legacy_checkouts",commands.receiptRecovery().getString("blockingScope"));
        }}}finally{clean(root);}
    }
    @Test public void filenameAndOriginalOperationIdentityCannotBeAliasedToAnotherRecord()throws Exception {
        Path root=Files.createTempDirectory("legacy-receipt-identity-");byte[] bytes=record("aliased","completed");
        try{Files.write(root.resolve("different-name.json"),bytes);try(var commands=open(root,new AtomicInteger())){assertEquals(0,commands.list().length());assertThrows(IllegalArgumentException.class,()->commands.get("aliased"));assertArrayEquals(bytes,Files.readAllBytes(root.resolve("different-name.json")));}}
        finally{clean(root);}
    }
    @Test public void linkedSpecialOrMalformedUtf8RecordsAreNotReadAsAJobAndOriginalBytesRemain()throws Exception {
        Path base=Files.createTempDirectory("legacy-receipt-no-follow-"),root=Files.createDirectory(base.resolve("receipts"));byte[] secret=record("linked","completed"),malformed="{\"id\":\"malformed\",\"operationId\":\"malformed\",\"status\":\"completed\",\"checkoutId\":\"own-checkout\",\"output\":\"~\"}".getBytes(StandardCharsets.UTF_8);malformed[malformed.length-3]=(byte)0xc3;
        try{Path outside=Files.write(base.resolve("outside.json"),secret);Files.createSymbolicLink(root.resolve("linked.json"),outside);Files.write(root.resolve("malformed.json"),malformed);Files.createDirectory(root.resolve("directory.json"));try(var commands=open(root,new AtomicInteger())){assertEquals(0,commands.list().length());assertEquals(3,commands.receiptRecovery().getInt("unreadableOriginalRecordsRetained"));assertTrue(commands.busy("any-checkout"));assertArrayEquals(secret,Files.readAllBytes(outside));assertArrayEquals(malformed,Files.readAllBytes(root.resolve("malformed.json")));}
            Path rootLink=base.resolve("linked-root");Files.createSymbolicLink(rootLink,root);try(var commands=open(rootLink,new AtomicInteger())){assertEquals(0,commands.list().length());assertTrue(commands.receiptRecovery().getBoolean("originalInventoryUnconfirmed"));}
        }finally{clean(base);}
    }
    @Test public void unreadableInventoryCannotStartAReplacementCommandOrReuseAnUnobservedOperation()throws Exception {
        Path root=Files.createTempDirectory("legacy-receipt-fence-");AtomicInteger events=new AtomicInteger();
        try{Files.write(root.resolve("original.json"),"{torn".getBytes(StandardCharsets.UTF_8));try(var commands=new NativeCommandExecutor(root.toFile(),List.of(new java.io.File("/usr/bin"),new java.io.File("/bin")),event->events.incrementAndGet())){
            for(String id:List.of("replacement","original")){IllegalStateException stopped=assertThrows(IllegalStateException.class,()->commands.submit("own-checkout",root.toFile(),new JSONArray().put("pwd"),id,2));assertTrue(stopped.getMessage().contains("回执归属尚未核对"));}assertEquals(0,commands.list().length());assertFalse(Files.exists(root.resolve("replacement.json")));assertEquals("{torn",new String(Files.readAllBytes(root.resolve("original.json")),StandardCharsets.UTF_8));assertEquals(0,events.get());assertFalse(commands.receiptRecovery().getBoolean("executionAuthorityRestored"));assertFalse(commands.receiptRecovery().getBoolean("originalFilesChangedByRecovery"));
        }}finally{clean(root);}
    }
}
