package dev.pocketengine.features.devices;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Parameter-contract checks only. No claim that a fixture lease is execution authority. */
public final class DeviceSourceToolContractsTest {
    @Test public void desktopLeaseTargetAndVerificationHaveClosedActualShapes()throws Exception{
        DeviceSourceToolContracts.validate("desktop-v2","desktop.observe",new JSONObject().put("discovery",true));
        DeviceSourceToolContracts.validate("desktop-v2","desktop.observe",new JSONObject().put("pid",7).put("windowId",9));
        DeviceSourceToolContracts.validate("desktop-v2","desktop.control",new JSONObject().put("action","acquire").put("durationMs",30000));
        JSONObject act=new JSONObject().put("kind","set_text").put("lease",lease()).put("elementToken","s1234abcd:7").put("text","真实文本").put("verification",new JSONObject().put("kind","element_value_equals").put("selector",new JSONObject().put("role","edit")).put("value","真实文本"));
        DeviceSourceToolContracts.validate("desktop-v2","desktop.act",act);denied("desktop-v2","desktop.act",new JSONObject(act.toString()).put("x",7));denied("desktop-v2","desktop.act",new JSONObject(act.toString()).put("deliveryMode","foreground"));
        JSONObject noLease=new JSONObject(act.toString());noLease.remove("lease");denied("desktop-v2","desktop.act",noLease);
        JSONObject badCondition=new JSONObject(act.toString()).put("verification",new JSONObject().put("kind","element_value_equals").put("selector",new JSONObject().put("role","edit")));denied("desktop-v2","desktop.act",badCondition);
    }
    @Test public void nativeLocationAndWorkspaceVersionsDoNotGuessPathOrPermitTraversal()throws Exception{
        DeviceSourceToolContracts.validate("workspace-v1","workspace.location",new JSONObject());denied("workspace-v1","workspace.location",new JSONObject().put("path","Documents"));
        DeviceSourceToolContracts.validate("workspace-v1","workspace.write",new JSONObject().put("path","结果/中文.txt").put("content","保存字节").put("expectedVersion","absent"));
        denied("workspace-v1","workspace.read",new JSONObject().put("path","../private"));denied("workspace-v1","workspace.write",new JSONObject().put("path","a.txt").put("content","x"));
        assertTrue(DeviceSourceToolContracts.guidance("workspace.location").contains("guiDirectorySandbox=false"));assertNull(DeviceSourceToolContracts.effect("desktop-v1","desktop.act"));assertEquals(0,DeviceSourceToolContracts.variants("workspace-v1","invented.shell").length());
    }
    @Test public void textReplacementCanClearButInsertionCannotBecomeEmptyOrForeground()throws Exception{
        JSONObject replace=new JSONObject().put("kind","set_text").put("lease",lease()).put("elementToken","s1234abcd:7").put("text","")
                .put("verification",new JSONObject().put("kind","element_value_equals").put("selector",new JSONObject().put("role","Edit")).put("value",""));
        DeviceSourceToolContracts.validate("desktop-v2","desktop.act",replace);
        denied("desktop-v2","desktop.act",new JSONObject(replace.toString()).put("deliveryMode","foreground"));
        denied("desktop-v2","desktop.act",new JSONObject().put("kind","insert_text").put("lease",lease()).put("text",""));
        denied("desktop-v2","desktop.act",new JSONObject(replace.toString()).put("text","a\u0000b"));
        assertTrue(DeviceSourceToolContracts.guidance("desktop.act").contains("ValuePattern"));
        assertTrue(DeviceSourceToolContracts.guidance("desktop.act").contains("不自动按键清空或降为追加"));
    }
    @Test public void phoneAndCommandNumericAndTextBoundsAreNativeWireSafe()throws Exception{
        DeviceSourceToolContracts.validate("1.0","phone_type",new JSONObject().put("nodeId","native-node").put("text","中文"));
        denied("1.0","phone_type",new JSONObject().put("text","no focus fallback"));denied("1.0","phone_tap",new JSONObject().put("x",1.0).put("y",1));
        DeviceSourceToolContracts.validate("workspace-v1","command.run",new JSONObject().put("argv",new JSONArray().put("allowed-command").put("")));denied("workspace-v1","command.run",new JSONObject().put("argv",new JSONArray()));
        denied("desktop-v2","desktop.act",new JSONObject().put("kind","hotkey").put("lease",lease()).put("keys",new JSONArray().put("Enter")));
    }
    private static JSONObject lease()throws Exception{return new JSONObject().put("id","12345678123456781234567812345678").put("fence",1).put("expiresAt",1234567).put("resource","native-receiver-resource");}
    private static void denied(String version,String tool,JSONObject args)throws Exception{try{DeviceSourceToolContracts.validate(version,tool,args);fail("invalid receiver arguments accepted");}catch(SecurityException|IllegalArgumentException expected){}}
}
