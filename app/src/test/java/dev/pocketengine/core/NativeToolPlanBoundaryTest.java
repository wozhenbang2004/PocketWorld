package dev.pocketengine.core;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Native capture/consume boundaries only; plans never authorize or dispatch an action here. */
public final class NativeToolPlanBoundaryTest {
    private static RunControl original(){RunControl control=new RunControl();control.bindHostRun("task","run",1);return control;}
    private static NativeToolPlan.BrowserIdentity browser(){return new NativeToolPlan.BrowserIdentity("task","run",1,"profile",1,"session","space","tab",1,"document",0);}
    private static NativeToolPlan.Identity phone(){return new NativeToolPlan.Identity("task","run",1,0,"session","host-user","observation");}
    private static NativeToolPlan.Resolved waitStep()throws Exception{return new NativeToolPlan.Resolved(NativeToolPlan.Tool.BROWSER_WAIT,new JSONObject().put("tabId","tab").put("condition","load"),()->{});}

    @Test public void browserParentIsOneUseAndCannotBeConsumedAsPhoneOrAnotherOperation()throws Exception {
        RunControl control=original();NativeToolPlan plan=NativeToolPlan.captureBrowser("operation",control,browser(),1,(index,actual)->waitStep(),()->{});
        assertEquals("browser_task_prepare_sequence",plan.parentTool());
        assertThrows(SecurityException.class,()->plan.consume("phone_prepare_sequence","operation",control));
        assertThrows(SecurityException.class,()->plan.consume("browser_task_prepare_sequence","another-operation",control));
        plan.consume("browser_task_prepare_sequence","operation",control);
        assertThrows(SecurityException.class,()->plan.consume("browser_task_prepare_sequence","operation",control));
        NativeToolPlan.Step step=plan.next(control);assertEquals(NativeToolPlan.Tool.BROWSER_WAIT,step.tool);assertTrue(step.tool.requiresMatched());assertNull(plan.next(control));plan.close();
    }
    @Test public void browserPlanNeverAcceptsAnotherControllerPauseOrClosedGeneration()throws Exception {
        RunControl control=original();NativeToolPlan plan=NativeToolPlan.captureBrowser("operation",control,browser(),1,(index,actual)->waitStep(),()->{});
        assertThrows(SecurityException.class,()->plan.consume("browser_task_prepare_sequence","operation",original()));
        plan.consume("browser_task_prepare_sequence","operation",control);control.pause();control.resume();assertThrows(SecurityException.class,()->plan.next(control));
        NativeToolPlan closed=NativeToolPlan.captureBrowser("other-operation",control,browser(),1,(index,actual)->waitStep(),()->{});closed.close();
        assertThrows(SecurityException.class,()->closed.consume("browser_task_prepare_sequence","other-operation",control));
    }
    @Test public void parentIdentityCannotBeUsedToResolveTheOtherSurface()throws Exception {
        RunControl browserControl=original();NativeToolPlan browser=NativeToolPlan.captureBrowser("operation",browserControl,browser(),1,
                (index,actual)->new NativeToolPlan.Resolved(NativeToolPlan.Tool.CONDITION_WAIT,new JSONObject().put("observationId","phone-observation"),()->{}),()->{});
        browser.consume("browser_task_prepare_sequence","operation",browserControl);assertThrows(SecurityException.class,()->browser.next(browserControl));
        RunControl phoneControl=original();NativeToolPlan phone=NativeToolPlan.capture("phone_prepare_sequence","phone-operation",phoneControl,phone(),1,(index,actual)->waitStep(),()->{});
        phone.consume("phone_prepare_sequence","phone-operation",phoneControl);assertThrows(SecurityException.class,()->phone.next(phoneControl));
    }
    @Test public void phoneCaptureCannotUseBrowserParentAndFreshTreeRequirementsRemainStrict()throws Exception {
        RunControl control=original();assertThrows(IllegalArgumentException.class,()->NativeToolPlan.capture("browser_task_prepare_sequence","operation",control,phone(),1,(index,actual)->waitStep(),()->{}));
        assertThrows(IllegalArgumentException.class,()->new NativeToolPlan.Resolved(NativeToolPlan.Tool.CLICK_NODE,new JSONObject().put("observationId","o").put("nodeId","n"),()->{}));
        assertThrows(IllegalArgumentException.class,()->new NativeToolPlan.Resolved(NativeToolPlan.Tool.BROWSER_CLICK,new JSONObject().put("tabId","tab").put("observationId","o").put("ref","n0"),()->{}));
        assertThrows(IllegalArgumentException.class,()->new NativeToolPlan.Resolved(NativeToolPlan.Tool.BROWSER_WAIT,new JSONObject().put("tabId","tab").put("condition","load").put("approvalId","model-provided"),()->{}));
    }
}
