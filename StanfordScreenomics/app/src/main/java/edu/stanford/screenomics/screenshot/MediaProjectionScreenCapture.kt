package edu.stanford.screenomics.screenshot

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import java.nio.ByteBuffer

/**
 * Full-screen capture via [MediaProjection] + [VirtualDisplay] / [ImageReader].
 * Call [start] after the user approves [MediaProjectionManager.createScreenCaptureIntent] and
 * **after** a foreground service with type [android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION]
 * has started on API 34+.
 */
class MediaProjectionScreenCapture(private val appContext: Context) {

    private val lock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private val projectionCallback: MediaProjection.Callback = object : MediaProjection.Callback() {
        override fun onStop() {
            synchronized(lock) {
                virtualDisplay?.release()
                virtualDisplay = null
                runCatching { imageReader?.close() }
                imageReader = null
                mediaProjection = null
            }
        }
    }

    fun start(resultCode: Int, data: Intent): Boolean {
        synchronized(lock) {
            tearDownLocked()
            return try {
                val mgr = appContext.getSystemService(MediaProjectionManager::class.java) ?: return false
                val projection = mgr.getMediaProjection(resultCode, data) ?: return false
                mediaProjection = projection

                val (w, h, dpi) = displaySizeDpi(appContext)
                if (w <= 0 || h <= 0) {
                    Log.e(TAG, "Invalid display size $w x $h")
                    tearDownLocked()
                    return false
                }

                val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
                imageReader = reader

                projection.registerCallback(projectionCallback, mainHandler)

                val vd = projection.createVirtualDisplay(
                    VIRTUAL_DISPLAY_NAME,
                    w,
                    h,
                    dpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    reader.surface,
                    null,
                    null,
                )
                if (vd == null) {
                    Log.e(TAG, "createVirtualDisplay returned null")
                    tearDownLocked()
                    return false
                }
                virtualDisplay = vd
                Log.i(TAG, "MediaProjection capture started ${w}x$h @ $dpi dpi")
                true
            } catch (e: SecurityException) {
                Log.e(TAG, "MediaProjection refused (stale or invalid consent?)", e)
                tearDownLocked()
                false
            } catch (e: IllegalStateException) {
                Log.e(TAG, "MediaProjection invalid state", e)
                tearDownLocked()
                false
            } catch (e: RuntimeException) {
                Log.e(TAG, "MediaProjection start failed", e)
                tearDownLocked()
                false
            }
        }
    }

    /**
     * Returns the latest frame, or null if none yet / capture stopped.
     */
    fun acquireLatestBitmap(): Bitmap? {
        synchronized(lock) {
            val reader = imageReader ?: return null
            val image = reader.acquireLatestImage() ?: return null
            try {
                return imageToBitmap(image)
            } catch (e: Exception) {
                Log.w(TAG, "imageToBitmap failed", e)
                return null
            } finally {
                image.close()
            }
        }
    }

    fun stop() {
        synchronized(lock) {
            tearDownLocked()
        }
    }

    fun isRunning(): Boolean = synchronized(lock) { mediaProjection != null }

    private fun tearDownLocked() {
        virtualDisplay?.release()
        virtualDisplay = null
        runCatching { imageReader?.close() }
        imageReader = null
        val mp = mediaProjection
        mediaProjection = null
        mp?.let {
            runCatching { it.unregisterCallback(projectionCallback) }
            runCatching { it.stop() }
        }
    }

    private companion object {
        private const val TAG = "MediaProjectionCapture"
        private const val VIRTUAL_DISPLAY_NAME = "screenomics_projection"

        private fun displaySizeDpi(context: Context): Triple<Int, Int, Int> {
            val wm = context.getSystemService(WindowManager::class.java)!!
            val dpi = context.resources.configuration.densityDpi
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val b = wm.currentWindowMetrics.bounds
                Triple(b.width(), b.height(), dpi)
            } else {
                val m = android.util.DisplayMetrics()
                @Suppress("DEPRECATION")
                wm.defaultDisplay.getRealMetrics(m)
                Triple(m.widthPixels, m.heightPixels, dpi)
            }
        }

        private fun imageToBitmap(image: Image): Bitmap {
            val planes = image.planes
            val buffer: ByteBuffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * image.width
            buffer.rewind()
            val bitmap = Bitmap.createBitmap(
                image.width + rowPadding / pixelStride,
                image.height,
                Bitmap.Config.ARGB_8888,
            )
            bitmap.copyPixelsFromBuffer(buffer)
            return if (rowPadding == 0) {
                bitmap
            } else {
                val cropped = Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
                bitmap.recycle()
                cropped
            }
        }
    }
}
