package dev.pocketengine.ui.web;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class PocketWebAccountCommandsTest {
    @Test public void newAccountAlwaysHasARealNonemptyLabel()throws Exception{
        assertEquals("我的 ChatGPT 账号",PocketWebAccountCommands.prepare("accounts.beginLogin",new JSONObject()).payload().getString("label"));
        assertEquals("我的 ChatGPT 账号",PocketWebAccountCommands.prepare("accounts.beginLogin",new JSONObject().put("label","  ")).payload().getString("label"));
        assertEquals("工作账号",PocketWebAccountCommands.prepare("accounts.beginLogin",new JSONObject().put("label","  工作账号  ")).payload().getString("label"));
        assertThrows(IllegalArgumentException.class,()->PocketWebAccountCommands.prepare("accounts.beginLogin",new JSONObject().put("label",JSONObject.NULL)));
        assertThrows(IllegalArgumentException.class,()->PocketWebAccountCommands.prepare("accounts.beginLogin",new JSONObject().put("label","x".repeat(81))));
    }
    @Test public void addCannotTurnIntoEditingAnExistingAccount()throws Exception{
        assertThrows(IllegalArgumentException.class,()->PocketWebAccountCommands.prepare("accounts.beginLogin",new JSONObject().put("label","新账号").put("accountId","old")));
        assertThrows(IllegalArgumentException.class,()->PocketWebAccountCommands.prepare("accounts.beginLogin",new JSONObject().put("label","新账号").put("expectedVersion",4)));
        assertThrows(IllegalArgumentException.class,()->PocketWebAccountCommands.prepare("accounts.beginLogin",new JSONObject().put("label","新账号").put("token","secret")));
    }
    @Test public void reauthorizationKeepsItsOriginalIdentityAndVersion()throws Exception{
        JSONObject input=new JSONObject().put("accountId","original").put("expectedVersion",4);
        var command=PocketWebAccountCommands.prepare("accounts.reauthorize",input);input.put("accountId","other");
        assertEquals("beginLogin",command.command());assertEquals("original",command.payload().getString("accountId"));assertEquals(4,command.payload().getLong("expectedVersion"));
        assertFalse(command.payload().has("label"));
        assertThrows(IllegalArgumentException.class,()->PocketWebAccountCommands.prepare("accounts.select",new JSONObject().put("accountId","original").put("expectedVersion",4.1)));
    }
    @Test public void refreshEnableAndCancellationHaveSeparateExactPayloads()throws Exception{
        assertEquals("snapshot",PocketWebAccountCommands.prepare("accounts.refresh",new JSONObject()).command());
        assertEquals("refresh",PocketWebAccountCommands.prepare("accounts.refreshAuthorization",new JSONObject().put("accountId","a").put("expectedVersion",2)).command());
        assertEquals("session-a",PocketWebAccountCommands.prepare("accounts.cancelLogin",new JSONObject().put("sessionId","session-a")).payload().getString("sessionId"));
        assertThrows(IllegalArgumentException.class,()->PocketWebAccountCommands.prepare("accounts.cancelLogin",new JSONObject().put("accountId","a")));
        assertThrows(IllegalArgumentException.class,()->PocketWebAccountCommands.prepare("accounts.setEnabled",new JSONObject().put("accountId","a").put("expectedVersion",2).put("enabled","true")));
    }
}
