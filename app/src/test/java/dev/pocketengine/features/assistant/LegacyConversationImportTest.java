package dev.pocketengine.features.assistant;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Pure exact-byte locator contracts, not Android/device or model execution. */
public final class LegacyConversationImportTest {
    @Test public void unicodeQuotedBracesWhitespaceAndFieldsAfterMessagesKeepExactByteRanges()throws Exception{
        byte[] raw=(" \n[ { \"conversationId\":\"one\",\"messages\" : [ {\"role\":\"user\",\"content\":\"🙂中文, } ] \\\" {\"}, {\"role\":\"assistant\",\"content\":\"第二条\"} ] , \"unknownHeader\":{\"keep\":true} }, {\"conversationId\":\"two\",\"messages\":[]} ]\t ").getBytes(StandardCharsets.UTF_8);
        byte[] copy=raw.clone();var indexed=LegacyConversationImport.index(raw,ConversationRepository.shaBytes(raw));assertEquals(2,indexed.size());assertEquals(2,indexed.get(0).messages.size());assertTrue(indexed.get(0).header.getJSONObject("unknownHeader").getBoolean("keep"));var range=indexed.get(0).messages.get(0);String fragment=new String(Arrays.copyOfRange(raw,range.start,range.end),StandardCharsets.UTF_8);assertEquals("🙂中文, } ] \" {",new JSONObject(fragment).getString("content"));assertArrayEquals(copy,raw);assertThrows(UnsupportedOperationException.class,()->indexed.clear());
    }
    @Test public void assignedConversationLocatorIsExplicitAndNeverCreatesHistoricalTimeOrSourceRevision()throws Exception{
        byte[] raw="[{\"messages\":[{\"role\":\"user\",\"content\":\"无历史身份\"}]}]".getBytes(StandardCharsets.UTF_8);String sha=ConversationRepository.shaBytes(raw);var entry=LegacyConversationImport.index(raw,sha).get(0);assertTrue(entry.id.startsWith("import-conversation:"+sha));assertEquals("import_assigned_not_historical",entry.header.getString("conversationIdProvenance"));assertFalse(entry.header.has("createdAt"));assertFalse(entry.header.has("revision"));assertFalse(new JSONObject(LegacyConversationImport.text(raw,entry.messages.get(0))).has("revision"));assertFalse(entry.header.getBoolean("historicalIdentityVerified"));
    }
    @Test public void malformedBoundaryDuplicateFieldAndTrailingNonWhitespaceAreNotGuessed()throws Exception{
        for(String invalid:new String[]{"[{\"conversationId\":\"one\",\"messages\":[{\"content\":\"unterminated}]}]","[{\"messages\":[],\"messages\":[]}]","[{\"messages\":[]}] trailing"}){byte[] raw=invalid.getBytes(StandardCharsets.UTF_8);assertThrows(Exception.class,()->LegacyConversationImport.index(raw,ConversationRepository.shaBytes(raw)));}
    }
    @Test public void invalidUtf8AndMissingRealSourceShaFailWithoutReplacingOriginalBytes()throws Exception{
        byte[] raw={(byte)'[',(byte)'"',(byte)0xc3,(byte)'"',(byte)']'},copy=raw.clone();assertThrows(Exception.class,()->LegacyConversationImport.index(raw,"a".repeat(64)));assertArrayEquals(copy,raw);byte[] valid="[]".getBytes(StandardCharsets.UTF_8);assertThrows(IllegalArgumentException.class,()->LegacyConversationImport.index(valid,""));
    }
}
