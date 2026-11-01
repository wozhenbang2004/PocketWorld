package dev.pocketengine.features.local.language;

import static org.junit.Assert.*;
import dev.pocketengine.features.local.project.*;
import java.nio.file.Files;
import java.util.*;
import org.json.*;
import org.junit.Test;

public final class ProjectLanguageSnapshotTest {
    private static final String PROJECT="a1111111-1111-4111-8111-111111111111",CHECKOUT="b2222222-2222-4222-8222-222222222222";
    private static JSONObject source(Map<String,String> files)throws Exception{JSONArray rows=new JSONArray();for(var file:files.entrySet())rows.put(new JSONObject().put("path",file.getKey()).put("content",file.getValue()));return new JSONObject().put("checkout",new JSONObject().put("id",CHECKOUT).put("projectId",PROJECT)).put("files",rows);}
    private static JSONObject result(ProjectLanguageSnapshot source,String action,int position,JSONArray rows)throws Exception{return new JSONObject().put("format",ProjectLanguageSnapshot.FORMAT).put("compilerVersion","5.9.3").put("snapshotIdentity",source.identity).put("path",source.path).put("sha256",source.draftSha256).put("action",action).put("position",position).put("rows",rows).put("truncated",false).put("userCodeExecuted",false).put("projectPluginsLoaded",false);}
    @Test public void snapshotPinsActualProjectAndUnsavedBufferAndExcludesKnownPrivateConfiguration()throws Exception{
        JSONObject nativeSource=source(Map.of("main.ts","export const amount=40;","src.v2/calc.ts","export const plus=5;","credentials.json","{\"private\":true}","package.json","{\"type\":\"module\"}"));
        var snapshot=ProjectLanguageSnapshot.fromNativeExport(nativeSource,"main.ts","export const amount=45;",7);assertEquals(7,snapshot.draftRevision);assertEquals(3,snapshot.request("diagnostics",0,null).getJSONArray("files").length());assertEquals("export const amount=45;",snapshot.originalText("main.ts"));assertThrows(Exception.class,()->snapshot.expectedHash("credentials.json"));
        var changed=ProjectLanguageSnapshot.fromNativeExport(nativeSource,"main.ts","export const amount=46;",8);assertNotEquals(snapshot.identity,changed.identity);assertEquals(ProjectSources.describe(nativeSource).getString("sourceDigest"),snapshot.sourceDigest);assertThrows(Exception.class,()->ProjectLanguageSnapshot.fromNativeExport(nativeSource,"main.ts","\ud800",9));
    }
    @Test public void foreignHashRangeAndPartialSemanticEditsNeverBecomeAnApplicableResult()throws Exception{
        var snapshot=ProjectLanguageSnapshot.fromNativeExport(source(Map.of("main.ts","const amount=40;\n")),"main.ts",null,0);JSONObject edit=new JSONObject().put("path","main.ts").put("sha256",snapshot.draftSha256).put("start",6).put("length",6).put("newText","total");JSONObject valid=result(snapshot,"rename",7,new JSONArray().put(edit));assertEquals(CHECKOUT,snapshot.accept(valid,"rename",7).getString("checkoutId"));
        assertThrows(Exception.class,()->snapshot.accept(new JSONObject(valid.toString()).put("snapshotIdentity","0".repeat(64)),"rename",7));assertThrows(Exception.class,()->snapshot.accept(new JSONObject(valid.toString()).put("rows",new JSONArray().put(new JSONObject(edit.toString()).put("start",999))),"rename",7));assertThrows(Exception.class,()->snapshot.accept(new JSONObject(valid.toString()).put("truncated",true),"rename",7));assertThrows(Exception.class,()->snapshot.accept(new JSONObject(valid.toString()).put("rows",new JSONArray().put(new JSONObject(edit.toString()).put("sha256","0".repeat(64)))),"rename",7));
    }
    @Test public void completeRenamePreviewsEveryPinnedFileAndCompletionNeverOverwritesLaterText()throws Exception{
        var snapshot=ProjectLanguageSnapshot.fromNativeExport(source(Map.of("main.ts","const amount=40;\n","other.ts","amount;\n")),"main.ts",null,0);JSONArray rows=new JSONArray().put(new JSONObject().put("path","main.ts").put("sha256",snapshot.expectedHash("main.ts")).put("start",6).put("length",6).put("newText","total")).put(new JSONObject().put("path","other.ts").put("sha256",snapshot.expectedHash("other.ts")).put("start",0).put("length",6).put("newText","total"));
        var edits=ProjectLanguageEdits.review(snapshot,result(snapshot,"rename",7,rows));assertEquals(2,edits.size());assertEquals("const total=40;\n",edits.get(0).afterText);assertEquals("total;\n",edits.get(1).afterText);assertEquals("const amount=40;\n",snapshot.originalText("main.ts"));
        JSONObject completion=result(snapshot,"complete",7,new JSONArray().put(new JSONObject().put("name","total").put("insertText","total").put("replacementSpan",new JSONObject().put("start",6).put("length",6))));assertEquals("const total=40;\n",ProjectLanguageEdits.applyCompletion(snapshot,completion,0,snapshot.originalText("main.ts")));assertThrows(Exception.class,()->ProjectLanguageEdits.applyCompletion(snapshot,completion,0,"const amount=45;\n"));
        JSONObject overlap=new JSONObject(rows.getJSONObject(0).toString()).put("start",8);assertThrows(Exception.class,()->ProjectLanguageEdits.review(snapshot,result(snapshot,"rename",7,new JSONArray().put(rows.getJSONObject(0)).put(overlap))));
    }
    @Test public void batchDraftAcceptanceChecksEveryOriginalRevisionBeforeChangingAnyBuffer()throws Exception{
        var store=new ProjectFileDraftStore(Files.createTempDirectory("c2-language-drafts-").toFile(),Runnable::run);var a=store.open(PROJECT,CHECKOUT,"a.ts",true);var b=store.open(PROJECT,CHECKOUT,"b.ts",true);var nextA=a.edit("const first=1;");var nextB=b.edit("const second=2;");
        store.retain(b.edit("owner later text"));assertThrows(Exception.class,()->store.retainAllExact(List.of(a,b),List.of(nextA,nextB)));assertSame(a,store.open(PROJECT,CHECKOUT,"a.ts",true));assertEquals("owner later text",store.open(PROJECT,CHECKOUT,"b.ts",true).text);
        var currentB=store.open(PROJECT,CHECKOUT,"b.ts",true);var actualB=currentB.edit("explicit next draft");store.retainAllExact(List.of(a,currentB),List.of(nextA,actualB));assertEquals(nextA.text,store.open(PROJECT,CHECKOUT,"a.ts",true).text);assertEquals(actualB.text,store.open(PROJECT,CHECKOUT,"b.ts",true).text);assertTrue(store.durable(nextA));assertTrue(store.durable(actualB));
    }
}
