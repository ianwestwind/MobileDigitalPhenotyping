package edu.stanford.screenomics.debug

import android.util.Log
import edu.stanford.screenomics.core.debug.PipelineDiagnostics
import edu.stanford.screenomics.core.debug.toModuleLogTag
import edu.stanford.screenomics.core.unified.ModalityKind

/**
 * Routes [edu.stanford.screenomics.core.debug.PipelineDiagnosticsRegistry] emissions to Logcat.
 * Install once from [edu.stanford.screenomics.MainActivity] so JVM `:core` code can log on-device.
 */
class AndroidPipelineDiagnostics : PipelineDiagnostics {

    override fun emit(logTag: String, module: ModalityKind?, stage: String, dataType: String, detail: String) {
        val moduleTag = module?.toModuleLogTag() ?: "SYSTEM"
        val ts = System.currentTimeMillis()
        Log.d(logTag, "[$moduleTag][ts=$ts][stage=$stage][type=$dataType] $detail")
    }
}
