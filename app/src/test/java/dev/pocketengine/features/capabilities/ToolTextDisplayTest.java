package dev.pocketengine.features.capabilities;

import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;
import org.junit.Test;

public final class ToolTextDisplayTest {
    @Test public void smallActualTextIsUnchangedAndDoesNotInventAnOmission()throws Exception {
        String source="head \"🙂\"\n尾部";var part=ToolTextDisplay.clip(source,512);assertEquals(source,part.text);assertFalse(part.truncated());assertEquals(source.getBytes(StandardCharsets.UTF_8).length,part.headBytes);assertEquals(0,part.tailBytes);assertEquals(0,part.omittedBytes);
    }
    @Test public void bothEndsSurviveUtf8JsonBudgetAndOmittedBytesReferOnlyToActualText()throws Exception {
        String source="HEAD-开始-"+"汉🙂\\\"\n</>".repeat(4000)+"-ENDING-原因";
        for(int budget:new int[]{128,1024,4096,12000}){var part=ToolTextDisplay.clip(source,budget);assertTrue(part.truncated());assertTrue(part.text.startsWith("HEAD-开始-"));assertTrue(part.text.endsWith("-ENDING-原因"));assertTrue(JSONObject.quote(part.text).getBytes(StandardCharsets.UTF_8).length<=budget);assertEquals(source.getBytes(StandardCharsets.UTF_8).length,part.sourceBytes);assertEquals(part.sourceBytes-part.headBytes-part.tailBytes,part.omittedBytes);
            for(int i=0;i<part.text.length();i++){char c=part.text.charAt(i);if(Character.isHighSurrogate(c)){assertTrue(i+1<part.text.length());assertTrue(Character.isLowSurrogate(part.text.charAt(++i)));}else assertFalse(Character.isLowSurrogate(c));}
            assertFalse(part.metadata().getBoolean("nativeOutputChanged"));assertFalse(part.metadata().getBoolean("gapIsSourceContent"));
        }
    }
    @Test public void exhaustedDisplayBudgetDoesNotClaimEmptyNativeOutput()throws Exception {
        var part=ToolTextDisplay.clip("actual original output",0);assertEquals("",part.text);assertTrue(part.truncated());assertEquals(part.sourceBytes,part.omittedBytes);assertEquals(0,part.headBytes+part.tailBytes);
    }
}
