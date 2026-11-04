package dev.pocketengine.ui

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.app.ActivityOptionsCompat
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Actual AndroidX registry with a controlled onLaunch, not a mounted Activity or external document provider. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],manifest=Config.NONE,application=Application::class)
class AttachmentExportPickerRegistryTest {
    private class Registry:ActivityResultRegistry() {
        val launches=ArrayList<Pair<Int,Intent>>()
        var failure=""
        var synchronous=false
        override fun <I,O> onLaunch(requestCode:Int,contract:ActivityResultContract<I,O>,input:I,options:ActivityOptionsCompat?) {
            launches.add(requestCode to (input as Intent))
            if(synchronous)dispatchResult(requestCode,Activity.RESULT_OK,Intent().setData(uri()))
            when(failure){"missing"->throw android.content.ActivityNotFoundException("No picker");"unknown"->throw IllegalStateException("Dispatch is uncertain")}
        }
    }
    companion object {private fun id()=java.util.UUID.randomUUID().toString();private fun uri()=Uri.parse("content://registry-fixture/chosen")}
    @Test fun launchAndResultKeepTheExactOriginalNonceAndIntent() {
        val registry=Registry();val results=ArrayList<Pair<String,Uri?>>()
        val picker=AttachmentExportPicker(registry){nonce,uri->results.add(nonce to uri)};val request=id()
        assertEquals(OutcomeExportLaunch.REQUESTED,picker.launch(request,"text/plain","fixed.txt"))
        val (code,intent)=registry.launches.single();assertEquals(Intent.ACTION_CREATE_DOCUMENT,intent.action);assertEquals("text/plain",intent.type);assertEquals("fixed.txt",intent.getStringExtra(Intent.EXTRA_TITLE));assertTrue(intent.categories.contains(Intent.CATEGORY_OPENABLE))
        assertTrue(registry.dispatchResult(code,Activity.RESULT_OK,Intent().setData(uri())))
        assertEquals(listOf(request to uri()),results)
        registry.dispatchResult(code,Activity.RESULT_CANCELED,null);assertEquals(1,results.size)
    }
    @Test fun restoringTheSameRegistrationNeverLaunchesAnotherPicker() {
        val original=Registry();val request=id();val first=AttachmentExportPicker(original){_,_->}
        first.launch(request,"text/plain","original.txt");val code=original.launches.single().first;val saved=Bundle();original.onSaveInstanceState(saved);first.close()
        val restored=Registry();restored.onRestoreInstanceState(saved);val results=ArrayList<Pair<String,Uri?>>()
        val picker=AttachmentExportPicker(restored){nonce,uri->results.add(nonce to uri)}
        picker.restore(request);assertTrue(restored.launches.isEmpty())
        restored.dispatchResult(code,Activity.RESULT_OK,Intent().setData(uri()));assertEquals(listOf(request to uri()),results)
    }
    @Test fun definiteMissingPickerIsReleasedAndCanBeExplicitlyRetried() {
        val registry=Registry();registry.failure="missing";val results=ArrayList<Pair<String,Uri?>>()
        val picker=AttachmentExportPicker(registry){nonce,uri->results.add(nonce to uri)};val old=id()
        assertEquals(OutcomeExportLaunch.NOT_STARTED,picker.launch(old,"text/plain","old.txt"))
        val oldCode=registry.launches.single().first;registry.failure="";val next=id()
        assertEquals(OutcomeExportLaunch.REQUESTED,picker.launch(next,"text/plain","next.txt"))
        assertFalse(registry.dispatchResult(oldCode,Activity.RESULT_OK,Intent().setData(uri())))
        registry.dispatchResult(registry.launches.last().first,Activity.RESULT_OK,Intent().setData(uri()));assertEquals(listOf(next to uri()),results)
    }
    @Test fun unknownLaunchRetainsItsKeyAndForegroundRegistrationRetrievesItsLaterResult() {
        val registry=Registry();registry.failure="unknown";val results=ArrayList<Pair<String,Uri?>>()
        val picker=AttachmentExportPicker(registry){nonce,uri->results.add(nonce to uri)};val request=id()
        assertEquals(OutcomeExportLaunch.UNKNOWN,picker.launch(request,"text/plain","uncertain.txt"))
        assertEquals(OutcomeExportLaunch.UNKNOWN,picker.launch(request,"text/plain","must-not-launch.txt"));assertEquals(1,registry.launches.size)
        registry.dispatchResult(registry.launches.single().first,Activity.RESULT_OK,Intent().setData(uri()))
        // AndroidX clears launchedKeys on the exception, so this result is pending until registration.
        assertTrue(results.isEmpty());picker.restore(request)
        assertEquals(listOf(request to uri()),results);assertEquals(1,registry.launches.size)
    }
    @Test fun pendingUnknownResultCanRestoreItsOriginalNonceWithoutReissuing() {
        val original=Registry();original.failure="unknown";val request=id();val first=AttachmentExportPicker(original){_,_->}
        first.launch(request,"text/plain","unknown.txt");original.dispatchResult(original.launches.single().first,Activity.RESULT_OK,Intent().setData(uri()))
        val saved=Bundle();original.onSaveInstanceState(saved);first.close()
        val restored=Registry();restored.onRestoreInstanceState(saved);val results=ArrayList<Pair<String,Uri?>>()
        val picker=AttachmentExportPicker(restored){nonce,uri->results.add(nonce to uri)};picker.restore(request)
        assertEquals(listOf(request to uri()),results);assertTrue(restored.launches.isEmpty())
    }
    @Test fun aSynchronousResultDuringRegistrationDoesNotCauseANewLaunch() {
        val registry=Registry();registry.failure="unknown";val request=id()
        val first=AttachmentExportPicker(registry){_,_->};first.launch(request,"text/plain","before.txt")
        registry.dispatchResult(registry.launches.single().first,Activity.RESULT_OK,Intent().setData(uri()))
        val results=ArrayList<Pair<String,Uri?>>()
        val replacement=AttachmentExportPicker(registry){nonce,uri->results.add(nonce to uri)}
        assertEquals(OutcomeExportLaunch.REQUESTED,replacement.launch(request,"text/plain","not-launched.txt"))
        assertEquals(1,registry.launches.size);assertEquals(listOf(request to uri()),results);first.close();replacement.close()
    }
    @Test fun synchronousLaunchResultStaysOriginalEvenBeforeLaunchStatusReturns() {
        val registry=Registry();registry.synchronous=true;val results=ArrayList<Pair<String,Uri?>>()
        val picker=AttachmentExportPicker(registry){nonce,uri->results.add(nonce to uri)};val request=id()
        assertEquals(OutcomeExportLaunch.REQUESTED,picker.launch(request,"text/plain","sync.txt"))
        assertEquals(listOf(request to uri()),results);assertEquals(1,registry.launches.size);picker.close()
    }
    @Test fun closingRegistrationsCannotDeliverTheirLateResultsToANewNonce() {
        val registry=Registry();val results=ArrayList<Pair<String,Uri?>>()
        val first=AttachmentExportPicker(registry){nonce,uri->results.add(nonce to uri)};first.launch(id(),"text/plain","old.txt");val old=registry.launches.single().first;first.close()
        val second=AttachmentExportPicker(registry){nonce,uri->results.add(nonce to uri)};val current=id();second.launch(current,"text/plain","new.txt")
        registry.dispatchResult(old,Activity.RESULT_OK,Intent().setData(uri()));assertTrue(results.isEmpty())
        registry.dispatchResult(registry.launches.last().first,Activity.RESULT_CANCELED,null);assertEquals(listOf(current to null),results)
    }
}
