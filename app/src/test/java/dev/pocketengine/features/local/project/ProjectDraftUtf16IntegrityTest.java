package dev.pocketengine.features.local.project;

import static org.junit.Assert.*;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Comparator;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Pure original draft/store only. Does not simulate Android IME, a native file write or a grant. */
public final class ProjectDraftUtf16IntegrityTest {
    private Path directory;
    @Before public void create()throws Exception{directory=Files.createTempDirectory("c2-draft-utf16-");}
    @After public void close()throws Exception{try(var paths=Files.walk(directory)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
    private ProjectFileDraftStore store(){return new ProjectFileDraftStore(directory.toFile(),Runnable::run);}
    private Path record()throws Exception{try(var paths=Files.list(directory)){return paths.filter(p->p.toString().endsWith(".draft.json")).findFirst().orElseThrow();}}
    private void roundTripIsolated(char unit,char changed)throws Exception{
        ProjectFileDraftStore first=store();ProjectFileDraft draft=first.open("project","checkout","main.js",true).edit("literal \\uD800; value="+unit+"; suffix");String before=draft.text;long revision=draft.revision;
        assertTrue("Current official org.json passes an isolated code unit raw before UTF8 encoding",draft.toJson().toString().indexOf(unit)>=0);
        first.retain(draft);assertTrue(first.persistenceError(draft),first.durable(draft));byte[] persisted=Files.readAllBytes(record());
        String encoded=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(persisted)).toString();
        ProjectFileDraft cold=store().open("project","checkout","main.js",true);assertEquals("Cold read must preserve the exact isolated UTF16 unit, never '?'/replacement",before,cold.text);assertTrue(cold.dirty());assertNull(cold.latestReview());assertEquals(revision,cold.revision);assertEquals(unit,cold.text.charAt(cold.text.indexOf("value=")+6));
        IllegalArgumentException refused=assertThrows(IllegalArgumentException.class,cold::saveIntent);assertTrue(refused.getMessage(),refused.getMessage().contains("草稿"));assertEquals(before,cold.text);assertTrue(cold.dirty());
        JSONObject tampered=new JSONObject(encoded).put("text",before.replace(unit,changed));assertThrows(IllegalArgumentException.class,()->ProjectFileDraft.restore(tampered,"project","checkout","main.js"));
        assertTrue("Incomplete integrity must have an explicit private type",draft.toJson().getString("draftHash").startsWith("utf16-v1:"));
        assertNotEquals("An isolated unit must not use replacement-byte integrity",draft.toJson().getString("draftHash"),ProjectFileDraft.digest(before.replace(unit,'?')));
        System.out.println("C2_DRAFT_UTF16 isolated="+Integer.toHexString(unit)+" original JSONObject raw -> strict-valid private JSON escape -> original parser cold exact unit; save rejected/dirty preserved; changed raw unit integrity rejected. Host only.");
    }
    @Test public void isolatedHighSurrogateSurvivesPrivateColdDraftButCannotBecomeFileBytes()throws Exception{roundTripIsolated('\uD83D','\uD83E');}
    @Test public void isolatedLowSurrogateSurvivesPrivateColdDraftButCannotBecomeFileBytes()throws Exception{roundTripIsolated('\uDE00','\uDE01');}
    @Test public void legitimateEmojiOriginalUtf8BytesLiteralEscapeAndV1HashRemainUnchanged()throws Exception{
        ProjectFileDraftStore first=store();String text="// 中文 \uD83D\uDE00\r\nconst literal='\\uD800';\n";ProjectFileDraft draft=first.open("project","checkout","emoji.js",true).edit(text);first.retain(draft);assertTrue(first.durable(draft));assertArrayEquals("Valid original JSON bytes must not be rewritten",draft.toJson().toString().getBytes(StandardCharsets.UTF_8),Files.readAllBytes(record()));
        assertEquals(java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))),ProjectFileDraft.digest(text));ProjectFileDraft cold=store().open("project","checkout","emoji.js",true);assertEquals(text,cold.text);assertEquals(text,cold.saveIntent().text);assertEquals("absent",cold.saveIntent().expectedHash);assertTrue(cold.text.contains("\\uD800"));
    }
    @Test public void continuedInputCompletesPairThenNormalIntentAndReceiptCanClearOnlySubmittedRevision()throws Exception{
        ProjectFileDraftStore first=store();ProjectFileDraft incomplete=first.open("project","checkout","typing.js",true).edit("const value='\uD83D");first.retain(incomplete);assertThrows(IllegalArgumentException.class,incomplete::saveIntent);ProjectFileDraft complete=incomplete.edit("const value='\uD83D\uDE00';\n");first.retain(complete);assertTrue(first.durable(complete));ProjectFileDraft cold=store().open("project","checkout","typing.js",true);assertEquals(complete.text,cold.text);ProjectFileDraft.SaveIntent intent=cold.saveIntent();assertEquals(complete.revision,intent.revision);assertEquals(complete.text,intent.input().getString("content"));
        // Pure state receipt contract only; no actual Local/workspace write is claimed.
        ProjectFileDraft acknowledged=cold.saved(intent,new JSONObject().put("checkoutId","checkout").put("path","typing.js").put("content",intent.text).put("sha256",ProjectFileDraft.digest(intent.text)));assertFalse(acknowledged.dirty());assertEquals(complete.text,acknowledged.text);
        assertEquals(262144,ProjectFileDraft.empty("project","checkout","bounded.js",true).edit("a".repeat(262144)).saveIntent().text.length());assertThrows(IllegalArgumentException.class,()->ProjectFileDraft.empty("project","checkout","bounded.js",true).edit("a".repeat(262145)).saveIntent());
    }
}
