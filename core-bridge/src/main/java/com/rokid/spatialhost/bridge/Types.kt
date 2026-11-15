package com.rokid.spatialhost.bridge

/**
 * Head pose in the recentered session frame.
 *
 * Quaternion convention: rotates a vector from the recentered world frame into
 * the head/camera frame is the inverse; we store the head orientation itself,
 * i.e. `v_head = q^-1 * v_world`. Euler angles are provided for HUD/debug and
 * ray math convenience (radians, yaw around +Y, pitch around +X, roll around +Z).
 */
data class HeadPose(
    val qx: Float,
    val qy: Float,
    val qz: Float,
    val qw: Float,
    val yaw: Float,
    val pitch: Float,
    val roll: Float,
    val timestampNanos: Long,
) {
    companion object {
        val IDENTITY = HeadPose(0f, 0f, 0f, 1f, 0f, 0f, 0f, 0L)
    }
}

/** Degrees of freedom the active bridge can deliver. */
enum class DoF {
    /** Orientation fixed at identity (content locked to display). */
    ZERO,
    /** Orientation only — Rokid Max, Viture, phone gyro remote. */
    THREE,
    /** Smooth-follow orientation mode (Viture native DOF smooth follow). */
    SMOOTH_FOLLOW,
}

enum class BridgeKind { ROKID_MAX, VITURE, EMULATOR }

sealed class BridgeState {
    data object Disconnected : BridgeState()
    data object Connecting : BridgeState()
    data class Connected(val kind: BridgeKind, val name: String) : BridgeState()
    data class Error(val kind: BridgeKind, val message: String) : BridgeState()
}

// ---------------------------------------------------------------------------
// Quaternion helpers shared by bridges (pure Kotlin, no Android deps)
// ---------------------------------------------------------------------------

object Quat {
    /** q = qy(yaw) * qx(pitch) * qz(roll), returns [x, y, z, w]. */
    fun fromEulerYXZ(yaw: Float, pitch: Float, roll: Float): FloatArray {
        val cy = kotlin.math.cos(yaw * 0.5f)
        val sy = kotlin.math.sin(yaw * 0.5f)
        val cp = kotlin.math.cos(pitch * 0.5f)
        val sp = kotlin.math.sin(pitch * 0.5f)
        val cr = kotlin.math.cos(roll * 0.5f)
        val sr = kotlin.math.sin(roll * 0.5f)
        val w = cy * cp * cr + sy * sp * sr
        val x = cy * sp * cr + sy * cp * sr
        val y = sy * cp * cr - cy * sp * sr
        val z = cy * cp * sr - sy * sp * cr
        return floatArrayOf(x, y, z, w)
    }

    /** [x,y,z,w] * [x,y,z,w] (Hamilton). */
    fun mul(a: FloatArray, b: FloatArray): FloatArray {
        val (ax, ay, az, aw) = a
        val (bx, by, bz, bw) = b
        return floatArrayOf(
            aw * bx + ax * bw + ay * bz - az * by,
            aw * by - ax * bz + ay * bw + az * bx,
            aw * bz + ax * by - ay * bx + az * bw,
            aw * bw - ax * bx - ay * by - az * bz,
        )
    }

    fun conjugate(q: FloatArray): FloatArray = floatArrayOf(-q[0], -q[1], -q[2], q[3])

    /** Rotate vector v by q (q assumed unit). */
    fun rotate(q: FloatArray, vx: Float, vy: Float, vz: Float): FloatArray {
        val (x, y, z, w) = q
        // t = 2 * cross(q.xyz, v); v' = v + w*t + cross(q.xyz, t)
        val tx = 2f * (y * vz - z * vy)
        val ty = 2f * (z * vx - x * vz)
        val tz = 2f * (x * vy - y * vx)
        return floatArrayOf(
            vx + w * tx + (y * tz - z * ty),
            vy + w * ty + (z * tx - x * tz),
            vz + w * tz + (x * ty - y * tx),
        )
    }

    fun normalize(q: FloatArray): FloatArray {
        val len = kotlin.math.sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3])
        if (len < 1e-9f) return floatArrayOf(0f, 0f, 0f, 1f)
        return floatArrayOf(q[0] / len, q[1] / len, q[2] / len, q[3] / len)
    }

    /** Forward (-Z), up (+Y), right (+X) of a head orientation. */
    fun forward(q: FloatArray): FloatArray = rotate(q, 0f, 0f, -1f)

    /** Yaw/pitch/roll extracted from orientation (for HUD + touch integration).
     *
     * Canonical Tait-Bryan YXZ extraction (R = Ry(yaw)·Rx(pitch)·Rz(roll)):
     *   m12 = -sin(pitch); m02/m22 = sin/cos(yaw)·cos(pitch);
     *   m10/m11 = cos(pitch)·sin/cos(roll).
     * The previous forward/right-vector heuristics produced a bogus ±180° roll
     * for any non-zero yaw — which made the tilt-based auto-recenter fire
     * spuriously (the "upside down / jumping view" bug).
     */
    fun toEulerYXZ(q: FloatArray): FloatArray {
        val n = normalize(q)
        val (x, y, z, w) = floatArrayOf(n[0], n[1], n[2], n[3])
        val m02 = 2f * (x * z + y * w)
        val m10 = 2f * (x * y + z * w)
        val m11 = 1f - 2f * (x * x + z * z)
        val m12 = 2f * (y * z - x * w)
        val m22 = 1f - 2f * (x * x + y * y)

        val s = m12.coerceIn(-1f, 1f)
        // m12 = -sin(pitch) for the YXZ order
        val pitch = kotlin.math.asin(-s)
        return if (kotlin.math.abs(m12) < 0.9999999f) {
            floatArrayOf(
                kotlin.math.atan2(m02, m22), // yaw
                pitch,
                kotlin.math.atan2(m10, m11), // roll
            )
        } else {
            // Gimbal lock: fold yaw/roll, keep pitch.
            floatArrayOf(0f, pitch, 0f)
        }
    }
}
