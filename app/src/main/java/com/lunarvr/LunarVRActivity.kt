package com.lunarvr

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.opengl.EGL14
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import com.lunarvr.core.VRManager
import kotlin.math.abs

/**
 * Single activity of LUNAR VR:
 *  - a native launch screen (device compatibility + [ INICIAR MOTOR VR ])
 *  - the VR experience (GLSurfaceView, immersive fullscreen)
 */
class LunarVRActivity : ComponentActivity() {

    private var glSurface: GLSurfaceView? = null
    private var overlay: FrameLayout? = null
    private var warningView: TextView? = null
    private var vrManager: VRManager? = null
    private var vrActive = false

    private val cameraPermission = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startVR()
    }

    companion object {
        // Minimum required: OpenGL ES 2.0 (present on essentially all
        // Android devices since API 18). ES 3.0 is used opportunistically
        // (native VAOs) via GLUtil when the context provides it.
        private const val EGL_OPENGL_ES2_BIT = 0x0004
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = FrameLayout(this)
        val surface = GLSurfaceView(this)
        // ES 2.0 context: works on the maximum set of devices. The render
        // layer is ES2/ES3-compatible (GLUtil emulates VAOs on ES 2.0).
        surface.setEGLContextClientVersion(2)
        surface.visibility = View.INVISIBLE
        glSurface = surface
        root.addView(surface, FrameLayout.LayoutParams(-1, -1))

        val ov = buildLaunchOverlay()
        overlay = ov
        root.addView(ov, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
    }

    // ------------------------------------------------------------------
    // Launch screen (pre-VR, native views)
    // ------------------------------------------------------------------
    private fun buildLaunchOverlay(): FrameLayout {
        val root = FrameLayout(this)
        val bg = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0xFF0A0E18.toInt(), 0xFF0D1322.toInt(), 0xFF080B14.toInt())
        )
        root.background = bg

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.gravity = Gravity.CENTER
        val pad = dp(24)
        col.setPadding(pad, pad, pad, pad)

        val title = TextView(this).apply {
            text = "LUNAR VR"
            setTextColor(0xFFF2F6FF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 42f)
            typeface = Typeface.create("sans-serif-light", Typeface.BOLD)
            gravity = Gravity.CENTER
            setLetterSpacing(0.28f)
        }
        col.addView(title, centerLP(0f))

        val sub = TextView(this).apply {
            text = "SISTEMA OPERACIONAL VR  •  3DOF  •  SIDE-BY-SIDE  •  HAND TRACKING"
            setTextColor(0xFF8FA0C0.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(22))
        }
        col.addView(sub, centerLP(0f))

        val checks = deviceChecks()
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(22), dp(16), dp(22), dp(16))
        box.background = roundedBg(0x14FFFFFF, dp(18))
        box.gravity = Gravity.CENTER_HORIZONTAL
        for ((label, ok, hint) in checks) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.setPadding(0, dp(5), 0, dp(5))
            val dot = TextView(this).apply {
                text = if (ok) "●" else "○"
                setTextColor(if (ok) 0xFF63C78A.toInt() else 0xFFE8B84D.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setPadding(0, 0, dp(12), 0)
            }
            val txt = TextView(this).apply {
                text = "$label   ${if (ok) "OK" else hint}"
                setTextColor(if (ok) 0xFFC9D6EC.toInt() else 0xFFE8B84D.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            }
            row.addView(dot)
            row.addView(txt)
            box.addView(row)
        }
        col.addView(box, centerLP(0f))

        val warn = TextView(this).apply {
            text = ""
            setTextColor(0xFFE8B84D.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, dp(10))
        }
        warningView = warn
        col.addView(warn, centerLP(0f))

        val btn = Button(this).apply {
            text = "INICIAR MOTOR VR"
            setTextColor(0xFFF2F6FF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setAllCaps(false)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            background = roundedBg(0xFF3D5A9E.toInt(), dp(27))
            stateListAnimator = null
            setPadding(dp(36), 0, dp(36), 0)
            setOnClickListener {
                if (hasCameraPermission()) startVR()
                else cameraPermission.launch(Manifest.permission.CAMERA)
            }
        }
        btn.layoutParams = LinearLayout.LayoutParams(dp(250), dp(54)).apply {
            gravity = Gravity.CENTER
        }
        col.addView(btn)

        val footer = TextView(this).apply {
            text = "VR Box / Cardboard  •  giroscópio + câmera  •  sem SLAM, sem 6DOF"
            setTextColor(0xFF5A6880.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, 0)
        }
        col.addView(footer, centerLP(0f))

        root.addView(col, FrameLayout.LayoutParams(-1, -1).apply { gravity = Gravity.CENTER })
        return root
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    /** Real capability checks — the launch screen never fakes availability. */
    private fun deviceChecks(): List<Triple<String, Boolean, String>> {
        val sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val hasOrientation = sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR) != null ||
            sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) != null ||
            (sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null &&
                sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null)
        val hasGles = hasGles2()
        val cm = getSystemService(Context.CAMERA_SERVICE) as? CameraManager
        var hasCam = false
        cm?.cameraIdList?.forEach { id ->
            val f = cm.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)
            if (f == CameraCharacteristics.LENS_FACING_FRONT || f == CameraCharacteristics.LENS_FACING_BACK) hasCam = true
        }
        var hasModel = false
        try {
            assets.open("hand_landmarker.task").close()
            hasModel = true
        } catch (e: Exception) {
            hasModel = false
        }
        val list = listOf(
            Triple("Giroscópio (3DOF)", hasOrientation, "indisponível — orientação fixa"),
            Triple("OpenGL ES 2.0+", hasGles, "indisponível"),
            Triple("Câmera (hand tracking)", hasCam, "indisponível"),
            Triple("Modelo de hand tracking", hasModel, "será baixado no 1º uso")
        )
        if (!hasOrientation || !hasCam) {
            warningView?.text =
                "Este dispositivo não possui suporte a todos os recursos. " +
                "O Lunar VR vai continuar funcionando com os recursos disponíveis."
        }
        if (!hasGles) {
            warningView?.text =
                "Este dispositivo não possui suporte necessário para este recurso (OpenGL ES 2.0). " +
                "A experiência VR não poderá iniciar."
        }
        return list
    }

    private fun hasGles2(): Boolean {
        return try {
            val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val v = IntArray(2)
            if (!EGL14.eglInitialize(display, v, 0, v, 1)) return false
            val attrs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_NONE
            )
            val conf = arrayOfNulls<android.opengl.EGLConfig>(1)
            val count = IntArray(1)
            val ok = EGL14.eglChooseConfig(display, attrs, 0, conf, 0, 1, count, 0)
            EGL14.eglTerminate(display)
            ok && count[0] > 0
        } catch (e: Exception) {
            false
        }
    }

    // ------------------------------------------------------------------
    // VR entry
    // ------------------------------------------------------------------
    private fun startVR() {
        if (vrActive) return
        if (!hasGles2()) {
            warningView?.text = "OpenGL ES 2.0 indisponível neste dispositivo — a experiência VR não pode iniciar."
            return
        }
        val surface = glSurface ?: return
        val vm = VRManager(this, surface)
        vm.start()
        vrManager = vm
        vrActive = true
        surface.visibility = View.VISIBLE
        surface.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        surface.onResume()
        applyImmersive()
        // fade the launch screen out — entering the VR experience
        val ov = overlay ?: return
        ov.animate().alpha(0f).setDuration(450)
            .withEndAction { ov.visibility = View.GONE }
            .start()
    }

    @Suppress("DEPRECATION")
    private fun applyImmersive() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            )
    }

    fun onVRQuit() {
        vrActive = false
        vrManager?.stop()
        vrManager?.releaseGL()
        vrManager = null
        glSurface?.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        glSurface?.visibility = View.INVISIBLE
        val ov = overlay
        if (ov != null) {
            ov.visibility = View.VISIBLE
            ov.alpha = 1f
        }
        finish()
    }

    // ------------------------------------------------------------------
    // Input / lifecycle
    // ------------------------------------------------------------------
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && vrActive) applyImmersive()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (vrActive && ev.action == MotionEvent.ACTION_DOWN) {
            // head-ray fallback: tap = select (only used when hand tracking
            // is unavailable — the system menu states the active mode)
            vrManager?.let { if (it.interaction.useHeadRayFallback) it.requestTap() }
        }
        return super.dispatchTouchEvent(ev)
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (vrActive) {
            val vm = vrManager ?: return
            if (!vm.backStep()) onVRQuit()
        } else {
            super.onBackPressed()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // sensorLandscape can flip 180°; the gravity-based roll correction
        // in HeadTracker handles any orientation automatically
    }

    override fun onPause() {
        super.onPause()
        if (vrActive) glSurface?.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (vrActive) {
            glSurface?.onResume()
            applyImmersive()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        vrManager?.stop()
    }

    // ------------------------------------------------------------------
    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()

    private fun dpF(v: Float): Float =
        (v * resources.displayMetrics.density)

    private fun centerLP(marginTop: Float): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(marginTop.toInt())
        }

    private fun roundedBg(color: Int, radiusPx: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = radiusPx.toFloat()
        }
    }
}
