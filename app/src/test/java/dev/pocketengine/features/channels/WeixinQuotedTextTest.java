package dev.pocketengine.features.channels;

import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.json.JSONObject;
import org.junit.Test;

/** Selected protocol text fixtures; no account authentication or real WeChat assertions. */
public final class WeixinQuotedTextTest {
    private static JSONObject selection(String left,int a,String right,int b,String selected)throws Exception {
        StringBuilder hash=new StringBuilder();for(byte v:MessageDigest.getInstance("MD5").digest(selected.getBytes(StandardCharsets.UTF_8)))hash.append(String.format(java.util.Locale.ROOT,"%02x",v&255));
        return new JSONObject().put("start",left).put("startindex",a).put("end",right).put("endindex",b).put("quotemd5",hash.toString());
    }
    @Test public void occurrenceOrdinalIsNotCharacterOffsetAndActualSelectedTextIsVerified()throws Exception {
        String original="甲[一]乙[二]丙[三]尾";JSONObject result=WeixinQuotedText.select(original,selection("[",1,"]",1,"[二]"));assertTrue(result.getBoolean("available"));assertEquals("[二]",result.getString("text"));assertEquals("global_occurrence",result.getString("indexInterpretation"));assertEquals("md5_matched",result.getString("integrity"));
    }
    @Test public void hashCanDisambiguateRelativeEndOccurrenceWithoutReturningTheWholeOriginal()throws Exception {
        String original="[旧] before [新] after";JSONObject result=WeixinQuotedText.select(original,selection("[",1,"]",0,"[新]"));assertEquals("[新]",result.getString("text"));assertEquals("relative_end_occurrence",result.getString("indexInterpretation"));
    }
    @Test public void missingOrWrongPartialEvidenceNeverSilentlyFallsBackToFullText()throws Exception {
        JSONObject wrong=selection("[",0,"]",0,"different");JSONObject no=WeixinQuotedText.select("[selected] PRIVATE REST",wrong);assertFalse(no.getBoolean("available"));assertEquals("",no.getString("text"));assertEquals("partial_hash_mismatch",no.getString("reason"));
        wrong.put("startindex",-1);assertFalse(WeixinQuotedText.select("[selected]",wrong).getBoolean("available"));wrong.put("startindex",0.5);assertFalse(WeixinQuotedText.select("[selected]",wrong).getBoolean("available"));
    }
    @Test public void noHashUsesExplicitUnverifiedGlobalVariantAndDoesNotPretendAnIntegrityProof()throws Exception {
        JSONObject partial=selection("[",0,"]",0,"[actual]");partial.remove("quotemd5");JSONObject result=WeixinQuotedText.select("[actual] suffix",partial);assertEquals("[actual]",result.getString("text"));assertEquals("no_hash_provided",result.getString("integrity"));assertFalse(result.has("owner"));assertFalse(result.has("scope"));
    }
    @Test public void validUnicodeIsPreservedButSurrogatePairBoundariesCannotBeSplit()throws Exception {
        assertEquals("🙂中🙂",WeixinQuotedText.select("先🙂中🙂后",selection("🙂",0,"🙂",1,"🙂中🙂")).getString("text"));
        JSONObject half=new JSONObject().put("start","\ude42").put("startindex",0).put("end","中").put("endindex",0);assertFalse(WeixinQuotedText.select("🙂中",half).getBoolean("available"));
    }
    @Test public void oversizedMissingAndNonNumericOrdinalsRemainUnavailable()throws Exception {
        assertFalse(WeixinQuotedText.select("a".repeat(4001),null).getBoolean("available"));assertFalse(WeixinQuotedText.select("",null).getBoolean("available"));JSONObject partial=selection("[",0,"]",0,"[a]").put("endindex","0");assertFalse(WeixinQuotedText.select("[a]",partial).getBoolean("available"));
    }
}
