package dev.pocketengine.ui

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SkillSourceControlsStateTest {
    @Test fun oldPreviewNeverChangesToNewSelectedTargetOrRevision() {
        val original = JSONObject().put("id", "skill-a").put("revision", 2).put("digest", "d".repeat(64))
        val view = JSONObject().put("targetId", "skill-a").put("comparison", JSONObject().put("id", "skill-a").put("revision", 2).put("expectedCurrentDigest", "d".repeat(64)))
        assertTrue(skillSourceReviewMatches(view, original))
        assertFalse(skillSourceReviewMatches(view, JSONObject(original.toString()).put("id", "skill-b")))
        assertFalse(skillSourceReviewMatches(view, JSONObject(original.toString()).put("revision", 3)))
        assertFalse(skillSourceReviewMatches(view, null))
        assertFalse(skillSourceReviewMatches(view, JSONObject(original.toString()).put("digest", "e".repeat(64))))
    }
    @Test fun newImportAndUnknownStatesAreNotShownAsValidatedExistingUpdates() {
        val view = JSONObject().put("targetId", "").put("comparison", JSONObject().put("kind", "new"))
        assertTrue(skillSourceReviewMatches(view, null))
        assertFalse(skillSourceReviewMatches(view, JSONObject().put("id", "selected")))
        assertEquals("暂不能确认变化", skillSourceClassification("unexpected"))
        assertNotEquals(skillSourceClassification("current"), skillSourceClassification("both_changed"))
        assertTrue(skillSourceClassification("local_changed").contains("本地已修改"))
    }
}
