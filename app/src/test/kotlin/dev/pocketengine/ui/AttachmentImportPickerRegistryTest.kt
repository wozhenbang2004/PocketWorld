package dev.pocketengine.ui

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.app.ActivityOptionsCompat
import dev.pocketengine.features.attachments.OwnerAttachmentImport.Target
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real AndroidX registry; controlled OS launch. No mounted Activity, process death or device claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],manifest=Config.NONE,application=Application::class)
class AttachmentImportPickerRegistryTest {
    private class Registry:ActivityResultRegistry() {
        val launches=ArrayList<Pair<Int,Intent>>()
        var failure=""
        var synchronous=false
        override fun <I,O> onLaunch(requestCode:Int,contract:ActivityResultContract<I,O>,input:I,options:ActivityOptionsCompat?) {
            launches.add(requestCode to (input as Intent))
            if(synchronous)dispatchResult(requestCode,Activity.RESULT_OK,chosen())
            when(failure){"missing"->throw android.content.ActivityNotFoundException("No picker");"unknown"->throw IllegalStateException("Dispatch is uncertain")}
        }
    }
    companion object {private fun chosen()=Intent().setData(Uri.parse("content://import-registry-fixture/chosen"))}
    @Test fun reentryCannotReplaceTheOriginalTaskOrProject() {
        val registry=Registry();val results=ArrayList<Pair<Intent,Target>>()
        val picker=AttachmentImportPicker(registry){data,original->results.add(data to original)}
        assertEquals(AttachmentImportPicker.Launch.REQUESTED,picker.launch("original-task","original-project"))
        assertEquals(AttachmentImportPicker.Launch.BUSY,picker.launch("later-task","later-project"));assertEquals(1,registry.launches.size)
        val (code,intent)=registry.launches.single();assertEquals(Intent.ACTION_OPEN_DOCUMENT,intent.action);assertEquals("*/*",intent.type);assertTrue(intent.categories.contains(Intent.CATEGORY_OPENABLE));assertTrue(intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE,false))
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,intent.flags)
        registry.dispatchResult(code,Activity.RESULT_OK,chosen());assertEquals(1,results.size);assertEquals("original-task",results.single().second.taskId);assertEquals("original-project",results.single().second.projectId)
        registry.dispatchResult(code,Activity.RESULT_OK,chosen());assertEquals(1,results.size)
    }
    @Test fun explicitGlobalSelectionStillHasKnownOriginalContext() {
        val registry=Registry();val results=ArrayList<Target>();val picker=AttachmentImportPicker(registry){_,original->results.add(original)}
        picker.launch(null,null);registry.dispatchResult(registry.launches.single().first,Activity.RESULT_OK,chosen());assertEquals(1,results.size);assertNull(results.single().taskId);assertNull(results.single().projectId)
    }
    @Test fun restorationOnlyRegistersTheSameNonceAndNeverLaunchesOrImportsByItself() {
        val first=Registry();val before=AttachmentImportPicker(first){_,_->fail("No result yet")};before.launch("original-task","original-project");val code=first.launches.single().first;val saved=Bundle();first.onSaveInstanceState(saved);before.save(saved);before.close()
        val restored=Registry();restored.onRestoreInstanceState(saved);val results=ArrayList<Target>();val after=AttachmentImportPicker(restored){_,original->results.add(original)}
        assertTrue(after.restore(saved));assertTrue(restored.launches.isEmpty());assertTrue(results.isEmpty());assertEquals(AttachmentImportPicker.Launch.BUSY,after.launch("later-task",null))
        restored.dispatchResult(code,Activity.RESULT_OK,chosen());assertEquals("original-task",results.single().taskId);assertEquals("original-project",results.single().projectId)
    }
    @Test fun pendingResultDuringRegistrationIsConsumedOnceWithoutAnotherLaunch() {
        val first=Registry();val before=AttachmentImportPicker(first){_,_->};before.launch("original-task",null);val code=first.launches.single().first;val saved=Bundle();first.onSaveInstanceState(saved);before.save(saved);before.close()
        val restored=Registry();restored.onRestoreInstanceState(saved);restored.dispatchResult(code,Activity.RESULT_OK,chosen());val results=ArrayList<Target>();val after=AttachmentImportPicker(restored){_,original->results.add(original)}
        assertTrue(after.restore(saved));assertEquals(1,results.size);after.resume();assertEquals(1,results.size);assertTrue(restored.launches.isEmpty());val cleared=Bundle();after.save(cleared);assertFalse(cleared.containsKey("ownerAttachmentImport"))
    }
    @Test fun aCancelledNonceCannotConsumeALaterSelection() {
        val registry=Registry();val results=ArrayList<Target>();val picker=AttachmentImportPicker(registry){_,original->results.add(original)}
        picker.launch("original-task",null);val old=registry.launches.single().first;registry.dispatchResult(old,Activity.RESULT_CANCELED,null);assertTrue(results.isEmpty())
        assertEquals(AttachmentImportPicker.Launch.REQUESTED,picker.launch("later-task",null));val current=registry.launches.last().first;assertNotEquals(old,current)
        registry.dispatchResult(old,Activity.RESULT_OK,chosen());assertTrue(results.isEmpty());registry.dispatchResult(current,Activity.RESULT_OK,chosen());assertEquals("later-task",results.single().taskId)
    }
    @Test fun definiteMissingHandlerReleasesOnlyTheUnstartedSelection() {
        val registry=Registry();registry.failure="missing";val results=ArrayList<Target>();val picker=AttachmentImportPicker(registry){_,original->results.add(original)}
        assertEquals(AttachmentImportPicker.Launch.NOT_STARTED,picker.launch("original-task",null));val old=registry.launches.single().first;registry.failure=""
        assertEquals(AttachmentImportPicker.Launch.REQUESTED,picker.launch("later-task",null));registry.dispatchResult(old,Activity.RESULT_OK,chosen());assertTrue(results.isEmpty())
        registry.dispatchResult(registry.launches.last().first,Activity.RESULT_OK,chosen());assertEquals("later-task",results.single().taskId)
    }
    @Test fun unknownLaunchRetainsTheOriginalPinAndResumeOnlyRetrievesItsResult() {
        val registry=Registry();registry.failure="unknown";val results=ArrayList<Target>();val picker=AttachmentImportPicker(registry){_,original->results.add(original)}
        assertEquals(AttachmentImportPicker.Launch.UNKNOWN,picker.launch("original-task","original-project"));assertEquals(AttachmentImportPicker.Launch.BUSY,picker.launch("later-task",null));assertEquals(1,registry.launches.size)
        registry.dispatchResult(registry.launches.single().first,Activity.RESULT_OK,chosen());assertTrue(results.isEmpty());picker.resume();assertEquals("original-task",results.single().taskId);assertEquals(1,registry.launches.size)
    }
    @Test fun unknownLaunchCanRestoreWithoutASecondExternalSelection() {
        val first=Registry();first.failure="unknown";val before=AttachmentImportPicker(first){_,_->};before.launch("original-task",null);first.dispatchResult(first.launches.single().first,Activity.RESULT_OK,chosen());val saved=Bundle();first.onSaveInstanceState(saved);before.save(saved);before.close()
        val restored=Registry();restored.onRestoreInstanceState(saved);val results=ArrayList<Target>();val after=AttachmentImportPicker(restored){_,original->results.add(original)}
        assertTrue(after.restore(saved));assertEquals("original-task",results.single().taskId);assertTrue(restored.launches.isEmpty())
    }
    @Test fun absentOrDamagedSavedContextNeverGrantsALateResultToTheNewTask() {
        val first=Registry();val before=AttachmentImportPicker(first){_,_->};before.launch("original-task",null);val old=first.launches.single().first;val saved=Bundle();first.onSaveInstanceState(saved);before.save(saved);before.close();saved.getBundle("ownerAttachmentImport")!!.remove("taskId")
        val restored=Registry();restored.onRestoreInstanceState(saved);val results=ArrayList<Target>();val after=AttachmentImportPicker(restored){_,original->results.add(original)}
        assertFalse(after.restore(saved));assertEquals(AttachmentImportPicker.Launch.REQUESTED,after.launch("later-task",null));restored.dispatchResult(old,Activity.RESULT_OK,chosen());assertTrue(results.isEmpty())
        restored.dispatchResult(restored.launches.single().first,Activity.RESULT_OK,chosen());assertEquals("later-task",results.single().taskId)
    }
    @Test fun closingAnOldRegistrationCannotDeliverItsResultThroughANewNonce() {
        val registry=Registry();val results=ArrayList<Target>();val first=AttachmentImportPicker(registry){_,original->results.add(original)};first.launch("original-task",null);val old=registry.launches.single().first;first.close()
        val second=AttachmentImportPicker(registry){_,original->results.add(original)};second.launch("later-task",null);registry.dispatchResult(old,Activity.RESULT_OK,chosen());assertTrue(results.isEmpty());registry.dispatchResult(registry.launches.last().first,Activity.RESULT_OK,chosen());assertEquals("later-task",results.single().taskId)
    }
    @Test fun synchronousConsumptionPrecedesLaunchReturnAndIgnoresAnAfterwardException() {
        val registry=Registry();registry.synchronous=true;registry.failure="unknown";val results=ArrayList<Target>();val picker=AttachmentImportPicker(registry){_,original->results.add(original)}
        assertEquals(AttachmentImportPicker.Launch.REQUESTED,picker.launch("original-task",null));assertEquals("original-task",results.single().taskId);val saved=Bundle();picker.save(saved);assertFalse(saved.containsKey("ownerAttachmentImport"))
    }
    @Test fun cancellationOrOkWithoutFilesNeverCreatesAnImport() {
        val registry=Registry();var count=0;val ends=ArrayList<Boolean>();val picker=AttachmentImportPicker(registry,{_,_->count++},{cancelled->ends.add(cancelled)})
        picker.launch("one",null);registry.dispatchResult(registry.launches.last().first,Activity.RESULT_CANCELED,chosen());picker.launch("two",null);registry.dispatchResult(registry.launches.last().first,Activity.RESULT_OK,null);assertEquals(0,count);assertEquals(listOf(true,false),ends)
        assertEquals(AttachmentImportPicker.Launch.REQUESTED,picker.launch("three",null))
    }
}
