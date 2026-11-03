package dev.pocketengine.features.automation;

import java.io.File;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.*;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** Actual shared Book monitor and atomic file writer, not a UI/currentness simulation. */
public final class AutomationRescheduleAdmissionTest {
    @Rule public final TemporaryFolder temp=new TemporaryFolder();
    private JSONObject draft(String title)throws Exception {return new JSONObject().put("title",title).put("kind","reminder").put("schedule",new JSONObject().put("type","once").put("at",200000));}
    private JSONObject sourceReference(JSONObject source)throws Exception {return new JSONObject().put("id",source.getString("id")).put("revision",source.getInt("revision"));}
    private JSONObject copied(JSONObject source)throws Exception {return draft("重新安排的新目标").put("rescheduleSource",sourceReference(source));}
    private JSONObject origin()throws Exception {return new JSONObject().put("ownerId","owner").put("spaceId","private").put("taskId",UUID.randomUUID().toString()).put("conversationId","original-conversation").put("runId",UUID.randomUUID().toString()).put("epoch",1);}
    private JSONObject nativeRule(AutomationBook book,JSONObject origin)throws Exception {
        JSONObject proposal=book.proposeSchedule(origin,UUID.randomUUID().toString(),draft("原对话来源"),90000);
        return book.approveScheduleProposal(proposal.getString("id"),proposal.getString("digest"),90001).getJSONObject("rule");
    }
    private Object monitor(AutomationBook book)throws Exception {var field=AutomationBook.class.getDeclaredField("lock");field.setAccessible(true);return field.get(book);}
    @FunctionalInterface private interface Change {void run()throws Exception;}
    private void queuedSaveMustReject(File file,AutomationBook book,JSONObject frozen,Change first)throws Exception {
        AutomationBook queuedBook=new AutomationBook(file);ExecutorService executor=Executors.newSingleThreadExecutor();byte[] after;Future<Boolean> rejected;
        try {CountDownLatch queued=new CountDownLatch(1);
            synchronized(monitor(book)) {
                rejected=executor.submit(()->{queued.countDown();try{queuedBook.save(frozen,90005);return false;}catch(SecurityException expected){return true;}});
                assertTrue(queued.await(2,TimeUnit.SECONDS));first.run();after=Files.readAllBytes(file.toPath());
            }
            assertTrue(rejected.get(2,TimeUnit.SECONDS));assertArrayEquals(after,Files.readAllBytes(file.toPath()));
        } finally {executor.shutdownNow();}
    }

    @Test public void matchingManualSourceCreatesIndependentFutureRule()throws Exception {
        File file=new File(temp.getRoot(),"manual.json");AutomationBook book=new AutomationBook(file);JSONObject source=book.save(draft("原手建提醒"),90000);
        JSONObject next=book.save(copied(source),90001);assertNotEquals(source.getString("id"),next.getString("id"));assertEquals(2,book.rules().length());
        assertEquals(1,source.getInt("manualOriginVersion"));assertEquals(1,next.getInt("manualOriginVersion"));
        assertFalse(next.has("rescheduleSource"));assertFalse(next.has("nativeOrigin"));assertEquals(1,book.rules().getJSONObject(0).getInt("revision"));
    }

    @Test public void durableNativeProvenanceCannotBeDetachedEvenIfTheCallerOmitsIt()throws Exception {
        File file=new File(temp.getRoot(),"native.json");AutomationBook book=new AutomationBook(file);JSONObject source=nativeRule(book,origin());byte[] before=Files.readAllBytes(file.toPath());
        JSONObject frozen=copied(source);assertFalse(frozen.has("nativeOrigin"));assertThrows(SecurityException.class,()->new AutomationBook(file).save(frozen,90002));
        assertArrayEquals(before,Files.readAllBytes(file.toPath()));assertEquals(1,book.rules().length());
    }

    @Test public void legacyMissingCarrierCannotDetachARuleRetainedByItsOriginalProposal()throws Exception {
        File file=new File(temp.getRoot(),"legacy-carrier.json");AutomationBook book=new AutomationBook(file);JSONObject source=nativeRule(book,origin());
        JSONObject old=new JSONObject(Files.readString(file.toPath()));old.getJSONArray("rules").getJSONObject(0).remove("nativeOrigin");Files.writeString(file.toPath(),old.toString());byte[] before=Files.readAllBytes(file.toPath());
        assertThrows(SecurityException.class,()->new AutomationBook(file).save(copied(source),90002));
        assertArrayEquals(before,Files.readAllBytes(file.toPath()));assertEquals(1,book.rules().length());
    }

