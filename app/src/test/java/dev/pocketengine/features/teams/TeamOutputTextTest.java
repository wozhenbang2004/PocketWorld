package dev.pocketengine.features.teams;

import dev.pocketengine.features.workspace.JsonFiles;
import java.io.File;
import java.nio.file.Files;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class TeamOutputTextTest {
    @Test public void completeReceivedOutputsSurviveStorageAndAllPreviewBoundaries() throws Exception {
        File record = new File(Files.createTempDirectory("team-original-text-").toFile(), "run.json");
        for (int boundary : new int[]{3999, 4000, 4001, 7999, 8000, 8001, 11999, 12000, 12001, 15999, 16000, 16001, 64001}) {
            String source = "\n " + "材".repeat(boundary) + "\uD83D\uDE00原文尾部 \n";
            String received = TeamOutputText.received(source, "");
            JsonFiles.write(record, new JSONObject().put("text", received));
            String saved = JsonFiles.read(record).getString("text");
            assertEquals(source, saved);
            JSONObject preview = TeamOutputText.preview(saved, "original-turn", 8000);
            assertEquals(saved.length(), preview.getInt("originalChars"));
            assertEquals(saved.length(), preview.getInt("characters"));
            assertEquals(preview.getString("content").length(), preview.getInt("returnedChars"));
            assertEquals(saved.length() > 8000, preview.getBoolean("truncated"));
            StringBuilder assembled = new StringBuilder();
            long next = 0;
            do {
                JSONObject page = TeamOutputText.page(saved, "original-turn", next, 4000);
                assertEquals(saved.length(), page.getInt("totalCharacters"));
                assertEquals(preview.getString("sha256"), page.getString("sha256"));
                assertEquals("original-turn", page.getJSONObject("readRef").getString("turnId"));
                assertTrue(page.isNull("upstreamOriginalChars"));
                assertTrue(page.isNull("upstreamTruncated"));
                assembled.append(page.getString("content"));
                if (page.isNull("nextOffset")) break;
                next = page.getLong("nextOffset");
            } while (true);
            assertEquals(source, assembled.toString());
            assertEquals(preview.getString("sha256"), TeamOutputText.hash(assembled.toString()));
        }
    }

    @Test public void redactionPrecedesAllLengthsAndHashesWithoutDiscardingTailOrWhitespace() throws Exception {
        String original = "  " + "x".repeat(15998) + "private-key" + "\n实际尾部  ";
        String safe = TeamOutputText.received(original, "private-key");
        assertEquals(original.replace("private-key", "[密钥已隐藏]"), safe);
        JSONObject page = TeamOutputText.page(safe, "turn", 15998, 8000);
        assertFalse(page.toString().contains("private-key"));
        assertTrue(page.getString("content").endsWith("\n实际尾部  "));
        assertEquals(safe.length(), page.getInt("originalChars"));
        assertEquals(TeamOutputText.hash(safe), page.getString("sha256"));
    }

    @Test public void previewAndPagedJsonNeverSplitUnicodeSurrogatesAtTheirBoundaries() throws Exception {
        String source = "x".repeat(3999) + "\uD83D\uDE00" + "y".repeat(3998) + "\uD83D\uDE00" + "tail";
        JSONObject preview = TeamOutputText.preview(source, "turn", 8000);
        assertEquals(7999, preview.getInt("returnedChars"));
        assertTrue(java.nio.charset.StandardCharsets.UTF_8.newEncoder().canEncode(preview.toString()));
        StringBuilder full = new StringBuilder(); long offset = 0;
        do {
            JSONObject page = TeamOutputText.page(source, "turn", offset, 4000);
            assertTrue(java.nio.charset.StandardCharsets.UTF_8.newEncoder().canEncode(page.toString()));
            full.append(page.getString("content"));
            if (page.isNull("nextOffset")) break;
            offset = page.getLong("nextOffset");
        } while (true);
        assertEquals(source, full.toString());
        try { TeamOutputText.page(source, "turn", 4000, 8); fail("mid-surrogate offset accepted"); }
        catch (IllegalArgumentException expected) { }
        try { TeamOutputText.page(source, "turn", 3999, 1); fail("partial Unicode character returned"); }
        catch (IllegalArgumentException expected) { }
        try { TeamOutputText.received("broken\uD800", ""); fail("malformed Unicode silently replaced"); }
        catch (IllegalArgumentException expected) { }
    }

    @Test public void pagingCannotExpandItsExistingBoundOrInventAnUpstreamLength() throws Exception {
        for (long[] range : new long[][]{{-1, 1}, {4, 1}, {0, 0}, {0, 8001}, {0, Long.MAX_VALUE}}) {
            try { TeamOutputText.page("abc", "turn", range[0], range[1]); fail("invalid range accepted"); }
            catch (IllegalArgumentException expected) { }
        }
        JSONObject end = TeamOutputText.page("abc", "turn", 3, 8000);
        assertEquals("", end.getString("content"));
        assertTrue(end.isNull("nextOffset"));
        assertEquals(3, end.getInt("originalChars"));
        assertTrue(end.isNull("upstreamOriginalChars"));
        try { TeamOutputText.received(" \t\n", ""); fail("blank output accepted"); }
        catch (IllegalArgumentException expected) { }
    }

    @Test public void existingStorageLimitStillRejectsOversizeWithoutReplacingPreviousReceipt() throws Exception {
        File record = new File(Files.createTempDirectory("team-output-limit-").toFile(), "run.json");
        JsonFiles.write(record, new JSONObject().put("text", "previous complete result"));
        try {
            JsonFiles.write(record, new JSONObject().put("text", TeamOutputText.received("x".repeat(8 * 1024 * 1024), "")));
            fail("existing record size limit was bypassed");
        } catch (java.io.IOException expected) { }
        assertEquals("previous complete result", JsonFiles.read(record).getString("text"));
    }
}
