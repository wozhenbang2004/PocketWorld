package dev.pocketengine.features.attachments;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.workspace.JsonFiles;
import dev.pocketengine.features.workspace.VersionedWorkspace;
import dev.pocketengine.features.workspace.WorkspaceArtifacts;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Real versioned files and immutable artifact records. The native SQL read-authority port is an explicit fixture, not TaskStore integration acceptance. */
public final class TeamWorkspaceOutcomeHostTest {
    private static String uuid(){return java.util.UUID.randomUUID().toString();}
    private static String hash(String content)throws Exception {StringBuilder value=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)))value.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return value.toString();}
    private static final class Fixture {
        final File root=Files.createTempDirectory("team-workspace-outcome-").toFile(),artifactRoot=new File(root,"artifacts");
        final VersionedWorkspace workspace=new VersionedWorkspace(new File(root,"workspace"));
        final WorkspaceArtifacts artifacts=new WorkspaceArtifacts(artifactRoot,workspace);
        final JSONObject reference,artifact;final String project,target,content;
        final java.util.Map<String,JSONObject> originalReceipts=new java.util.HashMap<>();
        final AtomicBoolean allowed=new AtomicBoolean(true);final TeamWorkspaceOutcomeHost.NativeSource source;
        final TeamWorkspaceOutcomeHost host;final TeamWorkspaceOutcomeHost.Selection selected;
        Fixture(String content)throws Exception {
            this.content=content;target=workspace.create("原项目").getString("id");workspace.write(target,"docs/member.md","original\n",0,false);project=workspace.create("成员隔离草稿").getString("id");workspace.write(project,"docs/member.md","original\n",0,false);JSONObject revision=workspace.write(project,"docs/member.md",content,1,false);String task=uuid(),run=uuid();
            artifact=artifacts.create(project,"docs/member.md",revision.getInt("version"),new JSONObject().put("_taskId",task).put("_runId",run));
            reference=new JSONObject().put("teamRunId",uuid()).put("assignmentId",task).put("memberId","writer").put("taskId",task).put("runId",run).put("artifactId",artifact.getString("id"))
                .put("draftProjectId",project).put("targetProjectId",target).put("path","docs/member.md").put("baseVersion",1).put("baseHash",hash("original\n")).put("draftVersion",revision.getInt("version")).put("draftRevisionId",revision.getString("revisionId")).put("sha256",artifact.getString("sha256"));
            originalReceipts.put(artifact.getString("id"),reference);
            source=new TeamWorkspaceOutcomeHost.NativeSource(){
                public JSONObject resolveTeamArtifact(String team,String assignment,String member,String taskId,String frozenRun,String id,RunControl control)throws Exception {
                    control.checkpoint();JSONObject actual=originalReceipts.get(id);if(!allowed.get()||actual==null||!team.equals(actual.getString("teamRunId"))||!assignment.equals(actual.getString("assignmentId"))||!member.equals(actual.getString("memberId"))||!taskId.equals(actual.getString("taskId"))||!frozenRun.equals(actual.getString("runId")))throw new SecurityException("fixture original receipt unavailable");return new JSONObject(actual.toString());
                }
                public void checkTeamArtifact(JSONObject exact,RunControl control)throws Exception {control.checkpoint();JSONObject actual=originalReceipts.get(exact.optString("artifactId"));if(!allowed.get()||actual==null||!exact.toString().equals(new JSONObject(actual.toString()).toString()))throw new SecurityException("fixture source revoked or changed");}
            };
            host=new TeamWorkspaceOutcomeHost(source,artifacts::read);selected=TeamWorkspaceOutcomeHost.Selection.fromManifest(reference);
        }
        JSONObject stored()throws Exception{return JsonFiles.read(new File(artifactRoot,artifact.getString("id")+".json"));}
        void store(JSONObject changed)throws Exception{JsonFiles.write(new File(artifactRoot,artifact.getString("id")+".json"),changed);}
        JSONObject actualReview()throws Exception {
            JSONObject current=workspace.read(target,"docs/member.md",0),frozen=artifacts.read(artifact.getString("id"));String digest=hash(current.getString("content"));
            JSONObject row=VersionedWorkspace.compare("docs/member.md",current.getString("content"),frozen.getString("content"),"current","frozen").put("artifactId",artifact.getString("id")).put("assignmentId",reference.getString("assignmentId")).put("memberId",reference.getString("memberId")).put("currentVersion",current.getInt("version")).put("currentHash",digest).put("matchesOwnerBase",current.getInt("version")==reference.getInt("baseVersion")&&digest.equals(reference.getString("baseHash")));
            return new JSONObject().put("diffs",new org.json.JSONArray().put(row)).put("canIntegrate",true).put("plan",new JSONObject().put("format","pocketworld.team.integration.v1").put("teamRunId",reference.getString("teamRunId")).put("targetProjectId",target).put("files",new org.json.JSONArray().put(new JSONObject(reference.toString()))).put("privatePlanField","must not escape viewer"));
        }
        JSONObject freezeRevision(int revision,String changed)throws Exception {
            String draft=workspace.create("后继修订草稿").getString("id");workspace.write(draft,"docs/member.md",artifacts.read(artifact.getString("id")).getString("content"),0,false);JSONObject saved=workspace.write(draft,"docs/member.md",changed,1,false);String run=uuid();JSONObject frozen=artifacts.create(draft,"docs/member.md",2,new JSONObject().put("_taskId",reference.getString("taskId")).put("_runId",run));
            JSONObject ref=new JSONObject(reference.toString()).put("assignmentRevision",revision).put("runId",run).put("artifactId",frozen.getString("id")).put("draftProjectId",draft).put("draftVersion",2).put("draftRevisionId",saved.getString("revisionId")).put("sha256",frozen.getString("sha256"));originalReceipts.put(frozen.getString("id"),ref);return ref;
        }
    }
    @Test public void pagesActualFrozenTextAndProvidesExactUtf8BytesWithoutCreatingAnotherAttachment()throws Exception {
        String content=("真实成员产物 🌿\n").repeat(2000);Fixture f=new Fixture(content);RunControl read=new RunControl();assertFalse(read.hasHostRun());JSONObject first=f.host.preview(f.selected,0,12000,read);
        assertEquals(content.substring(0,first.getInt("nextOffset")),first.getString("content"));assertFalse(first.getJSONObject("artifact").has("content"));assertEquals("teams",first.getString("provider"));assertEquals("team_assignment",first.getString("source"));assertTrue(first.getBoolean("readOnly"));assertFalse(first.getBoolean("businessVerified"));assertTrue(first.toString().getBytes(StandardCharsets.UTF_8).length<64*1024);
        StringBuilder restored=new StringBuilder(first.getString("content"));JSONObject page=first;while(!page.isNull("nextOffset")){page=f.host.preview(f.selected,page.getInt("nextOffset"),12000,read);restored.append(page.getString("content"));}assertEquals(content,restored.toString());
        byte[] bytes=f.host.verifiedBytes(f.selected,read);File exported=new File(f.root,"actual-export.md");Files.write(exported.toPath(),bytes);assertArrayEquals(content.getBytes(StandardCharsets.UTF_8),Files.readAllBytes(exported.toPath()));assertEquals(f.reference.getString("sha256"),hash(new String(bytes,StandardCharsets.UTF_8)));assertEquals(1,f.artifacts.list(null).length());assertFalse(new File(f.root,"attachments").exists());
    }
    @Test public void frozenContentRemainsReadableAfterEditableDraftHistoryHasMovedOn()throws Exception {
        Fixture f=new Fixture("frozen member output\n");for(int i=2;i<13;i++)f.workspace.write(f.project,"docs/member.md","later draft "+i,i,false);
        assertThrows(IllegalArgumentException.class,()->f.workspace.read(f.project,"docs/member.md",f.reference.getInt("draftVersion")));assertEquals(f.content,f.host.preview(f.selected,0,12000,new RunControl()).getString("content"));assertEquals("original\n",f.workspace.read(f.target,"docs/member.md",0).getString("content"));
    }
    @Test public void aUuidOrDifferentProviderCannotStandInForTheOriginalFrozenSource()throws Exception {
        Fixture f=new Fixture("actual");JSONObject wrong=f.selected.toJson().put("provider","attachments");assertThrows(SecurityException.class,()->new TeamWorkspaceOutcomeHost.Selection(wrong));
        assertThrows(Exception.class,()->new TeamWorkspaceOutcomeHost.Selection(new JSONObject().put("provider","teams").put("source","team_assignment").put("artifactId",f.artifact.getString("id"))));
        JSONObject other=f.selected.toJson().put("artifactId",uuid());assertThrows(SecurityException.class,()->f.host.preview(new TeamWorkspaceOutcomeHost.Selection(other),0,12000,new RunControl()));
    }
    @Test public void changedNativeVersionAndWrongActualMetadataAreRejected()throws Exception {
        Fixture version=new Fixture("actual");version.reference.put("draftVersion",3);assertThrows(SecurityException.class,()->version.host.preview(version.selected,0,12000,new RunControl()));
        Fixture task=new Fixture("actual");task.store(task.stored().put("taskId",uuid()));assertThrows(SecurityException.class,()->task.host.verifiedBytes(task.selected,new RunControl()));
        Fixture mime=new Fixture("actual");mime.store(mime.stored().put("mimeType","image/png"));assertThrows(SecurityException.class,()->mime.host.preview(mime.selected,0,12000,new RunControl()));
    }
    @Test public void alteredArtifactContentCannotPassByReplacingItsLocalStoredHash()throws Exception {
        Fixture f=new Fixture("original frozen text");String changed="changed frozen text!";f.store(f.stored().put("content",changed).put("sha256",hash(changed)).put("characters",changed.length()));
        assertEquals(changed,f.artifacts.read(f.artifact.getString("id")).getString("content"));assertThrows(SecurityException.class,()->f.host.verifiedBytes(f.selected,new RunControl()));
    }
    @Test public void revocationOrCancellationAfterActualReadDiscardsTheResult()throws Exception {
        Fixture revoked=new Fixture("actual");TeamWorkspaceOutcomeHost host=new TeamWorkspaceOutcomeHost(revoked.source,id->{JSONObject actual=revoked.artifacts.read(id);revoked.allowed.set(false);return actual;});assertThrows(SecurityException.class,()->host.preview(revoked.selected,0,12000,new RunControl()));
        Fixture cancelled=new Fixture("actual");RunControl control=new RunControl();TeamWorkspaceOutcomeHost stopped=new TeamWorkspaceOutcomeHost(cancelled.source,id->{JSONObject actual=cancelled.artifacts.read(id);control.cancel();return actual;});assertThrows(InterruptedException.class,()->stopped.verifiedBytes(cancelled.selected,control));assertEquals(1,cancelled.artifacts.list(null).length());
    }
    @Test public void returnLocatorPreservesOriginalAssignmentAndDoesNotResumeHistoricalRun()throws Exception {
        Fixture f=new Fixture("actual");JSONObject locator=f.host.assignmentLocator(f.selected,new RunControl());assertEquals(4,locator.length());for(String key:new String[]{"teamRunId","memberId","assignmentId","taskId"})assertEquals(f.reference.getString(key),locator.getString(key));assertFalse(locator.has("runId"));
        JSONObject copy=f.selected.toJson();copy.put("path","other.md");assertEquals("docs/member.md",f.selected.toJson().getString("path"));
    }
    @Test public void boundsPreserveWholeUnicodeCharactersAndNeverReturnAnUnchangedCursor()throws Exception {
        Fixture f=new Fixture("a".repeat(11999)+"🌿tail");JSONObject first=f.host.preview(f.selected,0,12000,new RunControl());assertEquals(11999,first.getInt("nextOffset"));assertTrue(f.host.preview(f.selected,11999,10,new RunControl()).getString("content").startsWith("🌿"));assertThrows(IllegalArgumentException.class,()->f.host.preview(f.selected,11999,1,new RunControl()));assertThrows(IllegalArgumentException.class,()->f.host.preview(f.selected,12000,10,new RunControl()));assertThrows(IllegalArgumentException.class,()->f.host.preview(f.selected,0,12001,new RunControl()));
    }
    @Test public void metadataProjectionCannotLeakUnrequestedExtraContent()throws Exception {
        Fixture f=new Fixture("actual");f.reference.put("extraNativeData","UNREQUESTED NATIVE DATA");f.store(f.stored().put("extraStoredData","UNREQUESTED STORE DATA"));JSONObject page=f.host.preview(f.selected,0,3,new RunControl());assertEquals("act",page.getString("content"));assertFalse(page.toString().contains("UNREQUESTED"));
    }
    @Test public void actualProjectDifferenceIsBoundedAndCannotCarryIntegrationPermission()throws Exception {
        Fixture f=new Fixture("new member output\n".repeat(1000));TeamWorkspaceOutcomeHost host=new TeamWorkspaceOutcomeHost(f.source,f.artifacts::read,(team,control)->f.actualReview());JSONObject first=host.difference(f.selected,0,12000,new RunControl());
        assertTrue(first.getString("diff").contains("-original"));assertTrue(first.getBoolean("matchesOwnerBase"));assertTrue(first.getBoolean("readOnly"));assertFalse(first.has("plan"));assertFalse(first.has("canIntegrate"));assertNotNull(first.opt("nextOffset"));assertTrue(first.getString("diff").length()<=12000);
        f.workspace.write(f.target,"docs/member.md","owner changed project\n",1,false);JSONObject changed=host.difference(f.selected,0,12000,new RunControl());assertFalse(changed.getBoolean("matchesOwnerBase"));assertEquals(2,changed.getInt("currentVersion"));assertTrue(changed.getString("diff").contains("-owner changed project"));assertEquals(f.content,f.host.preview(f.selected,0,12000,new RunControl()).getString("content")+f.host.preview(f.selected,12000,12000,new RunControl()).getString("content"));
    }
    @Test public void aDifferentAssignmentsDiffAndRevokedReviewAreNeverPublished()throws Exception {
        Fixture f=new Fixture("new");TeamWorkspaceOutcomeHost wrong=new TeamWorkspaceOutcomeHost(f.source,f.artifacts::read,(team,control)->{JSONObject review=f.actualReview();review.getJSONArray("diffs").getJSONObject(0).put("assignmentId",uuid());return review;});assertThrows(SecurityException.class,()->wrong.difference(f.selected,0,12000,new RunControl()));
        RunControl control=new RunControl();TeamWorkspaceOutcomeHost stopped=new TeamWorkspaceOutcomeHost(f.source,f.artifacts::read,(team,live)->{JSONObject review=f.actualReview();live.cancel();return review;});assertThrows(InterruptedException.class,()->stopped.difference(f.selected,0,12000,control));
    }
    @Test public void revisionedAssignmentsKeepTheirExactRevisionWhileLegacyRefsRemainReadable()throws Exception {
        Fixture f=new Fixture("legacy frozen");assertEquals("legacy frozen",f.host.preview(f.selected,0,12000,new RunControl()).getString("content"));f.reference.put("assignmentRevision",1);TeamWorkspaceOutcomeHost.Selection revision=TeamWorkspaceOutcomeHost.Selection.fromManifest(f.reference);assertEquals(1,f.host.preview(revision,0,12000,new RunControl()).getJSONObject("reference").getInt("assignmentRevision"));assertThrows(SecurityException.class,()->f.host.preview(f.selected,0,12000,new RunControl()));
        f.reference.put("assignmentRevision",2);assertThrows(SecurityException.class,()->f.host.preview(revision,0,12000,new RunControl()));
    }
    @Test public void nativeNewRevisionOpensItsOwnActualBytesAndPreservesTheOldVersionAndAssignment()throws Exception {
        Fixture f=new Fixture("first frozen output");JSONObject next=f.freezeRevision(2,"owner-reviewed revision output");TeamWorkspaceOutcomeHost host=new TeamWorkspaceOutcomeHost(f.source,f.artifacts::read,(team,control)->{JSONObject review=f.actualReview();review.getJSONObject("plan").put("files",new org.json.JSONArray().put(next));return review;});
        JSONObject result=host.newerArtifact(f.selected,new RunControl());assertFalse(result.has("plan"));assertFalse(result.has("canIntegrate"));TeamWorkspaceOutcomeHost.Selection selected=new TeamWorkspaceOutcomeHost.Selection(result.getJSONObject("newerArtifact"));assertEquals(2,selected.toJson().getInt("assignmentRevision"));assertEquals("owner-reviewed revision output",host.preview(selected,0,12000,new RunControl()).getString("content"));assertArrayEquals("owner-reviewed revision output".getBytes(StandardCharsets.UTF_8),host.verifiedBytes(selected,new RunControl()));
        assertEquals("first frozen output",host.preview(f.selected,0,12000,new RunControl()).getString("content"));assertEquals(host.assignmentLocator(f.selected,new RunControl()).toString(),host.assignmentLocator(selected,new RunControl()).toString());assertEquals(2,f.artifacts.list(null).length());assertEquals("original\n",f.workspace.read(f.target,"docs/member.md",0).getString("content"));
    }
    @Test public void noNewFrozenRevisionDoesNotSelectAnotherAssignmentsArtifact()throws Exception {
        Fixture f=new Fixture("first");TeamWorkspaceOutcomeHost unchanged=new TeamWorkspaceOutcomeHost(f.source,f.artifacts::read,(team,control)->f.actualReview());assertTrue(unchanged.newerArtifact(f.selected,new RunControl()).isNull("newerArtifact"));
        JSONObject other=f.freezeRevision(2,"other assignment output");other.put("assignmentId",uuid()).put("taskId",other.getString("assignmentId"));TeamWorkspaceOutcomeHost foreign=new TeamWorkspaceOutcomeHost(f.source,f.artifacts::read,(team,control)->{JSONObject review=f.actualReview();review.getJSONObject("plan").put("files",new org.json.JSONArray().put(other));return review;});assertTrue(foreign.newerArtifact(f.selected,new RunControl()).isNull("newerArtifact"));
    }
    @Test public void unknownOrConflictingNewerReferencesCannotReplaceTheReviewedSource()throws Exception {
        Fixture f=new Fixture("first");JSONObject unknown=f.freezeRevision(2,"new");f.originalReceipts.remove(unknown.getString("artifactId"));TeamWorkspaceOutcomeHost unavailable=new TeamWorkspaceOutcomeHost(f.source,f.artifacts::read,(team,control)->{JSONObject review=f.actualReview();review.getJSONObject("plan").put("files",new org.json.JSONArray().put(unknown));return review;});assertThrows(SecurityException.class,()->unavailable.newerArtifact(f.selected,new RunControl()));
        JSONObject one=f.freezeRevision(2,"one"),two=f.freezeRevision(2,"two");TeamWorkspaceOutcomeHost conflict=new TeamWorkspaceOutcomeHost(f.source,f.artifacts::read,(team,control)->{JSONObject review=f.actualReview();review.getJSONObject("plan").put("files",new org.json.JSONArray().put(one).put(two));return review;});assertThrows(SecurityException.class,()->conflict.newerArtifact(f.selected,new RunControl()));
    }
    @Test public void cancelledNewRevisionLookupCannotPublishAReplacement()throws Exception {
        Fixture f=new Fixture("first");JSONObject next=f.freezeRevision(2,"new");RunControl control=new RunControl();TeamWorkspaceOutcomeHost stopped=new TeamWorkspaceOutcomeHost(f.source,f.artifacts::read,(team,live)->{JSONObject review=f.actualReview();review.getJSONObject("plan").put("files",new org.json.JSONArray().put(next));live.cancel();return review;});assertThrows(InterruptedException.class,()->stopped.newerArtifact(f.selected,control));assertEquals("first",f.host.preview(f.selected,0,12000,new RunControl()).getString("content"));
    }
}