    @Test public void missingBothOriginAndProposalStillCannotProveManualProvenance()throws Exception {
        File file=new File(temp.getRoot(),"unknown-origin.json");AutomationBook book=new AutomationBook(file);JSONObject source=nativeRule(book,origin());
        JSONObject old=new JSONObject(Files.readString(file.toPath()));old.getJSONArray("rules").getJSONObject(0).remove("nativeOrigin");old.remove("proposals");Files.writeString(file.toPath(),old.toString());byte[] before=Files.readAllBytes(file.toPath());
        assertThrows(SecurityException.class,()->new AutomationBook(file).save(copied(source),90002));
        assertArrayEquals(before,Files.readAllBytes(file.toPath()));assertFalse(book.rules().getJSONObject(0).has("manualOriginVersion"));
    }

    @Test public void editingLegacyRowsCannotRetrofitCallerSuppliedManualProof()throws Exception {
        File file=new File(temp.getRoot(),"legacy-manual.json");AutomationBook book=new AutomationBook(file);JSONObject source=book.save(draft("旧手建行"),90000);
        JSONObject old=new JSONObject(Files.readString(file.toPath()));old.getJSONArray("rules").getJSONObject(0).remove("manualOriginVersion");Files.writeString(file.toPath(),old.toString());
        AutomationBook cold=new AutomationBook(file);JSONObject edited=cold.save(new JSONObject(source.toString()).put("title","只有内容修改").put("manualOriginVersion",1),90001);
        assertFalse(edited.has("manualOriginVersion"));byte[] before=Files.readAllBytes(file.toPath());assertThrows(SecurityException.class,()->cold.save(copied(edited),90002));
        assertArrayEquals(before,Files.readAllBytes(file.toPath()));
    }

    @Test public void aNativeEditCannotSelfSupplyTheManualMarker()throws Exception {
        File file=new File(temp.getRoot(),"native-marker-forgery.json");AutomationBook book=new AutomationBook(file);JSONObject source=nativeRule(book,origin());
        JSONObject edited=book.save(new JSONObject(source.toString()).put("title","明确修改原内容").put("manualOriginVersion",1),90002);
        assertTrue(edited.has("nativeOrigin"));assertFalse(edited.has("manualOriginVersion"));
        assertThrows(SecurityException.class,()->book.save(copied(edited),90003));
    }

    @Test public void newCallerCannotDropASuppliedNativeCarrierIntoManualCreation()throws Exception {
        File file=new File(temp.getRoot(),"new-native-carrier.json");AutomationBook book=new AutomationBook(file);
        assertThrows(SecurityException.class,()->book.save(draft("不能洗去来源").put("nativeOrigin",origin()).put("manualOriginVersion",1),90000));
        assertEquals(0,book.rules().length());assertFalse(file.exists());
        JSONObject actual=book.save(draft("空白明确新建").put("manualOriginVersion",99),90001);
        assertEquals(1,actual.getInt("manualOriginVersion"));
    }

    @Test public void sourceRevocationCommittedBeforeQueuedSaveCannotCreateAnUnboundCopy()throws Exception {
        File file=new File(temp.getRoot(),"revoked.json");AutomationBook book=new AutomationBook(file);JSONObject origin=origin(),source=nativeRule(book,origin);
        queuedSaveMustReject(file,book,copied(source),()->book.revokeNativeScheduleOrigin(origin,90003));
        assertEquals(1,book.rules().length());assertTrue(book.rules().getJSONObject(0).getBoolean("sourceInvalidated"));
    }

    @Test public void sourcePendingCommittedBeforeQueuedSaveCannotCreateAnUnboundCopy()throws Exception {
        File file=new File(temp.getRoot(),"pending.json");AutomationBook book=new AutomationBook(file);JSONObject origin=origin(),source=nativeRule(book,origin);
        queuedSaveMustReject(file,book,copied(source),()->book.nativeScheduleSourcePending(origin,true,90003));
        assertEquals(1,book.rules().length());assertTrue(book.rules().getJSONObject(0).getBoolean("sourceCheckPending"));
    }

