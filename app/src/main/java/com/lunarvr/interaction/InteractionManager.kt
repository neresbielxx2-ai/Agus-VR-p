package com.lunarvr.interaction

import com.lunarvr.hand.HandPose
import com.lunarvr.math.Quat
import com.lunarvr.math.Vec3
import com.lunarvr.settings.SettingsStore
import com.lunarvr.ui.Panel3D
import com.lunarvr.ui.UIItem
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Ray interaction:
 *
 *   POINT  ->  Ray (from the index fingertip)
 *   Ray    ->  Hover (closest intersected button/panel)
 *   PINCH  ->  Select (click)
 *
 * When hand tracking is unavailable, a HEAD RAY fallback is used: the ray
 * comes from the head and the user taps the screen to select — the
 * SystemMenu always states clearly which mode is active (never faked).
 */
class InteractionManager {

    interface Listener {
        fun onCommand(cmd: Int, arg: Int)
        fun onHoverChanged(item: UIItem?)
        fun onSliderLive(item: UIItem, t01: Float)
        fun onSliderEnd(item: UIItem)
    }

    class RayState {
        var active = false
        val origin = Vec3()
        val dir = Vec3()
        var hit = false
        val hitPoint = Vec3()
        var hitT = 0f
        var hitLocalX = 0f
        var hitLocalY = 0f
        var hoverPanel: Panel3D? = null
        var hoverItem: UIItem? = null
        var hoveringItem = false
        var cursorScale = 1f
        var cursorPulse = 0f
        var rayColorR = 1f
        var rayColorG = 1f
        var rayColorB = 1f
        var lastHoverItem: UIItem? = null
        var dragging: UIItem? = null
    }

    val leftHand = RayState()
    val rightHand = RayState()
    val headRay = RayState()

    var useHeadRayFallback = false
    @Volatile var tapRequested = false

    var listener: Listener? = null

    private fun updateRayFromHand(ray: RayState, hand: HandPose, cameraQ: Quat, rayOn: Boolean) {
        ray.active = hand.visible && rayOn && (hand.isPoint || hand.pinching)
        if (!ray.active) {
            ray.hoverItem = null
            ray.hoverPanel = null
            ray.hit = false
            return
        }
        ray.origin.set(hand.indexTip.x, hand.indexTip.y, hand.indexTip.z)
        val fwd = cameraQ.rotate(Vec3(0f, 0f, -1f))
        val right = cameraQ.rotate(Vec3(1f, 0f, 0f))
        val up = cameraQ.rotate(Vec3(0f, 1f, 0f))
        // pointing direction of the index finger in the camera frame
        val dCam = cameraQ.conjugate().rotate(hand.indexTip.sub(hand.indexPip))
        var dx = dCam.x
        var dy = dCam.y
        val dl = sqrt(dx * dx + dy * dy)
        if (dl > 1e-4f) { dx /= dl; dy /= dl } else { dx = 0f; dy = 0f }
        val k = 0.5f
        val dir = fwd.addScaled(right, dx * k).addScaled(up, -dy * k).normalize()
        ray.dir.set(dir.x, dir.y, dir.z)
        ray.rayColorR = 1f; ray.rayColorG = 1f; ray.rayColorB = 1f
    }

    private fun updateHeadRay(ray: RayState, cameraQ: Quat, rayOn: Boolean) {
        ray.active = rayOn
        if (!ray.active) {
            ray.hoverItem = null
            ray.hoverPanel = null
            return
        }
        val o = cameraQ.rotate(Vec3(0f, 0f, -0.18f))
        ray.origin.set(o.x, o.y, o.z)
        val f = cameraQ.rotate(Vec3(0f, 0f, -1f))
        ray.dir.set(f.x, f.y, f.z)
        ray.rayColorR = 0.72f; ray.rayColorG = 0.82f; ray.rayColorB = 1f
    }

    /** Ray vs oriented quad (panel plane at local z=0, facing +z). */
    private fun intersectPanel(
        o: Vec3, d: Vec3, panel: Panel3D
    ): FloatArray? {
        val pos = panel.currentPos
        val q = panel.currentQuat
        val s = panel.currentScale
        val lpos = q.conjugate().rotate(o.sub(pos))
        val ldir = q.conjugate().rotate(d).normalize()
        val lo = Vec3(lpos.x / s, lpos.y / s, lpos.z / s)
        val ld = Vec3(ldir.x / s, ldir.y / s, ldir.z / s)
        if (ld.z >= -1e-5f) return null
        val t = -lo.z / ld.z
        if (t < 0.06f || t > 6f) return null
        val x = lo.x + ld.x * t
        val y = lo.y + ld.y * t
        val hw = panel.widthM * 0.5f
        val hh = panel.heightM * 0.5f
        if (abs(x) > hw || abs(y) > hh) return null
        return floatArrayOf(t, x, y)
    }

