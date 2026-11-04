package dev.pocketengine.features.assistant;

import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Real editor CAS and persistence; these values alone never restore a native preview selection. */
public final class OwnerComposerPreviewRefsTest {
    private static String id(){return UUID.randomUUID().toString();}
    private static JSONArray preview(String conversation,String text)throws Exception {
        return new JSONArray().put(new JSONObject().put("referenceId",id()).put("taskId",id()).put("runId",id()).put("epoch",1)
            .put("conversationId",conversation).put("projectId","original-project").put("checkoutId","original-checkout")
            .put("previewId","a".repeat(64)).put("sourceDigest","b".repeat(64)).put("documentId",id()).put("documentGeneration",2L)
            .put("path","dist/index.html").put("resourceSha256","c".repeat(64)).put("selectorHint","button#save span")
            .put("text",text).put("htmlSnippet","<span>"+text+"</span>")
            .put("bounds",new JSONObject().put("x",12).put("y",80).put("width",100).put("height",24))
            .put("truncated",new JSONObject().put("selectorHint",false).put("text",false).put("htmlSnippet",false))
            .put("untrusted",true).put("selectorIsSourceLocation",false));
    }
    private static JSONArray quote()throws Exception {
        return new JSONArray().put(new JSONObject().put("generation","native-generation").put("sourceConversationId","earlier-conversation")
            .put("sourceRef",new JSONObject().put("kind","message").put("sourceId","earlier-message").put("revision",3L).put("ownerId","owner").put("spaceId","private"))
            .put("bodyFingerprint","sha256:"+"d".repeat(64)).put("offset",4).put("chars",12).put("unit","UTF-16"));
    }
    private static JSONArray attachment()throws Exception {
        return new JSONArray().put(new JSONObject().put("id",id()).put("sha256","e".repeat(64)).put("name","original.png").put("mimeType","image/png").put("size",28));
    }
    private static OwnerComposerState seeded(String conversation)throws Exception {
        var state=OwnerComposerState.empty();var d=state.selectConversation(conversation);
        d=state.edit(d.key,d.revision,"请改这个按钮的文字","queue",attachment());state.editSources(d.key,d.revision,quote());return state;
    }
    @Test public void selectingElementPreservesOriginalTextQuoteAndAttachment()throws Exception {
        String conversation=id();var state=seeded(conversation);var before=state.active();JSONArray refs=preview(conversation,"保存");
        var after=state.editPreviewRefs(before.key,before.revision,refs);
        assertEquals(before.text,after.text);assertEquals(before.sourceRefs().toString(),after.sourceRefs().toString());assertEquals(before.attachments().toString(),after.attachments().toString());
        assertEquals(before.revision+1,after.revision);assertEquals(1,after.previewRefs().length());assertTrue(state.pending().isEmpty());
        refs.getJSONObject(0).put("text","forged");after.previewRefs().getJSONObject(0).put("text","another mutation");assertEquals("保存",after.previewRefs().getJSONObject(0).getString("text"));
    }
    @Test public void editsVoiceReadingAndColdMetadataRetainAllIndependentSlots()throws Exception {
        String conversation=id();var state=seeded(conversation);var d=state.active();d=state.editPreviewRefs(d.key,d.revision,preview(conversation,"保存"));String refs=d.previewRefs().toString(),quotes=d.sourceRefs().toString(),files=d.attachments().toString();
        d=state.edit(d.key,d.revision,"只改文字","steer",d.attachments());state.reading(d.key,5,17,"anchor","seen",true);state.retainVoice(d.key,id(),"最终听写");d=state.active();state.useVoice(d.key,d.revision,"补充说明");
        var restored=OwnerComposerState.fromJson(state.toJson()).active();assertEquals(refs,restored.previewRefs().toString());assertEquals(quotes,restored.sourceRefs().toString());assertEquals(files,restored.attachments().toString());assertEquals(5,restored.scrollIndex);assertTrue(restored.text.contains("补充说明"));
        assertFalse(state.toJson().toString().contains("selectionValidated"));assertFalse(state.toJson().toString().contains("executionAuthorized"));
    }
    @Test public void quoteChangeAndDomRemovalPreserveEachOtherAndFiles()throws Exception {
        String conversation=id();var state=seeded(conversation);var d=state.active();d=state.editPreviewRefs(d.key,d.revision,preview(conversation,"保存"));String originalDom=d.previewRefs().toString(),files=d.attachments().toString();
        JSONArray nextQuote=quote();nextQuote.getJSONObject(0).put("offset",80);d=state.editSources(d.key,d.revision,nextQuote);assertEquals(originalDom,d.previewRefs().toString());
        d=state.editPreviewRefs(d.key,d.revision,new JSONArray());assertEquals(0,d.previewRefs().length());assertEquals(80,d.sourceRefs().getJSONObject(0).getInt("offset"));assertEquals(files,d.attachments().toString());assertEquals("请改这个按钮的文字",d.text);
    }
    @Test public void lateAcknowledgementCannotClearANewerElementSelection()throws Exception {
        String conversation=id();var state=seeded(conversation);var d=state.active();d=state.editPreviewRefs(d.key,d.revision,preview(conversation,"第一版"));var pending=state.prepare(id(),d.key,d.revision,"a".repeat(64),null);
        d=state.editPreviewRefs(d.key,d.revision,preview(conversation,"后选版"));assertFalse(state.acknowledge(pending.clientId,conversation).cleared);assertEquals("后选版",state.active().previewRefs().getJSONObject(0).getString("text"));assertEquals(1,state.active().sourceRefs().length());assertEquals(1,state.active().attachments().length());
    }
    @Test public void exactAcknowledgementClearsOnlyTheAcceptedDraft()throws Exception {
        String conversation=id();var state=seeded(conversation);var d=state.active();d=state.editPreviewRefs(d.key,d.revision,preview(conversation,"保存"));var pending=state.prepare(id(),d.key,d.revision,"b".repeat(64),null);
        assertTrue(state.acknowledge(pending.clientId,conversation).cleared);assertEquals("",state.active().text);assertEquals(0,state.active().previewRefs().length());assertEquals(0,state.active().sourceRefs().length());assertEquals(0,state.active().attachments().length());
    }
    @Test public void staleCasAndCrossConversationSelectionLeaveDraftUnchanged()throws Exception {
        String conversation=id();var state=seeded(conversation);var old=state.active();state.edit(old.key,old.revision,"已经更新的文字","queue",old.attachments());String before=state.toJson().toString();
        assertThrows(IllegalStateException.class,()->state.editPreviewRefs(old.key,old.revision,preview(conversation,"迟到")));assertEquals(before,state.toJson().toString());
        var current=state.active();assertThrows(SecurityException.class,()->state.editPreviewRefs(current.key,current.revision,preview(id(),"别处")));assertEquals(before,state.toJson().toString());
    }
    @Test public void oldDraftKeepsItsExactPendingFingerprintAndDefaultsToNoPreview()throws Exception {
        String conversation=id();var state=seeded(conversation);var d=state.active();var pending=state.prepare(id(),d.key,d.revision,"f".repeat(64),null);JSONObject old=state.toJson();
        for(int i=0;i<old.getJSONArray("drafts").length();i++)old.getJSONArray("drafts").getJSONObject(i).remove("ownerPreviewRefs");
        var restored=OwnerComposerState.fromJson(old);assertEquals(pending.fingerprint,restored.pending(pending.clientId).fingerprint);assertEquals(pending.draftRevision,restored.active().revision);assertEquals(0,restored.active().previewRefs().length());
    }
    @Test public void coldCrossConversationMetadataIsRejectedWithoutMintingASelection()throws Exception {
        String conversation=id();var state=seeded(conversation);JSONObject invalid=state.toJson();JSONArray drafts=invalid.getJSONArray("drafts");
        for(int i=0;i<drafts.length();i++)if(drafts.getJSONObject(i).getString("conversationId").equals(conversation))drafts.getJSONObject(i).put("ownerPreviewRefs",preview(id(),"别处"));
        assertThrows(SecurityException.class,()->OwnerComposerState.fromJson(invalid));assertEquals(0,state.active().previewRefs().length());
    }
    @Test public void previewOnlyDraftIsNotEvictedAsEmpty()throws Exception {
        var state=OwnerComposerState.empty();var initial=state.active();state.edit(initial.key,initial.revision,"保留新草稿","queue",new JSONArray());String conversation=id();var d=state.selectConversation(conversation);String key=d.key;state.editPreviewRefs(key,d.revision,preview(conversation,"保存"));
        for(int i=2;i<OwnerComposerState.MAX_DRAFTS;i++){var other=state.selectConversation(id());state.edit(other.key,other.revision,"保留"+i,"queue",new JSONArray());}
        assertThrows(IllegalStateException.class,()->state.selectConversation(id()));assertEquals(1,state.draft(key).previewRefs().length());assertEquals("",state.draft(key).text);
    }
}
