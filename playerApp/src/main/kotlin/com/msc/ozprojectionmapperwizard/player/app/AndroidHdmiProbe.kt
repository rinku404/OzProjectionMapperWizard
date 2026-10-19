package com.msc.ozprojectionmapperwizard.player.app

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.media.ImageReader
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.media.tv.TvView
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.msc.ozprojectionmapperwizard.player.HdmiProbe
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Looks for every way this device could hand the HDMI input to the player: TV inputs, cameras,
 * video devices, and whether a TV input's picture can be read back as pixels (which warping needs).
 */
class AndroidHdmiProbe(private val app: PlayerApplication) : HdmiProbe {

    override suspend fun run(report: (String) -> Unit) {
        report("Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.HARDWARE}), Android ${Build.VERSION.RELEASE}, SDK ${Build.VERSION.SDK_INT}")
        val inputs = tvInputs(report)
        videoDevices(report)
        cameras(report)
        val hdmi = inputs.firstOrNull { it.type == TvInputInfo.TYPE_HDMI } ?: inputs.firstOrNull { it.isPassthroughInput }
        if (hdmi == null) {
            report("TvView test: skipped, there is no HDMI TV input.")
        } else {
            tvViewTest(hdmi, report)
        }
        report("Done.")
    }

    private fun tvInputs(report: (String) -> Unit): List<TvInputInfo> {
        val manager = app.getSystemService(TvInputManager::class.java)
        if (manager == null) {
            report("TV inputs: this device has no TV input service.")
            return emptyList()
        }
        val inputs = try {
            manager.tvInputList
        } catch (e: Exception) {
            report("TV inputs: can't list them (${e.message})")
            return emptyList()
        }
        report("TV inputs: ${inputs.size}")
        for (input in inputs) {
            val label = runCatching { input.loadLabel(app).toString() }.getOrDefault("?")
            val state = runCatching { manager.getInputState(input.id) }.getOrDefault(-1)
            report("  $label, type ${typeName(input.type)}, passthrough ${input.isPassthroughInput}, state ${stateName(state)}, id ${input.id}")
        }
        return inputs
    }

    private fun videoDevices(report: (String) -> Unit) {
        val devices = File("/dev").listFiles { f -> f.name.startsWith("video") }.orEmpty().sortedBy { it.name }
        if (devices.isEmpty()) {
            report("Video devices: none visible")
        } else {
            report("Video devices: " + devices.joinToString { "${it.name}${if (it.canRead()) " (readable)" else ""}" })
        }
    }

