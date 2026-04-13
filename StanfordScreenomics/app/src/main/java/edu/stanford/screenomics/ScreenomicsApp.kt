package edu.stanford.screenomics

import android.app.Application
import edu.stanford.screenomics.core.debug.PipelineDiagnosticsRegistry
import edu.stanford.screenomics.debug.AndroidPipelineDiagnostics
import edu.stanford.screenomics.screenshot.MediaProjectionScreenCapture

class ScreenomicsApp : Application() {

    @Volatile
    var phenotypingRuntime: PhenotypingRuntime? = null

    val mediaProjectionCapture: MediaProjectionScreenCapture by lazy {
        MediaProjectionScreenCapture(applicationContext)
    }

    override fun onCreate() {
        super.onCreate()
        PipelineDiagnosticsRegistry.install(AndroidPipelineDiagnostics())
    }
}
