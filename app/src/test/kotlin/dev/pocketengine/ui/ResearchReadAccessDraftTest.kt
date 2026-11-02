package dev.pocketengine.ui

import org.junit.Assert.*
import org.junit.Test

class ResearchReadAccessDraftTest {
    private val project = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"

    @Test fun selectionContainsOnlyOwnerChosenSitesAndOriginalFileLocators() {
        val selection = ResearchReadAccessDraft("https://example.org\nhttps://docs.example.org", listOf(ResearchReadFileChoice(project, "src/notes.txt", "原项目"))).selection()
        assertEquals(setOf("webOrigins", "projectFiles"), selection.keys().asSequence().toSet())
        val file = selection.getJSONArray("projectFiles").getJSONObject(0)
        assertEquals(setOf("projectId", "path"), file.keys().asSequence().toSet())
        assertEquals("src/notes.txt", file.getString("path"))
        assertFalse(selection.has("profileId")); assertFalse(file.has("version")); assertFalse(file.has("sha256"))
        assertEquals("https://example.org", ResearchReadAccessDraft("https://example.org/").selection().getJSONArray("webOrigins").getString(0))
    }

    @Test fun aPageUrlCannotBeSilentlyBroadenedToAnEntireSite() {
        listOf("https://example.org/article", "https://user:password@example.org", "https://localhost", "http://example.org").forEach { site ->
            assertNotNull(site, ResearchReadAccessDraft(site).error())
        }
    }

    @Test fun duplicateAndExcessChoicesAndPathsOutsideTheOriginalProjectAreRejected() {
        assertNotNull(ResearchReadAccessDraft("https://example.org\nhttps://example.org").error())
        assertNotNull(ResearchReadAccessDraft((1..5).joinToString("\n") { "https://site$it.example.org" }).error())
        assertNotNull(ResearchReadAccessDraft(files = listOf(ResearchReadFileChoice(project, "../secret", "原项目"))).error())
        val file = ResearchReadFileChoice(project, "notes.txt", "原项目")
        assertNotNull(ResearchReadAccessDraft(files = listOf(file, file)).error())
        assertNotNull(ResearchReadAccessDraft(files = (1..33).map { ResearchReadFileChoice(project, "$it.txt", "原项目") }).error())
    }
}
