package dev.pocketengine.features.workspace;

import android.app.Application;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.NativeOwnerWorkflows;
import dev.pocketengine.features.memory.MemoryArchive;
import dev.pocketengine.features.memory.ProjectMemoryArchive;
import dev.pocketengine.features.memory.ProjectMemoryNamespaces;
import dev.pocketengine.store.TaskStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

/** Original ZIP/canonical project restore/SQLite phase proof -> same Book passive namespace.
 * The test backend supplies the explicit native objects until the product archive host is wired;
 * no JSON/native=true map, second database authority, model or device/visual result is involved. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class ProjectMemoryArchiveNativeTest {
    private static final String WORDS="PROJECT_ARCHIVE_NATIVE_ORIGINAL_916：原始约束与选择理由。",GLOBAL="OWNER_GLOBAL_NOT_IN_PROJECT_PACKAGE_271";
    @Before public void keys(){OwnerDataArchiveFixture.installFixtureVault();}
    @After public void clear(){OwnerDataArchiveFixture.removeFixtureVault();}
    private static JSONObject owner()throws Exception{return new JSONObject().put("ownerId","owner").put("spaceId","private");}
    static final class Fixture implements AutoCloseable {
        final OwnerDataArchiveFixture nativeHost;final ProjectMemoryNamespaces scopes;final OwnerDataArchives archives;
        final boolean mounted;final String project,checkout;final JSONObject formal;ProjectMemoryNamespaces.Selection selected;
        JSONObject export;OwnerDataArchives.Confirmed memoryConfirmation;TaskStore.OwnerArchiveProjectMap memoryMap;OwnerDataArchives.Selection packageSelection;ProjectMemoryArchive.Placement placement;
        Fixture()throws Exception{this(false);}
        Fixture(boolean mounted)throws Exception{
            this.mounted=mounted;Path root=Files.createTempDirectory("project-memory-archive-");nativeHost=new OwnerDataArchiveFixture(RuntimeEnvironment.getApplication(),root,true);scopes=new NativeOwnerWorkflows(nativeHost.host,nativeHost.context,nativeHost.tasks,nativeHost.sources).projectMemoryForOwner();project=nativeHost.workspace.handle("create",new JSONObject().put("name","原项目记忆")).getString("id");nativeHost.workspace.handle("write",new JSONObject().put("projectId",project).put("path","notes.txt").put("expectedVersion",0).put("content",WORDS));checkout=nativeHost.local.handle("prepare",new JSONObject().put("projectId",project)).getString("id");selected=choose(project);JSONObject file=scopes.fileChoicesForOwner(selected).getJSONObject(0);JSONObject candidate=scopes.proposeFileForOwner(selected,file.getString("path"),file.getInt("version"),file.getString("revisionId"),file.getString("sha256"),0,4000).getJSONObject("candidate");formal=scopes.handleForOwner(selected,"acceptCandidate",new JSONObject().put("id",candidate.getString("id")).put("revision",candidate.getLong("revision"))).getJSONObject("record");nativeHost.memory.handle("save",new JSONObject().put("scope",owner()).put("record",new JSONObject().put("text",GLOBAL)));
            OwnerDataArchives.SectionBackend selectedMemory=new OwnerDataArchives.SectionBackend(){
                public JSONObject export(RunControl original)throws Exception{original.check();return export==null?scopes.archiveForOwner(selected):new JSONObject(export.toString());}
                public OwnerDataArchives.SectionReview review(JSONObject section,String action,RunControl original)throws Exception{ProjectMemoryArchive.checked(section);return new OwnerDataArchives.SectionReview(){public JSONObject metadata()throws Exception{return new JSONObject().put("counts",section.getJSONObject("counts")).put("readGrantIssued",false);}public JSONObject restore()throws Exception{placement=scopes.prepareArchivePlacementForOwner(archives,memoryConfirmation,memoryMap,section);return scopes.restoreArchiveForOwner(placement);}};}
                public JSONObject query(JSONObject section,String action,RunControl original)throws Exception{return scopes.queryArchiveRestoreForOwner(section,packageSelection.metadata().getString("archiveSha256"),action);}
            };
            archives=mounted?nativeHost.host.ownerDataArchives(nativeHost.android):new OwnerDataArchives(root.resolve("archives").toFile(),nativeHost.workspace,nativeHost.local,nativeHost.attachments,Map.of("memory",selectedMemory));
        }
        ProjectMemoryNamespaces.Selection choose(String project)throws Exception{JSONArray choices=scopes.choicesForOwner();for(int i=0;i<choices.length();i++){JSONObject row=choices.getJSONObject(i);if(project.equals(row.getString("projectId")))return scopes.selectForOwner(row.getString("checkoutId"),project,row.getString("revision"));}throw new AssertionError("native project has no current checkout");}
        void capture()throws Exception{packageSelection=mounted?archives.createProjectMemory(selected,new RunControl()):archives.create(Set.of("projects","memory"),new RunControl());}
        String restoreProjects()throws Exception{String sha=packageSelection.metadata().getString("archiveSha256");var review=archives.review(packageSelection,"projects",nativeHost.tasks.ownerArchiveProjectMapForOwner("",sha),new RunControl());var confirmed=review.confirm();nativeHost.tasks.beginOwnerArchivePhaseForOwner(confirmed);JSONObject result=confirmed.restore();nativeHost.tasks.recordOwnerArchivePhaseForOwner(confirmed.actionId(),result);return confirmed.actionId();}
        JSONObject restoreMemory(String projectPhase)throws Exception{String sha=packageSelection.metadata().getString("archiveSha256");memoryMap=nativeHost.tasks.ownerArchiveProjectMapForOwner(projectPhase,sha);memoryConfirmation=archives.review(packageSelection,"memory",memoryMap,new RunControl()).confirm();nativeHost.tasks.beginOwnerArchivePhaseForOwner(memoryConfirmation);JSONObject result=memoryConfirmation.restore();nativeHost.tasks.recordOwnerArchivePhaseForOwner(memoryConfirmation.actionId(),result);return result;}
        String target(String phase)throws Exception{return nativeHost.tasks.ownerArchiveProjectMapForOwner(phase,packageSelection.metadata().getString("archiveSha256")).forArchive(packageSelection.metadata().getString("archiveSha256")).getString(project);}
        JSONObject forgetOriginal()throws Exception{selected=choose(project);return scopes.handleForOwner(selected,"forgetReviewed",new JSONObject().put("id",formal.getString("id")).put("expectedRevision",formal.getLong("revision")));}
        public void close()throws Exception{nativeHost.close();}
    }
    @Test public void explicitNativeProjectExportKeepsOriginalSourceIdentityAndNeverSweepsOwnerGlobalOrConsent()throws Exception{try(Fixture f=new Fixture()){
        JSONObject archive=f.scopes.archiveForOwner(f.selected);assertEquals(ProjectMemoryArchive.FORMAT,archive.getString("format"));assertEquals("project:"+f.project,archive.getString("spaceId"));assertTrue(archive.toString().contains(WORDS));assertFalse(archive.toString().contains(GLOBAL));assertFalse(archive.toString().contains("native_owner_explicit_project_selection"));assertFalse(archive.toString().contains("consentId"));assertFalse(archive.getBoolean("activeAuthorityIncluded"));assertEquals(f.formal.getJSONArray("sourceRefs").toString(),archive.getJSONArray("records").getJSONObject(0).getJSONArray("sourceRefs").toString());assertThrows(SecurityException.class,()->MemoryArchive.checked(archive,owner()));f.scopes.clearForOwner();assertThrows(SecurityException.class,()->f.scopes.archiveForOwner(f.selected));
    }}
    @Test public void realSettledProjectPhasePlacesOnlyInactiveNewIdsAndIssuesNoReadSelection()throws Exception{try(Fixture f=new Fixture()){
        f.export=f.scopes.archiveForOwner(f.selected);f.capture();String phase=f.restoreProjects(),target=f.target(phase);assertNotEquals(f.project,target);f.scopes.clearForOwner();JSONObject result=f.restoreMemory(phase);assertTrue(result.toString(),result.getBoolean("found"));assertFalse(f.selected.metadata().getBoolean("selectionCurrent"));assertFalse(f.placement.metadata().getBoolean("readGrantIssued"));assertFalse(f.placement.metadata().getBoolean("consentRestored"));assertEquals(0,result.getJSONObject("nativeResult").getInt("formalMemoryActivated"));ProjectMemoryNamespaces.Selection selected=f.choose(target);JSONObject row=f.scopes.handleForOwner(selected,"list",new JSONObject()).getJSONArray("records").getJSONObject(0);assertTrue(row.getBoolean("archiveOnly"));assertFalse(row.getBoolean("active"));assertFalse(row.getBoolean("core"));assertEquals(0,row.getJSONArray("sourceRefs").length());assertEquals(0,row.getJSONArray("sourceIds").length());assertEquals("project:"+f.project,row.getJSONObject("archiveEvidence").getJSONObject("original").getString("spaceId"));assertEquals(dev.pocketengine.features.memory.SourceRef.fromJson(f.formal.getJSONArray("sourceRefs").getJSONObject(0)),dev.pocketengine.features.memory.SourceRef.fromJson(row.getJSONObject("archiveEvidence").getJSONObject("original").getJSONArray("sourceRefs").getJSONObject(0)));assertEquals(0,f.scopes.handleForOwner(selected,"search",new JSONObject().put("query","PROJECT_ARCHIVE_NATIVE_ORIGINAL_916")).getJSONArray("records").length());assertEquals(1,f.nativeHost.memory.handle("list",new JSONObject()).getJSONArray("records").length());assertThrows(SecurityException.class,()->f.nativeHost.memory.handle("get",new JSONObject().put("scope",selected.metadata()).put("id",row.getString("id"))));
    }}
    @Test public void missingSettledProjectMapCannotBorrowExistingSameUuidOrOwnerPrivate()throws Exception{try(Fixture f=new Fixture()){
        f.export=f.scopes.archiveForOwner(f.selected);f.capture();String sha=f.packageSelection.metadata().getString("archiveSha256");var empty=f.nativeHost.tasks.ownerArchiveProjectMapForOwner("",sha);assertThrows(UnsupportedOperationException.class,()->f.archives.review(f.packageSelection,"memory",empty,new RunControl()));assertEquals(1,f.nativeHost.memory.handle("list",new JSONObject()).getJSONArray("records").length());
    }}
    @Test public void originalNamespaceForgetBeforeRemapKeepsBarrierAndDoesNotEraseOwnerGlobalSameSource()throws Exception{try(Fixture f=new Fixture()){
        f.nativeHost.memory.handle("save",new JSONObject().put("record",new JSONObject().put("text","主人全局同原文件保留").put("sourceRefs",f.formal.getJSONArray("sourceRefs"))));f.export=f.scopes.archiveForOwner(f.selected);f.capture();f.forgetOriginal();String phase=f.restoreProjects();JSONObject result=f.restoreMemory(phase);assertEquals(0,result.getJSONObject("nativeResult").getInt("currentlyReadableRecords"));assertEquals(0,f.scopes.handleForOwner(f.choose(f.target(phase)),"list",new JSONObject()).getJSONArray("records").length());assertTrue(f.nativeHost.memory.handle("list",new JSONObject()).toString().contains("主人全局同原文件保留"));assertEquals(1,f.nativeHost.memory.contextSnapshot(owner(),"主人全局同原文件保留",16,12000).getJSONArray("records").length());
    }}
    @Test public void laterOriginalNamespaceForgetRetiresOnlyItsPassiveRemappedCopiesAndReexportBody()throws Exception{try(Fixture f=new Fixture()){
        f.export=f.scopes.archiveForOwner(f.selected);f.capture();String phase=f.restoreProjects();f.restoreMemory(phase);String target=f.target(phase);assertEquals(f.export.getJSONObject("counts").getInt("restoreRecordSlots"),f.scopes.handleForOwner(f.choose(target),"list",new JSONObject()).getJSONArray("records").length());f.forgetOriginal();var selected=f.choose(target);assertEquals(0,f.scopes.handleForOwner(selected,"list",new JSONObject()).getJSONArray("records").length());JSONObject exported=f.scopes.archiveForOwner(selected);assertFalse(exported.toString().contains(WORDS));assertTrue(exported.getJSONArray("namespaceSourceFences").length()>0);assertEquals(0,f.scopes.queryArchiveRestoreForOwner(f.export,f.packageSelection.metadata().getString("archiveSha256"),f.memoryConfirmation.actionId()).getInt("currentlyReadableRecords"));
    }}
    @Test public void coldSameBookRetainsExactMappedLineageButNeverOldSelectionOrConsent()throws Exception{try(Fixture f=new Fixture()){
        f.export=f.scopes.archiveForOwner(f.selected);f.capture();String phase=f.restoreProjects();f.restoreMemory(phase);String target=f.target(phase);var oldSelection=f.choose(target);
        var cold=new dev.pocketengine.features.memory.MemoryModule(f.nativeHost.context,f.nativeHost.sources.assertionResolver());var spaces=cold.projectNamespacesForOwner(f.nativeHost.local,f.nativeHost.workspace,f.nativeHost.sources);assertThrows(SecurityException.class,()->spaces.handleForOwner(oldSelection,"list",new JSONObject()));JSONArray choices=spaces.choicesForOwner();JSONObject actual=null;for(int i=0;i<choices.length();i++)if(target.equals(choices.getJSONObject(i).getString("projectId")))actual=choices.getJSONObject(i);assertNotNull(actual);var selected=spaces.selectForOwner(actual.getString("checkoutId"),target,actual.getString("revision"));assertTrue(spaces.handleForOwner(selected,"list",new JSONObject()).toString().contains(WORDS));assertEquals(0,spaces.handleForOwner(selected,"search",new JSONObject().put("query","PROJECT_ARCHIVE_NATIVE_ORIGINAL_916")).getJSONArray("records").length());assertEquals(0,spaces.captureTaskConsentForOwner(selected).metadata().getJSONArray("selectedRecords").length());ProjectMemoryArchive.checked(spaces.archiveForOwner(selected));f.forgetOriginal();
        var recold=new dev.pocketengine.features.memory.MemoryModule(f.nativeHost.context,f.nativeHost.sources.assertionResolver());assertFalse(new String(Files.readAllBytes(f.nativeHost.android.getFilesDir().toPath().resolve("memory/records.json")),java.nio.charset.StandardCharsets.UTF_8).contains(WORDS));assertEquals(1,recold.handle("list",new JSONObject()).getJSONArray("records").length());
    }}
    @Test public void jsonNamespaceSwapHashDamageForeignArchiveOrCancelledConfirmationCannotPlaceData()throws Exception{try(Fixture f=new Fixture()){
        f.export=f.scopes.archiveForOwner(f.selected);f.capture();String phase=f.restoreProjects(),sha=f.packageSelection.metadata().getString("archiveSha256");var map=f.nativeHost.tasks.ownerArchiveProjectMapForOwner(phase,sha);RunControl control=new RunControl();var confirmed=f.archives.review(f.packageSelection,"memory",map,control).confirm();JSONObject damaged=new JSONObject(f.export.toString()).put("spaceId","project:"+java.util.UUID.randomUUID());assertThrows(Exception.class,()->f.scopes.prepareArchivePlacementForOwner(f.archives,confirmed,map,damaged));assertThrows(SecurityException.class,()->f.scopes.prepareArchivePlacementForOwner(f.archives,confirmed,map,f.nativeHost.memory.ownerArchive(owner())));control.cancel();assertThrows(InterruptedException.class,()->f.scopes.prepareArchivePlacementForOwner(f.archives,confirmed,map,f.export));
    }}
}
