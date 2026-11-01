package dev.pocketengine.features.local.project;

import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

public final class ProjectEditorDocumentTest {
    private static ProjectEditorDocument.Search all(ProjectEditorDocument document, String query) { return document.search(query, true, 0, document.text.length()); }
    private static void rejected(Runnable action) { try { action.run(); fail("invalid or stale edit accepted"); } catch (IllegalArgumentException | IllegalStateException expected) {} }

    @Test public void lineAndCodePointPositionsPreserveMixedNewlinesAndUnicode() {
        ProjectEditorDocument document = new ProjectEditorDocument("中文\r\n😀x\nlast\rend\n");
        assertEquals(5, document.lineCount()); assertEquals(4, document.lineStart(2)); assertEquals(8, document.lineStart(3)); assertEquals(13, document.lineStart(4));
        assertEquals(2, document.lineAt(6)); assertEquals(2, document.columnAt(6)); assertEquals(5, document.lineAt(document.text.length()));
        assertEquals("中文\r\n😀x\nlast\rend\n", document.text); rejected(() -> document.lineAt(5));
    }
    @Test public void emptyAndTrailingLineHaveStablePositionsButInvalidLinesReject() {
        ProjectEditorDocument empty = new ProjectEditorDocument(""); assertEquals(1, empty.lineCount()); assertEquals(0, empty.lineStart(1)); assertEquals(1, empty.lineAt(0));
        ProjectEditorDocument newline = new ProjectEditorDocument("a\r\n"); assertEquals(2, newline.lineCount()); assertEquals(3, newline.lineStart(2));
        rejected(() -> newline.lineStart(0)); rejected(() -> newline.lineStart(3)); rejected(() -> newline.lineAt(4));
    }
    @Test public void literalMetacharactersAndNonOverlappingMatchesAreNotRegularExpressions() {
        ProjectEditorDocument document = new ProjectEditorDocument("a.*[x] a.*[x] aaaa");
        ProjectEditorDocument.Search literal = all(document, "a.*[x]"); assertEquals(2, literal.totalMatches); assertEquals(0, literal.match(0).start); assertEquals(7, literal.match(1).start);
        assertEquals(2, all(document, "aa").totalMatches); assertEquals(0, all(document, "^a").totalMatches); assertEquals(0, all(document, "").totalMatches);
    }
    @Test public void caseChoiceAndEmojiNeverMatchHalfASurrogate() {
        ProjectEditorDocument document = new ProjectEditorDocument("Écho éCHO 😀😀");
        assertEquals(1, all(document, "Écho").totalMatches); assertEquals(2, document.search("écho", false, 0, document.text.length()).totalMatches);
        ProjectEditorDocument.Search emoji = all(document, "😀"); assertEquals(2, emoji.totalMatches); assertEquals(2, emoji.match(0).end - emoji.match(0).start);
        rejected(() -> document.search("\ud83d", true, 0, document.text.length())); rejected(() -> document.search("x", true, 12, 13));
    }
    @Test public void selectionScopeDoesNotExpandOrRewriteTheOriginalDraft() {
        ProjectEditorDocument document = new ProjectEditorDocument("foo foo foo");
        ProjectEditorDocument.Search scoped = document.search("foo", true, 4, 7); assertEquals(1, scoped.totalMatches); assertEquals(4, scoped.match(0).start);
        ProjectEditorDocument.Replacement changed = ProjectEditorDocument.replaceAll(scoped, "中", document.text, () -> {});
        assertEquals("foo 中 foo", changed.text); assertEquals(1, changed.replaced); assertEquals("foo foo foo", document.text);
        rejected(() -> document.search("foo", true, 7, 4));
    }
    @Test public void explicitNextAndPreviousWrapWithoutAutomaticallyChangingSelection() {
        ProjectEditorDocument.Search found = all(new ProjectEditorDocument("ab xx ab xx ab"), "ab");
        assertEquals(0, found.next(0, false)); assertEquals(1, found.next(2, false)); assertEquals(0, found.next(14, false));
        assertEquals(2, found.next(0, true)); assertEquals(0, found.next(6, true)); assertEquals(-1, all(new ProjectEditorDocument("x"), "z").next(0, false));
    }
    @Test public void replacementRequiresTheExactSearchedDraftAndOneChosenMatch() {
        ProjectEditorDocument document = new ProjectEditorDocument("40.00 40.00"); ProjectEditorDocument.Search search = all(document, "40.00");
        ProjectEditorDocument.Replacement changed = ProjectEditorDocument.replace(search, 1, "45.00", document.text);
        assertEquals("40.00 45.00", changed.text); assertEquals(6, changed.selectionStart); assertEquals(11, changed.selectionEnd); assertEquals(1, changed.replaced);
        rejected(() -> ProjectEditorDocument.replace(search, 1, "45.00", "40.00 41.00")); rejected(() -> ProjectEditorDocument.replace(search, 2, "45.00", document.text));
    }
    @Test public void replaceAllPreservesEveryUnmatchedNewlineAndAllowsLiteralDeletion() {
        ProjectEditorDocument document = new ProjectEditorDocument("😀v1\r\nv1\rv1\n结束");
        ProjectEditorDocument.Replacement changed = ProjectEditorDocument.replaceAll(all(document, "v1"), "$1\\x", document.text, () -> {});
        assertEquals("😀$1\\x\r\n$1\\x\r$1\\x\n结束", changed.text); assertEquals(3, changed.replaced);
        assertEquals("😀\r\n\r\n结束", ProjectEditorDocument.replaceAll(all(document, "v1"), "", document.text, () -> {}).text);
    }
    @Test public void matchDisplayCapNeverSilentlyTurnsIntoAPartialReplaceAll() {
        ProjectEditorDocument document = new ProjectEditorDocument("a".repeat(ProjectEditorDocument.MAX_MATCHES + 1));
        ProjectEditorDocument.Search search = all(document, "a"); assertEquals(4097, search.totalMatches); assertEquals(4096, search.visibleMatches()); assertFalse(search.complete);
        rejected(() -> ProjectEditorDocument.replaceAll(search, "b", document.text, () -> {})); assertEquals('a', document.text.charAt(4096));
    }
    @Test public void backgroundSearchAndReplaceAreCancellableWithoutChangingTheDraft() {
        ProjectEditorDocument document = new ProjectEditorDocument("z".repeat(20000)); AtomicInteger calls = new AtomicInteger();
        try { document.search("x", true, 0, document.text.length(), () -> { if (calls.incrementAndGet() > 2) throw new CancellationException(); }); fail("search ignored cancellation"); } catch (CancellationException expected) {}
        ProjectEditorDocument small = new ProjectEditorDocument("foo foo foo"); calls.set(0);
        try { ProjectEditorDocument.replaceAll(all(small, "foo"), "bar", small.text, () -> { if (calls.incrementAndGet() > 2) throw new CancellationException(); }); fail("replace ignored cancellation"); } catch (CancellationException expected) {}
        assertEquals("foo foo foo", small.text); assertEquals(20000, document.text.length());
    }
    @Test public void utf8CapacityAndMalformedInputFailWithoutLosingOriginalBytes() {
        rejected(() -> new ProjectEditorDocument("中".repeat(90000))); rejected(() -> new ProjectEditorDocument("a\0b")); rejected(() -> new ProjectEditorDocument("\ud83d"));
        ProjectEditorDocument document = new ProjectEditorDocument("a".repeat(100000)); ProjectEditorDocument.Search search = all(new ProjectEditorDocument("x"), "x");
        rejected(() -> all(document, "x".repeat(513))); rejected(() -> ProjectEditorDocument.replace(search, 0, "中".repeat(90000), "x"));
        ProjectEditorDocument edge = new ProjectEditorDocument("x".repeat(262140) + "a");
        rejected(() -> ProjectEditorDocument.replace(all(edge, "a"), 0, "中中", edge.text)); assertEquals(262141, edge.text.length());
    }
    @Test public void draftConflictAndRecoveryStillRequireOriginalFreshCasAndExplicitOwnerDecision() throws Exception {
        String original = "// 中文\r\nconst amount = 40.00;\r\n";
        org.json.JSONObject review = new org.json.JSONObject().put("projectId", "project").put("checkoutId", "checkout").put("path", "src/main.js")
            .put("content", original).put("expectedHash", ProjectFileDraft.digest(original));
        ProjectFileDraft draft = ProjectFileDraft.empty("project", "checkout", "src/main.js", false).observe(review);
        ProjectEditorDocument document = new ProjectEditorDocument(draft.text); draft = draft.edit(ProjectEditorDocument.replace(all(document, "40.00"), 0, "45.00", draft.text).text);
        ProjectFileDraft cold = ProjectFileDraft.restore(draft.toJson(), "project", "checkout", "src/main.js");
        rejected(cold::saveIntent); String other = original.replace("40.00", "42.00");
        cold = cold.observe(new org.json.JSONObject(review.toString()).put("content", other).put("expectedHash", ProjectFileDraft.digest(other)));
        assertTrue(cold.conflict()); assertTrue(cold.text.contains("45.00")); ProjectFileDraft conflict = cold; rejected(conflict::saveIntent);
        ProjectFileDraft agreed = cold.keepMineAgainstLatest(); assertEquals(ProjectFileDraft.digest(other), agreed.saveIntent().expectedHash); assertTrue(agreed.text.endsWith("\r\n"));
    }
}
