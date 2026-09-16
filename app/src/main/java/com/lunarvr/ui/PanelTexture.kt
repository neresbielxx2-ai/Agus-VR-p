package com.lunarvr.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import com.lunarvr.scene.GLUtil
import kotlin.math.max

/**
 * Canvas-backed texture of a spatial menu panel: dark glass background with
 * subtle border, title block, and the interactive items.
 *
 * Rendering is a full redraw on creation and small region uploads
 * (glTexSubImage2D) on state changes (hover, toggle, slider) — cheap.
 */
class PanelTexture(val widthPx: Int, val heightPx: Int, private val titleIcon: Int = Icons.NONE) {

    val bitmap: Bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
    val canvas: Canvas = Canvas(bitmap)
    var texId: Int = 0
        private set

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun initGL() {
        if (texId == 0) texId = GLUtil.createTexture(widthPx, heightPx)
    }

    fun fullUpload() {
        initGL()
        GLUtil.uploadBitmap(texId, bitmap)
    }

    fun regionUpload(rect: Rect) {
        initGL()
        val w = rect.width()
        val h = rect.height()
        if (w <= 0 || h <= 0) return
        // Crop the exact region (returns a fresh ARGB_8888 bitmap) and upload
        // it at its texture offset — see GLUtil.uploadBitmapRegion.
        val crop = Bitmap.createBitmap(bitmap, rect.left, rect.top, w, h)
        GLUtil.uploadBitmapRegion(texId, crop, rect.left, rect.top)
        crop.recycle()
    }

    /** Clears a region to transparent. */
    fun clearRegion(r: RectF) {
        canvas.save()
        canvas.clipRect(r)
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        canvas.restore()
    }

