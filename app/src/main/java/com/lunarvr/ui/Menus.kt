package com.lunarvr.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.opengl.GLES20
import android.opengl.GLES30
import com.lunarvr.audio.SoundBank
import com.lunarvr.hand.HandTracker
import com.lunarvr.interaction.Commands
import com.lunarvr.math.Mat4
import com.lunarvr.math.Quat
import com.lunarvr.math.Vec3
import com.lunarvr.scene.GLUtil
import com.lunarvr.settings.SettingsStore
import com.lunarvr.tracking.HeadTracker
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.abs

/**
 * Builds and manages ALL spatial menus of LUNAR VR:
 *
 *   MainMenu, SettingsMenu, AppsMenu, SystemMenu, AboutMenu,
 *   ClockPanel (floating), TutorialMenu and the two floating pill buttons.
 *
 * Menus belong to the WorldRoot — after opening (placed in front of the
 * current head direction) they NEVER follow the head.
 */
class MenuController(
    private val settings: SettingsStore,
    private val head: HeadTracker,
    private val handTracker: HandTracker,
    private val sound: SoundBank
) {

    // ------------------------------------------------------------------
    // Panels
    // ------------------------------------------------------------------
    lateinit var mainMenu: Panel3D
    lateinit var settingsMenu: Panel3D
    lateinit var appsMenu: Panel3D
    lateinit var systemMenu: Panel3D
    lateinit var aboutMenu: Panel3D
    lateinit var clockPanel: Panel3D
    lateinit var tutorialMenu: Panel3D
    lateinit var floatMenu: Panel3D
    lateinit var floatSystem: Panel3D

    var openPanel: Panel3D? = null
    var openFloating: Panel3D? = null

    private val all = ArrayList<Panel3D>()

    /** All panels, for ray casting / drawing. */
    fun allPanelsList(): List<Panel3D> = all

    // shared GL
    private var panelProgram = 0
    private var uModel = 0
    private var uView = 0
    private var uProj = 0
    private var uTex = 0
    private var uAlpha = 0
    private var shadowTex = 0

    // settings rows
    private lateinit var itemSbs: UIItem
    private lateinit var itemIpd: UIItem
    private lateinit var itemHeadSens: UIItem
    private lateinit var itemHeadSmooth: UIItem
    private lateinit var itemHt: UIItem
    private lateinit var itemHandSide: UIItem
    private lateinit var itemPinch: UIItem
    private lateinit var itemHandSmooth: UIItem
    private lateinit var itemRay: UIItem
    private lateinit var itemVolume: UIItem
    private lateinit var itemSounds: UIItem
    private lateinit var itemGfxLow: UIItem
    private lateinit var itemGfxMed: UIItem
    private lateinit var itemGfxHigh: UIItem

    private lateinit var itemSysMotor: UIItem
    private lateinit var itemSys3dof: UIItem
    private lateinit var itemSysGyro: UIItem
    private lateinit var itemSysHand: UIItem
    private lateinit var itemSysRender: UIItem
    private lateinit var itemSysFps: UIItem
    private lateinit var itemSysSession: UIItem

    private lateinit var itemClockTime: UIItem
    private lateinit var itemClockDate: UIItem

    private var lastClockSecond = -1
    private var lastSysUpdate = 0f
    private var elapsedSec = 0f

    // callback into VRManager (graphics apply, head calibrate, quit, fps)
    var onApplyGraphics: (() -> Unit)? = null
    var onCalibrate: (() -> Unit)? = null
    var onQuit: (() -> Unit)? = null
    var fpsProvider: (() -> Int)? = null

    // ------------------------------------------------------------------
    // Building
    // ------------------------------------------------------------------
    fun buildAll() {
        buildMainMenu()
        buildSettingsMenu()
        buildAppsMenu()
        buildSystemMenu()
        buildAboutMenu()
        buildClockPanel()
        buildTutorialMenu()
        buildFloatingButtons()
    }

    fun initGL() {
        if (panelProgram != 0) return
        val vs = """
            attribute vec3 aPos;
            attribute vec2 aUv;
            uniform mat4 uModel;
            uniform mat4 uView;
            uniform mat4 uProj;
            varying vec2 vUv;
            void main() {
                vUv = aUv;
                gl_Position = uProj * uView * uModel * vec4(aPos, 1.0);
            }
        """.trimIndent()
        val fs = """
            precision mediump float;
            varying vec2 vUv;
            uniform sampler2D uTex;
            uniform float uAlpha;
            void main() {
                vec4 c = texture2D(uTex, vUv);
                gl_FragColor = vec4(c.rgb, c.a * uAlpha);
            }
        """.trimIndent()
        panelProgram = GLUtil.createProgram(vs, fs, "aPos" to 0, "aUv" to 1)
        uModel = GLES20.glGetUniformLocation(panelProgram, "uModel")
        uView = GLES20.glGetUniformLocation(panelProgram, "uView")
        uProj = GLES20.glGetUniformLocation(panelProgram, "uProj")
        uTex = GLES20.glGetUniformLocation(panelProgram, "uTex")
        uAlpha = GLES20.glGetUniformLocation(panelProgram, "uAlpha")

        // soft radial shadow texture (shared by all panels)
        val size = 256
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = RadialGradient(
            size / 2f, size / 2f, size / 2f,
            intArrayOf(0x66000000.toInt(), 0x33000000.toInt(), 0x00000000),
            floatArrayOf(0.15f, 0.6f, 1f),
            Shader.TileMode.CLAMP
        )
        cv.drawRoundRect(20f, 20f, size - 20f, size - 20f, 40f, 40f, paint)
        shadowTex = GLUtil.createTexture(size, size)
        GLUtil.uploadBitmap(shadowTex, bmp)
        bmp.recycle()

        for (p in all) {
            p.initGL()
            p.uploadGL()
        }
    }

    private fun makePanel(
        title: String, subtitle: String, icon: Int,
        wM: Float, hM: Float, texW: Int, texH: Int
    ): Panel3D {
        val p = Panel3D(title, subtitle, icon, wM, hM, texW, texH)
        all.add(p)
        p.prepare()
        return p
    }

    // ------------------------------------------------------------------
    // MainMenu
    // ------------------------------------------------------------------
    private fun buildMainMenu() {
        mainMenu = makePanel("LUNAR VR", "sistema operacional VR • 3DOF", Icons.MOON, 1.30f, 0.78f, 1024, 608)
        val specs = arrayOf(
            Triple("INICIAR", Icons.PLAY, Commands.START),
            Triple("APLICATIVOS", Icons.GRID, Commands.OPEN_APPS),
            Triple("CONFIGURAÇÕES", Icons.GEAR, Commands.OPEN_SETTINGS),
            Triple("FECHAR", Icons.POWER, Commands.QUIT)
        )
        var y = 176
        for ((label, icon, cmd) in specs) {
            val item = UIItem()
            item.kind = UIItem.KIND_BUTTON
            item.label = label
            item.icon = icon
            item.command = cmd
            item.rect = Rect(112f, y.toFloat(), 912f, (y + 82).toFloat())
            mainMenu.add(item)
            y += 100
        }
    }

    // ------------------------------------------------------------------
    // SettingsMenu
    // ------------------------------------------------------------------
    private fun buildSettingsMenu() {
        settingsMenu = makePanel("CONFIGURAÇÕES", "VR • Hand Tracking • Gráficos • Áudio", Icons.GEAR, 1.35f, 1.05f, 1024, 800)
        val m = settingsMenu

        // ---- VR column
        m.add(section(64f, 160f, 300f, 26f, "VR"))
        itemSbs = UIItem().also {
            it.kind = UIItem.KIND_TOGGLE; it.label = "SBS (Side-by-Side)"
            it.command = Commands.TOGGLE_SBS; it.boolValue = settings.sbs
            it.rect = Rect(64f, 196f, 496f, 250f)
        }
        m.add(itemSbs)
        itemIpd = UIItem().also {
            it.setSlider(55f, 75f, settings.ipdMm, 1, "mm"); it.label = "IPD"
            it.command = Commands.SLIDER_IPD
            it.rect = Rect(64f, 266f, 496f, 320f)
        }
        m.add(itemIpd)
        itemHeadSens = UIItem().also {
            it.setSlider(0.5f, 1.5f, settings.headSensitivity, 2, ""); it.label = "SENS. DA CABEÇA"
            it.command = Commands.SLIDER_HEAD_SENS
            it.rect = Rect(64f, 336f, 496f, 390f)
        }
        m.add(itemHeadSens)
        itemHeadSmooth = UIItem().also {
            it.setSlider(0f, 0.9f, settings.headSmoothing, 2, ""); it.label = "SUAVIZAÇÃO"
            it.command = Commands.SLIDER_HEAD_SMOOTH
            it.rect = Rect(64f, 406f, 496f, 460f)
        }
        m.add(itemHeadSmooth)
        m.add(button(64f, 476f, 432f, 58f, "CALIBRAR VISÃO", Icons.HEAD, Commands.CALIBRATE))
        m.add(button(64f, 548f, 432f, 58f, "RESET ORIENTAÇÃO", Icons.BACK, Commands.RESET_ORIENT))

        // ---- Hand Tracking column
        m.add(section(528f, 160f, 432f, 26f, "HAND TRACKING"))
        itemHt = UIItem().also {
            it.kind = UIItem.KIND_TOGGLE; it.label = "HAND TRACKING"
            it.command = Commands.TOGGLE_HT; it.boolValue = settings.handTrackingOn
            it.rect = Rect(528f, 196f, 960f, 250f)
        }
        m.add(itemHt)
        itemHandSide = UIItem().also {
            it.kind = UIItem.KIND_VALUE; it.label = "MÃO"
            it.options = listOf("AMBAS", "DIREITA", "ESQUERDA")
            it.optionIndex = settings.handSide
            it.command = Commands.HAND_SIDE
            it.rect = Rect(528f, 266f, 960f, 320f)
        }
        m.add(itemHandSide)
        itemPinch = UIItem().also {
            it.setSlider(0.02f, 0.12f, settings.pinchSensitivity, 3, ""); it.label = "SENS. PINCH"
            it.command = Commands.SLIDER_PINCH
            it.rect = Rect(528f, 336f, 960f, 390f)
        }
        m.add(itemPinch)
        itemHandSmooth = UIItem().also {
            it.setSlider(0f, 0.9f, settings.handSmoothing, 2, ""); it.label = "SUAV. DA MÃO"
            it.command = Commands.SLIDER_HAND_SMOOTH
            it.rect = Rect(528f, 406f, 960f, 460f)
        }
        m.add(itemHandSmooth)
        itemRay = UIItem().also {
            it.kind = UIItem.KIND_TOGGLE; it.label = "RAY"
            it.command = Commands.TOGGLE_RAY; it.boolValue = settings.rayEnabled
            it.rect = Rect(528f, 476f, 960f, 530f)
        }
        m.add(itemRay)

        // ---- Áudio
        m.add(section(528f, 566f, 432f, 26f, "ÁUDIO"))
        itemVolume = UIItem().also {
            it.setSlider(0f, 100f, settings.volume.toFloat(), 0, ""); it.label = "VOLUME"
            it.command = Commands.SLIDER_VOLUME
            it.rect = Rect(528f, 590f, 960f, 644f)
        }
        m.add(itemVolume)
        itemSounds = UIItem().also {
            it.kind = UIItem.KIND_TOGGLE; it.label = "SONS"
            it.command = Commands.TOGGLE_SOUNDS; it.boolValue = settings.soundsOn
            it.rect = Rect(528f, 658f, 960f, 712f)
        }
        m.add(itemSounds)

        // ---- Gráficos
        m.add(section(64f, 730f, 432f, 26f, "GRÁFICOS"))
        itemGfxLow = UIItem().also {
            it.kind = UIItem.KIND_BUTTON; it.label = "LOW"; it.command = Commands.GRAPHICS; it.commandArg = 0
            it.rect = Rect(64f, 748f, 352f, 796f)
        }
        m.add(itemGfxLow)
        itemGfxMed = UIItem().also {
            it.kind = UIItem.KIND_BUTTON; it.label = "MEDIUM"; it.command = Commands.GRAPHICS; it.commandArg = 1
            it.rect = Rect(368f, 748f, 656f, 796f)
        }
        m.add(itemGfxMed)
        itemGfxHigh = UIItem().also {
            it.kind = UIItem.KIND_BUTTON; it.label = "HIGH"; it.command = Commands.GRAPHICS; it.commandArg = 2
            it.rect = Rect(672f, 748f, 960f, 796f)
        }
        m.add(itemGfxHigh)
    }

    // ------------------------------------------------------------------
    // AppsMenu
    // ------------------------------------------------------------------
    private fun buildAppsMenu() {
        appsMenu = makePanel("APLICATIVOS", "experiências embarcadas", Icons.GRID, 1.30f, 0.72f, 1024, 560)
        val m = appsMenu
        val tiles = arrayOf(
            Triple("RELÓGIO", Icons.CLOCK, Commands.OPEN_CLOCK),
            Triple("SOBRE", Icons.INFO, Commands.OPEN_ABOUT),
            Triple("SISTEMA", Icons.CHIP, Commands.OPEN_SYSTEM),
            Triple("TUTORIAL", Icons.HAND, Commands.OPEN_TUTORIAL)
        )
        var i = 0
        for (row in 0..1) for (col in 0..1) {
            val t = tiles[i++]
            val x = if (col == 0) 64f else 528f
            val y = if (row == 0) 180f else 354f
            val item = UIItem()
            item.kind = UIItem.KIND_BUTTON
            item.label = t.first
            item.icon = t.second
            item.command = t.third
            item.rect = Rect(x, y, x + 432f, y + 150f)
            m.add(item)
        }
    }

    // ------------------------------------------------------------------
    // SystemMenu
    // ------------------------------------------------------------------
    private fun buildSystemMenu() {
        systemMenu = makePanel("SISTEMA", "status do motor VR", Icons.CHIP, 1.30f, 0.86f, 1024, 672)
        val m = systemMenu
        val rows = arrayOf(
            "MOTOR VR" to "ATIVO",
            "3DOF" to "—",
            "GIROSCÓPIO" to "—",
            "HAND TRACKING" to "—",
            "RENDER" to "—",
            "FPS" to "—",
            "SESSÃO" to "00:00"
        )
        var y = 168
        for ((label, value) in rows) {
            val it = UIItem()
            it.kind = UIItem.KIND_LABEL
            it.label = label
            it.valueText = value
            it.rect = Rect(64f, y.toFloat(), 960f, (y + 52).toFloat())
            m.add(it)
            when (label) {
                "MOTOR VR" -> itemSysMotor = it
                "3DOF" -> itemSys3dof = it
                "GIROSCÓPIO" -> itemSysGyro = it
                "HAND TRACKING" -> itemSysHand = it
                "RENDER" -> itemSysRender = it
                "FPS" -> itemSysFps = it
                "SESSÃO" -> itemSysSession = it
            }
            y += 56
        }
        m.add(button(64f, 584f, 432f, 56f, "MENU PRINCIPAL", Icons.LINES, Commands.OPEN_MAIN))
        m.add(button(528f, 584f, 432f, 56f, "FECHAR LUNAR VR", Icons.POWER, Commands.QUIT))
    }

    // ------------------------------------------------------------------
    // AboutMenu
    // ------------------------------------------------------------------
    private fun buildAboutMenu() {
        aboutMenu = makePanel("SOBRE", "LUNAR VR", Icons.INFO, 1.30f, 0.82f, 1024, 640)
        aboutMenu.showHeader = false
        aboutMenu.add(label(120f, 120f, 784f, 60f, "LUNAR VR", 56f))
        aboutMenu.add(label(120f, 200f, 784f, 50f, "versão 1.0.0", 28f))
        aboutMenu.add(label(120f, 300f, 784f, 44f, "Motor VR próprio • 3DOF • Side-by-Side", 25f))
        aboutMenu.add(label(120f, 352f, 784f, 44f, "Hand tracking real: 21 landmarks por mão", 25f))
        aboutMenu.add(label(120f, 404f, 784f, 44f, "Projetado para VR Box e Cardboard", 25f))
        aboutMenu.add(button(352f, 490f, 320f, 64f, "VOLTAR", Icons.BACK, Commands.CLOSE))
    }

    // ------------------------------------------------------------------
    // ClockPanel (floating)
    // ------------------------------------------------------------------
    private fun buildClockPanel() {
        clockPanel = makePanel("", "", Icons.CLOCK, 0.62f, 0.39f, 512, 320)
        clockPanel.showHeader = false
        itemClockTime = UIItem().also {
            it.kind = UIItem.KIND_CLOCK
            it.valueText = "--:--:--"
            it.rect = Rect(20f, 24f, 492f, 176f)
        }
        clockPanel.add(itemClockTime)
        itemClockDate = UIItem().also {
            it.kind = UIItem.KIND_LABEL
            it.label = " "
            it.rect = Rect(20f, 196f, 492f, 246f)
        }
        clockPanel.add(itemClockDate)
        clockPanel.add(button(140f, 258f, 232f, 46f, "FECHAR", Icons.BACK, Commands.CLOSE_FLOATING))
    }

    // ------------------------------------------------------------------
    // TutorialMenu
    // ------------------------------------------------------------------
    private fun buildTutorialMenu() {
        tutorialMenu = makePanel("TUTORIAL", "como interagir", Icons.HAND, 1.30f, 0.87f, 1024, 680)
        val m = tutorialMenu
        val rows = arrayOf(
            Icons.POINT to "Aponte com o dedo indicador — o ray aparece",
            Icons.RAY to "Pouse o ray em um botão para destacar (hover)",
            Icons.PINCH to "PINCH (polegar + indicador) seleciona",
            Icons.HEAD to "Gire a cabeça para olhar — os menus ficam no lugar",
            Icons.GEAR to "Ajuste SBS, IPD e gráficos em CONFIGURAÇÕES"
        )
        var y = 168
        for ((icon, text) in rows) {
            val it = UIItem()
            it.kind = UIItem.KIND_LABEL
            it.icon = icon
            it.label = text
            it.rect = Rect(64f, y.toFloat(), 960f, (y + 70).toFloat())
            m.add(it)
            y += 78
        }
        m.add(button(352f, 584f, 320f, 60f, "VOLTAR", Icons.BACK, Commands.CLOSE))
    }

    // ------------------------------------------------------------------
    // Floating pill buttons (always in the WorldRoot)
    // ------------------------------------------------------------------
    private fun buildFloatingButtons() {
        floatMenu = makePanel("", "", Icons.LINES, 0.26f, 0.10f, 256, 96)
        floatMenu.showHeader = false
        floatMenu.add(pillButton(Icons.LINES, "MENU", Commands.OPEN_MAIN))
        floatMenu.pos = Vec3(-0.135f, 0.32f, -1.05f)
        faceOrigin(floatMenu)
        showInstantly(floatMenu)

        floatSystem = makePanel("", "", Icons.CHIP, 0.26f, 0.10f, 256, 96)
        floatSystem.showHeader = false
        floatSystem.add(pillButton(Icons.CHIP, "SISTEMA", Commands.OPEN_SYSTEM))
        floatSystem.pos = Vec3(0.135f, 0.32f, -1.05f)
        faceOrigin(floatSystem)
        showInstantly(floatSystem)
    }

    /** Floating UI: visible from the start (no open animation). */
    private fun showInstantly(p: Panel3D) {
        p.state = Panel3D.STATE_OPEN
        p.animT = 1f
    }

    private fun pillButton(icon: Int, label: String, cmd: Int): UIItem {
        val item = UIItem()
        item.kind = UIItem.KIND_BUTTON
        item.icon = icon
        item.label = label
        item.command = cmd
        item.rect = Rect(10f, 12f, 246f, 84f)
        return item
    }

    private fun faceOrigin(p: Panel3D) {
        p.yaw = atan2(-p.pos.x.toDouble(), -p.pos.z.toDouble()).toFloat()
    }

    // ------------------------------------------------------------------
    // Item helpers
    // ------------------------------------------------------------------
    private fun Rect(l: Float, t: Float, r: Float, b: Float) = android.graphics.RectF(l, t, r, b)

    private fun section(x: Float, y: Float, w: Float, h: Float, text: String): UIItem {
        val it = UIItem()
        it.kind = UIItem.KIND_SECTION
        it.label = text
        it.rect = Rect(x, y, x + w, y + h)
        return it
    }

    private fun button(x: Float, y: Float, w: Float, h: Float, label: String, icon: Int, cmd: Int): UIItem {
        val it = UIItem()
        it.kind = UIItem.KIND_BUTTON
        it.label = label
        it.icon = icon
        it.command = cmd
        it.rect = Rect(x, y, x + w, y + h)
        return it
    }

    private fun label(x: Float, y: Float, w: Float, h: Float, text: String, size: Float): UIItem {
        val it = UIItem()
        it.kind = UIItem.KIND_LABEL
        it.label = text
        it.fontSize = size
        it.centered = true
        it.rect = Rect(x, y, x + w, y + h)
        return it
    }

    // ------------------------------------------------------------------
    // Open / close
    // ------------------------------------------------------------------
    fun openPanelNow(panel: Panel3D, cameraQ: Quat) {
        if (openPanel !== panel) {
            openPanel?.closeNow()
        }
        if (panel === clockPanel) {
            openFloating = panel
            val fwd = cameraQ.rotate(Vec3(0f, 0f, -1f))
            val up = cameraQ.rotate(Vec3(0f, 1f, 0f))
            panel.pos.set(
                fwd.x * 1.25f + up.x * 0.30f,
                fwd.y * 1.25f + up.y * 0.30f + 0.12f,
                fwd.z * 1.25f + up.z * 0.30f
            )
            panel.yaw = atan2(-panel.pos.x.toDouble(), -panel.pos.z.toDouble()).toFloat()
            panel.state = Panel3D.STATE_OPENING
            panel.animT = 0.02f
            sound.play("open")
            return
        }
        openFloating = null
        openPanel = panel
        val fwd = cameraQ.rotate(Vec3(0f, 0f, -1f))
        panel.openAt(fwd)
        sound.play("open")
    }

    fun closeCurrent() {
        openPanel?.closeNow()
        openPanel = null
        if (openFloating != null) {
            openFloating!!.closeNow()
            openFloating = null
            sound.play("close")
        }
    }

    fun closeFloating() {
        if (openFloating != null) {
            openFloating!!.closeNow()
            openFloating = null
            sound.play("close")
        }
    }

    fun anyOpen(): Boolean {
        for (p in all) if (p.isOpen()) return true
        return false
    }

    /** Which panel (if any) contains an open state — for back handling. */
    fun backStep(): Boolean {
        if (openFloating != null) { closeFloating(); return true }
        if (openPanel != null) {
            openPanel!!.closeNow()
            openPanel = null
            sound.play("close")
            return true
        }
        return false
    }

    // ------------------------------------------------------------------
    // Per-frame update (GL thread)
    // ------------------------------------------------------------------
    fun update(dt: Float, cameraQ: Quat) {
        elapsedSec += dt
        for (p in all) p.update(dt)

        // clock panel live time
        if (clockPanel.isOpen()) {
            val now = Date()
            val sec = now.time / 1000
            if (sec != lastClockSecond) {
                lastClockSecond = sec
                itemClockTime.valueText = timeFmt.format(now)
                itemClockDate.label = " " + dateFmt.format(now)
                clockPanel.texture.redrawItem(itemClockTime)
                clockPanel.texture.redrawItem(itemClockDate)
            }
        }

        // system panel status (0.5 s cadence)
        if (systemMenu.isOpen() && elapsedSec - lastSysUpdate > 0.5f) {
            lastSysUpdate = elapsedSec
            updateSystemRows()
        }
    }

    private fun updateSystemRows() {
        setRow(itemSys3dof, if (head.source == HeadTracker.Source.NONE) "ORIENTAÇÃO FIXA" else "ATIVO")
        setRow(itemSysGyro, head.statusText)
        val hand = if (!settings.handTrackingOn) "DESATIVADO"
        else handTracker.status.label.uppercase()
        setRow(itemSysHand, hand)
        setRow(itemSysRender,
            "SBS ${if (settings.sbs) "ON" else "OFF"} • ${(settings.renderScale() * 100).toInt()}% • ${settings.graphicsName()}")
        setRow(itemSysFps, (fpsProvider?.invoke() ?: 0).toString())
        val mm = (elapsedSec / 60f).toInt()
        val ss = (elapsedSec % 60f).toInt()
        setRow(itemSysSession, "%02d:%02d".format(mm, ss))
    }

    private fun setRow(item: UIItem, text: String) {
        if (item.valueText == text) return
        item.valueText = text
        systemMenu.texture.redrawItem(item)
    }

    // ------------------------------------------------------------------
    // Interaction
    // ------------------------------------------------------------------
    fun onHoverChanged(item: UIItem?) {
        if (item != null) sound.play("hover")
    }

    fun onCommand(cmd: Int, arg: Int, cameraQ: Quat) {
        when (cmd) {
            Commands.START -> closeCurrent()
            Commands.OPEN_APPS -> openPanelNow(appsMenu, cameraQ)
            Commands.OPEN_SETTINGS -> openPanelNow(settingsMenu, cameraQ)
            Commands.OPEN_SYSTEM -> openPanelNow(systemMenu, cameraQ)
            Commands.OPEN_ABOUT -> openPanelNow(aboutMenu, cameraQ)
            Commands.OPEN_TUTORIAL -> openPanelNow(tutorialMenu, cameraQ)
            Commands.OPEN_CLOCK -> openPanelNow(clockPanel, cameraQ)
            Commands.CLOSE -> {
                if (openFloating != null) closeFloating() else closeCurrent()
            }
            Commands.CLOSE_FLOATING -> closeFloating()
            Commands.QUIT -> {
                sound.play("close")
                onQuit?.invoke()
            }
            Commands.OPEN_MAIN -> openPanelNow(mainMenu, cameraQ)
            Commands.CALIBRATE, Commands.RESET_ORIENT -> {
                onCalibrate?.invoke()
                sound.play("toggle")
            }
            Commands.TOGGLE_SBS -> {
                val v = !settings.sbs
                settings.setSbs(v)
                itemSbs.boolValue = v
                settingsMenu.texture.redrawItem(itemSbs)
                sound.play("toggle")
            }
            Commands.TOGGLE_HT -> {
                val v = !settings.handTrackingOn
                settings.setHandTrackingOn(v)
                itemHt.boolValue = v
                itemHandSide.enabled = v
                itemPinch.enabled = v
                itemHandSmooth.enabled = v
                itemRay.enabled = v
                settingsMenu.texture.redrawItem(itemHt)
                settingsMenu.texture.redrawItem(itemHandSide)
                settingsMenu.texture.redrawItem(itemPinch)
                settingsMenu.texture.redrawItem(itemHandSmooth)
                settingsMenu.texture.redrawItem(itemRay)
                sound.play("toggle")
                onHandTrackingToggled?.invoke(v)
            }
            Commands.TOGGLE_RAY -> {
                val v = !settings.rayEnabled
                settings.setRayEnabled(v)
                itemRay.boolValue = v
                settingsMenu.texture.redrawItem(itemRay)
                sound.play("toggle")
            }
            Commands.TOGGLE_SOUNDS -> {
                val v = !settings.soundsOn
                settings.setSoundsOn(v)
                sound.enabled = v
                itemSounds.boolValue = v
                settingsMenu.texture.redrawItem(itemSounds)
                if (v) sound.play("toggle")
            }
            Commands.HAND_SIDE -> {
                itemHandSide.cycleValue()
                settings.setHandSide(itemHandSide.optionIndex)
                settingsMenu.texture.redrawItem(itemHandSide)
                sound.play("toggle")
            }
            Commands.GRAPHICS -> {
                settings.setGraphics(arg)
                sound.play("toggle")
                onApplyGraphics?.invoke()
            }
            Commands.SLIDER_IPD -> applySlider(itemIpd) { v -> settings.setIpdMm(v) }
            Commands.SLIDER_HEAD_SENS -> applySlider(itemHeadSens) { v -> settings.setHeadSensitivity(v) }
            Commands.SLIDER_HEAD_SMOOTH -> applySlider(itemHeadSmooth) { v -> settings.setHeadSmoothing(v) }
            Commands.SLIDER_PINCH -> applySlider(itemPinch) { v -> settings.setPinchSensitivity(v) }
            Commands.SLIDER_HAND_SMOOTH -> applySlider(itemHandSmooth) { v -> settings.setHandSmoothing(v) }
            Commands.SLIDER_VOLUME -> applySlider(itemVolume) { v ->
                settings.setVolume(v.toInt())
                sound.setVolume(settings.volume)
            }
        }
    }

    private fun applySlider(item: UIItem, apply: (Float) -> Unit) {
        apply(item.floatValue)
        item.valueText = item.formatValue()
        settingsMenu.texture.redrawItem(item)
    }

    fun onSliderLive(item: UIItem, t01: Float) {
        val v = item.floatMin + (item.floatMax - item.floatMin) * t01
        item.floatValue = v
        when (item.command) {
            Commands.SLIDER_IPD -> applySlider(item) { x -> settings.setIpdMm(x) }
            Commands.SLIDER_HEAD_SENS -> applySlider(item) { x -> settings.setHeadSensitivity(x) }
            Commands.SLIDER_HEAD_SMOOTH -> applySlider(item) { x -> settings.setHeadSmoothing(x) }
            Commands.SLIDER_PINCH -> applySlider(item) { x -> settings.setPinchSensitivity(x) }
            Commands.SLIDER_HAND_SMOOTH -> applySlider(item) { x -> settings.setHandSmoothing(x) }
            Commands.SLIDER_VOLUME -> applySlider(item) { x ->
                settings.setVolume(x.toInt())
                sound.setVolume(settings.volume)
            }
        }
    }

    fun onSliderEnd(item: UIItem) {
        sound.play("toggle")
    }

    // ------------------------------------------------------------------
    // Draw
    // ------------------------------------------------------------------
    fun drawAll(proj: Mat4, view: Mat4, eyePos: Vec3) {
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDepthMask(false)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        // far -> near for correct alpha blending
        val sorted = all.sortedByDescending { p ->
            if (p.state == Panel3D.STATE_HIDDEN) -1f
            else p.currentPos.distanceTo(eyePos)
        }
        for (p in sorted) {
            p.draw(proj, view, panelProgram, uModel, uView, uProj, uTex, uAlpha, shadowTex)
        }
        GLES20.glDepthMask(true)
    }

    /** GL context was destroyed: objects are gone, just reset the ids. */
    fun contextLost() {
        panelProgram = 0
        shadowTex = 0
        for (p in all) p.contextLost()
    }

    fun release() {
        for (p in all) p.release()
        if (panelProgram != 0) GLES20.glDeleteProgram(panelProgram)
        if (shadowTex != 0) GLES20.glDeleteTextures(1, intArrayOf(shadowTex), 0)
        panelProgram = 0; shadowTex = 0
    }

    var onHandTrackingToggled: ((Boolean) -> Unit)? = null

    companion object {
        private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale("pt", "BR"))
        private val dateFmt = SimpleDateFormat("EEEE, dd 'de' MMMM", Locale("pt", "BR"))
    }
}
