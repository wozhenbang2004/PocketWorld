package dev.pocketengine.browser;

import android.app.Application;
import dev.pocketengine.core.CapabilityPort;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.memory.ToolOutputSourcePort;
import dev.pocketengine.runtime.extensions.ExtensionHost;
import dev.pocketengine.runtime.extensions.ExtensionRuntime;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

/** Actual native browser/read/afterTool registration and production outer decorators. Metadata
 * stands in for the physical WebView document witness; this performs no new DOM or model call. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class BrowserStepReadSourceCurrentTest {
    @Test public void asyncNavigationAndLateOldCallbackInvalidateTheExactSourceThroughRealWrappers()throws Exception{
        try(BrowserSemanticReadSqlTest.F browser=new BrowserSemanticReadSqlTest.F(true,null);ExtensionHost extensions=ExtensionHost.disabled(browser.f.tasks)){
            JSONObject arguments=browser.args("table"),receipt=browser.invoke("observe",arguments);
            // A host without a current document witness must remain unknown, even after success.
            assertFalse(browser.port.stepReadSourceCurrent("browser_task_observe",receipt,browser.f.control));
            browser.ui.call(()->{browser.driver.original.pages.get(browser.tab).put("documentId",receipt.getString("documentId"));return null;});
            CapabilityPort extended=extensions.bind(browser.port,new ExtensionRuntime.Binding(browser.f.binding.taskId,browser.f.binding.runId,browser.f.binding.epoch),browser.f.control);
            CapabilityPort outer=new ToolOutputSourcePort(extended,browser.f.tasks.toolOutputFactory(browser.f.binding.taskId,browser.f.binding.runId,browser.f.binding.epoch,browser.f.control,text->text),browser.f.control);
            outer.prepareTurn("检查原来源",1,0,browser.f.control);
            int reads=browser.driver.reads,operations=browser.f.tasks.get(browser.f.binding.taskId,false).getJSONArray("operations").length();
            assertTrue(outer.stepReadSourceCurrent("browser_task_observe",receipt,browser.f.control));
            browser.ui.call(()->{browser.driver.original.pages.get(browser.tab).put("navigation",receipt.getLong("navigation")+1);return null;});
            assertFalse(outer.stepReadSourceCurrent("browser_task_observe",receipt,browser.f.control));
            // A late afterTool callback cannot restore the old document/selection after navigation.
            browser.port.afterTool("browser_task_observe",arguments,receipt,browser.f.control);
            assertFalse(outer.stepReadSourceCurrent("browser_task_observe",receipt,browser.f.control));
            browser.ui.call(()->{browser.driver.original.pages.get(browser.tab).put("navigation",receipt.getLong("navigation")).put("documentId",java.util.UUID.randomUUID().toString());return null;});
            assertFalse(outer.stepReadSourceCurrent("browser_task_observe",receipt,browser.f.control));
            assertEquals(reads,browser.driver.reads);assertEquals(operations,browser.f.tasks.get(browser.f.binding.taskId,false).getJSONArray("operations").length());assertEquals(0,browser.driver.original.mutableDispatches);
        }
    }

    @Test public void latestSelectionRevisionAndOriginalControlAreRequiredWithoutNewReads()throws Exception{
        try(BrowserSemanticReadSqlTest.F browser=new BrowserSemanticReadSqlTest.F(true,null)){
            browser.driver.deltaFixture=true;browser.driver.contents.put(browser.tab,"first row");
            browser.ui.call(()->{browser.driver.original.pages.get(browser.tab).put("documentId",browser.driver.visualDocument);return null;});
            JSONObject first=browser.invoke("observe",browser.args("text"));assertTrue(browser.port.stepReadSourceCurrent("browser_task_observe",first,browser.f.control));
            browser.driver.contents.put(browser.tab,"second row");JSONObject second=browser.invoke("observe",browser.args("text"));
            assertEquals(first.getString("documentId"),second.getString("documentId"));assertNotEquals(first.getJSONObject("read").getString("projectionSha256"),second.getJSONObject("read").getString("projectionSha256"));
            assertFalse(browser.port.stepReadSourceCurrent("browser_task_observe",first,browser.f.control));assertTrue(browser.port.stepReadSourceCurrent("browser_task_observe",second,browser.f.control));
            JSONObject changed=new JSONObject(second.toString());changed.getJSONObject("read").put("projectionSha256","a".repeat(64));assertFalse(browser.port.stepReadSourceCurrent("browser_task_observe",changed,browser.f.control));
            RunControl substitute=new RunControl();substitute.bindHostRun(browser.f.binding.taskId,browser.f.binding.runId,browser.f.binding.epoch);assertFalse(browser.port.stepReadSourceCurrent("browser_task_observe",second,substitute));
            assertFalse(browser.port.stepReadSourceCurrent("browser_task_inspect",second,browser.f.control));assertFalse(browser.port.stepReadSourceCurrent("browser_task_wait",new JSONObject(),browser.f.control));
            browser.ui.call(()->{browser.space.navigationChangedNative(browser.tab);return null;});assertFalse(browser.port.stepReadSourceCurrent("browser_task_observe",second,browser.f.control));
            assertEquals(2,browser.driver.reads);assertFalse(browser.f.control.isCancelled());assertEquals(0,browser.driver.original.mutableDispatches);
        }
    }
}
