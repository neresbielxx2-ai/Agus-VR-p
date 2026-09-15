package com.lunarvr.scene

import com.lunarvr.math.Mat4
import com.lunarvr.math.Quat
import com.lunarvr.math.Vec3

/**
 * Node of the 3D scene graph.
 *
 * The full tree follows the LUNAR VR architecture:
 *
 * WorldRoot
 *  ├── CameraRig
 *  │    └── Camera
 *  ├── MainMenu
 *  ├── SettingsMenu
 *  ├── AppsMenu
 *  ├── SystemMenu
 *  └── FloatingPanels
 *
 * Menus are children of WorldRoot — never of the Camera. The camera (head)
 * rotates; the menus stay where they were placed in the virtual space.
 */
class SceneNode(val name: String = "node") {

    val children = ArrayList<SceneNode>()
    var parent: SceneNode? = null
    var position = Vec3()
    var rotation = Quat()
    var scale = Vec3(1f, 1f, 1f)
    var visible = true
    var worldMatrix = Mat4().identity()

    /** Optional per-frame update hook (used by dynamic nodes). */
    var onUpdate: ((dt: Float) -> Unit)? = null

    fun add(child: SceneNode): SceneNode {
        child.parent = this
        children.add(child)
        return child
    }

    fun remove(child: SceneNode) {
        children.remove(child)
        child.parent = null
    }

    /** Recalculates world matrices for this node and its subtree. */
    fun updateWorld() {
        val local = Mat4().compose(position, rotation, scale)
        val p = parent
        if (p == null) {
            worldMatrix.set(local)
        } else {
            worldMatrix.mul(p.worldMatrix, local)
        }
        for (c in children) c.updateWorld()
    }

    fun child(name: String): SceneNode? {
        for (c in children) if (c.name == name) return c
        return null
    }
}
