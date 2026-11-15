package com.rokid.spatialhost.bridge.emulator

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import com.rokid.spatialhost.bridge.BridgeKind
import com.rokid.spatialhost.bridge.BridgeState
import com.rokid.spatialhost.bridge.DisplayController
import com.rokid.spatialhost.bridge.DoF
import com.rokid.spatialhost.bridge.GlassesBridge
import com.rokid.spatialhost.bridge.HeadPose
import com.rokid.spatialhost.bridge.HeadPoseProvider
import com.rokid.spatialhost.bridge.Quat
import com.rokid.spatialhost.bridge.UnavailableDisplay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min

/**
 * Development fallback bridge — works with no glasses attached.
 *
 * Two modes:
 *  1. **Touch/mouse-look**: [look] integrates yaw/pitch from drag deltas
 *     (driven by the activity's touch handling).
 *  2. **Gyro remote**: when a rotation-vector sensor exists, the phone itself
 *     becomes the "head" — hold the phone up and point it where you'd look.
 *
 * Recenter makes the current orientation the identity of the session frame.
 */
class EmulatorBridge(
    private val context: Context? = null,
    private val useDeviceSensors: Boolean = false,
) : GlassesBridge {

    private val _state = MutableStateFlow<BridgeState>(BridgeState.Disconnected)
    override val state: StateFlow<BridgeState> = _state

    override val poses: HeadPoseProvider = EmulatorPoseProvider()
    override val display: DisplayController = UnavailableDisplay

    private var sensorManager: SensorManager? = null

    private val rotationListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            // q = [w, x, y, z]
            val q = FloatArray(4)
            SensorManager.getQuaternionFromVector(q, event.values)
            (poses as EmulatorPoseProvider).setOrientation(
                floatArrayOf(q[1], q[2], q[3], q[0]),
            )
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    override suspend fun connect() {
        if (useDeviceSensors && context != null) {
            val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
            val sensor = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            if (sensor != null) {
                sensorManager = sm
                sm.registerListener(rotationListener, sensor, SensorManager.SENSOR_DELAY_GAME)
                _state.value = BridgeState.Connected(
                    BridgeKind.EMULATOR,
                    "Gyro remote (phone sensors)",
                )
                return
            }
        }
        _state.value = BridgeState.Connected(BridgeKind.EMULATOR, "Touch/mouse-look")
    }

    override fun disconnect() {
        sensorManager?.unregisterListener(rotationListener)
        sensorManager = null
        (poses as EmulatorPoseProvider).reset()
        _state.value = BridgeState.Disconnected
    }

    /** Feed drag deltas (radians). Positive [deltaYaw] turns the view left (world follows the finger). */
    fun look(deltaYaw: Float, deltaPitch: Float) {
        (poses as EmulatorPoseProvider).look(deltaYaw, deltaPitch)
    }
}

class EmulatorPoseProvider : HeadPoseProvider {

    private val _latest = MutableStateFlow(HeadPose.IDENTITY)
    override val latest: StateFlow<HeadPose> = _latest

    override var dof: DoF = DoF.THREE

    private var yaw = 0f
    private var pitch = 0f
    private val maxPitch = (89.0 * PI / 180.0).toFloat()

    /** Latest raw orientation (touch euler or sensor quaternion), [x,y,z,w]. */
    private var orientation = Quat.fromEulerYXZ(0f, 0f, 0f)
    private var sensorMode = false

    /** Session reference quaternion; published pose = conj(ref) * orientation. */
    private var reference = floatArrayOf(0f, 0f, 0f, 1f)

    fun look(deltaYaw: Float, deltaPitch: Float) {
        sensorMode = false
        yaw += deltaYaw
        pitch = max(-maxPitch, min(maxPitch, pitch + deltaPitch))
        orientation = Quat.fromEulerYXZ(yaw, pitch, 0f)
        publish()
    }

    fun setOrientation(q: FloatArray) {
        if (!sensorMode) {
            sensorMode = true
            // entering sensor mode: keep current view continuous
            reference = Quat.conjugate(q)
        }
        orientation = Quat.normalize(q)
        publish()
    }

    fun reset() {
        yaw = 0f
        pitch = 0f
        sensorMode = false
        orientation = Quat.fromEulerYXZ(0f, 0f, 0f)
        reference = floatArrayOf(0f, 0f, 0f, 1f)
        _latest.value = HeadPose.IDENTITY
    }

    override fun recenter() {
        reference = Quat.conjugate(orientation)
        publish()
    }

    private fun publish() {
        val q = Quat.normalize(Quat.mul(reference, orientation))
        val (yawNow, pitchNow, rollNow) = Quat.toEulerYXZ(q)
        _latest.value = HeadPose(
            qx = q[0], qy = q[1], qz = q[2], qw = q[3],
            yaw = yawNow, pitch = pitchNow, roll = rollNow,
            timestampNanos = SystemClock.elapsedRealtimeNanos(),
        )
    }
}
