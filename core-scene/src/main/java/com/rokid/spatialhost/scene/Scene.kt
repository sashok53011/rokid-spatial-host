package com.rokid.spatialhost.scene

import kotlin.math.cos
import kotlin.math.sin

/** Minimal3D vector math (meters, right-handed, +Y up, -Z forward). */
data class Vec3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Float) = Vec3(x * s, y * s, z * s)
    fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
    fun cross(o: Vec3) = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    fun length() = kotlin.math.sqrt(dot(this))
    fun normalized(): Vec3 {
        val l = length()
        return if (l < 1e-6f) ZERO else this * (1f / l)
    }

    companion object {
        val ZERO = Vec3(0f, 0f, 0f)
        val FORWARD = Vec3(0f, 0f, -1f)
    }
}

/**
 * A floating panel in the session frame: axis-aligned quad of physical size
 * [widthM] x [heightM], centered at [position], facing the session origin
 * (its normal points at the user).
 */
data class SpatialPanel(
    val id: String,
    val widthM: Float,
    val heightM: Float,
    val position: Vec3,
    val label: String = id,
    /** Widgets drawn into this panel's texture (UV space, v = up). */
    val widgets: List<Widget> = emptyList(),
    /**
     * Keep the panel visible even while the immersive video sphere plays
     * (arc panels are pulled out of the scene, control decks are not).
     */
    val persistent: Boolean = false,
) {
    /** Forward axis (unit vector origin→panel flipped: panel faces origin). */
    val normal: Vec3 get() = (position * -1f).normalized()

    /** Right/up basis of the quad plane. */
    fun basis(): Pair<Vec3, Vec3> {
        val n = normal
        val worldUp = Vec3(0f, 1f, 0f)
        var right = worldUp.cross(n)
        if (right.length() < 1e-4f) right = Vec3(1f, 0f, 0f)
        right = right.normalized()
        val up = n.cross(right).normalized()
        return right to up
    }
}

/**
 * Ray vs axis-aligned box (slab method), both in the same space.
 * Returns the ray parameter t >= 0 of the entry point, or null on miss.
 * Works with non-normalized directions too (t is in direction units).
 */
fun hitBox(origin: FloatArray, dir: FloatArray, min: FloatArray, max: FloatArray): Float? {
    var tMin = Float.NEGATIVE_INFINITY
    var tMax = Float.POSITIVE_INFINITY
    for (axis in 0..2) {
        val o = origin[axis]
        val d = dir[axis]
        if (kotlin.math.abs(d) < 1e-9f) {
            if (o < min[axis] || o > max[axis]) return null
        } else {
            var t1 = (min[axis] - o) / d
            var t2 = (max[axis] - o) / d
            if (t1 > t2) { val tmp = t1; t1 = t2; t2 = tmp }
            if (t1 > tMin) tMin = t1
            if (t2 < tMax) tMax = t2
            if (tMin > tMax) return null
        }
    }
    return if (tMin >= 0f) tMin else if (tMax >= 0f) 0f else null
}

/** Result of a ray vs panel intersection. */
data class PanelHit(
    val panel: SpatialPanel,
    val distance: Float,
    /** UV in [0,1]² across the panel face. */
    val u: Float,
    val v: Float,
)

/**
 * Keeps the user's panels arranged and answers ray hit tests.
 * Pure Kotlin — no render dependencies, unit-testable.
 */
class LayoutEngine {

    val panels = linkedMapOf<String, SpatialPanel>()

    fun clear() = panels.clear()

    fun add(panel: SpatialPanel): SpatialPanel {
        panels[panel.id] = panel
        return panel
    }

    /**
     * Default arc of [count] panels at radius [radiusM] in front of the user,
     * each facing the origin.
     */
    fun defaultArc(count: Int = 3, radiusM: Float = 2.5f, arcDeg: Float = 60f): List<SpatialPanel> {
        clear()
        val result = mutableListOf<SpatialPanel>()
        for (i in 0 until count) {
            val t = if (count == 1) 0.5f else i / (count - 1f)
            val yawDeg = (t - 0.5f) * arcDeg
            val yaw = Math.toRadians(yawDeg.toDouble()).toFloat()
            // In front = -Z; yaw sweeps left(-) to right(+).
            val pos = Vec3(sin(yaw) * radiusM, 0f, -cos(yaw) * radiusM)
            val panel = SpatialPanel(
                id = "panel-$i",
                widthM = 1.6f,
                heightM = 0.9f,
                position = pos,
                label = "Panel ${i + 1}",
            )
            add(panel)
            result.add(panel)
        }
        return result
    }

    fun move(id: String, to: Vec3): SpatialPanel? {
        val p = panels[id] ?: return null
        val moved = p.copy(position = to)
        panels[id] = moved
        return moved
    }

    /**
     * Ray from the user's head (session origin, 3DoF → eye at origin).
     * [dir] must be normalized. Returns the nearest hit within [maxDistance].
     */
    fun hitTest(dir: Vec3, maxDistance: Float = 20f): PanelHit? {
        var best: PanelHit? = null
        for (p in panels.values) {
            val hit = intersectPanel(p, dir, maxDistance) ?: continue
            if (best == null || hit.distance < best.distance) best = hit
        }
        return best
    }

    private fun intersectPanel(panel: SpatialPanel, dir: Vec3, maxDistance: Float): PanelHit? {
        val n = panel.normal
        val denom = n.dot(dir)
        if (kotlin.math.abs(denom) < 1e-5f) return null // parallel
        val t = n.dot(panel.position) / denom
        if (t < 0f || t > maxDistance) return null
        val point = dir * t
        val toPoint = point - panel.position
        val (right, up) = panel.basis()
        val localX = toPoint.dot(right)
        val localY = toPoint.dot(up)
        val halfW = panel.widthM / 2f
        val halfH = panel.heightM / 2f
        if (kotlin.math.abs(localX) > halfW || kotlin.math.abs(localY) > halfH) return null
        return PanelHit(
            panel = panel,
            distance = t,
            u = (localX + halfW) / panel.widthM,
            v = (localY + halfH) / panel.heightM,
        )
    }
}
