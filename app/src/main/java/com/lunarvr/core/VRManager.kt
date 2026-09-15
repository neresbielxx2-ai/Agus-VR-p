package com.lunarvr.core

import android.opengl.EGL10
import android.opengl.EGLConfig
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import com.lunarvr.audio.SoundBank
import com.lunarvr.environment.Environment
import com.lunarvr.hand.HandPose
import com.lunarvr.hand.HandTracker
import com.lunarvr.hand.VirtualHand
import com.lunarvr.interaction.InteractionManager
import com.lunarvr.math.Quat
import com.lunarvr.math.Vec3
import com.lunarvr.scene.SceneNode
import com.lunarvr.settings.SettingsStore
import com.lunarvr.stereo.StereoRenderer
import com.lunarvr.tracking.HeadTracker
import com.lunarvr.ui.MenuController
import com.lunarvr.ui.Panel3D
import com.lunarvr.ui.UIItem
import com.lunarvr.interaction.RayRenderer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max

/**
 * LUNAR VR — central VR engine manager.
 *
 * Owns and coordinates: VR initialization/shutdown, CameraRig, 3DOF,
 * quaternion pipeline, rendering, SBS, IPD, Hand Tracking, Ray,
 * interaction, spatial menus and settings.
 *
 * Startup sequence ([INICIAR MOTOR VR]):
 *  1. VRManager init            5. camera config (eyes)
 *  2. 3DOF init (sensors)       6. SBS config
 *  3. CameraRig                 7. Hand Tracking init
 *  4. WorldRoot                 8. MainMenu 3D -> VR experience
 */
