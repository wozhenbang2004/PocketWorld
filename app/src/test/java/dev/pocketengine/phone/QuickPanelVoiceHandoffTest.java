package dev.pocketengine.phone;

import dev.pocketengine.features.workspace.JsonFiles;
import java.io.File;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Actual existing draft file and CAS, with an explicit original-task source; no Android microphone simulation. */
public final class QuickPanelVoiceHandoffTest {
    private static final class Fixture implements AutoCloseable {
        final File root=Files.createTempDirectory("quick-panel-voice-").toFile();
        final QuickPanelDraftStore store=new QuickPanelDraftStore(root,"original-task","original-run",2);
        final AtomicLong clock=new AtomicLong(1);
        final AtomicInteger claims=new AtomicInteger();
        final JSONObject task=new JSONObject().put("id","original-task").put("runId","original-run").put("coordinatorEpoch",2)
                .put("kind","phone").put("executionMode","background").put("actorId","owner").put("spaceId","private").put("status","running");
        final JSONObject expected=new JSONObject().put("taskId","original-task").put("runId","original-run").put("epoch",2).put("executionMode","background");
        QuickPanelVoiceHandoff.Offer offered;
        QuickPanelVoiceHandoff.Session session;
        Fixture() throws Exception { store.load();store.saveNote("  原说明，不替换\n保留空格  "); }
        QuickPanelVoiceHandoff.Offer offer() throws Exception {
            offered=QuickPanelVoiceHandoff.offer(store,store.load(),expected,id->new JSONObject(task.toString()),claims::incrementAndGet,clock::get);return offered;
        }
        QuickPanelVoiceHandoff.Session claim() throws Exception { session=QuickPanelVoiceHandoff.claim(offer().token);return session; }
        QuickPanelDraftStore reopened() { return new QuickPanelDraftStore(root,"original-task","original-run",2); }
        public void close() { if(session!=null)session.close();if(offered!=null)offered.close(); }
    }

    @Test public void reviewedTranscriptAppendsOnceToTheSameOriginalDraftWithoutSending() throws Exception {
        try(Fixture f=new Fixture()){
            String previous=f.store.load().note;var session=f.claim();JSONObject original=session.snapshot();
            assertEquals("original-task",original.getString("taskId"));assertEquals("original-run",original.getString("runId"));assertEquals(2,original.getInt("epoch"));
            JSONObject saved=session.appendReviewed("  主人已核对的听写文字  ");
            QuickPanelDraftStore.State actual=f.reopened().load();assertEquals(previous+"\n主人已核对的听写文字",actual.note);assertTrue(actual.pendingClient.isEmpty());
            assertTrue(saved.getBoolean("saved"));assertFalse(saved.getBoolean("automaticSend"));assertFalse(saved.getBoolean("executionAuthorized"));
            assertEquals(original.getLong("draftRevision")+1,saved.getLong("draftRevision"));
            assertTrue(session.appendReviewed("主人已核对的听写文字").getBoolean("duplicate"));assertEquals(actual.revision,f.reopened().load().revision);
            assertThrows(SecurityException.class,()->session.appendReviewed("不同的新文字"));assertEquals(1,f.claims.get());
        }
    }

    @Test public void fabricatedReplayedAndClosedIntentTokensCannotClaimAWindow() throws Exception {
        assertThrows(SecurityException.class,()->QuickPanelVoiceHandoff.claim(UUID.randomUUID().toString()));
        try(Fixture f=new Fixture()){
            var offered=f.offer();f.session=QuickPanelVoiceHandoff.claim(offered.token);
            assertThrows(SecurityException.class,()->QuickPanelVoiceHandoff.claim(offered.token));
            f.session.close();assertThrows(IllegalStateException.class,()->f.session.appendReviewed("不能追加"));
            assertEquals("  原说明，不替换\n保留空格  ",f.reopened().load().note);
        }
        try(Fixture f=new Fixture()){
            var offered=f.offer();offered.close();assertThrows(SecurityException.class,()->QuickPanelVoiceHandoff.claim(offered.token));assertEquals(0,f.claims.get());
        }
    }

    @Test public void expiredOfferDoesNotRestoreAuthorityWhenTheSameIntentArrivesLater() throws Exception {
        try(Fixture f=new Fixture()){
            var offered=f.offer();f.clock.addAndGet(TimeUnit.MINUTES.toNanos(3));
            assertThrows(SecurityException.class,()->QuickPanelVoiceHandoff.claim(offered.token));assertEquals(0,f.claims.get());
            f.clock.set(1);assertThrows(SecurityException.class,()->QuickPanelVoiceHandoff.claim(offered.token));
        }
    }

