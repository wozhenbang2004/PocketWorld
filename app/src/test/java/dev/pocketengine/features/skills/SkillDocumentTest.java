package dev.pocketengine.features.skills;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class SkillDocumentTest {
    private static final String GOOD="---\nname: local-note\ndescription: A selected reference\n---\nRead resources/example.md only when explicitly available.\n";
    private static JSONObject read(String text)throws Exception{return SkillDocument.read(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));}
    @Test public void keepsExactTextAsOneUntrustedEntryWithoutFetchingReferencedResources()throws Exception{JSONObject files=read(GOOD);assertEquals(1,files.length());assertEquals(GOOD,files.getString("SKILL.md"));assertFalse(files.has("resources/example.md"));}
    @Test public void unchangedInvalidManifestAndBinaryTextRemainRejected()throws Exception{assertThrows(IllegalArgumentException.class,()->read(GOOD.replace("name: local-note","name: ../outside")));assertThrows(IllegalArgumentException.class,()->read(GOOD+"\u0000"));assertThrows(IllegalArgumentException.class,()->read("ordinary markdown without frontmatter"));}
    @Test public void invalidUtf8AndOriginalByteAndCharacterLimitsRemainStrict()throws Exception{assertThrows(java.nio.charset.CharacterCodingException.class,()->SkillDocument.read(new ByteArrayInputStream(new byte[]{(byte)0xc3,0x28})));assertThrows(IllegalArgumentException.class,()->read(GOOD+"a".repeat(64000)));assertThrows(IllegalArgumentException.class,()->SkillDocument.read(new ByteArrayInputStream(new byte[256001])));}
    @Test public void currentManifestInvocationRestrictionsRemainExactAndValidated()throws Exception{
        String restricted=GOOD.replace("description: A selected reference","description: A selected reference\ndisable-model-invocation: true\nuser-invocable: false");
        JSONObject files=read(restricted),parsed=SkillManifest.parse(files.getString("SKILL.md"));assertEquals(restricted,files.getString("SKILL.md"));assertTrue(parsed.getBoolean("disable-model-invocation"));assertFalse(parsed.getBoolean("user-invocable"));
        assertThrows(IllegalArgumentException.class,()->read(restricted.replace("disable-model-invocation: true","disable-model-invocation: sometimes")));
    }
}