class VRManager(
    private val activity: ComponentActivity,
    private val glSurface: android.opengl.GLSurfaceView
) {

    lateinit var settings: SettingsStore
    lateinit var sound: SoundBank
    lateinit var head: HeadTracker
    lateinit var handTracker: HandTracker
    lateinit var stereo: StereoRenderer
    lateinit var env: Environment
    lateinit var menus: MenuController
    lateinit var interaction: InteractionManager
    lateinit var handRenderL: VirtualHand
    lateinit var handRenderR: VirtualHand
    lateinit var rayRender: RayRenderer
    lateinit var poseL: HandPose
    lateinit var poseR: HandPose

    // WorldRoot architecture:
    //   WorldRoot
    //     CameraRig -> Camera
    //     MainMenu / SettingsMenu / AppsMenu / SystemMenu / FloatingPanels
    lateinit var worldRoot: SceneNode
    lateinit var cameraRig: SceneNode
    lateinit var cameraNode: SceneNode
    private val menuNodes = HashMap<String, SceneNode>()

    @Volatile var glReady = false
    private var startNs = 0L
    private var lastNs = 0L
    private var fpsSmooth = 60f
    var fpsInt: Int = 60
        private set
    private var mainMenuOpened = false

    companion object {
        private const val TAG = "LunarVR"
        const val FOV_V = 1.08f      // ~62 degrees vertical
        const val CAM_VFOV = 0.96f   // ~55 deg (phone camera, depth estimate)
        const val CAM_ASPECT = 4f / 3f
    }

    // ------------------------------------------------------------------
    // 1-8: engine startup
    // ------------------------------------------------------------------
    fun start() {
        Log.i(TAG, "[1/8] VRManager init")
        settings = SettingsStore(activity)
        sound = SoundBank(activity)
        sound.init()
        sound.setVolume(settings.volume)
        sound.enabled = settings.soundsOn

        Log.i(TAG, "[2/8] 3DOF init (gyroscope/quaternion)")
        head = HeadTracker(activity)
        head.start()
        if (!head.hasOrientationSensor) {
            Log.w(TAG, "No orientation sensor — 3DOF fallback: fixed orientation")
        }

        Log.i(TAG, "[7/8] Hand Tracking init (MediaPipe)")
        handTracker = HandTracker(activity)
        poseL = HandPose(0)
        poseR = HandPose(1)
        if (settings.handTrackingOn) handTracker.start()

        interaction = InteractionManager()
        interaction.listener = object : InteractionManager.Listener {
            override fun onCommand(cmd: Int, arg: Int) {
                menus.onCommand(cmd, arg, head.currentOrientation)
            }

            override fun onHoverChanged(item: UIItem?) {
                menus.onHoverChanged(item)
            }

            override fun onSliderLive(item: UIItem, t01: Float) {
                menus.onSliderLive(item, t01)
            }

            override fun onSliderEnd(item: UIItem) {
                menus.onSliderEnd(item)
            }
        }

        stereo = StereoRenderer()
        stereo.renderScale = settings.renderScale()

        Log.i(TAG, "[3-4/8] WorldRoot + CameraRig created (GL side)")
        glSurface.setEGLContextClientVersion(3)
        glSurface.setRenderer(renderer)
        glSurface.renderMode = android.opengl.GLSurfaceView.RENDERMODE_CONTINUOUSLY
        glSurface.visibility = View.VISIBLE
        Log.i(TAG, "VR engine started. 3DOF=${head.statusText} | HT=${handTracker.status.label}")
    }

    fun stop() {
        head.stop()
        handTracker.stop()
        sound.release()
    }

    // ------------------------------------------------------------------
    // GL renderer (GL thread = the render thread)
    // ------------------------------------------------------------------
    private val renderer = object : android.opengl.GLSurfaceView.Renderer {
        override fun onSurfaceCreated(gl: EGL10?, config: EGLConfig?) {
            Log.i(TAG, "[5-6/8] GL context: cameras + SBS configured")
            // if the surface was destroyed/recreated (rotation), the GL
            // objects died with the context — reset state and rebuild
            val recreating = ::menus.isInitialized
            if (recreating) {
                menus.contextLost()
                env.contextLost()
                handRenderL.contextLost()
                handRenderR.contextLost()
                rayRender.contextLost()
                stereo.contextLost()
                // menus are rebuilt from scratch below — reopen MainMenu
                mainMenuOpened = false
            }
            stereo.initGL()
            env = Environment()
            env.initGL(settings.graphics)
            menus = MenuController(settings, head, handTracker, sound)
            menus.buildAll()
            menus.initGL()
            menus.onApplyGraphics = { applyGraphics() }
            menus.onCalibrate = { calibrateNow() }
            menus.onQuit = { activity.runOnUiThread { quitToLauncher() } }
            menus.fpsProvider = { fpsInt }
            menus.onHandTrackingToggled = { v ->
                if (v) handTracker.start() else handTracker.stop()
            }
            handRenderL = VirtualHand().also { it.initGL() }
            handRenderR = VirtualHand().also { it.initGL() }
            rayRender = RayRenderer().also { it.initGL() }

            // WorldRoot scene graph (menus are children of WorldRoot)
            worldRoot = SceneNode("WorldRoot")
            cameraRig = worldRoot.add(SceneNode("CameraRig"))
            cameraNode = cameraRig.add(SceneNode("Camera"))
            menuNodes["MainMenu"] = worldRoot.add(SceneNode("MainMenu"))
            menuNodes["SettingsMenu"] = worldRoot.add(SceneNode("SettingsMenu"))
            menuNodes["AppsMenu"] = worldRoot.add(SceneNode("AppsMenu"))
            menuNodes["SystemMenu"] = worldRoot.add(SceneNode("SystemMenu"))
            worldRoot.add(SceneNode("FloatingPanels"))
            glReady = true
            startNs = System.nanoTime()
            lastNs = 0L
        }

        override fun onSurfaceChanged(gl: EGL10?, width: Int, height: Int) {
            stereo.onSurfaceChanged(width, height)
        }

        override fun onDrawFrame(gl: EGL10?) {
            if (!glReady) return
            drawFrame()
        }
    }

    // ------------------------------------------------------------------
    // Frame
    // ------------------------------------------------------------------
    private fun drawFrame() {
        val now = System.nanoTime()
        val dt = if (lastNs == 0L) 0.016f
        else ((now - lastNs) / 1e9f).toFloat().coerceIn(0.001f, 0.1f)
        lastNs = now
        val timeSec = ((now - startNs) / 1e9f).toFloat()

        // ---- 3DOF (gyroscope + quaternion + smoothing) ----
        head.sensitivity = settings.headSensitivity
        head.smoothing = settings.headSmoothing
        if (head.hasOrientationSensor && !head.isCalibrated) {
            head.calibrateNow(head.rawHeadNow())
        }
        val qCam = head.update(dt)

        // ---- hand tracking ----
        val handsReady = settings.handTrackingOn && handTracker.ready
        interaction.useHeadRayFallback = !handsReady
        val nowMs = System.currentTimeMillis()
        val frame = handTracker.latest
        if (handsReady && frame != null && nowMs - frame.timeMs < 700) {
            for (i in frame.hands.indices) {
                val slot = frame.handedness[i]
                if (settings.handSide == 1 && slot != 1) continue
                if (settings.handSide == 2 && slot != 0) continue
                val pose = if (slot == 0) poseL else poseR
                pose.handSmoothing = settings.handSmoothing
                pose.pinchThresholdNorm = settings.pinchSensitivity
                pose.update(frame.hands[i], qCam, CAM_VFOV, CAM_ASPECT, dt, nowMs)
            }
        } else {
            if (!handsReady) {
                poseL.expire(nowMs)
                poseR.expire(nowMs)
            }
        }
        poseL.expire(nowMs)
        poseR.expire(nowMs)
        if (poseL.visible) handRenderL.build(poseL, settings.graphics)
        if (poseR.visible) handRenderR.build(poseR, settings.graphics)

        // ---- menus / interaction ----
        menus.update(dt, qCam)
        val panels = menus.allPanelsList()
        interaction.update(dt, qCam, poseL, poseR, panels, settings)
        syncHover(panels)

        // first frame: open the MainMenu (in front of the user)
        if (!mainMenuOpened && head.isCalibrated) {
            mainMenuOpened = true
            menus.openPanelNow(menus.mainMenu, qCam)
        }

        // ---- architecture nodes follow the panels ----
        syncArchitectureNodes(qCam)

        // ---- SBS stereo render ----
        stereo.renderScale = settings.renderScale() // graphics preset may change live
        val sbs = settings.sbs
        val ipd = settings.ipdMm / 1000f
        val useFallbackRay = interaction.useHeadRayFallback
        stereo.renderFrame(
            sbs,
            makeEye = { eye ->
                val off = if (sbs) (if (eye == 0) -ipd * 0.5f else ipd * 0.5f) else 0f
                val eyePos = qCam.rotate(Vec3(off, 0f, 0f))
                val rtW = stereo.targetWidth
                val rtH = max(1, stereo.targetHeight)
                val eyeW = if (sbs) rtW / 2 else rtW
                val aspect = eyeW.toFloat() / rtH.toFloat()
                val proj = com.lunarvr.math.Mat4().perspective(FOV_V, aspect, 0.02f, 120f)
                val view = com.lunarvr.math.Mat4().viewFromPose(eyePos, qCam)
                Triple(proj, view, eyePos)
            },
            drawEye = { _, proj, view, eyePos ->
                env.drawSky(proj, view, eyePos)
                env.drawStars(proj, view, eyePos, timeSec)
                env.drawFloor(proj, view)
                menus.drawAll(proj, view, eyePos)
                if (poseL.visible) handRenderL.render(proj, view, eyePos, poseL, settings.graphics, timeSec)
                if (poseR.visible) handRenderR.render(proj, view, eyePos, poseR, settings.graphics, timeSec)
                rayRender.draw(
                    proj, view,
                    interaction.leftHand, interaction.rightHand, interaction.headRay,
                    useFallbackRay
                )
            }
        )

        fpsSmooth = fpsSmooth * 0.95f + (1f / dt) * 0.05f
        fpsInt = fpsSmooth.toInt()
    }

    private fun syncHover(panels: List<Panel3D>) {
        val rays = if (interaction.useHeadRayFallback) {
            listOf(interaction.headRay)
        } else {
            listOf(interaction.leftHand, interaction.rightHand)
        }
        for (p in panels) {
            var item: UIItem? = null
            if (p.alphaForHit >= 0.5f) {
                for (r in rays) {
                    if (r.active && r.hoverPanel === p) {
                        item = r.hoverItem
                        break
                    }
                }
            }
            p.setHovered(item, item != null)
        }
    }

    private fun syncArchitectureNodes(qCam: Quat) {
        val map = mapOf(
            "MainMenu" to menus.mainMenu,
            "SettingsMenu" to menus.settingsMenu,
            "AppsMenu" to menus.appsMenu,
            "SystemMenu" to menus.systemMenu
        )
        for ((name, p) in map) {
            val node = menuNodes[name] ?: continue
            node.position.copy(p.currentPos)
            node.rotation.set(p.currentQuat.x, p.currentQuat.y, p.currentQuat.z, p.currentQuat.w)
            node.scale.set(p.currentScale, p.currentScale, p.currentScale)
        }
        cameraRig.rotation.set(qCam.x, qCam.y, qCam.z, qCam.w)
        worldRoot.updateWorld()
    }

    // ------------------------------------------------------------------
    // Actions
    // ------------------------------------------------------------------
    fun calibrateNow() {
        if (head.hasOrientationSensor) {
            head.calibrateNow(head.rawHeadNow())
            Log.i(TAG, "Orientation calibrated: current head pose is now the reference (3DOF)")
        } else {
            sound.play("error")
        }
    }

    private fun applyGraphics() {
        env.rebuildStars(settings.graphics)
        Log.i(TAG, "Graphics preset: ${settings.graphicsName()} (scale ${settings.renderScale()})")
    }

    fun backStep(): Boolean = menus.backStep()

    fun requestTap() {
        interaction.tapRequested = true
    }

    /** Fades back to the launcher screen and tears the engine down. */
    private fun quitToLauncher() {
        Log.i(TAG, "VR engine shutdown")
        glSurface.visibility = View.INVISIBLE
        glSurface.renderMode = android.opengl.GLSurfaceView.RENDERMODE_CONTINUOUSLY
        glReady = false
        activity.onVRQuit()
    }

    fun releaseGL() {
        if (!glReady) return
        if (::env.isInitialized) env.release()
        if (::menus.isInitialized) menus.release()
        if (::handRenderL.isInitialized) handRenderL.release()
        if (::handRenderR.isInitialized) handRenderR.release()
        if (::rayRender.isInitialized) rayRender.release()
        stereo.release()
        glReady = false
    }
}
