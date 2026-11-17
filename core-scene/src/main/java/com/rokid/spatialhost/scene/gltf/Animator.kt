package com.rokid.spatialhost.scene.gltf

/**
 * Rigid-keyframe playback for [GltfAnimation] clips (D5).
 *
 * Pure math, no renderer dependency — the render thread calls [advance]
 * per frame and applies [sample] results to node transforms. Skinning/
 * morph targets are out of scope (channels for them are dropped at parse).
 *
 * Interpolation per glTF 2.0 §Animation:
 *  * LINEAR   — component lerp; rotations are quaternions → nlerp + normalize
 *  * STEP     — hold the value of the key at or before t
 *  * CUBICSPLINE — Hermite over (in-tangent, value, out-tangent) triples,
 *    tangents scaled by Δt; rotations normalized
 *
 * Time wraps at the clip duration (looping playback).
 */
class Animator(private val model: GltfModel) {

    val clipCount: Int get() = model.animations.size

    /** Active clip index (clamped on read — assets vary). */
    var clipIndex: Int = 0

    var playing: Boolean = true

    /** Playback position inside the active clip, seconds. */
    var time: Float = 0f
        private set

    /** Active clip duration in seconds (0 when there are no clips). */
    fun duration(): Float {
        val anim = clipOrNull() ?: return 0f
        var d = 0f
        for (s in anim.samplers) {
            s.times.lastOrNull()?.let { if (it > d) d = it }
        }
        return d
    }

    fun clipName(): String = clipOrNull()?.name ?: "-"

    /** Advance playback by [dt] seconds (loops at [duration]). */
    fun advance(dt: Float) {
        if (!playing || clipOrNull() == null) return
        val d = duration()
        if (d <= 0f) {
            time = 0f
            return
        }
        time += dt
        // Wrap (dt may exceed d after a stall; two wraps cover any delta).
        while (time >= d) time -= d
        while (time < 0f) time += d
    }

    /**
     * Sample the active clip at the current [time]:
     * node index → LOCAL transform matrix (16 floats, column-major).
     * Only nodes touched by channels are present; non-animated components
     * keep their base TRS from the node definition.
     */
    fun sample(): Map<Int, FloatArray> {
        val anim = clipOrNull() ?: return emptyMap()
        // node → [translation, rotation, scale]; channels override pieces.
        val comps = HashMap<Int, Array<FloatArray>>(anim.channels.size)
        for (ch in anim.channels) {
            val sampler = anim.samplers.getOrNull(ch.sampler) ?: continue
            val node = model.nodes.getOrNull(ch.node) ?: continue
            val v = sampleChannel(sampler, ch.path, time) ?: continue
            val c = comps.getOrPut(ch.node) {
                arrayOf(
                    node.translation ?: DEFAULT_T,
                    node.rotation ?: DEFAULT_R,
                    node.scale ?: DEFAULT_S,
                )
            }
            when (ch.path) {
                "translation" -> c[0] = v
                "rotation" -> c[1] = v
                "scale" -> c[2] = v
            }
        }
        return comps.mapValues { (_, c) -> GltfParser.trsMatrix(c[0], c[1], c[2]) }
    }

    private fun clipOrNull(): GltfAnimation? =
        model.animations.getOrNull(clipIndex) ?: model.animations.firstOrNull()

    companion object {
        private val DEFAULT_T = floatArrayOf(0f, 0f, 0f)
        private val DEFAULT_R = floatArrayOf(0f, 0f, 0f, 1f)
        private val DEFAULT_S = floatArrayOf(1f, 1f, 1f)

        /**
         * Sample one channel value at [t], or null when the sampler holds
         * no keys. Comps per key: 4 for rotation, else 3.
         */
        fun sampleChannel(sampler: GltfSampler, path: String, t: Float): FloatArray? {
            val times = sampler.times
            val vals = sampler.values
            val comps = if (path == "rotation") 4 else 3
            val keys = times.size
            if (keys == 0 || vals.size < comps) return null

            fun valueAt(k: Int): FloatArray {
                val base = k * comps
                return if (sampler.interpolation == "CUBICSPLINE") {
                    // triples: in-tangent, value, out-tangent per key
                    val v = (3 * k + 1) * comps
                    FloatArray(comps) { vals[v + it] }
                } else {
                    FloatArray(comps) { vals[base + it] }
                }
            }

            // Outside the key range: clamp to the first/last value.
            if (t <= times[0]) return valueAt(0)
            if (t >= times[keys - 1]) return valueAt(keys - 1)

            // Find segment [k, k+1] containing t (key counts are small).
            var k = 0
            while (k + 1 < keys && times[k + 1] <= t) k++
            val t0 = times[k]
            val t1 = times[k + 1]
            val span = t1 - t0
            val s = if (span > 0f) (t - t0) / span else 0f

            when (sampler.interpolation) {
                "STEP" -> return valueAt(k)

                "CUBICSPLINE" -> {
                    val vBase = (3 * k + 1) * comps
                    val inT = (3 * k) * comps
                    val outT = (3 * (k + 1) + 2) * comps
                    val s2 = s * s
                    val s3 = s2 * s
                    val h00 = 2f * s3 - 3f * s2 + 1f
                    val h10 = s3 - 2f * s2 + s
                    val h01 = -2f * s3 + 3f * s2
                    val h11 = s3 - s2
                    val out = FloatArray(comps)
                    for (i in 0 until comps) {
                        val p0 = vals[vBase + i]
                        val p1 = vals[vBase + comps + i]
                        // glTF tangents are derivatives per second → scale by Δt.
                        val m0 = vals[inT + i] * span
                        val m1 = vals[outT + i] * span
                        out[i] = h00 * p0 + h10 * m0 + h01 * p1 + h11 * m1
                    }
                    if (path == "rotation") normalize(out)
                    return out
                }

                else -> { // LINEAR
                    val a = valueAt(k)
                    val b = valueAt(k + 1)
                    for (i in 0 until comps) a[i] += (b[i] - a[i]) * s
                    if (path == "rotation") normalize(a)
                    return a
                }
            }
        }

        /** In-place quaternion normalization (zero-safe → identity). */
        private fun normalize(q: FloatArray) {
            val len = kotlin.math.sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3])
            if (len < 1e-8f) {
                q[0] = 0f; q[1] = 0f; q[2] = 0f; q[3] = 1f
                return
            }
            for (i in 0..3) q[i] /= len
        }
    }
}
