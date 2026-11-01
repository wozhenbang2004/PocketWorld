package dev.pocketengine.features.assistant;

import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Native locator persistence only, not source availability or grant verification. */
public final class OwnerComposerSourcesTest {
    private static String id(){return UUID.randomUUID().toString();}
    private static JSONArray source(int offset)throws Exception{return new JSONArray().put(new JSONObject()
        .put("generation","actual-native-generation").put("sourceConversationId","original-conversation")
        .put("sourceRef",new JSONObject().put("kind","message").put("sourceId","original-message").put("revision",4_294_967_299L).put("ownerId","owner").put("spaceId","private"))
        .put("bodyFingerprint","sha256:"+"a".repeat(64)).put("offset",offset).put("chars",12).put("unit","UTF-16"));}
    private static OwnerComposerState.Draft text(OwnerComposerState state)throws Exception{var d=state.active();return state.edit(d.key,d.revision,"针对这次原文范围回答","queue",new JSONArray());}
    @Test public void sourceLocatorsAreCanonicalDefensiveAndSurviveTextVoiceReadingAndReopen()throws Exception{
        var state=OwnerComposerState.empty();var d=text(state);JSONArray selected=source(3);var with=state.editSources(d.key,d.revision,selected);
        selected.getJSONObject(0).put("offset",900);with.sourceRefs().getJSONObject(0).put("chars",99);assertEquals(3,with.sourceRefs().getJSONObject(0).getInt("offset"));assertEquals(12,with.sourceRefs().getJSONObject(0).getInt("chars"));
        state.edit(with.key,with.revision,"后续编辑仍保留原文选择","steer",new JSONArray());state.reading(with.key,4,22,"anchor","seen",true);state.retainVoice(with.key,id(),"最终听写");var voice=state.active();state.useVoice(voice.key,voice.revision,"明确采用的听写");
        var restored=OwnerComposerState.fromJson(state.toJson());assertEquals(with.sourceRefs().toString(),restored.active().sourceRefs().toString());assertEquals(4,restored.active().scrollIndex);assertTrue(restored.active().text.contains("明确采用的听写"));assertTrue(restored.pending().isEmpty());assertFalse(restored.toJson().toString().contains("old source body"));
    }
    @Test public void referenceEditIsContentRevisionAndLateAcknowledgementNeverClearsNewSelection()throws Exception{
        var state=OwnerComposerState.empty();String conversation=id();state.selectConversation(conversation);var d=text(state);var with=state.editSources(d.key,d.revision,source(0));
        var p=state.prepare(id(),with.key,with.revision,"a".repeat(64),null);var changed=state.editSources(with.key,with.revision,source(20));assertEquals(with.revision+1,changed.revision);
        assertFalse(state.acknowledge(p.clientId,conversation).cleared);assertEquals(20,state.active().sourceRefs().getJSONObject(0).getInt("offset"));
        var second=state.prepare(id(),changed.key,changed.revision,"b".repeat(64),null);assertTrue(state.acknowledge(second.clientId,conversation).cleared);assertEquals(0,state.active().sourceRefs().length());assertEquals("",state.active().text);
    }
    @Test public void oldDraftWithoutOptionalFieldKeepsExactPendingAndEmptySelection()throws Exception{
        var state=OwnerComposerState.empty();var d=text(state);var p=state.prepare(id(),d.key,d.revision,"c".repeat(64),null);JSONObject old=state.toJson();old.getJSONArray("drafts").getJSONObject(0).remove("ownerSourceRefs");
        var restored=OwnerComposerState.fromJson(old);assertEquals(0,restored.active().sourceRefs().length());assertEquals(p.fingerprint,restored.pending(p.clientId).fingerprint);assertEquals(p.draftRevision,restored.active().revision);
    }
    @Test public void staleCasAndInvalidLocatorDoNotMutateOriginalDraft()throws Exception{
        var state=OwnerComposerState.empty();var d=text(state);state.editSources(d.key,d.revision,source(0));String before=state.toJson().toString();
        assertThrows(IllegalStateException.class,()->state.editSources(d.key,d.revision,new JSONArray()));assertEquals(before,state.toJson().toString());
        JSONArray invalid=source(0);invalid.getJSONObject(0).put("body","must not stage old prose");var current=state.active();assertThrows(SecurityException.class,()->state.editSources(current.key,current.revision,invalid));assertEquals(before,state.toJson().toString());
    }
    @Test public void sourceOnlyDraftIsNotEvictedAsBlankWhenCapacityIsReached()throws Exception{
        var state=OwnerComposerState.empty();String sourceKey=state.activeKey();state.editSources(sourceKey,state.active().revision,source(0));
        for(int i=1;i<OwnerComposerState.MAX_DRAFTS;i++){var d=state.selectConversation(id());state.edit(d.key,d.revision,"保留"+i,"queue",new JSONArray());}
        assertThrows(IllegalStateException.class,()->state.selectConversation(id()));assertNotNull(state.draft(sourceKey));assertEquals(1,state.draft(sourceKey).sourceRefs().length());
    }
}
