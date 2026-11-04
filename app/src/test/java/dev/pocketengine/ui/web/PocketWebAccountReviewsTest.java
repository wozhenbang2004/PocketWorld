package dev.pocketengine.ui.web;

import static org.junit.Assert.*;
import dev.pocketengine.runtime.models.ModelSelectionCoordinator;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONObject;
import org.junit.Test;

/** Original window and selection provenance; no account credentials or network requests. */
public final class PocketWebAccountReviewsTest {
    private static JSONObject model()throws Exception{return new JSONObject().put("reference",new JSONObject().put("accountId","original-account").put("accountVersion",3).put("modelId","original-model"));}
    @Test public void modelReviewCannotBecomeLogoutOrBeReplayed()throws Exception{
        var reviews=new PocketWebAccountReviews(()->100);
        String handle=reviews.issue(PocketWebAccountReviews.Kind.MODEL,model(),8);
        assertThrows(IllegalStateException.class,()->reviews.take(handle,PocketWebAccountReviews.Kind.LOGOUT,8));
        assertThrows(IllegalStateException.class,()->reviews.take(handle,PocketWebAccountReviews.Kind.MODEL,8));
    }
    @Test public void otherActivityMutationMakesTheVisibleReviewStale()throws Exception{
        var shared=new ModelSelectionCoordinator();var reviews=new PocketWebAccountReviews(()->100);
        String handle=reviews.issue(PocketWebAccountReviews.Kind.MODEL,model(),shared.status().revision());
        shared.beginMutation().run(()->new JSONObject().put("saved",true));
        assertThrows(IllegalStateException.class,()->reviews.take(handle,PocketWebAccountReviews.Kind.MODEL,shared.status().revision()));
    }
    @Test public void aTakenQueuedSelectionCannotRunAfterWindowReentry()throws Exception{
        var reviews=new PocketWebAccountReviews(()->100);
        var entry=reviews.take(reviews.issue(PocketWebAccountReviews.Kind.MODEL,model(),1),PocketWebAccountReviews.Kind.MODEL,1);
        reviews.invalidate(); // Leaving and returning to the same account does not revive the object.
        assertThrows(IllegalStateException.class,()->reviews.claim(entry,PocketWebAccountReviews.Kind.MODEL));
    }
    @Test public void invalidationBetweenLocalAccountReadAndNetworkRegistrationIsRejected()throws Exception{
        var reviews=new PocketWebAccountReviews(()->100);
        var entry=reviews.take(reviews.issue(PocketWebAccountReviews.Kind.MODEL,model(),1),PocketWebAccountReviews.Kind.MODEL,1);
        reviews.claim(entry,PocketWebAccountReviews.Kind.MODEL);
        reviews.invalidate();
        assertThrows(IllegalStateException.class,()->reviews.requireCurrent(entry,PocketWebAccountReviews.Kind.MODEL));
    }
    @Test public void issuedPayloadAndClaimedReferenceCannotBeRetargeted()throws Exception{
        var reviews=new PocketWebAccountReviews(()->100);JSONObject input=model();
        String handle=reviews.issue(PocketWebAccountReviews.Kind.MODEL,input,1);
        input.getJSONObject("reference").put("accountId","replacement-account");
        var entry=reviews.take(handle,PocketWebAccountReviews.Kind.MODEL,1);
        entry.payload().getJSONObject("reference").put("modelId","replacement-model");
        JSONObject claimed=reviews.claim(entry,PocketWebAccountReviews.Kind.MODEL).getJSONObject("reference");
        assertEquals("original-account",claimed.getString("accountId"));assertEquals("original-model",claimed.getString("modelId"));
        assertThrows(IllegalStateException.class,()->reviews.claim(entry,PocketWebAccountReviews.Kind.MODEL));
    }
    @Test public void expiryAlsoAppliesAfterTheWriteHasBeenQueued()throws Exception{
        var now=new AtomicLong(100);var reviews=new PocketWebAccountReviews(now::get);
        var entry=reviews.take(reviews.issue(PocketWebAccountReviews.Kind.LOGOUT,new JSONObject().put("accountId","original").put("expectedVersion",4),1),PocketWebAccountReviews.Kind.LOGOUT,1);
        now.set(120_100);
        assertThrows(IllegalStateException.class,()->reviews.claim(entry,PocketWebAccountReviews.Kind.LOGOUT));
    }
    @Test public void anotherOwnerWindowCannotClaimAnOriginalWindowEntry()throws Exception{
        var first=new PocketWebAccountReviews(()->100);var second=new PocketWebAccountReviews(()->100);
        var entry=first.take(first.issue(PocketWebAccountReviews.Kind.MODEL,model(),1),PocketWebAccountReviews.Kind.MODEL,1);
        assertThrows(IllegalStateException.class,()->second.claim(entry,PocketWebAccountReviews.Kind.MODEL));
        assertEquals("original-account",first.claim(entry,PocketWebAccountReviews.Kind.MODEL).getJSONObject("reference").getString("accountId"));
    }
    @Test public void closingWindowRevokesTakenAndUntakenReviews()throws Exception{
        var reviews=new PocketWebAccountReviews(()->100);
        String pending=reviews.issue(PocketWebAccountReviews.Kind.LOGOUT,new JSONObject(),1);
        var taken=reviews.take(reviews.issue(PocketWebAccountReviews.Kind.MODEL,model(),1),PocketWebAccountReviews.Kind.MODEL,1);
        reviews.close();
        assertThrows(IllegalStateException.class,()->reviews.take(pending,PocketWebAccountReviews.Kind.LOGOUT,1));
        assertThrows(IllegalStateException.class,()->reviews.claim(taken,PocketWebAccountReviews.Kind.MODEL));
        assertThrows(IllegalStateException.class,()->reviews.issue(PocketWebAccountReviews.Kind.MODEL,model(),1));
    }
    @Test public void boundedReviewCacheEvictsOldHandlesAndNeverPermanentlyExhausts()throws Exception{
        var reviews=new PocketWebAccountReviews(()->100);String oldest=reviews.issue(PocketWebAccountReviews.Kind.MODEL,model(),1);
        for(int i=0;i<25;i++)reviews.issue(PocketWebAccountReviews.Kind.MODEL,model(),1);
        assertThrows(IllegalStateException.class,()->reviews.take(oldest,PocketWebAccountReviews.Kind.MODEL,1));
        for(int i=0;i<300;i++){
            var entry=reviews.take(reviews.issue(PocketWebAccountReviews.Kind.MODEL,model(),1),PocketWebAccountReviews.Kind.MODEL,1);
            assertNotNull(reviews.claim(entry,PocketWebAccountReviews.Kind.MODEL));
        }
    }
}
