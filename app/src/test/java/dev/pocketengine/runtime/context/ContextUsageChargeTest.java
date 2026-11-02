package dev.pocketengine.runtime.context;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class ContextUsageChargeTest {
    @Test public void nativeUsageCanAvoidPrematureSummaryWhileHardBytesAndOwnerTextRemain()throws Exception {
        JSONArray messages=new JSONArray().put(new JSONObject().put("role","system").put("content","native")).put(new JSONObject().put("role","user").put("content","中".repeat(3000)));ContextWindow.Budget budget=new ContextWindow.Budget(8192,256,256,100000,20,0);
        assertFalse(ContextWindow.plan(messages,null,budget,ContextWindow.Retention.preserveAll()).canSend());ContextWindow.UsageCharge charge=new ContextWindow.UsageCharge(1200,1000,"native-call",2);ContextWindow.Plan plan=ContextWindow.plan(messages,null,budget,ContextWindow.Retention.preserveAll(),charge);assertTrue(plan.canSend());assertEquals(messages.toString(),plan.messages().toString());assertTrue(plan.diagnostics().getBoolean("providerUsageUsed"));assertEquals(1000,plan.diagnostics().getLong("providerReportedInputTokens"));assertFalse(ContextWindow.plan(messages,null,budget.withByteCeiling(1000),ContextWindow.Retention.preserveAll(),charge).canSend());
    }
    @Test public void unknownUsageAndInvalidChargesRetainConservativeAccounting()throws Exception {
        JSONArray messages=new JSONArray().put(new JSONObject().put("role","user").put("content","owner"));ContextWindow.Plan plan=ContextWindow.plan(messages,null,new ContextWindow.Budget(8192,256,256,10000,20,0),ContextWindow.Retention.preserveAll());assertFalse(plan.diagnostics().getBoolean("providerUsageUsed"));assertTrue(plan.diagnostics().isNull("providerReportedInputTokens"));assertThrows(IllegalArgumentException.class,()->new ContextWindow.UsageCharge(-1,100,"bad",1));
    }
}