    private suspend fun cameras(report: (String) -> Unit) {
        val manager = app.getSystemService(CameraManager::class.java)
        val ids = runCatching { manager?.cameraIdList?.toList() }.getOrNull().orEmpty()
        report("Cameras: ${ids.size}")
        if (ids.isEmpty()) return
        for (id in ids) {
            val facing = runCatching { manager!!.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) }.getOrNull()
            report("  Camera $id, facing ${facingName(facing)}")
        }
        if (app.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            withContext(Dispatchers.Main) { app.activity?.requestPermissions(arrayOf(Manifest.permission.CAMERA), 1) }
            report("Camera frames: not allowed yet. Allow the camera on the projector, then run the test again.")
            return
        }
        for (id in ids) report("  Camera $id frames: ${countFrames(manager!!, id)}")
    }

    /** Streams a camera for a few seconds and says how many frames came and how bright they were. */
    private suspend fun countFrames(manager: CameraManager, id: String): String {
        val thread = HandlerThread("hdmi-probe-camera").apply { start() }
        val handler = Handler(thread.looper)
        var camera: CameraDevice? = null
        var reader: ImageReader? = null
        try {
            val map = manager.getCameraCharacteristics(id).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?: return "no stream sizes"
            val sizes = map.getOutputSizes(ImageFormat.YUV_420_888)?.toList().orEmpty()
            val size = sizes.maxByOrNull { it.width * it.height } ?: return "no YUV output"
            val frames = AtomicInteger()
            val brightness = AtomicLong(-1)
            reader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 3).apply {
                setOnImageAvailableListener({ r ->
                    val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                    frames.incrementAndGet()
                    val luma = image.planes[0].buffer
                    var sum = 0L
                    var n = 0
                    var i = 0
                    while (i < luma.limit()) {
                        sum += luma.get(i).toInt() and 0xFF
                        n++
                        i += 997
                    }
                    if (n > 0) brightness.set(sum / n)
                    image.close()
                }, handler)
            }
            val opened = CompletableDeferred<CameraDevice?>()
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) { opened.complete(device) }
                override fun onDisconnected(device: CameraDevice) { device.close(); opened.complete(null) }
                override fun onError(device: CameraDevice, error: Int) { device.close(); opened.complete(null) }
            }, handler)
            camera = withTimeoutOrNull(5_000) { opened.await() } ?: return "could not open it"
            val configured = CompletableDeferred<CameraCaptureSession?>()
            @Suppress("DEPRECATION")
            camera.createCaptureSession(listOf(reader.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) { configured.complete(session) }
                override fun onConfigureFailed(session: CameraCaptureSession) { configured.complete(null) }
            }, handler)
            val session = withTimeoutOrNull(5_000) { configured.await() } ?: return "could not start streaming"
            val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply { addTarget(reader.surface) }.build()
            session.setRepeatingRequest(request, null, handler)
            delay(3_000)
            val level = brightness.get()
            return "${frames.get()} frames in 3 s at ${size.width}x${size.height}" +
                if (level >= 0) ", brightness $level of 255" else ""
        } catch (e: Exception) {
            return "failed (${e.message ?: e::class.simpleName})"
        } finally {
            camera?.close()
            reader?.close()
            thread.quitSafely()
        }
    }

    /**
     * Shows the HDMI input full screen for a few seconds and tries to copy its pixels. If the copy
     * works, the player can draw the input into its own warped surface; if not, the projector's
     * hardware draws it straight to the screen and it can't be warped by an app.
     */
    private suspend fun tvViewTest(input: TvInputInfo, report: (String) -> Unit) {
        val activity = app.activity
        if (activity == null) {
            report("TvView test: skipped, the player screen isn't open.")
            return
        }
        report("TvView test: showing ${runCatching { input.loadLabel(app) }.getOrDefault(input.id)} on the projector for about 15 s")
        val tv = withContext(Dispatchers.Main) {
            TvView(activity).also { tv ->
                tv.setCallback(object : TvView.TvInputCallback() {
                    override fun onConnectionFailed(inputId: String) = report("  connection failed")
                    override fun onDisconnected(inputId: String) = report("  disconnected")
                    override fun onVideoAvailable(inputId: String) = report("  video available")
                    override fun onVideoUnavailable(inputId: String, reason: Int) = report("  video unavailable (reason $reason)")
                    override fun onVideoSizeChanged(inputId: String, width: Int, height: Int) = report("  video size ${width}x$height")
                })
                activity.findViewById<ViewGroup>(android.R.id.content)
                    .addView(tv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                tv.tune(input.id, TvContract.buildChannelUriForPassthroughInput(input.id))
            }
        }
        try {
            delay(8_000)
            for (attempt in 1..2) {
                report("  pixel copy $attempt: ${withContext(Dispatchers.Main) { copyPixels(tv) }}")
                delay(3_000)
            }
        } finally {
            withContext(Dispatchers.Main) {
                tv.reset()
                (tv.parent as? ViewGroup)?.removeView(tv)
            }
        }
    }

    private suspend fun copyPixels(tv: TvView): String {
        val surface = findSurfaceView(tv) ?: return "no surface inside the TV view"
        if (surface.width == 0 || surface.height == 0) return "the TV surface has no size"
        val bitmap = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888)
        val result = CompletableDeferred<Int>()
        try {
            PixelCopy.request(surface, bitmap, { result.complete(it) }, Handler(Looper.getMainLooper()))
        } catch (e: Exception) {
            return "failed (${e.message ?: e::class.simpleName})"
        }
        val code = withTimeoutOrNull(3_000) { result.await() } ?: return "no answer"
        if (code != PixelCopy.SUCCESS) return "refused (${copyError(code)})"
        var sum = 0L
        val colors = HashSet<Int>()
        for (y in 0 until bitmap.height step 6) for (x in 0 until bitmap.width step 6) {
            val p = bitmap.getPixel(x, y)
            sum += ((p shr 16 and 0xFF) + (p shr 8 and 0xFF) + (p and 0xFF)) / 3
            colors += p and 0xF0F0F0
        }
        val samples = (bitmap.height / 6 + 1) * (bitmap.width / 6 + 1)
        return "worked, brightness ${sum / samples} of 255, ${colors.size} distinct colors" +
            if (colors.size <= 2) " (looks blank: the picture may be drawn by hardware)" else ""
    }

    private fun findSurfaceView(view: View): SurfaceView? {
        if (view is SurfaceView) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) findSurfaceView(view.getChildAt(i))?.let { return it }
        return null
    }

    private fun typeName(type: Int) = when (type) {
        TvInputInfo.TYPE_TUNER -> "tuner"
        TvInputInfo.TYPE_HDMI -> "HDMI"
        TvInputInfo.TYPE_COMPONENT -> "component"
        TvInputInfo.TYPE_COMPOSITE -> "composite"
        TvInputInfo.TYPE_DISPLAY_PORT -> "DisplayPort"
        TvInputInfo.TYPE_DVI -> "DVI"
        TvInputInfo.TYPE_VGA -> "VGA"
        TvInputInfo.TYPE_SVIDEO -> "S-Video"
        TvInputInfo.TYPE_SCART -> "SCART"
        TvInputInfo.TYPE_OTHER -> "other"
        else -> type.toString()
    }

    private fun stateName(state: Int) = when (state) {
        TvInputManager.INPUT_STATE_CONNECTED -> "connected"
        TvInputManager.INPUT_STATE_CONNECTED_STANDBY -> "standby"
        TvInputManager.INPUT_STATE_DISCONNECTED -> "disconnected"
        else -> "unknown"
    }

    private fun facingName(facing: Int?) = when (facing) {
        CameraCharacteristics.LENS_FACING_FRONT -> "front"
        CameraCharacteristics.LENS_FACING_BACK -> "back"
        CameraCharacteristics.LENS_FACING_EXTERNAL -> "external"
        else -> "unknown"
    }

    private fun copyError(code: Int) = when (code) {
        PixelCopy.ERROR_SOURCE_NO_DATA -> "no picture yet"
        PixelCopy.ERROR_SOURCE_INVALID -> "the surface can't be read"
        PixelCopy.ERROR_TIMEOUT -> "timed out"
        PixelCopy.ERROR_DESTINATION_INVALID -> "bad destination"
        else -> "error $code"
    }
}