    @Test public void changedManualSourceWinsBeforeQueuedSave()throws Exception {
        File file=new File(temp.getRoot(),"changed.json");AutomationBook book=new AutomationBook(file);JSONObject source=book.save(draft("原手建提醒"),90000);
        queuedSaveMustReject(file,book,copied(source),()->book.save(new JSONObject(source.toString()).put("title","已经改版"),90003));
        assertEquals(1,book.rules().length());assertEquals(2,book.rules().getJSONObject(0).getInt("revision"));
    }

    @Test public void deletedManualSourceWinsBeforeQueuedSave()throws Exception {
        File file=new File(temp.getRoot(),"deleted.json");AutomationBook book=new AutomationBook(file);JSONObject source=book.save(draft("原手建提醒"),90000);
        queuedSaveMustReject(file,book,copied(source),()->book.delete(source.getString("id"),source.getInt("revision")));
        assertEquals(0,book.rules().length());
    }

    @Test public void referenceIsStrictAndNeverAnUpdateOrProvenanceGrant()throws Exception {
        File file=new File(temp.getRoot(),"shape.json");AutomationBook book=new AutomationBook(file);JSONObject source=book.save(draft("原手建提醒"),90000);byte[] before=Files.readAllBytes(file.toPath());
        for(Object invalid:new Object[]{JSONObject.NULL,"source",new JSONObject().put("id",source.getString("id")).put("revision","1"),sourceReference(source).put("revision",1.5),sourceReference(source).put("approved",true)})
            assertThrows(SecurityException.class,()->book.save(draft("错误引用").put("rescheduleSource",invalid),90001));
        assertThrows(SecurityException.class,()->book.save(copied(source).put("id",source.getString("id")).put("revision",1),90001));
        assertArrayEquals(before,Files.readAllBytes(file.toPath()));
    }

    @Test public void pendingWinsBeforeQueuedEnableAndRecoveryStillRequiresExplicitEnable()throws Exception {
        File file=new File(temp.getRoot(),"enable-pending.json");AutomationBook book=new AutomationBook(file);JSONObject origin=origin(),source=nativeRule(book,origin);
        source=book.setEnabled(source.getString("id"),false,90002,source.getInt("revision"));String id=source.getString("id");int revision=source.getInt("revision");
        AutomationBook queuedBook=new AutomationBook(file);ExecutorService executor=Executors.newSingleThreadExecutor();byte[] after;Future<Boolean> rejected;
        try {CountDownLatch queued=new CountDownLatch(1);
            synchronized(monitor(book)) {
                rejected=executor.submit(()->{queued.countDown();try{queuedBook.setEnabled(id,true,90005,revision);return false;}catch(SecurityException expected){return true;}});
                assertTrue(queued.await(2,TimeUnit.SECONDS));book.nativeScheduleSourcePending(origin,true,90003);after=Files.readAllBytes(file.toPath());
            }
            assertTrue(rejected.get(2,TimeUnit.SECONDS));assertArrayEquals(after,Files.readAllBytes(file.toPath()));
        } finally {executor.shutdownNow();}
        assertFalse(book.rules().getJSONObject(0).getBoolean("enabled"));assertEquals(revision,book.rules().getJSONObject(0).getInt("revision"));
        book.nativeScheduleSourcePending(origin,false,90006);assertFalse(book.rules().getJSONObject(0).getBoolean("enabled"));
        assertTrue(book.setEnabled(id,true,90007,revision).getBoolean("enabled"));
    }

    @Test public void pendingStillAllowsPauseAndKeepsOriginalRevisionGate()throws Exception {
        File file=new File(temp.getRoot(),"pause-pending.json");AutomationBook book=new AutomationBook(file);JSONObject origin=origin(),source=nativeRule(book,origin);
        book.nativeScheduleSourcePending(origin,true,90002);
        JSONObject paused=book.setEnabled(source.getString("id"),false,90003,source.getInt("revision"));assertFalse(paused.getBoolean("enabled"));assertTrue(paused.getBoolean("sourceCheckPending"));
        book.nativeScheduleSourcePending(origin,false,90004);
        assertThrows(IllegalStateException.class,()->book.setEnabled(source.getString("id"),true,90005,source.getInt("revision")));
    }
}
