package dev.pocketengine.features.automation;

import dev.pocketengine.core.RunControl;
import java.io.File;
import java.nio.file.Files;
import java.util.UUID;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public final class AutomationBackupTest {
    @Rule public final TemporaryFolder temp=new TemporaryFolder();
    private JSONObject daily()throws Exception{return new JSONObject().put("title","原提醒").put("kind","reminder").put("schedule",new JSONObject().put("type","daily").put("time","09:00").put("zone","UTC"));}
    private JSONObject archive()throws Exception{AutomationBook source=new AutomationBook(new File(temp.newFolder(),"rules.json"));source.save(daily(),1000);return source.exportBackupForOwner(new RunControl());}
    @Test public void restoreCreatesDisabledIdentityAndColdQueryNeverReplays()throws Exception {
        JSONObject section=archive();File file=new File(temp.newFolder(),"rules.json");AutomationBook target=new AutomationBook(file);
        JSONObject existing=target.save(daily(),1000);String action=UUID.randomUUID().toString();var review=target.reviewBackupForOwner(section,action,new RunControl());
        assertEquals(1,review.metadata().getJSONArray("sameNameConflicts").length());assertEquals(1,target.rules().length());
        JSONObject result=review.restore();assertTrue(result.getBoolean("found"));assertEquals(2,target.rules().length());
        JSONObject restored=target.rules().getJSONObject(1);assertFalse(restored.getBoolean("enabled"));assertTrue(restored.getBoolean("taskRequestsRevoked"));assertTrue(restored.isNull("nextAt"));
        assertNotEquals(existing.getString("id"),restored.getString("id"));assertNotEquals(section.getJSONObject("content").getJSONArray("rules").getJSONObject(0).getString("sourceId"),restored.getString("id"));assertEquals(0,target.receipts().length());
        AutomationBook cold=new AutomationBook(file);assertTrue(cold.queryBackupForOwner(section,action,new RunControl()).getBoolean("found"));assertEquals(2,cold.rules().length());assertThrows(SecurityException.class,review::restore);
        cold.save(new JSONObject(restored.toString()).put("title","本人后来编辑"),2000);assertTrue(cold.queryBackupForOwner(section,action,new RunControl()).getBoolean("found"));
    }
    @Test public void staleReviewAndCancelledControlCannotOverwriteCurrentRules()throws Exception {
        JSONObject section=archive();AutomationBook target=new AutomationBook(new File(temp.newFolder(),"rules.json"));String action=UUID.randomUUID().toString();var stale=target.reviewBackupForOwner(section,action,new RunControl());
        target.save(daily().put("title","审阅后的新提醒"),1000);String before=target.rules().toString();assertThrows(SecurityException.class,stale::restore);assertEquals(before,target.rules().toString());assertFalse(target.queryBackupForOwner(section,action,new RunControl()).getBoolean("found"));
        RunControl original=new RunControl();var cancelled=target.reviewBackupForOwner(section,UUID.randomUUID().toString(),original);original.cancel();assertThrows(InterruptedException.class,cancelled::restore);assertEquals(before,target.rules().toString());
    }
    @Test public void failedAtomicPublishKeepsOldFileAndQueryReportsMissingWithoutRetry()throws Exception {
        JSONObject section=archive();File file=new File(temp.newFolder(),"rules.json");AutomationBook initial=new AutomationBook(file);initial.save(daily().put("title","保留原条目"),1000);byte[] before=Files.readAllBytes(file.toPath());
        AutomationBook failing=new AutomationBook(file,null,null,(source,target,options)->{throw new java.io.IOException("injected atomic publish failure");});String action=UUID.randomUUID().toString();var review=failing.reviewBackupForOwner(section,action,new RunControl());
        assertThrows(java.io.IOException.class,review::restore);assertArrayEquals(before,Files.readAllBytes(file.toPath()));assertFalse(new AutomationBook(file).queryBackupForOwner(section,action,new RunControl()).getBoolean("found"));assertThrows(SecurityException.class,review::restore);
    }
}
