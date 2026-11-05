package dev.pocketengine.features.assistant;

import java.util.UUID;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** Separate first sends from an unbound editor can have different native conversation IDs. */
public final class OwnerComposerPendingIdentityTest {
    private static String id(){return UUID.randomUUID().toString();}
    private static OwnerComposerState.Pending prepare(OwnerComposerState state,String text,String hash)throws Exception{
        var prior=state.active();var draft=state.edit(prior.key,prior.revision,text,prior.mode,prior.attachments());
        return state.prepare(id(),draft.key,draft.revision,hash.repeat(64),null);
    }
    @Test public void acceptedSecondFirstSendDoesNotRetargetAnEarlierUnknownSubmission()throws Exception{
        var state=OwnerComposerState.empty();var earlier=prepare(state,"First admission remains unknown","a");var later=prepare(state,"A different explicit new input","b");String firstConversation=id(),secondConversation=id();
        var ack=state.acknowledge(later.clientId,secondConversation);assertTrue(ack.matched);assertTrue(ack.cleared);assertTrue(ack.migrated);
        assertEquals(secondConversation,state.active().conversationId);assertEquals("",state.active().text);
        var pending=state.pending(earlier.clientId);assertEquals("",pending.conversationId);assertEquals(earlier.draftKey,pending.draftKey);assertEquals(earlier.fingerprint,pending.fingerprint);assertEquals(earlier.draftRevision,pending.draftRevision);
        assertNotNull(state.draft(earlier.draftKey));assertNull(state.pending(later.clientId));
        var restored=OwnerComposerState.fromJson(state.toJson());assertTrue(restored.acknowledge(earlier.clientId,firstConversation).matched);
        assertEquals(secondConversation,restored.active().conversationId);assertTrue(restored.pending().isEmpty());assertNotNull(restored.draft("conversation:"+firstConversation));
    }
    @Test public void laterDraftEditsRemainIntactWhenSeparatePendingIdentitiesSplit()throws Exception{
        var state=OwnerComposerState.empty();var first=prepare(state,"Unknown first input","a");var second=prepare(state,"Accepted later input","b");var draft=state.active();
        state.edit(draft.key,draft.revision,"New unsent text after both inputs","steer",new JSONArray());state.retainVoice(draft.key,id(),"Unsent reviewed voice");
        String conversation=id();var ack=state.acknowledge(second.clientId,conversation);assertFalse(ack.cleared);
        assertEquals("New unsent text after both inputs",state.active().text);assertEquals("Unsent reviewed voice",state.active().voiceText);assertEquals("",state.pending(first.clientId).conversationId);
        var restored=OwnerComposerState.fromJson(state.toJson());assertEquals(state.active().text,restored.active().text);assertEquals(first.draftKey,restored.pending(first.clientId).draftKey);
    }
    @Test public void aSplitCannotEvictAnotherUnsentDraftOrOverflowTheBound()throws Exception{
        var state=OwnerComposerState.empty();var first=prepare(state,"Unknown first input","a");var second=prepare(state,"Accepted later input","b");
        for(int i=1;i<OwnerComposerState.MAX_DRAFTS;i++){var draft=state.selectConversation(id());state.edit(draft.key,draft.revision,"Must retain "+i,draft.mode,draft.attachments());}
        state.selectPending(second.clientId);String before=state.toJson().toString();
        assertThrows(IllegalStateException.class,()->state.acknowledge(second.clientId,id()));assertEquals(before,state.toJson().toString());assertEquals("",state.pending(first.clientId).conversationId);
    }
}
