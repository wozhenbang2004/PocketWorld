package dev.pocketengine.features.skills;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** Replays an actual old owner review across enable/disable changes on one SkillBook. */
public final class SkillReviewedGenerationTest {
    @Rule public final TemporaryFolder temp = new TemporaryFolder();
    private JSONObject scope() throws Exception { return new JSONObject().put("ownerId", "owner").put("spaceId", "private"); }
    private String manifest() { return "---\nname: reviewed-reference\ndescription: Read the selected reference before using it\n---\nReview the original content."; }
    private JSONObject install(SkillBook book) throws Exception { return book.install(scope(), new JSONObject().put("manifest", manifest()).put("source", "owner-written"), 1000); }
    @Test public void oldEnableReviewCannotReviveApprovalAfterAba() throws Exception {
        SkillBook book = new SkillBook(temp.newFolder("aba")); JSONObject first = install(book); String id = first.getString("id"), digest = first.getString("digest");
        book.setEnabledReviewed(scope(), id, first.getInt("revision"), digest, first.optLong("executionGeneration", 0), true, 1100);
        JSONObject disabled = book.setEnabled(scope(), id, false, digest, true, 1200);
        assertThrows(SecurityException.class, () -> book.setEnabledReviewed(scope(), id, first.getInt("revision"), digest, first.optLong("executionGeneration", 0), true, 1300));
        assertFalse(book.get(scope(), id, 1400).getBoolean("enabled"));
        assertTrue(book.setEnabledReviewed(scope(), id, disabled.getInt("revision"), digest, disabled.getLong("executionGeneration"), true, 1500).getBoolean("enabled"));
    }
    @Test public void oldDisableReviewCannotStopTheLaterApproval() throws Exception {
        SkillBook book = new SkillBook(temp.newFolder("disable")); JSONObject first = install(book); String id = first.getString("id"), digest = first.getString("digest");
        JSONObject activeA = book.setEnabled(scope(), id, true, digest, true, 1100);
        book.setEnabled(scope(), id, false, digest, true, 1200); JSONObject activeB = book.setEnabled(scope(), id, true, digest, true, 1300);
        assertThrows(SecurityException.class, () -> book.setEnabledReviewed(scope(), id, activeA.getInt("revision"), digest, activeA.getLong("executionGeneration"), false, 1400));
        assertEquals(activeB.getLong("executionGeneration"), book.get(scope(), id, 1500).getLong("executionGeneration")); assertTrue(book.get(scope(), id, 1500).getBoolean("enabled"));
    }
    @Test public void oldFilePreviewCannotOverwriteNewApprovalWithSameContentRevision() throws Exception {
        SkillBook book = new SkillBook(temp.newFolder("import")); JSONObject first = install(book); String id = first.getString("id"); byte[] document = manifest().getBytes(StandardCharsets.UTF_8);
        JSONObject preview = book.inspectDocument(scope(), new ByteArrayInputStream(document), id, 1100), comparison = preview.getJSONObject("comparison");
        JSONObject options = new JSONObject().put("source", "owner-selected-file").put("id", id).put("revision", comparison.getInt("revision")).put("expectedCurrentDigest", comparison.getString("expectedCurrentDigest"))
            .put("expectedExecutionGeneration", comparison.getLong("expectedExecutionGeneration")).put("inspectedDigest", preview.getJSONObject("package").getString("digest"));
        book.setEnabled(scope(), id, true, first.getString("digest"), true, 1200);
        assertThrows(SecurityException.class, () -> book.importDocument(scope(), new ByteArrayInputStream(document), options, 1300));
        JSONObject actual = book.get(scope(), id, 1400); assertEquals(first.getInt("revision"), actual.getInt("revision")); assertTrue(actual.getBoolean("enabled"));
    }
}
