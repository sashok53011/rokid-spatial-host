package com.rokid.spatialhost.bridge

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * Reusable pose provider for bridges that deliver absolute orientation
 * quaternions (already converted to the engine's GL convention:
 * +X right, +Y up, -Z forward, [x,y,z,w] Hamilton).
 *
 * Published pose = `conj(recenterReference) * absolute`, so [recenter] simply
 * makes the current absolute orientation the session identity.
 */
class QuaternionPoseProvider : HeadPoseProvider {

    private val _latest = MutableStateFlow(HeadPose.IDENTITY)
    override val latest: StateFlow<HeadPose> = _latest

    override var dof: DoF = DoF.THREE

    private var absolute = floatArrayOf(0f, 0f, 0f, 1f)
    private var reference = floatArrayOf(0f, 0f, 0f, 1f)

    /** Feed an absolute head orientation in GL convention. */
    fun setOrientation(rawGl: FloatArray) {
        absolute = Quat.normalize(rawGl)
        publish()
    }

    override fun recenter() {
        reference = Quat.conjugate(absolute)
        publish()
    }

    /** Debug snapshot: absolute / reference / published as yaw-pitch-roll °. */
    fun debugState(): String {
        fun eul(q: FloatArray): String {
            val (y, p, r) = Quat.toEulerYXZ(Quat.normalize(q))
            return "y%.0f/p%.0f/r%.0f".format(
                Math.toDegrees(y.toDouble()), Math.toDegrees(p.toDouble()), Math.toDegrees(r.toDouble()),
            )
        }
        val published = Quat.normalize(Quat.mul(reference, absolute))
        return "abs=${eul(absolute)} ref=${eul(reference)} pub=${eul(published)}"
    }

    private fun publish() {
        val q = Quat.normalize(Quat.mul(reference, absolute))
        val (yaw, pitch, roll) = Quat.toEulerYXZ(q)
        _latest.update {
            HeadPose(
                qx = q[0], qy = q[1], qz = q[2], qw = q[3],
                yaw = yaw, pitch = pitch, roll = roll,
                timestampNanos = SystemClock.elapsedRealtimeNanos(),
            )
        }
    }
}
