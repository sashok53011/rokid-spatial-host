package com.rokid.spatialhost.bridge.rokid

import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sqrt
import kotlin.math.sin

/**
 * Exact port of the Madgwick AHRS used by Rokid Spatial Studio (the variant
 * verified on real Rokid Max hardware), plus the frame handling that was
 * validated by offline simulation:
 *
 *  - Device frame: X-right, Y-up, Z-back.
 *  - Filter gravity convention: Z-down → input vectors are mapped with
 *    Rx(-90°): (x,y,z) → (x, z, -y). With this the filter sits exactly at
 *    its stable fixed point when the head is upright (no start-up crawl).
 *  - Camera output (three.js/Filament, -Z forward / +Y up): undo with the
 *    similarity transform cam = Rx(+90°) ⊗ q ⊗ Rx(-90°).
 *
 * Thread-affine: call from the USB reader thread only.
 */
class MadgwickRokid(private val beta: Float = 0.045f) {

    // w, x, y, z
    private var q0 = 1f
    private var q1 = 0f
    private var q2 = 0f
    private var q3 = 0f

    private var initializedFromAccel = false

    fun reset() {
        q0 = 1f; q1 = 0f; q2 = 0f; q3 = 0f
        initializedFromAccel = false
    }

    /**
     * Seed orientation from a (near-)stationary accelerometer sample so the
     * view is level immediately at boot instead of crawling to the fixed
     * point at ~2.6°/s. Builds the rotation taking +Z to -â.
     */
    fun initFromAccel(ax: Float, ay: Float, az: Float) {
        val n = sqrt(ax * ax + ay * ay + az * az)
        if (n < 1e-6f) return
        // Input frame (after Rx(-90) mapping): (x, z, -y)
        val ix = ax / n
        val iy = az / n
        val iz = -ay / n
        // want R such that R * (0,0,1) = (-ix, -iy, -iz)
        val tx = -ix
        val ty = -iy
        val tz = -iz
        // rotation from z=(0,0,1) to t
        val dot = tz.coerceIn(-1f, 1f)
        val angle = acos(dot)
        if (angle < 1e-4f) {
            q0 = 1f; q1 = 0f; q2 = 0f; q3 = 0f
            initializedFromAccel = true
            return
        }
        // axis = z × t (normalized); when parallel this path isn't taken
        var axx = -ty
        var ayy = tx
        var azz = 0f
        val alen = sqrt(axx * axx + ayy * ayy + azz * azz)
        if (alen < 1e-6f) return
        axx /= alen; ayy /= alen; azz /= alen
        val s = sin(angle / 2f)
        q0 = cos(angle / 2f)
        q1 = axx * s
        q2 = ayy * s
        q3 = azz * s
        initializedFromAccel = true
    }

    val isInitializedFromAccel: Boolean get() = initializedFromAccel

    /**
     * Feed one sample in the DEVICE frame (gyro rad/s, accel m/s²).
     * Returns false when the sample was unusable (dt out of range).
     */
    fun update(gx: Float, gy: Float, gz: Float, ax: Float, ay: Float, az: Float, dt: Float): Boolean {
        if (dt <= 0f || dt > 0.25f) return false
        // Rx(-90°): (x,y,z) -> (x, z, -y)
        val fx = gx
        val fy = gz
        val fz = -gy
        val fax = ax
        val fay = az
        val faz = -ay

        var w = q0
        var x = q1
        var y = q2
        var z = q3

        var qDot1 = 0.5f * (-x * fx - y * fy - z * fz)
        var qDot2 = 0.5f * (w * fx + y * fz - z * fy)
        var qDot3 = 0.5f * (w * fy - x * fz + z * fx)
        var qDot4 = 0.5f * (w * fz + x * fy - y * fx)

        var axn = fax
        var ayn = fay
        var azn = faz
        val normSq = axn * axn + ayn * ayn + azn * azn
        if (normSq > 1e-6f) {
            val recipNorm = 1f / sqrt(normSq)
            axn *= recipNorm
            ayn *= recipNorm
            azn *= recipNorm

            val _2w = 2f * w
            val _2x = 2f * x
            val _2y = 2f * y
            val _2z = 2f * z
            val _4w = 4f * w
            val _4x = 4f * x
            val _4y = 4f * y
            val _8x = 8f * x
            val _8y = 8f * y
            val ww = w * w
            val xx = x * x
            val yy = y * y
            val zz = z * z

            var s0 = _4w * yy + _2y * axn + _4w * xx - _2x * ayn
            var s1 = _4x * zz - _2z * axn + 4f * ww * x - _2w * ayn - _4x + _8x * xx + _8x * yy + _4x * azn
            var s2 = 4f * ww * y + _2w * axn + _4y * zz - _2z * ayn - _4y + _8y * xx + _8y * yy + _4y * azn
            var s3 = 4f * xx * z - _2x * axn + 4f * yy * z - _2y * ayn

            val sNormSq = s0 * s0 + s1 * s1 + s2 * s2 + s3 * s3
            if (sNormSq > 1e-12f) {
                val sRecip = 1f / sqrt(sNormSq)
                s0 *= sRecip
                s1 *= sRecip
                s2 *= sRecip
                s3 *= sRecip

                qDot1 -= beta * s0
                qDot2 -= beta * s1
                qDot3 -= beta * s2
                qDot4 -= beta * s3
            }
        }

        w += qDot1 * dt
        x += qDot2 * dt
        y += qDot3 * dt
        z += qDot4 * dt

        val qRecip = 1f / sqrt(w * w + x * x + y * y + z * z)
        q0 = w * qRecip
        q1 = x * qRecip
        q2 = y * qRecip
        q3 = z * qRecip
        return true
    }

    /**
     * Filter output in the GL camera frame: Rx(+90°) ⊗ q ⊗ Rx(-90°).
     * Returns [x, y, z, w] ready for [QuaternionPoseProvider.setOrientation].
     *
     * Rx(±90°) quaternions as (w,x,y,z): Rx(+90) = (s, s, 0, 0),
     * Rx(-90) = (s, -s, 0, 0) with s = √½.
     */
    fun cameraQuat(): FloatArray {
        val s = SQRT_HALF
        val a = mul(s, s, 0f, 0f, q0, q1, q2, q3)            // Rx(+90) ⊗ q
        val out = mul(a[0], a[1], a[2], a[3], s, -s, 0f, 0f) // ... ⊗ Rx(-90)
        return floatArrayOf(out[1], out[2], out[3], out[0])  // [x,y,z,w]
    }

    /** Hamilton product, args (w,x,y,z). */
    private fun mul(w1: Float, x1: Float, y1: Float, z1: Float, w2: Float, x2: Float, y2: Float, z2: Float): FloatArray {
        return floatArrayOf(
            w1 * w2 - x1 * x2 - y1 * y2 - z1 * z2,
            w1 * x2 + x1 * w2 + y1 * z2 - z1 * y2,
            w1 * y2 - x1 * z2 + y1 * w2 + z1 * x2,
            w1 * z2 + x1 * y2 - y1 * x2 + z1 * w2,
        )
    }

    companion object {
        private val SQRT_HALF = kotlin.math.sqrt(0.5f).toFloat()
    }
}
