package dev.pocketengine.features.notifications;

import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.*;

public final class NotificationInboxTest {
    private static final String PKG="example.messages";
    private static NotificationInbox.Content content(String text){return new NotificationInbox.Content("title",text,"messages","msg",10,false);}
    private static NotificationInbox enabled(){NotificationInbox inbox=new NotificationInbox();inbox.enable(inbox.revision(),Set.of(PKG));return inbox;}
    @Test public void freshProcessIsOffAndCannotConnectOrAcceptText(){
        NotificationInbox inbox=new NotificationInbox();assertFalse(inbox.enabled());assertThrows(SecurityException.class,inbox::connect);
        assertFalse(inbox.posted(null,"private-tag",PKG,content("secret"),false,10));assertTrue(inbox.active(10).isEmpty());
    }
    @Test public void sourceAllowlistIsExactAndConsentRevisionCannotBeForged(){
        NotificationInbox inbox=enabled();NotificationInbox.Lease lease=inbox.connect();
        assertFalse(inbox.posted(lease,"key","example.messages.attacker",content("ignored"),false,10));
        assertThrows(SecurityException.class,()->inbox.disable("owner=true"));assertTrue(inbox.current(lease));
    }
    @Test public void baselineAndReplayNeverCreateATriggerOrDelta(){
        NotificationInbox inbox=enabled();NotificationInbox.Lease lease=inbox.connect();assertTrue(inbox.posted(lease,"key",PKG,content("existing"),true,10));
        NotificationInbox.Notice notice=inbox.active(10).get(0);assertEquals("baseline",notice.kind);assertFalse(notice.triggerCandidate);assertTrue(inbox.changes(10).isEmpty());
        assertFalse(inbox.posted(lease,"key",PKG,content("existing"),false,11));assertEquals(1,inbox.sequence());assertTrue(inbox.changes(11).isEmpty());
    }
    @Test public void postUpdateAndRemoveUseOneRefAndNeverRetainRemovedText(){
        NotificationInbox inbox=enabled();NotificationInbox.Lease lease=inbox.connect();inbox.posted(lease,"key",PKG,content("one"),false,10);
        NotificationInbox.Notice first=inbox.active(10).get(0);assertEquals("posted",first.kind);assertTrue(first.triggerCandidate);
        assertTrue(inbox.posted(lease,"key",PKG,content("two"),false,11));NotificationInbox.Notice second=inbox.active(11).get(0);assertEquals(first.ref,second.ref);assertEquals("updated",second.kind);assertTrue(second.version>first.version);
        assertThrows(SecurityException.class,()->inbox.reviewed(inbox.revision(),first.ref,first.version,11));
        assertTrue(inbox.removed(lease,"key",23,12));assertTrue(inbox.active(12).isEmpty());assertFalse(inbox.removed(lease,"key",23,13));
        NotificationInbox.Change removed=inbox.changes(13).get(2);assertEquals("removed",removed.kind);assertEquals(23,removed.reason);assertFalse(removed.triggerCandidate);
        assertThrows(SecurityException.class,()->inbox.reviewed(inbox.revision(),second.ref,second.version,13));
    }
    @Test public void timestampOnlyUpdateDoesNotDuplicateAVisiblePreview(){
        NotificationInbox inbox=enabled();NotificationInbox.Lease lease=inbox.connect();inbox.posted(lease,"key",PKG,content("one"),false,10);
        assertFalse(inbox.posted(lease,"key",PKG,new NotificationInbox.Content("title","one","messages","msg",999,false),false,11));assertEquals(1,inbox.sequence());
    }
    @Test public void disableFencesLatePostsRemovalsDisconnectAndOldOwnerReview(){
        NotificationInbox inbox=enabled();NotificationInbox.Lease old=inbox.connect();String oldRevision=inbox.revision();inbox.posted(old,"key",PKG,content("private"),false,10);NotificationInbox.Notice notice=inbox.active(10).get(0);
        inbox.disable(oldRevision);assertTrue(inbox.active(10).isEmpty());assertTrue(inbox.changes(10).isEmpty());assertFalse(inbox.current(old));
        inbox.enable(inbox.revision(),Set.of(PKG));NotificationInbox.Lease next=inbox.connect();inbox.posted(next,"key",PKG,content("new"),true,11);
        assertFalse(inbox.posted(old,"late",PKG,content("must not enter"),false,12));assertFalse(inbox.removed(old,"key",1,12));assertFalse(inbox.disconnect(old));assertTrue(inbox.current(next));
        assertNotEquals(notice.ref,inbox.active(12).get(0).ref);assertThrows(SecurityException.class,()->inbox.reviewed(oldRevision,notice.ref,notice.version,12));
    }
    @Test public void revokeClearsBuffersAndRequiresNewOwnerEnable(){
        NotificationInbox inbox=enabled();NotificationInbox.Lease lease=inbox.connect();inbox.posted(lease,"key",PKG,content("private"),false,10);String rev=inbox.revision();
        inbox.revoke();assertFalse(inbox.enabled());assertFalse(inbox.current(lease));assertTrue(inbox.active(11).isEmpty());assertTrue(inbox.changes(11).isEmpty());assertNotEquals(rev,inbox.revision());assertThrows(SecurityException.class,inbox::connect);
    }
    @Test public void reconnectClearsOldPreviewAndBaselinesCannotTrigger(){
        NotificationInbox inbox=enabled();NotificationInbox.Lease old=inbox.connect();inbox.posted(old,"key",PKG,content("before disconnect"),false,10);assertTrue(inbox.disconnect(old));assertTrue(inbox.active(10).isEmpty());
        NotificationInbox.Lease next=inbox.connect();inbox.posted(next,"key",PKG,content("still present"),true,11);
        assertFalse(inbox.posted(old,"late",PKG,content("old"),false,12));assertEquals("baseline",inbox.active(12).get(0).kind);assertTrue(inbox.changes(12).isEmpty());
    }
    @Test public void reviewRequiresExactActiveVersionAndReadLifetime(){
        NotificationInbox inbox=enabled();NotificationInbox.Lease lease=inbox.connect();inbox.posted(lease,"key",PKG,content("one"),false,10);NotificationInbox.Notice notice=inbox.active(10).get(0);
        assertSame(notice,inbox.reviewed(inbox.revision(),notice.ref,notice.version,11));assertThrows(SecurityException.class,()->inbox.reviewed(inbox.revision(),notice.ref,notice.version,10+NotificationInbox.RETENTION_MILLIS));assertEquals("expired",inbox.changes(10+NotificationInbox.RETENTION_MILLIS).get(0).kind);
    }
    @Test public void capacityAndDeltaHistoryAreBounded(){
        NotificationInbox inbox=enabled();NotificationInbox.Lease lease=inbox.connect();for(int i=0;i<1000;i++)inbox.posted(lease,"key"+i,PKG,content("text"),false,10+i);
        assertEquals(NotificationInbox.MAX_ACTIVE,inbox.active(1010).size());assertEquals(NotificationInbox.MAX_CHANGES,inbox.changes(1010).size());assertEquals(800,inbox.evicted());
        assertTrue(inbox.active(NotificationInbox.RETENTION_MILLIS+2000).isEmpty());assertTrue(inbox.changes(NotificationInbox.RETENTION_MILLIS*2+2000).isEmpty());
    }
    @Test public void keyIsHashedPerConsentAndRawTagNeverLeavesCore(){
        NotificationInbox inbox=enabled();NotificationInbox.Lease lease=inbox.connect();inbox.posted(lease,"sender/private@example.com",PKG,content("one"),true,10);
        assertTrue(inbox.active(10).get(0).ref.matches("notice-[a-f0-9]{64}"));assertFalse(inbox.active(10).get(0).ref.contains("sender"));
    }
    @Test public void boundedPlainTextPreservesSurrogateBoundaries(){
        String title="x".repeat(511)+"😀";NotificationInbox.Content value=new NotificationInbox.Content(title,"x".repeat(3000),null,null,1,false);
        assertEquals(511,value.title.length());assertEquals(2048,value.text.length());assertEquals("",value.channel);
    }
    @Test public void malformedSelectionDoesNotRevokeAnExistingValidGrant(){
        NotificationInbox inbox=enabled();NotificationInbox.Lease lease=inbox.connect();assertThrows(IllegalArgumentException.class,()->inbox.enable(inbox.revision(),Set.of("bad/pkg")));assertTrue(inbox.current(lease));
        assertThrows(IllegalArgumentException.class,()->NotificationInbox.validatePackages(Set.of()));
    }
    @Test public void restoredConsentStillRejectsPreviousProcessEvidenceEvenWhenVersionResets(){
        NotificationInbox first=enabled();NotificationInbox.Lease old=first.connect();first.posted(old,"key",PKG,content("old"),true,10);NotificationInbox.Notice previous=first.active(10).get(0);
        NotificationInbox replacement=new NotificationInbox();replacement.restore(first.policy());NotificationInbox.Lease next=replacement.connect();replacement.posted(next,"key",PKG,content("replacement"),true,11);
        assertEquals(first.revision(),replacement.revision());assertEquals(previous.version,replacement.active(11).get(0).version);assertNotEquals(previous.ref,replacement.active(11).get(0).ref);
        assertThrows(SecurityException.class,()->replacement.reviewed(first.revision(),previous.ref,previous.version,12));assertFalse(replacement.posted(old,"late",PKG,content("stale"),false,12));
    }
    @Test public void newProcessCannotReusePreviousOwnerRevision(){
        NotificationInbox first=enabled();NotificationInbox replacement=new NotificationInbox();assertNotEquals(first.revision(),replacement.revision());assertFalse(replacement.enabled());assertThrows(SecurityException.class,()->replacement.enable(first.revision(),Set.of(PKG)));
    }
}
