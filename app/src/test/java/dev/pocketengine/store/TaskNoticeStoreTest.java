package dev.pocketengine.store;

import android.app.Application;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.core.RunJournal;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Exercises the original TaskStore SQL and real RunControl; delivery is a counted native boundary. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class TaskNoticeStoreTest {
    private static final class Fixture {
        final TaskStore tasks=new TaskStore(RuntimeEnvironment.getApplication());final RunControl control=new RunControl();final String task,run;final int epoch;final RunJournal journal;
        Fixture()throws Exception {
            JSONObject live=tasks.startAttempt(tasks.createCapabilityTask("private owner result",UUID.randomUUID().toString()).getString("id"),"capabilities",20);
            task=live.getString("id");run=live.getString("runId");epoch=live.getInt("coordinatorEpoch");control.bindHostRun(task,run,epoch);tasks.authorizeTools(task,run,new JSONArray().put("post_notice"));journal=tasks.journal(task,run,epoch,control);
        }
        String approval(JSONObject args)throws Exception {
            JSONObject capability=new JSONObject().put("feature","assistant").put("command","$post_notice");JSONObject pending=tasks.requireCapabilityApproval(task,run,"post_notice",args,capability);
            tasks.decideCapabilityApproval(task,pending.getString("approvalId"),true);return tasks.requireCapabilityApproval(task,run,"post_notice",args,capability).getString("approvalId");
        }
        JSONObject post(String operation,JSONObject args,String approval,TaskStore.TaskNoticeDelivery delivery)throws Exception{return tasks.postTaskNoticeForHost(task,run,epoch,operation,args,approval,control,delivery);}
        JSONObject operation(String operation)throws Exception {JSONArray rows=tasks.get(task,false).getJSONArray("operations");for(int i=0;i<rows.length();i++)if(operation.equals(rows.getJSONObject(i).getString("operationId")))return rows.getJSONObject(i);throw new AssertionError("missing original operation");}
    }
    private static JSONObject args(String key)throws Exception{return new JSONObject().put("title","private account result").put("body","private result details").put("dedupeKey",key);}
    @Test public void originalUnknownIsCommittedBeforeEffectAndSameKeyCannotSendAgain()throws Exception {
        Fixture f=new Fixture();JSONObject args=args("result");String approval=f.approval(args),operation=f.journal.beginOperation("post_notice",args);AtomicInteger effects=new AtomicInteger();
        JSONObject posted=f.post(operation,args,approval,(notice,entry)->entry.dispatch(()->{assertEquals("unknown",f.operation(operation).getString("state"));effects.incrementAndGet();return notice.receipt("posted","notification_api_accepted");}));
        assertTrue(posted.getBoolean("accepted"));assertFalse(posted.getBoolean("deliveryConfirmed"));assertFalse(posted.getBoolean("userReadConfirmed"));
        String duplicateApproval=f.approval(args),duplicate=f.journal.beginOperation("post_notice",args);
        JSONObject receipt=f.post(duplicate,args,duplicateApproval,(notice,entry)->{throw new AssertionError("duplicate cannot enter delivery");});assertEquals(operation,receipt.getString("duplicateOfOperationId"));assertEquals(1,effects.get());
        JSONObject changed=new JSONObject(args.toString()).put("body","changed details");String changedApproval=f.approval(changed),changedOperation=f.journal.beginOperation("post_notice",changed);
        assertThrows(SecurityException.class,()->f.post(changedOperation,changed,changedApproval,(notice,entry)->{throw new AssertionError("changed duplicate cannot enter delivery");}));assertEquals(1,effects.get());
    }
    @Test public void lostAcknowledgementRemainsUnknownThroughColdRestoreWithoutReplay()throws Exception {
        Fixture f=new Fixture();JSONObject args=args("unknown");String approval=f.approval(args),operation=f.journal.beginOperation("post_notice",args);AtomicInteger effects=new AtomicInteger();
        JSONObject unknown=f.post(operation,args,approval,(notice,entry)->entry.dispatch(()->{effects.incrementAndGet();throw new java.io.IOException("lost after native effect");}));
        assertTrue(unknown.isNull("accepted"));assertFalse(unknown.getBoolean("replayAllowed"));assertEquals("unknown",f.operation(operation).getString("state"));
        TaskStore cold=new TaskStore(RuntimeEnvironment.getApplication());
        assertThrows(Exception.class,()->cold.postTaskNoticeForHost(f.task,f.run,f.epoch,operation,args,approval,f.control,(notice,entry)->{effects.incrementAndGet();return notice.receipt("posted","notification_api_accepted");}));
        assertThrows(SecurityException.class,()->cold.resolveOperation(f.task,operation,"not_occurred"));
        JSONObject opened=cold.openOriginalTaskNotice(f.task,f.run,f.epoch,operation,unknown.getString("token"));assertTrue(opened.getBoolean("expired"));assertFalse(opened.getBoolean("notificationReplayed"));assertEquals(1,effects.get());
        JSONObject content=opened.getJSONObject("noticeContent");assertEquals(args.getString("title"),content.getString("title"));assertEquals(args.getString("body"),content.getString("body"));assertEquals(f.run,content.getString("runId"));assertEquals(operation,content.getString("operationId"));
    }
    @Test public void pauseAfterUnknownCommitCannotEnterTheNativeEffect()throws Exception {
        Fixture f=new Fixture();JSONObject args=args("paused");String approval=f.approval(args),operation=f.journal.beginOperation("post_notice",args);AtomicInteger effects=new AtomicInteger();
        f.control.setAuthorityCheck(()->{if("unknown".equals(f.operation(operation).getString("state")))f.control.pause();});
        JSONObject result=f.post(operation,args,approval,(notice,entry)->entry.dispatch(()->{effects.incrementAndGet();return notice.receipt("posted","notification_api_accepted");}));
        assertEquals(0,effects.get());assertTrue(result.isNull("accepted"));assertEquals("unknown",f.operation(operation).getString("state"));
    }
    @Test public void aSecondDistinctNoticeIsRateLimitedAndAWrapperCannotOverwriteTheNativeReceipt()throws Exception {
        Fixture f=new Fixture();AtomicInteger effects=new AtomicInteger();JSONObject first=args("first");String approval=f.approval(first),operation=f.journal.beginOperation("post_notice",first);
        f.post(operation,first,approval,(notice,entry)->entry.dispatch(()->{effects.incrementAndGet();return notice.receipt("posted","notification_api_accepted");}));
        f.journal.finishOperation(operation,new JSONObject().put("status","unknown").put("accepted",JSONObject.NULL));assertEquals("posted",f.operation(operation).getJSONObject("receipt").getString("phase"));assertEquals("receipt",f.operation(operation).getString("state"));
        JSONObject second=args("second");String nextApproval=f.approval(second),next=f.journal.beginOperation("post_notice",second);
        JSONObject limited=f.post(next,second,nextApproval,(notice,entry)->entry.dispatch(()->{effects.incrementAndGet();return notice.receipt("posted","notification_api_accepted");}));
        assertEquals("rate_limited",limited.getString("phase"));assertFalse(limited.getBoolean("accepted"));assertEquals(1,effects.get());
    }
}