    @Test public void successorRunBeforeClaimOrBeforeSaveCannotReceiveOldVoiceText() throws Exception {
        try(Fixture f=new Fixture()){
            var offered=f.offer();f.task.put("runId","successor");assertThrows(SecurityException.class,()->QuickPanelVoiceHandoff.claim(offered.token));assertEquals(0,f.claims.get());
        }
        try(Fixture f=new Fixture()){
            var session=f.claim();f.task.put("coordinatorEpoch",3);assertThrows(SecurityException.class,()->session.appendReviewed("旧执行的文字"));
            assertEquals("  原说明，不替换\n保留空格  ",f.reopened().load().note);
            assertEquals("",new QuickPanelDraftStore(f.root,"original-task","original-run",3).load().note);
        }
    }

    @Test public void anotherWindowCannotBeOverwrittenAtClaimOrFinalReview() throws Exception {
        try(Fixture f=new Fixture()){
            var offered=f.offer();var other=f.reopened();other.load();other.saveNote("另一个原窗口的新草稿");
            assertThrows(IllegalStateException.class,()->QuickPanelVoiceHandoff.claim(offered.token));assertEquals("另一个原窗口的新草稿",f.reopened().load().note);
        }
        try(Fixture f=new Fixture()){
            var session=f.claim();var other=f.reopened();other.load();other.saveNote("更新后的文字");
            assertThrows(IllegalStateException.class,()->session.appendReviewed("旧ASR"));assertEquals("更新后的文字",f.reopened().load().note);
        }
    }

    @Test public void sameRevisionChangedBytesAreNotTreatedAsTheReviewedOriginal() throws Exception {
        try(Fixture f=new Fixture()){
            var session=f.claim();File[] rows=new File(f.root,"quick-panel-drafts").listFiles();assertNotNull(rows);assertEquals(1,rows.length);
            JsonFiles.write(rows[0],JsonFiles.read(rows[0]).put("note","same revision different bytes"));
            assertThrows(IllegalStateException.class,()->session.appendReviewed("不能覆盖"));assertEquals("same revision different bytes",f.reopened().load().note);
        }
    }

    @Test public void unknownOriginalSubmissionBlocksHandoffAndCannotBeClearedByVoice() throws Exception {
        try(Fixture f=new Fixture()){
            String client=UUID.randomUUID().toString();f.store.prepareSubmission("原输入结果待核对",client);
            assertThrows(IllegalStateException.class,f::offer);assertEquals(client,f.reopened().load().pendingClient);
        }
        try(Fixture f=new Fixture()){
            var session=f.claim();var other=f.reopened();other.load();String client=UUID.randomUUID().toString();other.prepareSubmission("只查询这个原回执",client);
            assertThrows(IllegalStateException.class,()->session.appendReviewed("不是重新发送"));assertEquals(client,f.reopened().load().pendingClient);
        }
    }

    @Test public void privateOwnerTaskAndOriginalExecutionModeRemainRequired() throws Exception {
        for(String field:new String[]{"actorId","spaceId","executionMode","kind","status"})try(Fixture f=new Fixture()){
            f.task.put(field,"different");assertThrows(SecurityException.class,f::offer);
        }
        try(Fixture f=new Fixture()){
            f.task.put("origin",new JSONObject().put("kind","channel"));assertThrows(SecurityException.class,f::offer);
        }
        try(Fixture f=new Fixture()){
            var session=f.claim();f.task.put("cancelRequested",true);assertThrows(SecurityException.class,()->session.appendReviewed("停止后不追加"));
        }
    }

    @Test public void oversizedFinalTextIsNotTruncatedAndExistingSendLimitIsNotExpanded() throws Exception {
        try(Fixture f=new Fixture()){
            f.store.saveNote("原".repeat(1500));var session=f.claim();JSONObject saved=session.appendReviewed("听".repeat(4000));
            assertEquals(5501,f.reopened().load().note.length());assertTrue(saved.getBoolean("requiresEditingBeforeSend"));
            var current=f.reopened();current.load();assertThrows(IllegalArgumentException.class,()->current.prepareSubmission(current.load().note,UUID.randomUUID().toString()));
        }
        try(Fixture f=new Fixture()){
            f.store.saveNote("原".repeat(8190));var session=f.claim();assertThrows(IllegalArgumentException.class,()->session.appendReviewed("不能截掉原文"));
            assertEquals(8190,f.reopened().load().note.length());assertTrue(session.appendReviewed("字").getBoolean("saved"));assertEquals(8192,f.reopened().load().note.length());
        }
    }

    @Test public void twoHandoffsForTheSameRunHaveDifferentVoiceInputTargets() throws Exception {
        try(Fixture f=new Fixture()){
            var first=f.claim();String a=first.voiceTarget(1).conversationId;first.close();
            var second=f.claim();String b=second.voiceTarget(1).conversationId;
            assertNotEquals(a,b);assertTrue(a.startsWith("quick-panel:"));assertTrue(second.voiceTarget(1).phoneInput);
        }
    }
}
