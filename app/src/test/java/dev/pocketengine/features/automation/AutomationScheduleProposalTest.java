package dev.pocketengine.features.automation;

import java.io.File;
import java.util.UUID;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public final class AutomationScheduleProposalTest {
    @Rule public final TemporaryFolder temp=new TemporaryFolder();
    private JSONObject origin()throws Exception{return new JSONObject().put("ownerId","owner").put("spaceId","private").put("taskId",UUID.randomUUID().toString()).put("conversationId","original-conversation").put("runId",UUID.randomUUID().toString()).put("epoch",1);}
    private JSONObject draft()throws Exception{return new JSONObject().put("title","原对话提出的提醒").put("kind","phone_goal").put("goal","由主人核对后执行").put("schedule",new JSONObject().put("type","once").put("at",100000));}
    private static final class FailingFile extends File {
        final File blockedParent;int remaining=-1;
        FailingFile(File path,File blockedParent){super(path.getPath());this.blockedParent=blockedParent;}
        @Override public File getParentFile(){if(remaining>0&&--remaining==0)return blockedParent;return super.getParentFile();}
    }
    @Test public void proposalIsInertAndExactNativeApprovalCreatesOnePersistentRule()throws Exception{
        File file=new File(temp.getRoot(),"rules.json");AutomationBook book=new AutomationBook(file);JSONObject nativeOrigin=origin();String operation=UUID.randomUUID().toString();JSONObject proposal=book.proposeSchedule(nativeOrigin,operation,draft(),90000);
        assertEquals(0,book.rules().length());assertEquals(-1,book.nextAt());assertEquals("pending",proposal.getString("status"));assertEquals(proposal.getString("id"),book.proposeSchedule(nativeOrigin,operation,draft(),90001).getString("id"));
        assertThrows(SecurityException.class,()->book.approveScheduleProposal(proposal.getString("id"),"0".repeat(64),90002));book.approveScheduleProposal(proposal.getString("id"),proposal.getString("digest"),90003);
        AutomationBook restored=new AutomationBook(file);restored.approveScheduleProposal(proposal.getString("id"),proposal.getString("digest"),90004);assertEquals(1,restored.rules().length());assertEquals(1,restored.rules().getJSONObject(0).getInt("revision"));
    }
    @Test public void occurrencePreservesTheNativeOriginalConversationAndTask()throws Exception{
        AutomationBook book=new AutomationBook(new File(temp.getRoot(),"origin.json"));JSONObject nativeOrigin=origin(),proposal=book.proposeSchedule(nativeOrigin,UUID.randomUUID().toString(),draft(),90000);book.approveScheduleProposal(proposal.getString("id"),proposal.getString("digest"),90001);JSONObject occurrence=book.claimDue(100000,10).getJSONObject(0);book.delivered(occurrence.getString("id"),"awaiting_user","原机主核对",100000);JSONObject request=book.taskRequest(occurrence.getString("id"),100000);
        assertEquals("original-conversation",request.getJSONObject("origin").getString("originConversationId"));assertEquals(nativeOrigin.getString("taskId"),request.getJSONObject("origin").getString("originTaskId"));assertEquals(nativeOrigin.getString("runId"),request.getJSONObject("origin").getJSONObject("nativeOrigin").getString("runId"));assertEquals(1,request.getJSONObject("origin").getJSONObject("nativeOrigin").getInt("epoch"));assertFalse(request.getBoolean("executionAuthorized"));
    }
    @Test public void sameOperationCannotReplaceContentOrSupplySelfApproval()throws Exception{
        AutomationBook book=new AutomationBook(new File(temp.getRoot(),"injection.json"));JSONObject nativeOrigin=origin();String operation=UUID.randomUUID().toString();book.proposeSchedule(nativeOrigin,operation,draft(),90000);
        assertThrows(SecurityException.class,()->book.proposeSchedule(nativeOrigin,operation,draft().put("goal","更换未来目标"),90001));assertThrows(SecurityException.class,()->book.proposeSchedule(nativeOrigin,UUID.randomUUID().toString(),draft().put("approved",true),90002));assertEquals(0,book.rules().length());
    }
    @Test public void legacyMissingRunAndChangedRunCannotReviveTheOriginalProposal()throws Exception{
        AutomationBook book=new AutomationBook(new File(temp.getRoot(),"run-origin.json"));JSONObject nativeOrigin=origin(),legacy=new JSONObject(nativeOrigin.toString());legacy.remove("runId");legacy.remove("epoch");
        assertThrows(SecurityException.class,()->book.proposeSchedule(legacy,UUID.randomUUID().toString(),draft(),90000));
        String operation=UUID.randomUUID().toString();book.proposeSchedule(nativeOrigin,operation,draft(),90000);JSONObject changed=new JSONObject(nativeOrigin.toString()).put("runId",UUID.randomUUID().toString()).put("epoch",2);
        assertThrows(SecurityException.class,()->book.proposeSchedule(changed,operation,draft(),90001));assertEquals(0,book.rules().length());
    }
    @Test public void oldDisplayedDigestCannotApproveAnotherOriginalRunProposal()throws Exception{
        AutomationBook book=new AutomationBook(new File(temp.getRoot(),"old-digest.json"));JSONObject firstOrigin=origin(),first=book.proposeSchedule(firstOrigin,UUID.randomUUID().toString(),draft(),90000);
        JSONObject nextOrigin=new JSONObject(firstOrigin.toString()).put("runId",UUID.randomUUID().toString()).put("epoch",2),next=book.proposeSchedule(nextOrigin,UUID.randomUUID().toString(),draft(),90001);
        assertNotEquals(first.getString("digest"),next.getString("digest"));assertThrows(SecurityException.class,()->book.approveScheduleProposal(next.getString("id"),first.getString("digest"),90002));assertEquals(0,book.rules().length());
    }
    @Test public void elapsedOnceCannotBeApprovedAsANewFutureArrangement()throws Exception{
        AutomationBook book=new AutomationBook(new File(temp.getRoot(),"elapsed-draft.json"));JSONObject row=book.proposeSchedule(origin(),UUID.randomUUID().toString(),draft(),90000);
        assertThrows(IllegalStateException.class,()->book.approveScheduleProposal(row.getString("id"),row.getString("digest"),100001));assertEquals(0,book.rules().length());
    }
    @Test public void retiredRuleCannotBeRecreatedByAnOldApprovedProposal()throws Exception{
        AutomationBook book=new AutomationBook(new File(temp.getRoot(),"retired.json"));JSONObject row=book.proposeSchedule(origin(),UUID.randomUUID().toString(),draft(),90000);JSONObject approved=book.approveScheduleProposal(row.getString("id"),row.getString("digest"),90001);book.delete(approved.getJSONObject("rule").getString("id"));
        assertThrows(SecurityException.class,()->book.approveScheduleProposal(row.getString("id"),row.getString("digest"),90002));assertEquals(0,book.rules().length());assertEquals(1,book.scheduleProposals(null).length());
    }
    @Test public void oldDisplayedProposalCannotApproveAnOwnerEditedOrPausedRule()throws Exception{
        AutomationBook book=new AutomationBook(new File(temp.getRoot(),"owner-edited.json"));JSONObject row=book.proposeSchedule(origin(),UUID.randomUUID().toString(),draft(),90000);JSONObject rule=book.approveScheduleProposal(row.getString("id"),row.getString("digest"),90001).getJSONObject("rule");
        book.save(new JSONObject(rule.toString()).put("goal","主人已重新修改的另一目标"),90002);assertThrows(SecurityException.class,()->book.approveScheduleProposal(row.getString("id"),row.getString("digest"),90003));assertEquals("主人已重新修改的另一目标",book.rules().getJSONObject(0).getString("goal"));
        book.setEnabled(rule.getString("id"),false,90004);assertThrows(SecurityException.class,()->book.approveScheduleProposal(row.getString("id"),row.getString("digest"),90005));assertFalse(book.rules().getJSONObject(0).getBoolean("enabled"));
    }
    @Test public void conditionalDraftCannotBeSilentlyLoweredToAnUnconditionalTask()throws Exception{
        AutomationBook book=new AutomationBook(new File(temp.getRoot(),"condition-not-installed.json"));JSONObject conditional=draft().put("semanticCondition",new JSONObject().put("sourceKey","https://example.org/status").put("goal","仅真正满足条件才做"));
        assertThrows(SecurityException.class,()->book.proposeSchedule(origin(),UUID.randomUUID().toString(),conditional,90000));assertEquals(0,book.rules().length());assertEquals(0,book.scheduleProposals(null).length());
    }
    @Test public void failureAfterTheDurableOriginalCreationClaimIsQueryOnlyAfterColdRead()throws Exception{
        File blockedParent=temp.newFile("blocked-parent");
        // Only a File parent fault is injected to fail the second real atomic writer.
        // The original Book/read/write/normalization and surviving bytes remain real.
        FailingFile file=new FailingFile(new File(temp.getRoot(),"claimed-write-failed.json"),blockedParent);AutomationBook book=new AutomationBook(file);JSONObject row=book.proposeSchedule(origin(),UUID.randomUUID().toString(),draft(),90000);file.remaining=2;
        assertThrows(IllegalStateException.class,()->book.approveScheduleProposal(row.getString("id"),row.getString("digest"),90001));assertEquals(0,book.rules().length());
        AutomationBook cold=new AutomationBook(new File(file.getPath()));JSONObject retained=cold.scheduleProposals(null).getJSONObject(0);assertTrue(retained.getBoolean("ruleCreationEntered"));assertEquals("pending",retained.getString("status"));
        assertThrows(SecurityException.class,()->cold.approveScheduleProposal(row.getString("id"),row.getString("digest"),90002));assertEquals(0,cold.rules().length());
    }
    @Test public void duplicateApprovalOnlyReadsTheExactOriginalRuleAndWritesNothing()throws Exception{
        FailingFile file=new FailingFile(new File(temp.getRoot(),"duplicate-no-write.json"),temp.newFile("blocked-duplicate-parent"));AutomationBook book=new AutomationBook(file);JSONObject row=book.proposeSchedule(origin(),UUID.randomUUID().toString(),draft(),90000);book.approveScheduleProposal(row.getString("id"),row.getString("digest"),90001);byte[] before=java.nio.file.Files.readAllBytes(file.toPath());file.remaining=1;
        JSONObject duplicate=book.approveScheduleProposal(row.getString("id"),row.getString("digest"),90002);assertTrue(duplicate.getBoolean("duplicate"));assertFalse(duplicate.getBoolean("executionAuthorized"));assertArrayEquals(before,java.nio.file.Files.readAllBytes(file.toPath()));assertEquals(1,file.remaining);assertEquals(1,book.rules().getJSONObject(0).getInt("revision"));
    }
    @Test public void committedOriginalRuleAfterReceiptWriteFailureIsObservedWithoutAnotherCreation()throws Exception{
        FailingFile file=new FailingFile(new File(temp.getRoot(),"rule-committed-before-receipt.json"),temp.newFile("blocked-receipt-parent"));AutomationBook book=new AutomationBook(file);JSONObject row=book.proposeSchedule(origin(),UUID.randomUUID().toString(),draft(),90000);file.remaining=3;
        assertThrows(IllegalStateException.class,()->book.approveScheduleProposal(row.getString("id"),row.getString("digest"),90001));assertEquals(1,book.rules().length());AutomationBook cold=new AutomationBook(new File(file.getPath()));JSONObject observed=cold.approveScheduleProposal(row.getString("id"),row.getString("digest"),90002);
        assertEquals(row.getString("id"),observed.getJSONObject("rule").getString("id"));assertEquals(1,observed.getJSONObject("rule").getInt("revision"));assertFalse(observed.getBoolean("executionAuthorized"));assertEquals(1,cold.rules().length());
    }
    @Test public void legacyPendingWithoutAnEnteredStateDoesNotProvePermissionToCreate()throws Exception{
        File file=new File(temp.getRoot(),"legacy-unconfirmed.json");AutomationBook book=new AutomationBook(file);JSONObject row=book.proposeSchedule(origin(),UUID.randomUUID().toString(),draft(),90000);
        JSONObject old=new JSONObject(new String(java.nio.file.Files.readAllBytes(file.toPath()),java.nio.charset.StandardCharsets.UTF_8));JSONObject legacy=old.getJSONArray("proposals").getJSONObject(0);legacy.remove("ruleCreationEntered");legacy.remove("ruleCreationState");java.nio.file.Files.write(file.toPath(),old.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        AutomationBook cold=new AutomationBook(file);assertThrows(SecurityException.class,()->cold.approveScheduleProposal(row.getString("id"),row.getString("digest"),90001));assertEquals(0,cold.rules().length());assertEquals(1,cold.scheduleProposals(null).length());
    }    @Test public void sourceRevocationIncludesAnOlderReceiptThroughOnlyItsExactOriginalRule()throws Exception {
        File file=new File(temp.getRoot(),"legacy-native-receipt.json");AutomationBook book=new AutomationBook(file);JSONObject nativeOrigin=origin(),row=book.proposeSchedule(nativeOrigin,UUID.randomUUID().toString(),draft(),90000);book.approveScheduleProposal(row.getString("id"),row.getString("digest"),90001);JSONObject occurrence=book.claimDue(100000,10).getJSONObject(0);
        JSONObject old=new JSONObject(new String(java.nio.file.Files.readAllBytes(file.toPath()),java.nio.charset.StandardCharsets.UTF_8));old.getJSONArray("receipts").getJSONObject(0).remove("nativeOrigin");java.nio.file.Files.write(file.toPath(),old.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(nativeOrigin.getString("runId"),book.receiptNativeOrigin(occurrence.getString("id")).getString("runId"));book.revokeNativeScheduleOrigin(nativeOrigin,100001);
        assertEquals("cancelled",book.receipts().getJSONObject(0).getString("status"));assertEquals(1,book.sourceNotificationClears().length());assertFalse(new String(java.nio.file.Files.readAllBytes(file.toPath()),java.nio.charset.StandardCharsets.UTF_8).contains("由主人核对后执行"));
        assertThrows(SecurityException.class,()->book.approveScheduleProposal(row.getString("id"),row.getString("digest"),100002));
    }

    @Test public void cronDraftRequiresExactOriginalReviewAndKeepsSourceThroughOccurrence()throws Exception {
        long before=java.time.Instant.parse("2027-01-04T08:00:00Z").toEpochMilli();File file=new File(temp.getRoot(),"cron-proposal.json");AutomationBook book=new AutomationBook(file);JSONObject nativeOrigin=origin();String operation=UUID.randomUUID().toString();
        JSONObject requested=draft().put("schedule",new JSONObject().put("type","cron").put("scheduleVersion",1).put("expression","0 9 * * 1-5").put("zone","UTC"));
        JSONObject row=book.proposeSchedule(nativeOrigin,operation,requested,before),displayed=AutomationBook.reviewedProposalForDisplay(row);
        assertEquals(0,book.rules().length());assertEquals(-1,book.nextAt());assertFalse(row.getBoolean("executionAuthorized"));assertEquals(1,displayed.getJSONObject("draft").getJSONObject("schedule").getInt("scheduleVersion"));
        JSONObject altered=new JSONObject(row.toString());altered.getJSONObject("draft").getJSONObject("schedule").put("expression","0 10 * * 1,2,3,4,5");assertThrows(SecurityException.class,()->AutomationBook.reviewedProposalForDisplay(altered));
        JSONObject rule=book.approveScheduleProposal(row.getString("id"),row.getString("digest"),before+1).getJSONObject("rule");long planned=rule.getLong("scheduledAt");
        assertEquals(java.time.Instant.parse("2027-01-04T09:00:00Z").toEpochMilli(),planned);AutomationBook cold=new AutomationBook(file);assertTrue(cold.approveScheduleProposal(row.getString("id"),row.getString("digest"),before+2).getBoolean("duplicate"));
        JSONObject occurrence=cold.claimDue(planned,10).getJSONObject(0);cold.delivered(occurrence.getString("id"),"awaiting_user","主人仍需核对手机目标",planned);JSONObject admission=cold.taskRequest(occurrence.getString("id"),planned);
        assertEquals(nativeOrigin.toString(),occurrence.getJSONObject("nativeOrigin").toString());assertEquals(nativeOrigin.getString("runId"),admission.getJSONObject("origin").getJSONObject("nativeOrigin").getString("runId"));assertFalse(admission.getBoolean("executionAuthorized"));assertEquals(1,cold.rules().length());
        cold.revokeNativeScheduleOrigin(nativeOrigin,planned+1);assertThrows(IllegalStateException.class,()->cold.taskRequest(occurrence.getString("id"),planned+2));assertEquals(0,cold.claimDue(planned+86400000L,10).length());
    }
    @Test public void addingVersionSupportDoesNotRewriteAPreviouslyReviewedLegacyDigest()throws Exception {
        File file=new File(temp.getRoot(),"legacy-shape-version.json");AutomationBook book=new AutomationBook(file);JSONObject nativeOrigin=origin();String operation=UUID.randomUUID().toString();JSONObject requested=draft().put("schedule",new JSONObject().put("type","daily").put("time","09:00").put("zone","UTC"));
        JSONObject legacy=book.proposeSchedule(nativeOrigin,operation,requested,1000);assertEquals(3,legacy.getJSONObject("draft").getJSONObject("schedule").length());byte[] before=java.nio.file.Files.readAllBytes(file.toPath());
        AutomationBook cold=new AutomationBook(file);JSONObject read=AutomationBook.reviewedProposalForDisplay(cold.scheduleProposals(null).getJSONObject(0));assertEquals(legacy.getString("digest"),read.getString("digest"));assertArrayEquals(before,java.nio.file.Files.readAllBytes(file.toPath()));
        JSONObject versioned=new JSONObject(requested.toString());versioned.getJSONObject("schedule").put("scheduleVersion",1);assertThrows(SecurityException.class,()->cold.proposeSchedule(nativeOrigin,operation,versioned,1001));
        assertEquals(legacy.getString("digest"),cold.approveScheduleProposal(legacy.getString("id"),legacy.getString("digest"),1002).getJSONObject("proposal").getString("digest"));assertFalse(cold.rules().getJSONObject(0).getJSONObject("schedule").has("scheduleVersion"));
    }
    @Test public void sourcePendingRejectsStaleSaveAndPreservesOriginalUntilVerified()throws Exception {
        File file=new File(temp.getRoot(),"pending-save.json"); AutomationBook book=new AutomationBook(file); JSONObject nativeOrigin=origin();
        JSONObject proposal=book.proposeSchedule(nativeOrigin,UUID.randomUUID().toString(),draft(),90000);
        JSONObject rule=book.approveScheduleProposal(proposal.getString("id"),proposal.getString("digest"),90001).getJSONObject("rule");
        JSONObject stale=new JSONObject(rule.toString()).put("title","过期页面修改");
        // Hold the actual shared Book monitor: the stale page queues a save while
        // the same monitor records quarantine, then must observe the quarantine.
        java.lang.reflect.Field lockField=AutomationBook.class.getDeclaredField("lock");lockField.setAccessible(true);Object lock=lockField.get(book);
        AutomationBook staleBook=new AutomationBook(file);java.util.concurrent.ExecutorService executor=java.util.concurrent.Executors.newSingleThreadExecutor();byte[] original;
        try {
            java.util.concurrent.CountDownLatch queued=new java.util.concurrent.CountDownLatch(1);java.util.concurrent.Future<Boolean> rejected;
            synchronized(lock){
                rejected=executor.submit(()->{queued.countDown();try{staleBook.save(stale,90003);return false;}catch(SecurityException expected){return true;}});
                assertTrue(queued.await(2,java.util.concurrent.TimeUnit.SECONDS));book.nativeScheduleSourcePending(nativeOrigin,true,90002);original=java.nio.file.Files.readAllBytes(file.toPath());
            }
            assertTrue(rejected.get(2,java.util.concurrent.TimeUnit.SECONDS));
        } finally {executor.shutdownNow();}
        assertArrayEquals(original,java.nio.file.Files.readAllBytes(file.toPath()));
        assertTrue(book.rules().getJSONObject(0).getBoolean("sourceCheckPending"));assertEquals(0,book.claimDue(100000,10).length());
        book.nativeScheduleSourcePending(nativeOrigin,false,90004);
        JSONObject updated=book.save(stale,90005);assertEquals("过期页面修改",updated.getString("title"));assertEquals(rule.getInt("revision")+1,updated.getInt("revision"));
    }
}