    // ------------------------------------------------------------------
    // Background
    // ------------------------------------------------------------------
    fun drawBackground() {
        val w = widthPx.toFloat()
        val h = heightPx.toFloat()
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        paint.style = Paint.Style.FILL
        paint.shader = LinearGradient(
            0f, 0f, 0f, h,
            0xF2263250.toInt(), 0xEC141A2E.toInt(),
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(2f, 2f, w - 2f, h - 2f, 44f, 44f, paint)
        paint.shader = null
        // discreet glass border
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f
        paint.color = 0x5A7E9CC8.toInt()
        canvas.drawRoundRect(4f, 4f, w - 4f, h - 4f, 42f, 42f, paint)
        // soft top highlight
        paint.style = Paint.Style.FILL
        paint.color = 0x14FFFFFF
        canvas.drawRoundRect(60f, 8f, w - 60f, 11f, 3f, 3f, paint)
    }

    /** Header: small icon + title + optional subtitle + divider. */
    fun drawHeader(title: String, subtitle: String) {
        val iconSize = 40f
        Icons.draw(canvas, titleIcon, 64f, 50f, iconSize, 0xCCE3ECFF.toInt())
        paint.textSize = 40f
        paint.style = Paint.Style.FILL
        paint.color = 0xF5F2F6FF.toInt()
        paint.isFakeBoldText = true
        canvas.drawText(title, 124f, 86f, paint)
        if (subtitle.isNotEmpty()) {
            paint.isFakeBoldText = false
            paint.textSize = 23f
            paint.color = 0xB38FA0C2.toInt()
            canvas.drawText(subtitle, 124f, 118f, paint)
        }
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.5f
        paint.color = 0x22FFFFFF
        canvas.drawLine(64f, 138f, widthPx - 64f, 138f, paint)
    }

    // ------------------------------------------------------------------
    // Items
    // ------------------------------------------------------------------
    fun drawItem(item: UIItem) {
        val r = item.rect
        val hov = item.hovered && item.enabled
        when (item.kind) {
            UIItem.KIND_BUTTON -> drawButton(r, item, hov)
            UIItem.KIND_TOGGLE -> drawToggle(r, item, hov)
            UIItem.KIND_SLIDER -> drawSlider(r, item, hov)
            UIItem.KIND_VALUE -> drawValue(r, item, hov)
            UIItem.KIND_LABEL -> drawLabel(r, item)
            UIItem.KIND_SECTION -> drawSection(r, item)
            UIItem.KIND_CLOCK -> drawClock(r, item)
        }
    }

    private fun drawButton(r: RectF, item: UIItem, hov: Boolean) {
        paint.style = Paint.Style.FILL
        paint.color = if (hov) 0x465B83C8.toInt() else 0x17FFFFFF
        canvas.drawRoundRect(r, 26f, 26f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2.5f
        paint.color = if (hov) 0xAA9CC2FF.toInt() else 0x33A8C4E8.toInt()
        canvas.drawRoundRect(r, 26f, 26f, paint)
        // icon
        val iconSize = 40f
        val iy = r.centerY() - iconSize / 2f
        if (item.icon != Icons.NONE) {
            Icons.draw(canvas, item.icon, r.left + 30f, iy, iconSize,
                if (hov) 0xFFEAF1FF.toInt() else 0xB3C9D6EC.toInt())
        }
        // label
        paint.style = Paint.Style.FILL
        paint.textSize = 33f
        paint.isFakeBoldText = true
        paint.color = if (hov) 0xFFFFFFFF.toInt() else 0xE8E6EDF6.toInt()
        val textX = r.left + 96f
        val ty = r.centerY() + 12f
        canvas.drawText(item.label, textX, ty, paint)
        // chevron
        paint.isFakeBoldText = false
        paint.textSize = 30f
        paint.color = if (hov) 0x88FFFFFF.toInt() else 0x55FFFFFF
        canvas.drawText("›", r.right - 52f, r.centerY() + 11f, paint)
    }

    private fun drawToggle(r: RectF, item: UIItem, hov: Boolean) {
        paint.style = Paint.Style.FILL
        paint.color = if (hov) 0x2AFFFFFF else 0x10FFFFFF
        canvas.drawRoundRect(r, 24f, 24f, paint)
        // label
        paint.textSize = 29f
        paint.isFakeBoldText = true
        paint.color = 0xE0DDE6F4.toInt()
        canvas.drawText(item.label, r.left + 30f, r.centerY() + 11f, paint)
        // pill
        val pw = 88f
        val ph = 42f
        val px = r.right - 30f - pw
        val py = r.centerY() - ph / 2f
        val pill = RectF(px, py, px + pw, py + ph)
        paint.color = if (item.boolValue) 0x885B83C8.toInt() else 0x2AFFFFFF
        canvas.drawRoundRect(pill, ph / 2f, ph / 2f, paint)
        val kr = ph / 2f - 6f
        val kx = if (item.boolValue) px + pw - ph / 2f else px + ph / 2f
        paint.color = if (item.boolValue) 0xFFFFFFFF.toInt() else 0x99A8B6CC.toInt()
        canvas.drawCircle(kx, r.centerY(), kr, paint)
        // ON/OFF
        paint.textSize = 19f
        paint.isFakeBoldText = true
        paint.color = if (item.boolValue) 0xB3C9D6EC.toInt() else 0x66FFFFFF
        canvas.drawText(if (item.boolValue) "ON" else "OFF", px - 58f, r.centerY() + 7f, paint)
    }

    private fun drawSlider(r: RectF, item: UIItem, hov: Boolean) {
        // label left
        paint.style = Paint.Style.FILL
        paint.textSize = 27f
        paint.isFakeBoldText = true
        paint.color = 0xD9DDE6F4.toInt()
        canvas.drawText(item.label, r.left + 4f, r.centerY() - 4f, paint)
        // value right
        paint.textSize = 26f
        paint.color = if (hov) 0xFFFFFFFF.toInt() else 0xBFCFE8.toInt()
        paint.textAlign = Paint.Align.RIGHT
        canvas.drawText(item.valueText, r.right - 4f, r.centerY() - 4f, paint)
        paint.textAlign = Paint.Align.LEFT
        // track
        val ty = r.centerY() + 16f
        paint.color = 0x2AFFFFFF
        canvas.drawRoundRect(r.left + 4f, ty - 4f, r.right - 4f, ty + 4f, 4f, 4f, paint)
        val t = ((item.floatValue - item.floatMin) / (item.floatMax - item.floatMin)).coerceIn(0f, 1f)
        val fx = r.left + 4f + (r.width() - 8f) * t
        paint.color = if (hov) 0xEE8FB4E8.toInt() else 0xCC6FA0E8.toInt()
        canvas.drawRoundRect(r.left + 4f, ty - 4f, fx, ty + 4f, 4f, 4f, paint)
        // knob
        paint.color = 0xFFFFFFFF.toInt()
        canvas.drawCircle(fx, ty, 11f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f
        paint.color = 0x66FFFFFF
        canvas.drawCircle(fx, ty, 11f, paint)
        paint.style = Paint.Style.FILL
    }

    private fun drawValue(r: RectF, item: UIItem, hov: Boolean) {
        paint.style = Paint.Style.FILL
        paint.color = if (hov) 0x2AFFFFFF else 0x10FFFFFF
        canvas.drawRoundRect(r, 24f, 24f, paint)
        paint.textSize = 29f
        paint.isFakeBoldText = true
        paint.color = 0xE0DDE6F4.toInt()
        canvas.drawText(item.label, r.left + 30f, r.centerY() + 11f, paint)
        paint.textSize = 27f
        paint.color = if (hov) 0xFFFFFFFF.toInt() else 0xBFCFE8.toInt()
        paint.textAlign = Paint.Align.RIGHT
        canvas.drawText(item.currentOption(), r.right - 60f, r.centerY() + 10f, paint)
        paint.textAlign = Paint.Align.LEFT
        paint.color = if (hov) 0x88FFFFFF.toInt() else 0x55FFFFFF
        canvas.drawText("›", r.right - 44f, r.centerY() + 10f, paint)
    }

    private fun drawLabel(r: RectF, item: UIItem) {
        paint.style = Paint.Style.FILL
        paint.textSize = item.fontSize
        paint.isFakeBoldText = false
        paint.color = 0xA693A3BF.toInt()
        if (item.centered) {
            paint.textAlign = Paint.Align.CENTER
            canvas.drawText(item.label, r.centerX(), r.centerY() + item.fontSize * 0.35f, paint)
            paint.textAlign = Paint.Align.LEFT
        } else {
            // icon (if any) then text
            val tx = if (item.icon != Icons.NONE) r.left + 56f else r.left
            if (item.icon != Icons.NONE) {
                Icons.draw(canvas, item.icon, r.left + 4f, r.centerY() - 22f, 44f, 0xB3C9D6EC.toInt())
            }
            canvas.drawText(item.label, tx, r.centerY() + 9f, paint)
        }
        // status value (right side) for system rows
        if (item.valueText.isNotEmpty() && item.kind == UIItem.KIND_LABEL) {
            paint.textSize = 25f
            paint.isFakeBoldText = true
            paint.color = 0xCCE3ECFF.toInt()
            paint.textAlign = Paint.Align.RIGHT
            canvas.drawText(item.valueText, r.right, r.centerY() + 9f, paint)
            paint.textAlign = Paint.Align.LEFT
        }
    }

    private fun drawSection(r: RectF, item: UIItem) {
        paint.style = Paint.Style.FILL
        paint.textSize = 22f
        paint.isFakeBoldText = true
        paint.color = 0xB37E8FB0.toInt()
        canvas.drawText(item.label.uppercase(), r.left, r.centerY() + 8f, paint)
    }

    private fun drawClock(r: RectF, item: UIItem) {
        paint.style = Paint.Style.FILL
        paint.textSize = 88f
        paint.isFakeBoldText = true
        paint.color = 0xF5F2F6FF.toInt()
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText(item.valueText, r.centerX(), r.centerY() + 28f, paint)
        paint.textAlign = Paint.Align.LEFT
    }

    /** Redraws a single item in place and uploads the region. */
    fun redrawItem(item: UIItem) {
        val pad = 8f
        clearRegion(RectF(item.rect.left - pad, item.rect.top - pad, item.rect.right + pad, item.rect.bottom + pad))
        drawItem(item)
        regionUpload(
            Rect(
                max(0, (item.rect.left - pad).toInt()),
                max(0, (item.rect.top - pad).toInt()),
                minOf(widthPx, (item.rect.right + pad).toInt()),
                minOf(heightPx, (item.rect.bottom + pad).toInt())
            )
        )
    }

    /** GL context was destroyed: the texture is gone, just reset the id. */
    fun contextLost() {
        texId = 0
    }

    fun release() {
        if (texId != 0) {
            android.opengl.GLES20.glDeleteTextures(1, intArrayOf(texId), 0)
            texId = 0
        }
        if (!bitmap.isRecycled) bitmap.recycle()
    }
}
