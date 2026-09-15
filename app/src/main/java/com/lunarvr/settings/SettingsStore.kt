package com.lunarvr.settings

import android.content.Context

/**
 * Persistent settings (SharedPreferences) for the LUNAR VR system.
 * Fields are @Volatile: the render (GL) thread reads them every frame,
 * the UI writes them from interaction events.
 */
class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("lunarvr_settings", Context.MODE_PRIVATE)

    // ------------------------------------------------------------------
    // VR
    // ------------------------------------------------------------------
    /** 0 = LOW, 1 = MEDIUM, 2 = HIGH */
    @Volatile var graphics = prefs.getInt("graphics", 1)
        private set
    @Volatile var sbs = prefs.getBoolean("sbs", true)
        private set
    /** Eye distance in millimeters (55..75) */
    @Volatile var ipdMm = prefs.getFloat("ipd_mm", 62f)
        private set
    /** Head sensitivity 0.5..1.5 */
    @Volatile var headSensitivity = prefs.getFloat("head_sens", 1f)
        private set
    /** Head smoothing 0..0.9 */
    @Volatile var headSmoothing = prefs.getFloat("head_smooth", 0.35f)
        private set

    // ------------------------------------------------------------------
    // Hand tracking
    // ------------------------------------------------------------------
    @Volatile var handTrackingOn = prefs.getBoolean("ht_on", true)
        private set
    /** 0 = both, 1 = right, 2 = left */
    @Volatile var handSide = prefs.getInt("ht_side", 0)
        private set
    /** Pinch threshold in normalized image units (0.02..0.12) */
    @Volatile var pinchSensitivity = prefs.getFloat("pinch_sens", 0.055f)
        private set
    @Volatile var handSmoothing = prefs.getFloat("hand_smooth", 0.4f)
        private set
    @Volatile var rayEnabled = prefs.getBoolean("ray_on", true)
        private set

    // ------------------------------------------------------------------
    // Audio
    // ------------------------------------------------------------------
    /** 0..100 */
    @Volatile var volume = prefs.getInt("volume", 70)
        private set
    @Volatile var soundsOn = prefs.getBoolean("sounds_on", true)
        private set

    // ------------------------------------------------------------------
    // Setters (write-through)
    // ------------------------------------------------------------------
    fun setGraphics(v: Int) { graphics = v; persist("graphics", v) }
    fun setSbs(v: Boolean) { sbs = v; persistB("sbs", v) }
    fun setIpdMm(v: Float) { ipdMm = v.coerceIn(55f, 75f); persistF("ipd_mm", ipdMm) }
    fun setHeadSensitivity(v: Float) { headSensitivity = v.coerceIn(0.5f, 1.5f); persistF("head_sens", headSensitivity) }
    fun setHeadSmoothing(v: Float) { headSmoothing = v.coerceIn(0f, 0.9f); persistF("head_smooth", headSmoothing) }
    fun setHandTrackingOn(v: Boolean) { handTrackingOn = v; persistB("ht_on", v) }
    fun setHandSide(v: Int) { handSide = v; persist("ht_side", v) }
    fun setPinchSensitivity(v: Float) { pinchSensitivity = v.coerceIn(0.02f, 0.12f); persistF("pinch_sens", pinchSensitivity) }
    fun setHandSmoothing(v: Float) { handSmoothing = v.coerceIn(0f, 0.9f); persistF("hand_smooth", handSmoothing) }
    fun setRayEnabled(v: Boolean) { rayEnabled = v; persistB("ray_on", v) }
    fun setVolume(v: Int) { volume = v.coerceIn(0, 100); persist("volume", volume) }
    fun setSoundsOn(v: Boolean) { soundsOn = v; persistB("sounds_on", v) }

    fun renderScale(): Float = when (graphics) {
        0 -> 0.75f
        2 -> 1f
        else -> 0.85f
    }

    fun graphicsName(): String = when (graphics) {
        0 -> "LOW"
        2 -> "HIGH"
        else -> "MEDIUM"
    }

    fun handSideName(): String = when (handSide) {
        1 -> "DIREITA"
        2 -> "ESQUERDA"
        else -> "AMBAS"
    }

    // ------------------------------------------------------------------
    private fun persist(key: String, value: Int) =
        prefs.edit().putInt(key, value).apply()

    private fun persistF(key: String, value: Float) =
        prefs.edit().putFloat(key, value).apply()

    private fun persistB(key: String, value: Boolean) =
        prefs.edit().putBoolean(key, value).apply()
}