    private fun castRay(ray: RayState, panels: List<Panel3D>) {
        ray.hit = false
        ray.hoverPanel = null
        ray.hoverItem = null
        ray.hoveringItem = false
        var bestT = Float.MAX_VALUE
        var bestX = 0f
        var bestY = 0f
        var bestPanel: Panel3D? = null
        for (p in panels) {
            if (p.alphaForHit < 0.5f) continue
            val r = intersectPanel(ray.origin, ray.dir, p) ?: continue
            if (r[0] < bestT) {
                bestT = r[0]
                bestX = r[1]
                bestY = r[2]
                bestPanel = p
            }
        }
        val bp = bestPanel ?: return
        ray.hit = true
        ray.hitT = min(bestT, 2.6f)
        ray.hitLocalX = bestX
        ray.hitLocalY = bestY
        ray.hitPoint.set(
            ray.origin.x + ray.dir.x * ray.hitT,
            ray.origin.y + ray.dir.y * ray.hitT,
            ray.origin.z + ray.dir.z * ray.hitT
        )
        ray.hoverPanel = bp
        ray.hoverItem = bp.hitItem(bestX, bestY)
        ray.hoveringItem = ray.hoverItem != null
    }

    private fun tickHoverSound(ray: RayState) {
        val now = ray.hoverItem
        if (now !== ray.lastHoverItem) {
            ray.lastHoverItem = now
            listener?.onHoverChanged(now)
        }
    }

    private fun sliderT01(ray: RayState): Float {
        val p = ray.hoverPanel ?: return 0f
        val t = (ray.hitLocalX / (p.widthM * 0.5f) + 1f) * 0.5f
        return t.coerceIn(0f, 1f)
    }

    private fun handlePinch(ray: RayState, hand: HandPose) {
        val item = ray.hoverItem
        if (hand.pinching) {
            if (item != null && item.kind == UIItem.KIND_SLIDER) {
                // pinch on a slider = live adjust (and sets value immediately)
                if (ray.dragging !== item) {
                    ray.dragging = item
                }
                listener?.onSliderLive(item, sliderT01(ray))
            } else {
                if (hand.pinchStart && item != null) {
                    listener?.onCommand(item.command, item.commandArg)
                    ray.cursorPulse = 1f
                }
            }
        } else {
            if (ray.dragging != null) {
                listener?.onSliderEnd(ray.dragging!!)
                ray.dragging = null
            }
        }
    }

    fun update(
        dt: Float,
        cameraQ: Quat,
        handLeft: HandPose,
        handRight: HandPose,
        panels: List<Panel3D>,
        settings: SettingsStore
    ) {
        val rayOn = settings.rayEnabled

        if (useHeadRayFallback) {
            updateHeadRay(headRay, cameraQ, rayOn)
            castRay(headRay, panels)
            tickHoverSound(headRay)
            animateCursor(headRay, dt)
            if (tapRequested) {
                tapRequested = false
                if (headRay.hoverItem != null) {
                    listener?.onCommand(headRay.hoverItem!!.command, headRay.hoverItem!!.commandArg)
                    headRay.cursorPulse = 1f
                }
            }
            return
        }

        updateRayFromHand(leftHand, handLeft, cameraQ, rayOn)
        updateRayFromHand(rightHand, handRight, cameraQ, rayOn)
        castRay(leftHand, panels)
        castRay(rightHand, panels)
        tickHoverSound(leftHand)
        tickHoverSound(rightHand)
        animateCursor(leftHand, dt)
        animateCursor(rightHand, dt)
        handlePinch(leftHand, handLeft)
        handlePinch(rightHand, handRight)
    }

    private fun animateCursor(ray: RayState, dt: Float) {
        val target = if (ray.hoveringItem) 1.38f else if (ray.hit) 1f else 0.75f
        ray.cursorScale += (target - ray.cursorScale) * min(1f, dt * 14f)
        ray.cursorPulse = max(0f, ray.cursorPulse - dt * 3.2f)
    }
}
