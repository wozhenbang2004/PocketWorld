package dev.pocketengine.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.contract.ActivityResultContracts

internal enum class OutcomeExportLaunch { REQUESTED, NOT_STARTED, UNKNOWN }

/** Registers results for the original export nonce; registration/restoration never launches a picker. */
internal class AttachmentExportPicker(
    private val registry:ActivityResultRegistry,private val result:(String,Uri?)->Unit
) {
    private val launchers=linkedMapOf<String,ActivityResultLauncher<Intent>>()
    fun restore(requestId:String){register(requestId)}
    private fun register(requestId:String):ActivityResultLauncher<Intent>? {
        require(requestId.matches(Regex("[a-f0-9-]{36}")))
        var delivered=false
        // Re-registering the same key also retrieves a result the registry retained after an unknown launch exception.
        val launcher=registry.register("attachment_outcome_export_$requestId",ActivityResultContracts.StartActivityForResult()){actual->
            delivered=true
            launchers.remove(requestId)?.unregister()
            result(requestId,if(actual.resultCode==Activity.RESULT_OK)actual.data?.data else null)
        }
        if(delivered){launcher.unregister();return null}
        launchers[requestId]=launcher
        return launcher
    }
    fun launch(requestId:String,mime:String,name:String):OutcomeExportLaunch {
        if(launchers.containsKey(requestId))return OutcomeExportLaunch.UNKNOWN
        return try{
            val launcher=register(requestId)?:return OutcomeExportLaunch.REQUESTED
            launcher.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(mime).putExtra(Intent.EXTRA_TITLE,name))
            OutcomeExportLaunch.REQUESTED
        }catch(_:android.content.ActivityNotFoundException){
            launchers.remove(requestId)?.unregister();OutcomeExportLaunch.NOT_STARTED
        }catch(_:RuntimeException){
            // Dispatch may already have happened. Retain this original key until a result is known.
            OutcomeExportLaunch.UNKNOWN
        }
    }
    fun close(){launchers.values.toList().forEach{it.unregister()};launchers.clear()}
}
