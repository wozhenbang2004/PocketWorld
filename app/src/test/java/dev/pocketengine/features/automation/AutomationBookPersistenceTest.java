package dev.pocketengine.features.automation;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public final class AutomationBookPersistenceTest {
    @Rule public final TemporaryFolder temp=new TemporaryFolder();
    private JSONObject once(String title,long at)throws Exception {
        return new JSONObject().put("title",title).put("kind","reminder")
                .put("schedule",new JSONObject().put("type","once").put("at",at));
    }
    private String seedClaim(File file)throws Exception {
        AutomationBook book=new AutomationBook(file);book.save(once("原规则",2000),1000);
        return book.claimDue(2000,10).getJSONObject(0).getString("id");
    }
    private static final class UnsupportedMove implements AutomationBook.FileMove {
        final List<CopyOption[]> calls=new ArrayList<>();
        final AtomicMoveNotSupportedException failure=new AtomicMoveNotSupportedException("staged","rules","fixture");
        @Override public void move(File source,File target,CopyOption... options)throws IOException {
            calls.add(options.clone());
            for(CopyOption option:options)if(option==StandardCopyOption.ATOMIC_MOVE)throw failure;
            // A fallback would really replace the destination, exposing the old behavior.
            Files.move(source.toPath(),target.toPath(),options);
        }
        void assertAtomicOnly(){
            assertEquals("must not retry with a non-atomic replace",1,calls.size());
            assertArrayEquals(new CopyOption[]{StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING},calls.get(0));
        }
    }
    @Test public void unsupportedRuleEditPreservesOriginalRuleAndReceiptBytes()throws Exception {
        File file=new File(temp.getRoot(),"rules.json");String receiptId=seedClaim(file);
        AutomationBook original=new AutomationBook(file);byte[] before=Files.readAllBytes(file.toPath());
        JSONObject edit=original.rules().getJSONObject(0).put("title","未保存的新标题");
        UnsupportedMove move=new UnsupportedMove();AutomationBook failing=new AutomationBook(file,null,null,move);
        IllegalStateException failure=assertThrows(IllegalStateException.class,()->failing.save(edit,2100));
        assertSame(move.failure,failure.getCause());assertTrue(failure.getMessage().contains("不支持原子保存"));move.assertAtomicOnly();
        assertArrayEquals(before,Files.readAllBytes(file.toPath()));
        AutomationBook reopened=new AutomationBook(file);assertEquals("原规则",reopened.rules().getJSONObject(0).getString("title"));
        assertEquals(receiptId,reopened.receipts().getJSONObject(0).getString("id"));assertEquals("dispatching",reopened.receipts().getJSONObject(0).getString("status"));
    }
    @Test public void unsupportedReceiptUpdatePreservesOriginalRuleAndReceiptBytes()throws Exception {
        File file=new File(temp.getRoot(),"rules.json");String receiptId=seedClaim(file);byte[] before=Files.readAllBytes(file.toPath());
        UnsupportedMove move=new UnsupportedMove();AutomationBook failing=new AutomationBook(file,null,null,move);
        IllegalStateException failure=assertThrows(IllegalStateException.class,()->failing.delivered(receiptId,"notified","未保存的新回执",2100));
        assertSame(move.failure,failure.getCause());move.assertAtomicOnly();assertArrayEquals(before,Files.readAllBytes(file.toPath()));
        assertEquals("dispatching",new AutomationBook(file).receipts().getJSONObject(0).getString("status"));
    }
    @Test public void unsupportedFirstSaveDoesNotPublishAFileOrRule()throws Exception {
        File file=new File(temp.getRoot(),"rules.json");UnsupportedMove move=new UnsupportedMove();
        AutomationBook failing=new AutomationBook(file,null,null,move);
        assertThrows(IllegalStateException.class,()->failing.save(once("未保存的规则",2000),1000));
        move.assertAtomicOnly();assertFalse(file.exists());AutomationBook reopened=new AutomationBook(file);
        assertEquals(0,reopened.rules().length());assertEquals(0,reopened.receipts().length());
        // A later real save must write fresh state, not publish the failed staged bytes.
        reopened.save(once("新的规则",3000),1000);AutomationBook saved=new AutomationBook(file);
        assertEquals(1,saved.rules().length());assertEquals("新的规则",saved.rules().getJSONObject(0).getString("title"));
    }
    @Test public void ordinaryMoveFailurePropagatesWithoutRetryOrChangingOriginalBytes()throws Exception {
        File file=new File(temp.getRoot(),"rules.json");seedClaim(file);byte[] before=Files.readAllBytes(file.toPath());
        AtomicInteger calls=new AtomicInteger();IOException fault=new IOException("fixture before move");
        AutomationBook failing=new AutomationBook(file,null,null,(source,target,options)->{calls.incrementAndGet();throw fault;});
        assertSame(fault,assertThrows(IOException.class,()->failing.save(once("未保存的规则",3000),2100)));
        assertEquals(1,calls.get());assertArrayEquals(before,Files.readAllBytes(file.toPath()));
    }
    @Test public void stagedWriteFailureDoesNotEnterMoveOrChangeOriginalBytes()throws Exception {
        File file=new File(temp.getRoot(),"rules.json");seedClaim(file);byte[] before=Files.readAllBytes(file.toPath());
        assertTrue(new File(file.getPath()+".new").mkdir());AtomicInteger calls=new AtomicInteger();
        AutomationBook failing=new AutomationBook(file,null,null,(source,target,options)->{calls.incrementAndGet();Files.move(source.toPath(),target.toPath(),options);});
        assertThrows(IOException.class,()->failing.save(once("未保存的规则",3000),2100));
        assertEquals(0,calls.get());assertArrayEquals(before,Files.readAllBytes(file.toPath()));
    }
    @Test public void realAtomicSaveReopensNewRuleAndRetainsOriginalReceipt()throws Exception {
        File file=new File(temp.getRoot(),"nested/rules.json");String receiptId=seedClaim(file);
        JSONObject saved=new AutomationBook(file).save(once("新的规则",3000),2100);AutomationBook reopened=new AutomationBook(file);
        assertEquals(2,reopened.rules().length());assertEquals(saved.getString("id"),reopened.rules().getJSONObject(1).getString("id"));
        assertEquals(receiptId,reopened.receipts().getJSONObject(0).getString("id"));assertEquals("dispatching",reopened.receipts().getJSONObject(0).getString("status"));
        assertFalse(new File(file.getPath()+".new").exists());
    }
}
