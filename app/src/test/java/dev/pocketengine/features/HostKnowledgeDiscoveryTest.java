package dev.pocketengine.features;
import android.app.Application;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.memory.*;
import dev.pocketengine.features.skills.*;
import dev.pocketengine.store.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class HostKnowledgeDiscoveryTest {
 @Test public void approvedRelevantExperienceIsDiscoveredWithoutGrantAndDisappearsWhenForgotten()throws Exception{
  Application app=RuntimeEnvironment.getApplication();TaskStore tasks=new TaskStore(app);FeatureContext context=new FeatureContext(app,new SettingsStore(app),new SecretVault(app),e->{});JSONObject scope=new JSONObject().put("ownerId","owner").put("spaceId","private");SourceRef source=new SourceRef("message","fixture-message",1,"owner","private");
  MemoryModule memory=new MemoryModule(context,(identity,kind,id)->new SourceRef.State(source,false));SkillsModule skills=new SkillsModule(context,memory::resolveExperience);
  try(FeatureHost host=new FeatureHost(e->{})){
   host.register(memory);host.register(skills);
   JSONObject candidate=memory.proposeLearning(scope,new JSONObject().put("layer","procedural").put("text","备份前后核对目录").put("reason","真实来源").put("applicability","相同目录结构"),source).getJSONObject("candidate");JSONObject record=memory.acceptCandidate(scope,candidate.getString("id"),candidate.getLong("revision")).getJSONObject("record");
   ExperienceOrigin origin=new ExperienceOrigin(new ExperienceOrigin.Ref(record.getString("id"),record.getLong("revision"),"owner","private"),ExperienceOrigin.DraftAuthor.MODEL);
   JSONObject draft=new JSONObject().put("manifest","---\nname: backup-check\ndescription: 备份目录核对\n---\n仅作参考。先核对目录，再核对实际结果。\n");JSONObject inspection=skills.inspectExperience(scope,origin,draft,new JSONObject());JSONObject skill=skills.importExperience(scope,origin,draft,new JSONObject().put("inspectedDigest",inspection.getString("inspectedDigest"))).getJSONObject("skill");
   JSONObject attempt=tasks.startAttempt(tasks.createCapabilityTask("备份目录核对","").getString("id"),"capabilities",8);String task=attempt.getString("id"),run=attempt.getString("runId");HostToolSupervisor port=new HostToolSupervisor(host,tasks,task,run,attempt.getInt("coordinatorEpoch"),false);tasks.authorizeTools(task,run,new JSONArray().put("skills_list").put("skills_read").put("memory_search").put("memory_recall"));
   assertEquals(0,port.contextSnapshot("备份目录核对").getJSONObject("skillsDiscovery").getJSONArray("skills").length());
   skills.approveExperience(scope,skill.getString("id"),skill.getInt("revision"),skill.getString("digest"));JSONObject snapshot=port.contextSnapshot("备份目录核对");
   assertEquals(1,snapshot.getJSONObject("skillsDiscovery").getJSONArray("skills").length());assertEquals(0,snapshot.getJSONObject("skillsDiscovery").getJSONArray("grantsAdded").length());assertFalse(snapshot.getBoolean("executionAuthorized"));assertFalse(snapshot.getJSONObject("referenceValidation").toString().contains("先核对目录"));assertEquals(1,snapshot.getJSONObject("referenceValidation").getJSONArray("skills").length());
   assertEquals(1,port.execute("skills_list",new JSONObject(),new RunControl()).getJSONArray("skills").length());assertEquals(0,port.contextSnapshot("餐厅预订").getJSONObject("skillsDiscovery").getJSONArray("skills").length());
   assertEquals(1,port.contextSnapshot("餐厅预订").getJSONObject("referenceValidation").getJSONArray("memory").length());
   memory.forgetReviewed(scope,record.getString("id"),record.getLong("revision"),refs->{});snapshot=port.contextSnapshot("备份目录核对");assertEquals(0,snapshot.getJSONObject("skillsDiscovery").getJSONArray("skills").length());assertEquals(0,snapshot.getJSONObject("referenceValidation").getJSONArray("memory").length());assertEquals(0,snapshot.getJSONObject("referenceValidation").getJSONArray("skills").length());
  }
 }
}
