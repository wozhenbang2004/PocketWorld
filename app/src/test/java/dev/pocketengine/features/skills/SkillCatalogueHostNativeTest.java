package dev.pocketengine.features.skills;

import android.app.Application;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.features.FeatureHost;
import dev.pocketengine.features.HostToolSupervisor;
import dev.pocketengine.features.NativePluginHost;
import dev.pocketengine.features.controlplane.NativeWorkProfileHost;
import dev.pocketengine.features.controlplane.NativeWorkProfileSources;
import dev.pocketengine.features.controlplane.WorkProfile;
import dev.pocketengine.features.controlplane.WorkProfileToolSelection;
import dev.pocketengine.features.plugins.PluginsModule;
import dev.pocketengine.store.SecretVault;
import dev.pocketengine.store.SettingsStore;
import dev.pocketengine.store.TaskStore;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

/** Actual original Supervisor/Book/SQL and typed native profile selection. No provider or device claims. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class SkillCatalogueHostNativeTest {
    private static final class Rig implements AutoCloseable {
        final Application android=RuntimeEnvironment.getApplication();
        final TaskStore tasks=new TaskStore(android);
        final SettingsStore settings=new SettingsStore(android);
        final FeatureContext context=new FeatureContext(android,settings,new SecretVault(android),event->{});
        final SkillsModule skills=new SkillsModule(context);
        final PluginsModule plugins=new PluginsModule(context);
        final FeatureHost host=new FeatureHost(event->{},tasks,()->false);
        final NativePluginHost nativePlugins;
        final RunControl control=new RunControl();
        final List<JSONObject> rows=new ArrayList<>();
        final String longManifest="---\nname: guide-12\ndescription: 独立示例资料12\n---\n"+"PRIVATE_BODY_DO_NOT_INJECT ".repeat(700)+"FULL_BODY_END";
        final JSONObject task;
        final HostToolSupervisor port;
        Rig(boolean profile)throws Exception {
            host.register(skills);host.register(plugins);
            nativePlugins=new NativePluginHost(android,plugins,tasks,(binding,original)->tasks.checkExtensionRun(binding.taskId,binding.runId,binding.epoch,original),id->{});
            for(int i=0;i<13;i++){
                String manifest=i==12?longManifest:"---\nname: "+String.format(java.util.Locale.ROOT,"guide-%02d",i)+"\ndescription: 独立示例资料"+i+"\n---\nPRIVATE_BODY_DO_NOT_INJECT "+i;
                JSONObject row=skills.handle("import",new JSONObject().put("source","native:catalogue-test").put("manifest",manifest)).getJSONObject("skill");
                skills.handle("setEnabled",new JSONObject().put("id",row.getString("id")).put("enabled",true).put("confirmed",true).put("acceptedDigest",row.getString("digest")));rows.add(row);
            }
            if(profile){
                JSONObject chosen=rows.get(12),definition=WorkProfile.definition("native-catalogue",1,"Exact selected metadata");
                definition.getJSONArray("skillRefs").put(new JSONObject().put("id",chosen.getString("id")).put("revision",chosen.getInt("revision")).put("digest",chosen.getString("digest")).put("optional",false));
                NativeWorkProfileHost nativeHost=new NativeWorkProfileHost(settings,new NativeWorkProfileSources(settings,host,nativePlugins,Set.of(),null));
                NativeWorkProfileHost.Selection selection;
                try(var page=nativeHost.newOwnerPage()){
                    page.setForeground(true);JSONObject saved=page.save("",0,"Exact selected metadata",definition);var review=page.review(saved.getString("profileId"),1,new RunControl());assertTrue(review.view().getBoolean("ready"));selection=page.select(review,review.digest,control);
                }
                task=tasks.startAttemptWithWorkProfile(tasks.createCapabilityTask("calculator arithmetic only","").getString("id"),"capabilities",30,selection,control);
                String digest=selection.snapshot().getString("resolutionDigest");var policy=new WorkProfileToolSelection(selection,id(),run(),epoch(),control,()->tasks.checkWorkProfileRun(id(),run(),epoch(),digest,control),()->tasks.checkWorkProfileCapabilityBinding(id(),run(),epoch(),digest,control),(tool,op,args,live)->tasks.checkWorkProfilePreparedTool(id(),run(),epoch(),digest,tool,op,args,live));
                port=new HostToolSupervisor(host,tasks,id(),run(),epoch(),false,null,policy);
            }else{
                task=tasks.startAttempt(tasks.createCapabilityTask("calculator arithmetic only","").getString("id"),"capabilities",30);
                control.bindHostRun(id(),run(),epoch());port=new HostToolSupervisor(host,tasks,id(),run(),epoch(),false);
            }
            tasks.authorizeTools(id(),run(),new JSONArray().put("skills_list").put("skills_read"));
        }
        String id()throws Exception{return task.getString("id");}String run()throws Exception{return task.getString("runId");}int epoch()throws Exception{return task.getInt("coordinatorEpoch");}
        JSONObject call(String name,JSONObject args)throws Exception {
            port.validate(name,args);var journal=tasks.journal(id(),run(),epoch(),control);String op=journal.beginOperation(name,args);
            try{JSONObject result=port.execute(name,new JSONObject(args.toString()).put("_hostOperationId",op),control);journal.finishOperation(op,result);return result;}
            catch(Exception failure){journal.finishOperation(op,new JSONObject().put("ok",false).put("accepted",false).put("error","fixture: original operation failed"));throw failure;}
        }
        JSONObject catalogue(JSONObject cursor,int size)throws Exception{JSONObject args=new JSONObject().put("mode","catalogue").put("pageSize",size);if(cursor!=null)args.put("cursor",cursor);return call("skills_list",args);}
        void disable(JSONObject row)throws Exception{skills.handle("setEnabled",new JSONObject().put("id",row.getString("id")).put("enabled",false));}
        public void close()throws Exception {port.close();nativePlugins.close();host.close();var f=TaskStore.class.getDeclaredField("db");f.setAccessible(true);((SQLiteDatabase)f.get(tasks)).close();}
    }
    @Test public void originalListFindsEveryUnmatchedVersionAndBodyRemainsExplicitlyPaged()throws Exception {
        try(Rig r=new Rig(false)){
            assertEquals(0,r.call("skills_list",new JSONObject()).getJSONArray("skills").length());
            JSONObject cursor=null;Set<String> ids=new HashSet<>();int pages=0;
            do{JSONObject page=r.catalogue(cursor,4);assertEquals(13,page.getInt("totalCount"));assertFalse(page.toString().contains("PRIVATE_BODY"));assertFalse(page.getBoolean("executable"));assertEquals(0,page.getJSONArray("grantsAdded").length());for(int i=0;i<page.getJSONArray("skills").length();i++)assertTrue(ids.add(page.getJSONArray("skills").getJSONObject(i).getString("id")));cursor=page.optJSONObject("nextCursor");pages++;}while(cursor!=null);
            assertEquals(13,ids.size());assertEquals(4,pages);
            JSONObject row=r.rows.get(12);int offset=0,reads=0;StringBuilder body=new StringBuilder();boolean more;
            do{JSONObject part=r.call("skills_read",new JSONObject().put("id",row.getString("id")).put("revision",row.getInt("revision")).put("digest",row.getString("digest")).put("offset",offset).put("length",8000));assertEquals(row.getInt("revision"),part.getInt("revision"));body.append(part.getString("content"));assertTrue(part.getInt("nextOffset")>offset);offset=part.getInt("nextOffset");more=part.getBoolean("hasMore");reads++;}while(more);
            assertTrue(reads>=3);assertEquals(r.longManifest,body.toString());assertTrue(body.toString().endsWith("FULL_BODY_END"));assertEquals(1,r.tasks.list().length());assertTrue(r.tasks.get(r.id(),false).optJSONArray("approvals")==null||r.tasks.get(r.id(),false).getJSONArray("approvals").length()==0);
        }
    }
    @Test public void changedCatalogueAndExactBodyRevisionRejectOldContinuationWithoutRefreshingIt()throws Exception {
        try(Rig r=new Rig(false)){
            JSONObject cursor=r.catalogue(null,4).getJSONObject("nextCursor");r.disable(r.rows.get(0));
            assertThrows(SecurityException.class,()->r.catalogue(cursor,4));assertEquals(12,r.catalogue(null,4).getInt("totalCount"));
            JSONObject row=r.rows.get(12);JSONObject wrong=new JSONObject().put("id",row.getString("id")).put("revision",row.getInt("revision")+1).put("digest",row.getString("digest"));
            assertThrows(SecurityException.class,()->r.call("skills_read",wrong));
            assertTrue(r.tasks.get(r.id(),false).optJSONArray("approvals")==null||r.tasks.get(r.id(),false).getJSONArray("approvals").length()==0);
        }
    }
    @Test public void nativeProfileSelectionNarrowsBeforePaginationAndCannotExposeOtherNamesOrCounts()throws Exception {
        try(Rig r=new Rig(true)){
            JSONObject page=r.catalogue(null,1);assertEquals(1,page.getInt("totalCount"));assertFalse(page.getBoolean("hasMore"));assertTrue(page.getBoolean("workProfileSelection"));assertEquals(r.rows.get(12).getString("id"),page.getJSONArray("skills").getJSONObject(0).getString("id"));assertFalse(page.toString().contains("guide-00"));
            JSONObject hidden=r.rows.get(0);assertThrows(SecurityException.class,()->r.port.validate("skills_read",new JSONObject().put("id",hidden.getString("id")).put("digest",hidden.getString("digest"))));
        }
    }
    @Test public void noGrantIdentityInjectionAndMalformedModesStillFailBeforeBookRead()throws Exception {
        try(Rig r=new Rig(false)){
            assertThrows(IllegalArgumentException.class,()->r.port.validate("skills_list",new JSONObject().put("mode","all_private_bodies")));
            assertThrows(SecurityException.class,()->r.port.validate("skills_list",new JSONObject().put("mode","catalogue").put("scope",new JSONObject())));
            assertThrows(IllegalArgumentException.class,()->r.port.validate("skills_list",new JSONObject().put("mode","catalogue").put("cursor",new JSONObject().put("catalogueDigest","a").put("offset",0).put("extra",true))));
            assertThrows(IllegalArgumentException.class,()->r.call("skills_list",new JSONObject().put("pageSize",4)));
            RunControl cancelled=new RunControl();cancelled.cancel();assertThrows(InterruptedException.class,()->r.port.execute("skills_list",new JSONObject().put("mode","catalogue"),cancelled));
            r.tasks.authorizeTools(r.id(),r.run(),new JSONArray());assertThrows(SecurityException.class,()->r.port.execute("skills_list",new JSONObject().put("mode","catalogue"),r.control));
        }
    }
}
