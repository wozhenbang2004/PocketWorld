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
    @Test public void exportKeepsExactUnicodeAndLineEndingsWithOnlyOmittedPathHints()throws Exception{
        String exact=GOOD.replace("\n","\r\n")+"文字与 emoji 🙂\r\n";
        JSONObject files=read(exact).put("scripts/private.sh","SECRET_RESOURCE_BODY").put("resources/example.md","SECRET_REFERENCE_BODY");
        JSONObject preview=SkillDocument.exportPreview(files);
        assertEquals(exact,preview.getString("manifest"));assertEquals(exact.getBytes(StandardCharsets.UTF_8).length,preview.getInt("documentBytes"));
        String expected=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(exact.getBytes(StandardCharsets.UTF_8)));
        assertEquals(expected,preview.getString("documentSha256"));assertEquals("resources/example.md",preview.getJSONArray("omittedResources").getString(0));assertEquals(2,preview.getJSONArray("omittedResources").length());
        assertEquals(1,preview.getJSONArray("mentionedOmittedResources").length());assertEquals("resources/example.md",preview.getJSONArray("mentionedOmittedResources").getString(0));
        assertFalse(preview.toString().contains("SECRET_RESOURCE_BODY"));assertFalse(preview.toString().contains("SECRET_REFERENCE_BODY"));
        assertFalse(preview.getBoolean("executionAuthorized"));assertFalse(preview.getBoolean("containsHostApproval"));assertFalse(preview.getBoolean("containsHostSourceBinding"));assertFalse(preview.getBoolean("resourceReferencesComplete"));
        assertEquals(3,files.length());assertEquals("SECRET_RESOURCE_BODY",files.getString("scripts/private.sh"));
    }
    @Test public void absenceOfPackagedResourcesNeverClaimsExternalReferencesAreComplete()throws Exception{
        JSONObject preview=SkillDocument.exportPreview(read(GOOD));assertEquals(0,preview.getJSONArray("omittedResources").length());assertFalse(preview.getBoolean("resourceReferencesComplete"));assertEquals("exact_packaged_paths_in_text_only",preview.getString("resourceReferenceAnalysis"));
    }
    @Test public void exportStillValidatesManifestAndRejectsLossyUnicode()throws Exception{
        assertThrows(IllegalArgumentException.class,()->SkillDocument.exportPreview(new JSONObject().put("SKILL.md","ordinary markdown")));
        assertThrows(IllegalArgumentException.class,()->SkillDocument.exportPreview(new JSONObject().put("SKILL.md",GOOD+"\u0000")));
        assertThrows(IllegalArgumentException.class,()->SkillDocument.exportPreview(new JSONObject().put("SKILL.md",GOOD+"\ud800")));
    }
}
