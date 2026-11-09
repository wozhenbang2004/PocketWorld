package dev.pocketengine.features.assistant;

import static org.junit.Assert.*;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public final class OwnerComposerStateTest {
    private static String id(){return UUID.randomUUID().toString();}
    private static JSONArray file()throws Exception{return new JSONArray().put(new JSONObject().put("id",id()).put("sha256","a".repeat(64)).put("name","原中文文件.png").put("mimeType","image/png").put("size",24));}
    private static OwnerComposerState.Draft text(OwnerComposerState state,String value)throws Exception{var d=state.active();return state.edit(d.key,d.revision,value,d.mode,d.attachments());}
    private static OwnerComposerState.Pending prepared(OwnerComposerState state)throws Exception{var d=state.active();return state.prepare(id(),d.key,d.revision,"b".repeat(64),null);}
    @Test public void attachmentOnlyDraftMustKeepNativeSelectionAndDurablePendingIdentity()throws Exception{
        var state=OwnerComposerState.empty();var empty=state.active();
        assertThrows(IllegalArgumentException.class,()->state.prepare(id(),empty.key,empty.revision,"a".repeat(64),null));
        state.edit(empty.key,empty.revision,"","queue",file());
        var draft=state.active();
        var pending=state.prepare(id(),draft.key,draft.revision,"b".repeat(64),null);
        assertEquals("",state.active().text);assertEquals(1,state.active().attachments().length());
        var restored=OwnerComposerState.fromJson(state.toJson());
        assertEquals(pending.clientId,restored.pending().get(0).clientId);
        assertEquals("",restored.active().text);assertEquals(1,restored.active().attachments().length());
        var acknowledgement=restored.acknowledge(pending.clientId,id());
        assertTrue(acknowledgement.matched);assertTrue(acknowledgement.cleared);
        assertEquals(0,restored.active().attachments().length());
    }
    @Test public void eachConversationKeepsTextAttachmentAnchorAndFinalVoiceAcrossRoundTrip()throws Exception{
        var state=OwnerComposerState.empty();String a=id(),b=id();var first=state.selectConversation(a);state.edit(first.key,first.revision,"A中文草稿🧭","steer",file());state.reading(first.key,4,21,"original-message","seen-tail",true);state.retainVoice(first.key,id(),"仅属于A的最终听写");
        var second=state.selectConversation(b);state.edit(second.key,second.revision,"B的新目标","queue",new JSONArray());var restored=OwnerComposerState.fromJson(new JSONObject(state.toJson().toString()));assertEquals(b,restored.active().conversationId);
        var actual=restored.selectConversation(a);assertEquals("A中文草稿🧭",actual.text);assertEquals(1,actual.attachments().length());assertEquals("steer",actual.mode);assertEquals(4,actual.scrollIndex);assertEquals(21,actual.scrollOffset);assertEquals("original-message",actual.anchorMessage);assertTrue(actual.newReplies);assertEquals("仅属于A的最终听写",actual.voiceText);assertEquals("B的新目标",restored.selectConversation(b).text);
    }
    @Test public void pendingIdentityAndOriginalTargetSurviveWithoutCredentialOrGrant()throws Exception{
        var state=OwnerComposerState.empty();var d=text(state,"原待核对输入");String task=id(),run=id();var pending=state.prepare(id(),d.key,d.revision,"b".repeat(64),new JSONObject().put("taskId",task).put("runId",run).put("epoch",8).put("grant","NOT-AUTHORITY").put("apiKey","NOT-SAVED"));
        var restored=OwnerComposerState.fromJson(new JSONObject(state.toJson().toString()));var actual=restored.pending().get(0);assertEquals(pending.clientId,actual.clientId);assertEquals(d.key,actual.draftKey);assertEquals(d.revision,actual.draftRevision);assertEquals("b".repeat(64),actual.fingerprint);assertEquals(task,actual.originalTarget().getString("taskId"));assertEquals(8,actual.originalTarget().getInt("epoch"));assertFalse(restored.toJson().toString().contains("NOT-"));
    }
    @Test public void nativeAckMigratesOnlyOriginalTemporaryKeyAndClearsExactRevision()throws Exception{
        var state=OwnerComposerState.empty();var d=text(state,"首次发送");var p=prepared(state);String conversation=id();var ack=state.acknowledge(p.clientId,conversation);assertTrue(ack.matched);assertTrue(ack.cleared);assertTrue(ack.migrated);assertEquals("conversation:"+conversation,state.activeKey());assertEquals("",state.active().text);assertTrue(state.pending().isEmpty());assertNull(state.draft(d.key));
        assertFalse(state.acknowledge(p.clientId,conversation).matched);
    }
    @Test public void lateAckCannotClearAnotherVisibleConversationOrRewrittenIdenticalText()throws Exception{
        var state=OwnerComposerState.empty();String a=id(),b=id();state.selectConversation(a);text(state,"同样的文字");var p=prepared(state);text(state,"");text(state,"同样的文字");state.selectConversation(b);text(state,"B绝不被清除");var ack=state.acknowledge(p.clientId,a);assertFalse(ack.cleared);assertEquals(b,state.active().conversationId);assertEquals("B绝不被清除",state.active().text);assertEquals("同样的文字",state.selectConversation(a).text);
    }
    @Test public void readingUpdatesDoNotMakeUnchangedSubmissionLookEdited()throws Exception{
        var state=OwnerComposerState.empty();String cid=id();state.selectConversation(cid);var d=text(state,"只读滚动不是新输入");var p=prepared(state);state.reading(d.key,8,55,"message-8","tail",true);assertEquals(d.revision,state.active().revision);assertTrue(state.acknowledge(p.clientId,cid).cleared);assertEquals(8,state.active().scrollIndex);
    }
    @Test public void foreignAckAndStaleEditorCasNeverMutateDraft()throws Exception{
        var state=OwnerComposerState.empty();String a=id();state.selectConversation(a);var d=text(state,"原文字");var p=prepared(state);String before=state.toJson().toString();assertThrows(SecurityException.class,()->state.acknowledge(p.clientId,id()));assertEquals(before,state.toJson().toString());text(state,"新文字");assertThrows(IllegalStateException.class,()->state.edit(d.key,d.revision,"迟到覆盖","queue",new JSONArray()));assertEquals("新文字",state.active().text);
    }
    @Test public void sameFingerprintReturnsOriginalPendingAndNeverCreatesSecondIdentity()throws Exception{
        var state=OwnerComposerState.empty();var d=text(state,"这条不能自动重发");var p=prepared(state);var same=state.prepare(id(),d.key,d.revision,p.fingerprint,null);assertEquals(p.clientId,same.clientId);assertEquals(1,state.pending().size());assertThrows(IllegalStateException.class,()->state.prepare(p.clientId,d.key,d.revision,"c".repeat(64),null));
    }
    @Test public void storedAttachmentIsDefensiveAndUntrustedMetadataCannotPickAPath()throws Exception{
        var state=OwnerComposerState.empty();var d=state.active();JSONArray input=file();String original=input.getJSONObject(0).getString("id");state.edit(d.key,d.revision,"附原文件","queue",input);input.getJSONObject(0).put("id",id());state.active().attachments().getJSONObject(0).put("sha256","0".repeat(64));assertEquals(original,state.active().attachments().getJSONObject(0).getString("id"));assertEquals("a".repeat(64),state.active().attachments().getJSONObject(0).getString("sha256"));
        JSONArray invalid=new JSONArray().put(new JSONObject().put("id","../../private").put("sha256","a".repeat(64)).put("name","x").put("mimeType","text/plain").put("size",1));var actual=state.active();assertThrows(IllegalArgumentException.class,()->state.edit(actual.key,actual.revision,"不替换","queue",invalid));assertEquals("附原文件",state.active().text);
    }
    @Test public void voiceRestoresOnlyAsReviewableDataAndRequiresOriginalRevision()throws Exception{
        var state=OwnerComposerState.empty();text(state,"原草稿");var d=state.active();state.retainVoice(d.key,id(),"最终文字，不是partial");var restored=OwnerComposerState.fromJson(state.toJson());var actual=restored.active();assertFalse(restored.toJson().toString().contains("lease"));assertThrows(IllegalStateException.class,()->restored.useVoice(actual.key,actual.revision-1,"不该加入"));restored.useVoice(actual.key,actual.revision,"机主重新核对");assertEquals("原草稿\n机主重新核对",restored.active().text);assertEquals("",restored.active().voiceText);assertTrue(restored.pending().isEmpty());
    }
    @Test public void migrationCollisionKeepsBothInputsAndOriginalPendingInsteadOfOverwriting()throws Exception{
        var state=OwnerComposerState.empty();var original=text(state,"待核对之后的新草稿");var p=prepared(state);text(state,"原输入继续编辑");String cid=id();state.selectConversation(cid);text(state,"此处另一份草稿");String before=state.toJson().toString();assertThrows(IllegalStateException.class,()->state.acknowledge(p.clientId,cid));assertEquals(before,state.toJson().toString());assertEquals("原输入继续编辑",state.selectPending(p.clientId).text);assertEquals(original.key,state.activeKey());
    }
    @Test public void malformedNumbersUnknownFormatsAndDanglingPendingFailClosed()throws Exception{
        var state=OwnerComposerState.empty();text(state,"原件");prepared(state);JSONObject json=state.toJson();json.put("revision",1.5);assertThrows(IllegalArgumentException.class,()->OwnerComposerState.fromJson(json));json.put("revision",state.revision()).put("format","future-v9");assertThrows(IllegalArgumentException.class,()->OwnerComposerState.fromJson(json));JSONObject dangling=state.toJson();dangling.getJSONArray("pending").getJSONObject(0).put("draftKey","new:"+id());assertThrows(IllegalArgumentException.class,()->OwnerComposerState.fromJson(dangling));
    }
    @Test public void capacityNeverEvictsUnsentDraftOrPending()throws Exception{
        var state=OwnerComposerState.empty();text(state,"临时原输入");for(int i=1;i<OwnerComposerState.MAX_DRAFTS;i++){state.selectConversation(id());text(state,"必须保留"+i);}String before=state.toJson().toString();assertThrows(IllegalStateException.class,()->state.selectConversation(id()));assertEquals(before,state.toJson().toString());assertEquals(OwnerComposerState.MAX_DRAFTS,state.toJson().getJSONArray("drafts").length());
    }
    @Test public void nativeImportedConversationIdentityIsOpaqueNotGuessedIntoUuid()throws Exception{
        var state=OwnerComposerState.empty();String original="import-conversation:"+"d".repeat(64)+":428";state.selectConversation(original);text(state,"只属于已审核导入的原对话");var p=prepared(state);var restored=OwnerComposerState.fromJson(state.toJson());assertEquals(original,restored.active().conversationId);assertEquals(original,restored.pending(p.clientId).conversationId);assertTrue(restored.acknowledge(p.clientId,original).cleared);
    }
    @Test public void definitiveRejectionReleasesOnlyPendingIdentityAndPreservesExactDraft()throws Exception{
        var state=OwnerComposerState.empty();String cid=id();state.selectConversation(cid);var d=text(state,"未入队，仍需主人明确发送");
        state.edit(d.key,d.revision,d.text,"steer",file());d=state.active();var p=prepared(state);String draft=state.toJson().getJSONArray("drafts").toString();
        assertTrue(state.rejectNotAdmitted(p.clientId,cid));assertEquals(draft,state.toJson().getJSONArray("drafts").toString());assertTrue(state.pending().isEmpty());
        var restored=OwnerComposerState.fromJson(new JSONObject(state.toJson().toString()));assertEquals(draft,restored.toJson().getJSONArray("drafts").toString());
        assertNull(restored.matchingPending(d.key,p.fingerprint));assertFalse(restored.rejectNotAdmitted(p.clientId,cid));
        assertNotEquals(p.clientId,prepared(restored).clientId);
    }
    @Test public void lateRejectionCannotClearEditedDraftOrAnotherConversation()throws Exception{
        var state=OwnerComposerState.empty();String a=id(),b=id();state.selectConversation(a);text(state,"原消息");var p=prepared(state);text(state,"后来编辑的原草稿");
        state.selectConversation(b);text(state,"另一对话");assertTrue(state.rejectNotAdmitted(p.clientId,a));assertEquals(b,state.active().conversationId);assertEquals("另一对话",state.active().text);
        assertEquals("后来编辑的原草稿",state.selectConversation(a).text);
    }
    @Test public void foreignRejectionCannotReleasePendingIdentity()throws Exception{
        var state=OwnerComposerState.empty();String cid=id();state.selectConversation(cid);text(state,"仍待核对");var p=prepared(state);String before=state.toJson().toString();
        assertThrows(SecurityException.class,()->state.rejectNotAdmitted(p.clientId,id()));assertEquals(before,state.toJson().toString());
    }
}
