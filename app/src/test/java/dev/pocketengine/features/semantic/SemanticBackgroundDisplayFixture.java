package dev.pocketengine.features.semantic;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.os.Bundle;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.displayhelper.DisplayTypedIntentNativeTest;
import dev.pocketengine.phone.display.DisplayModule;
import dev.pocketengine.phone.observation.NativePhoneEntry;
import org.json.JSONArray;
import org.json.JSONObject;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import static org.junit.Assert.*;

/** Only helper IPC/topology are fixtures. Original module binding, Runner, SQL, BG port and typed native helper run. */
@Implements(DisplayModule.class)
public final class SemanticBackgroundDisplayFixture {
    static final int DISPLAY=7;
    static final String SESSION="original-semantic-display";
    static int dispatches;static boolean entered,loseReply,wrongUser;static String targetPackage,targetComponent;
    static void reset(boolean lose,boolean wrong){dispatches=0;entered=false;loseReply=lose;wrongUser=wrong;targetPackage="";targetComponent="";}
    @Implementation protected boolean hasSession(){return true;}
    @Implementation protected boolean isConnected(){return true;}
    @Implementation protected JSONObject session()throws Exception{return new JSONObject().put("sessionId",SESSION).put("displayId",DISPLAY).put("sessionActive",true)
            .put("backgroundOnly",true).put("focusIsolationRequested",true).put("displayFlagsConfirmed",true).put("imeIsolation","available");}
    @Implementation protected JSONObject connect(RunControl control)throws Exception{control.check();return session();}
    @Implementation protected JSONObject request(String command,JSONObject payload,RunControl control,long revision,boolean owner)throws Exception {
        assertEquals("ownership",command);assertFalse(owner);control.check();assertEquals(control.pauseRevision(),revision);
        JSONObject primary=task(1,0,"owner.launcher","owner.launcher/owner.launcher.Main",0),facts=new JSONObject().put("status","observed");
        JSONArray all=new JSONArray().put(primary),main=new JSONArray().put(primary),owned=new JSONArray();
        if(entered){JSONObject target=task(2,DISPLAY,targetPackage,targetComponent,wrongUser?10:0);all.put(target);owned.put(target);}
        facts.put("primary",main).put("owned",owned).put("taskTopology",new JSONObject().put("schemaVersion",1).put("status","observed").put("complete",true).put("readOnly",true).put("tasks",all));
        return new JSONObject().put("ok",true).put("ownership",facts);
    }
    @Implementation protected JSONObject requestAdmitted(String command,JSONObject payload,RunControl control,long revision,String session,int display,NativePhoneEntry.Admission admission)throws Exception {
        assertEquals("intent",command);assertEquals(SESSION,session);assertEquals(DISPLAY,display);assertEquals(control.pauseRevision(),revision);admission.check();control.check();
        targetPackage=payload.getJSONObject("target").getString("packageName");targetComponent=payload.getJSONObject("target").getString("component");dispatches++;
        Context context=RuntimeEnvironment.getApplication();if(loseReply)context=new ContextWrapper(context){@Override public void startActivity(Intent intent,Bundle options){super.startActivity(intent,options);throw new IllegalStateException("fixture lost reply after native entry");}};
        JSONObject receipt=DisplayTypedIntentNativeTest.dispatchFixture(context,display,payload,control);entered=Boolean.TRUE.equals(receipt.opt("accepted"))||receipt.isNull("accepted");return receipt;
    }
    private static JSONObject task(int id,int display,String pkg,String component,int user)throws Exception {
        return new JSONObject().put("taskId",id).put("rootTaskId",id).put("displayId",display).put("androidUserId",user).put("userId",user)
                .put("packageName",pkg).put("component",component).put("rootIdentity","fixture-root-"+id);
    }
}
