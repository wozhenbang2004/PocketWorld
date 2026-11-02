package dev.pocketengine.store;

import android.app.Application;
import dev.pocketengine.core.*;
import dev.pocketengine.features.capabilities.*;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

/** Real original preferences, TaskStore grants and prepared operation, without secrets/network. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class AgentPreferenceStoreTest {
    @Test public void disabledHiddenCallAndStaleOrUnknownSettingCannotWrite()throws Exception {
        var app=RuntimeEnvironment.getApplication();app.getSharedPreferences("private_settings",0).edit().clear().commit();app.deleteDatabase("pocket-world.db");SettingsStore settings=new SettingsStore(app);TaskStore tasks=new TaskStore(app);JSONObject task=tasks.startAttempt(tasks.createCapabilityTask("preferences","").getString("id"),"capabilities",8);String id=task.getString("id"),run=task.getString("runId");int epoch=task.getInt("coordinatorEpoch");RunControl original=new RunControl();original.bindHostRun(id,run,epoch);tasks.authorizeTools(id,run,new JSONArray().put(AgentPreferencePort.LIST).put(AgentPreferencePort.SET));
        CapabilityPort empty=new CapabilityPort(){public JSONArray tools(){return new JSONArray();}public boolean supports(String name){return false;}public boolean sideEffecting(String name){return false;}public JSONObject execute(String n,JSONObject a,RunControl c){throw new AssertionError("No unrelated capability");}};var binding=new ModelCapabilityAdapters.Binding(id,run,epoch,original,true,"");AgentPreferencePort port=new AgentPreferencePort(settings,empty,binding,new TaskStoreCapabilityAuthority(tasks,binding,event->{}),original);
        assertFalse(settings.agentPreferences().getBoolean("enabled"));assertFalse(port.supports(AgentPreferencePort.SET));assertEquals(0,port.tools().length());assertThrows(SecurityException.class,()->port.execute(AgentPreferencePort.SET,new JSONObject().put("key","response_detail").put("value","concise").put("expectedRevision",1),original));
        settings.saveAgentPreferences(true,"standard","standard",1);assertTrue(port.supports(AgentPreferencePort.SET));JSONObject args=new JSONObject().put("key","response_detail").put("value","concise").put("expectedRevision",2);port.validate(AgentPreferencePort.SET,args);RunJournal journal=tasks.journal(id,run,epoch,original);String operation=journal.beginOperation(AgentPreferencePort.SET,args);JSONObject actual=port.execute(AgentPreferencePort.SET,new JSONObject(args.toString()).put("_hostOperationId",operation),original);journal.finishOperation(operation,actual);assertEquals("standard",actual.getJSONObject("previous").getString("response_detail"));assertEquals("concise",actual.getJSONObject("current").getString("response_detail"));
        assertThrows(SecurityException.class,()->settings.setAgentPreference("response_detail","detailed",2));assertThrows(IllegalArgumentException.class,()->port.validate(AgentPreferencePort.SET,new JSONObject().put("key","apiKey").put("value","forged").put("expectedRevision",3)));
        settings.saveAgentPreferences(true,"concise","careful",3);long before=System.nanoTime();assertNull(port.beforeTool("phone_click_node",new JSONObject(),original));assertTrue("Ordinary Runner phone actions need the actual careful pause",System.nanoTime()-before>=200_000_000L);
        settings.saveAgentPreferences(false,"standard","standard",4);assertFalse(port.supports(AgentPreferencePort.SET));assertThrows(SecurityException.class,()->settings.setAgentPreference("operation_pace","fast",5));port.close();
    }
}
