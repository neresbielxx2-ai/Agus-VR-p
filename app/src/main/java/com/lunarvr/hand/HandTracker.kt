package com.lunarvr.hand

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import java.io.File
import java.net.URL
import java.util.concurrent.Executors

/**
 * Real hand tracking: front camera (CameraX) -> MediaPipe Hand Landmarker
 * (21 landmarks x up to 2 hands) on a background executor.
 *
 * Results are published to the render thread through [latest] (AtomicReference)
 * and feed the VR interaction system directly — no WebView, no HUD trickery.
 *
 * Fallback policy (never crash, never fake):
 *  - no permission  -> UNAVAILABLE_PERMISSION
 *  - no camera      -> UNAVAILABLE_NO_CAMERA
 *  - model problem  -> tries to download the model at runtime; on failure
 *                      UNAVAILABLE_MODEL
 */
class HandTracker(private val context: Context) {

    enum class Status(val label: String) {
        OFF("desligado"),
        LOADING("carregando..."),
        READY("ativo"),
        UNAVAILABLE_PERMISSION("sem permissão de câmera"),
        UNAVAILABLE_NO_CAMERA("sem câmera"),
        UNAVAILABLE_MODEL("modelo indisponível"),
        DISABLED("desligado (config)")
    }

    @Volatile var status: Status = Status.OFF
    @Volatile var ready: Boolean = false
    @Volatile var latest: HandFrame? = null
    @Volatile var lastResultMs: Long = 0
    @Volatile var usesFrontCamera: Boolean = true

