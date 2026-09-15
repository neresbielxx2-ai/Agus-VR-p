package com.lunarvr.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import java.io.File
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * Interaction sound effects, synthesized at runtime (no audio assets).
 * Sounds are generated as short PCM WAV files in the cache directory and
 * played through a SoundPool. Volume and enable/disable come from Settings.
 */
class SoundBank(context: Context) {

    private val pool = SoundPool.Builder()
        .setMaxStreams(4)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()

    private val ids = HashMap<String, Int>()
    @Volatile private var volume = 0.7f
    @Volatile var enabled = true

    fun init() {
        val dir = File(context.cacheDir, "lunarvr_audio")
        dir.mkdirs()
        val freq = 22050
        load(dir, "hover", hoverWav(freq), freq)
        load(dir, "click", clickWav(freq), freq)
        load(dir, "open", openWav(freq), freq)
        load(dir, "close", closeWav(freq), freq)
        load(dir, "toggle", toggleWav(freq), freq)
        load(dir, "error", errorWav(freq), freq)
    }

    private fun load(dir: File, name: String, samples: ShortArray, freq: Int) {
        val f = File(dir, "$name.wav")
        writeWav(f, samples, freq)
        val id = pool.load(f.absolutePath, 1)
        ids[name] = id
    }

    fun play(name: String) {
        if (!enabled) return
        val id = ids[name] ?: return
        if (id <= 0) return
        pool.play(id, volume, volume, 1, 0)
    }

    fun setVolume(pct: Int) {
        volume = (pct / 100f) * 0.9f
    }

    fun release() {
        pool.release()
        ids.clear()
    }

    // ------------------------------------------------------------------
    // Waveform synthesis
    // ------------------------------------------------------------------
    private fun env(t: Float, dur: Float, attack: Float = 0.004f): Float {
        val a = min(t / attack, 1f)
        val d = exp(-(t / dur) * 5f)
        return a * d
    }

    private fun hoverWav(freq: Int): ShortArray {
        val dur = 0.05
        val n = (freq * dur).toInt()
        val out = ShortArray(n)
        for (i in 0 until n) {
            val t = i.toFloat() / freq
            out[i] = (sin(2 * PI * 1500 * t) * 0.16 * env(t, dur)).toInt().toShort()
        }
        return out
    }

    private fun clickWav(freq: Int): ShortArray {
        val dur = 0.12
        val n = (freq * dur).toInt()
        val out = ShortArray(n)
        for (i in 0 until n) {
            val t = i.toFloat() / freq
            val f = 640f + (1250f - 640f) * (t / dur)
            out[i] = (sin(2 * PI * f * t) * 0.42 * env(t, dur)).toInt().toShort()
        }
        return out
    }

    private fun openWav(freq: Int): ShortArray {
        val dur = 0.20
        val n = (freq * dur).toInt()
        val out = ShortArray(n)
        for (i in 0 until n) {
            val t = i.toFloat() / freq
            val f = 320f + (760f - 320f) * (t / dur)
            val s = sin(2 * PI * f * t) * 0.34 + sin(2 * PI * f * 2 * t) * 0.08
            out[i] = (s * env(t, dur)).toInt().toShort()
        }
        return out
    }

    private fun closeWav(freq: Int): ShortArray {
        val dur = 0.17
        val n = (freq * dur).toInt()
        val out = ShortArray(n)
        for (i in 0 until n) {
            val t = i.toFloat() / freq
            val f = 720f - (720f - 300f) * (t / dur)
            out[i] = (sin(2 * PI * f * t) * 0.30 * env(t, dur)).toInt().toShort()
        }
        return out
    }

    private fun toggleWav(freq: Int): ShortArray {
        val dur = 0.11
        val n = (freq * dur).toInt()
        val out = ShortArray(n)
        for (i in 0 until n) {
            val t = i.toFloat() / freq
            val f = if (t < dur * 0.45f) 880f else 1320f
            val tt = if (t < dur * 0.45f) t else t - dur * 0.45f
            out[i] = (sin(2 * PI * f * tt) * 0.28 * env(tt, dur * 0.45f)).toInt().toShort()
        }
        return out
    }

    private fun errorWav(freq: Int): ShortArray {
        val dur = 0.18
        val n = (freq * dur).toInt()
        val out = ShortArray(n)
        for (i in 0 until n) {
            val t = i.toFloat() / freq
            val s = sin(2 * PI * 220 * t) * 0.30 + sin(2 * PI * 233 * t) * 0.12
            out[i] = (s * env(t, dur, 0.008f)).toInt().toShort()
        }
        return out
    }

    // ------------------------------------------------------------------
    // Minimal WAV writer (16-bit PCM mono)
    // ------------------------------------------------------------------
    private fun writeWav(file: File, samples: ShortArray, sampleRate: Int) {
        val dataLen = samples.size * 2
        val out = ByteArray(44 + dataLen)
        fun putStr(off: Int, s: String) {
            for (i in s.indices) out[off + i] = s[i].toByte()
        }
        fun putInt(off: Int, v: Int) {
            out[off] = (v and 0xFF).toByte()
            out[off + 1] = ((v shr 8) and 0xFF).toByte()
            out[off + 2] = ((v shr 16) and 0xFF).toByte()
            out[off + 3] = ((v shr 24) and 0xFF).toByte()
        }
        fun putShort(off: Int, v: Int) {
            out[off] = (v and 0xFF).toByte()
            out[off + 1] = ((v shr 8) and 0xFF).toByte()
        }
        putStr(0, "RIFF")
        putInt(4, 36 + dataLen)
        putStr(8, "WAVE")
        putStr(12, "fmt ")
        putInt(16, 16)
        putShort(20, 1)
        putShort(22, 1)
        putInt(24, sampleRate)
        putInt(28, sampleRate * 2)
        putShort(32, 2)
        putShort(34, 16)
        putStr(36, "data")
        putInt(40, dataLen)
        for (i in samples.indices) {
            out[44 + i * 2] = (samples[i].toInt() and 0xFF).toByte()
            out[45 + i * 2] = ((samples[i].toInt() shr 8) and 0xFF).toByte()
        }
        file.writeBytes(out)
    }
}
