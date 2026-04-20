package edu.stanford.screenomics

import android.app.Activity
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import edu.stanford.screenomics.collection.ModalityCollectionService

/**
 * Minimal activity started from [ModalityCollectionService] when the screen turns on but
 * [MediaProjectionScreenCapture] is no longer running. It immediately launches the system screen-capture
 * consent flow so we do not depend on [MainActivity] wiring or delays.
 */
class MediaProjectionRelaunchTrampolineActivity : AppCompatActivity() {

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK &&
            result.data != null &&
            ModalityCollectionService.isRunning
        ) {
            ModalityCollectionService.start(this, result.resultCode, result.data!!)
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) {
            finish()
            return
        }
        if (!ModalityCollectionService.isRunning) {
            finish()
            return
        }
        if ((application as ScreenomicsApp).mediaProjectionCapture.isRunning()) {
            finish()
            return
        }
        val mpm = getSystemService(MediaProjectionManager::class.java) ?: run {
            finish()
            return
        }
        // Post so the window token exists (required on some OEMs for StartActivityForResult).
        window.decorView.post {
            if (isFinishing) {
                return@post
            }
            if (!ModalityCollectionService.isRunning) {
                finish()
                return@post
            }
            if ((application as ScreenomicsApp).mediaProjectionCapture.isRunning()) {
                finish()
                return@post
            }
            projectionLauncher.launch(mpm.createScreenCaptureIntent())
        }
    }
}