    private var cameraProvider: ProcessCameraProvider? = null
    private var handLandmarker: HandLandmarker? = null
    private val cameraExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "LunarVR.Cam").also { it.priority = Thread.MAX_PRIORITY }
    }
    private var lastTsMs = 0L
    @Volatile private var stopping = false

    private val cameraSelector: CameraSelector? by lazy {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
        var hasFront = false
        var hasBack = false
        cm?.cameraIdList?.forEach { id ->
            val ch = cm.getCameraCharacteristics(id)
            val f = ch.get(CameraCharacteristics.LENS_FACING)
            if (f == CameraCharacteristics.LENS_FACING_FRONT) hasFront = true
            if (f == CameraCharacteristics.LENS_FACING_BACK) hasBack = true
        }
        if (hasFront) {
            usesFrontCamera = true
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else if (hasBack) {
            usesFrontCamera = false
            CameraSelector.DEFAULT_BACK_CAMERA
        } else null
    }

    fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    /** Starts the camera pipeline + MediaPipe model (async). */
    fun start() {
        if (ready || status == Status.LOADING) return
        if (!hasCameraPermission()) {
            status = Status.UNAVAILABLE_PERMISSION
            return
        }
        val selector = cameraSelector
        if (selector == null) {
            status = Status.UNAVAILABLE_NO_CAMERA
            return
        }
        stopping = false
        status = Status.LOADING
        cameraExecutor.execute {
            try {
                initModel()
                if (!ready || stopping) return@execute
                val provider = ProcessCameraProvider.getInstance(context)
                provider.get().also { p ->
                    cameraProvider = p
                    p.unbindAll()
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                        .setTargetResolution(Size(640, 480))
                        .build()
                    analysis.setAnalyzer(cameraExecutor, imageAnalyzer)
                    p.bindToLifecycle(context as androidx.lifecycle.LifecycleOwner, selector, analysis)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Hand tracking init failed", t)
                if (!ready) status = Status.UNAVAILABLE_MODEL
            }
        }
    }

    private fun initModel() {
        val modelFile = File(context.filesDir, "hand_landmarker.task")
        val base: BaseOptions
        val hasAsset = try {
            context.assets.open("hand_landmarker.task").close()
            true
        } catch (e: Exception) {
            false
        }
        if (hasAsset) {
            base = BaseOptions.builder().setModelAssetPath("hand_landmarker.task").build()
        } else if (modelFile.exists()) {
            base = BaseOptions.builder()
                .setModelAssetBuffer(readDirect(modelFile))
                .build()
        } else {
            // Runtime download fallback (normal builds bundle the asset).
            Log.i(TAG, "Model asset missing, trying runtime download...")
            if (!downloadModel(modelFile)) {
                status = Status.UNAVAILABLE_MODEL
                throw IllegalStateException("hand_landmarker.task unavailable")
            }
            base = BaseOptions.builder()
                .setModelAssetBuffer(readDirect(modelFile))
                .build()
        }
        val options = HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(base)
            .setRunningMode(RunningMode.VIDEO)
            .setNumHands(2)
            .setMinHandDetectionConfidence(0.5f)
            .setMinHandPresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .build()
        val landmarker = HandLandmarker.createFromOptions(context, options)
        handLandmarker = landmarker
        ready = true
        status = Status.READY
        Log.i(TAG, "Hand landmarker ready (front=${usesFrontCamera})")
    }

    private fun readDirect(file: File): java.nio.ByteBuffer {
        val buf = java.nio.ByteBuffer.allocateDirect(file.length().toInt())
        file.inputStream().use { input ->
            val tmp = ByteArray(8192)
            var r = input.read(tmp)
            while (r >= 0) { buf.put(tmp, 0, r); r = input.read(tmp) }
        }
        buf.position(0)
        return buf
    }

    private val imageAnalyzer = ImageAnalysis.Analyzer { proxy ->
        val landmarker = handLandmarker
        if (landmarker == null || stopping) {
            proxy.close()
            return@Analyzer
        }
        var ts = System.currentTimeMillis()
        if (ts <= lastTsMs) ts = lastTsMs + 1
        lastTsMs = ts
        var bmp: android.graphics.Bitmap? =
            try { proxy.toBitmap() } catch (t: Throwable) { null }
        if (bmp != null) {
            val rot = proxy.imageInfo.rotationDegrees
            if (rot != 0) {
                val m = android.graphics.Matrix().apply { setRotate(rot.toFloat()) }
                val original = bmp
                val rotated = android.graphics.Bitmap.createBitmap(
                    original, 0, 0, original.width, original.height, m, true
                )
                original.recycle()
                bmp = rotated
            }
        }
        val finalBmp = bmp
        if (finalBmp == null) {
            proxy.close()
            return@Analyzer
        }
        try {
            val mpImage = BitmapImageBuilder(finalBmp).build()
            val result = landmarker.detectForVideo(mpImage, ts)
            publish(result)
        } catch (t: Throwable) {
            Log.e(TAG, "detect failed", t)
        } finally {
            finalBmp.recycle()
            proxy.close()
        }
    }

    /** Copies the result into a plain snapshot immediately (task owns the buffers). */
    private fun publish(result: HandLandmarkerResult) {
        if (result == null) {
            latest = null
            return
        }
        val rawLm = result.landmarks()
        val rawH = result.handedness()
        val n = rawLm.size
        val hands = ArrayList<FloatArray>(n)
        val handed = IntArray(n)
        for (i in 0 until n) {
            val lm = rawLm[i]
            val arr = FloatArray(lm.size * 3)
            for (j in 0 until lm.size) {
                arr[j * 3] = lm[j].x()
                arr[j * 3 + 1] = lm[j].y()
                arr[j * 3 + 2] = lm[j].z()
            }
            hands.add(arr)
            // MediaPipe reports from the camera's viewpoint. For a raw front
            // camera image, the user's RIGHT hand is labeled "Left".
            val label = rawH.getOrNull(i)?.getOrNull(0)?.label()
            val mpLeft = label == "Left"
            val isUserLeft = if (usesFrontCamera) !mpLeft else mpLeft
            handed[i] = if (isUserLeft) 0 else 1
        }
        val frame = HandFrame(hands, handed, System.currentTimeMillis())
        latest = frame
        lastResultMs = frame.timeMs
    }

    private fun downloadModel(dest: File): Boolean {
        return try {
            val url = URL("https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/latest/hand_landmarker.task")
            val tmp = File(dest.parentFile, dest.name + ".tmp")
            url.openStream().use { input ->
                tmp.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            tmp.renameTo(dest)
            dest.length() > 1_000_000L
        } catch (e: Exception) {
            Log.e(TAG, "Model download failed", e)
            false
        }
    }

    fun stop() {
        stopping = true
        try {
            cameraProvider?.unbindAll()
        } catch (t: Throwable) {
            // ignore
        }
        try {
            handLandmarker?.close()
        } catch (t: Throwable) {
            // ignore
        }
        handLandmarker = null
        ready = false
        status = Status.OFF
        // NOTE: the executor is intentionally kept alive so the tracker can
        // be re-enabled later from the Settings menu (3DOF-style toggling)
    }

    companion object {
        private const val TAG = "LunarVR.Hand"
    }
}
