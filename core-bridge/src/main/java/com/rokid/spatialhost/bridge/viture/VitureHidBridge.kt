package com.rokid.spatialhost.bridge.viture

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.util.Log
import com.rokid.spatialhost.bridge.BridgeKind
import com.rokid.spatialhost.bridge.BridgeState
import com.rokid.spatialhost.bridge.DisplayController
import com.rokid.spatialhost.bridge.GlassesBridge
import com.rokid.spatialhost.bridge.HeadPoseProvider
import com.rokid.spatialhost.bridge.QuaternionPoseProvider
import com.rokid.spatialhost.bridge.UnavailableDisplay
import com.rokid.spatialhost.bridge.endpointList
import com.rokid.spatialhost.bridge.isInterruptIn
import com.rokid.spatialhost.bridge.isInterruptOut
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Native Android port of the VITURE glasses HID bridge.
 *
 * This is the Android equivalent of VertoXR Web's `/ar-bridge-web/viture_glasses_sdk`
 * (WebHID + WASM), reimplemented from the community reverse-engineering:
 *
 *  - **Gen2** frames: preamble `0x0010` LE, MsgID, PayloadLen, checksum (byte
 *    sum of payload), payload — see elasticjava/viture-v2 PROTOCOL.md.
 *    IMU pose stream is enabled with MsgID `0x0301` payload `[stream=1, rate=2]`
 *    (120 Hz) and arrives as `0x7308` (24-byte payload: u32, u32 ts, qw,qx,qy,qz).
 *    **No handshake is required** — open the device and send one command.
 *  - **Gen1** exposes a dedicated 64-byte IMU interrupt report carrying Euler
 *    angles as big-endian f32 at offsets 18/22/26 (Z, X, Y axes, NWU frame,
 *    degrees). Stream enabled via classic MCU frame, CmdIMU `0x15`.
 *
 * Coordinate conversion: VITURE reports orientation in **NWU** (X north, Y
 * west, Z up); the engine uses GL convention (+X right, +Y up, -Z forward).
 * [basisChangeNwuToGl] applies the similarity transform — **verify the axis
 * signs on real hardware** (constants isolated at the bottom of this file).
 *
 * Display control (brightness/volume/2D-3D) is intentionally NOT implemented:
 * the setter MsgIDs are unmapped in public RE and probing changes device
 * state. Pose is the mission-critical part.
 */
class VitureHidBridge(private val context: Context) : GlassesBridge {

    private val _state = MutableStateFlow<BridgeState>(BridgeState.Disconnected)
    override val state: StateFlow<BridgeState> = _state

    override val poses: HeadPoseProvider = QuaternionPoseProvider()
    override val display: DisplayController = UnavailableDisplay

    private var connection: UsbDeviceConnection? = null
    private var claimedInterfaces = mutableListOf<UsbInterface>()
    private var readerThreads = mutableListOf<Thread>()
    private var gen2 = false

    @Volatile
    private var running = false

    /** Diagnostics counters (visible in HUD/logcat). */
    @Volatile
    var poseCount: Long = 0
        private set

    @Volatile
    var rawCount: Long = 0
        private set

    override suspend fun connect() {
        _state.value = BridgeState.Connecting
        val mgr = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val device = mgr.deviceList.values.firstOrNull { isVitureDevice(it) }
        if (device == null) {
            _state.value = BridgeState.Error(
                BridgeKind.VITURE,
                "No Viture glasses attached (VID 0x${VID.toString(16)})",
            )
            return
        }
        if (!mgr.hasPermission(device)) {
            _state.value = BridgeState.Error(
                BridgeKind.VITURE,
                "USB permission required for ${device.productName ?: "Viture glasses"}",
            )
            return
        }
        val conn = mgr.openDevice(device)
        if (conn == null) {
            _state.value = BridgeState.Error(BridgeKind.VITURE, "openDevice() failed")
            return
        }

        gen2 = isGen2Pid(device.productId)
        claimedInterfaces.clear()
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            if (intf.endpointList().any { it.isInterruptIn() }) {
                if (conn.claimInterface(intf, true)) claimedInterfaces.add(intf)
            }
        }
        if (claimedInterfaces.isEmpty()) {
            conn.close()
            _state.value = BridgeState.Error(BridgeKind.VITURE, "No interrupt-IN interface claimable")
            return
        }

        connection = conn
        running = true
        for (intf in claimedInterfaces) {
            val inEp = intf.endpointList().firstOrNull { it.isInterruptIn() } ?: continue
            val t = Thread({ readLoop(conn, inEp) }, "viture-hid-${inEp.address}")
            t.isDaemon = true
            t.start()
            readerThreads.add(t)
        }

        // Enable the pose stream (readers already running so the ACK is seen).
        val outEp = claimedInterfaces.firstNotNullOfOrNull { intf ->
            intf.endpointList().firstOrNull { it.isInterruptOut() }
        }
        if (outEp != null) {
            val cmd = if (gen2) buildGen2Frame(MSG_IMU_CTRL, byteArrayOf(1, RATE_120HZ))
            else buildGen1ImuCommand(on = true, counter = 0)
            conn.bulkTransfer(outEp, cmd, cmd.size, 500)
            Log.i(TAG, "sent IMU enable (${if (gen2) "gen2" else "gen1"}, ${cmd.size} bytes)")
        }

        _state.value = BridgeState.Connected(
            BridgeKind.VITURE,
            device.productName ?: "Viture (pid 0x${device.productId.toString(16)})",
        )
    }

    override fun disconnect() {
        running = false
        for (t in readerThreads) t.join(200)
        readerThreads.clear()
        val conn = connection
        if (conn != null) {
            for (intf in claimedInterfaces) conn.releaseInterface(intf)
            conn.close()
        }
        connection = null
        claimedInterfaces.clear()
        _state.value = BridgeState.Disconnected
    }

    // ------------------------------------------------------------------
    // Reader
    // ------------------------------------------------------------------

    private fun readLoop(conn: UsbDeviceConnection, endpoint: UsbEndpoint) {
        val buf = ByteArray(64)
        while (running) {
            val len = conn.bulkTransfer(endpoint, buf, buf.size, 100)
            if (len == null || len <= 0) continue
            try {
                handlePacket(buf, len)
            } catch (t: Throwable) {
                Log.w(TAG, "packet parse failed: ${t.message}")
            }
        }
    }

    private fun handlePacket(buf: ByteArray, len: Int) {
        if (gen2) {
            if (len < 8 || buf[0] != 0x10.toByte() || buf[1] != 0x00.toByte()) return
            val msgId = le16(buf, 2)
            val payloadLen = le16(buf, 4)
            if (payloadLen < 0 || 8 + payloadLen > len) return
            when (msgId) {
                MSG_POSE -> {
                    if (payloadLen < 24) return
                    val bb = ByteBuffer.wrap(buf, 8, payloadLen).order(ByteOrder.LITTLE_ENDIAN)
                    bb.int // unknown field
                    bb.int // device timestamp
                    val qw = bb.float
                    val qx = bb.float
                    val qy = bb.float
                    val qz = bb.float
                    if (!qw.isFinite()) return
                    (poses as QuaternionPoseProvider).setOrientation(
                        basisChangeNwuToGl(floatArrayOf(qx, qy, qz, qw)),
                    )
                    poseCount++
                }
                MSG_RAW -> rawCount++
                MSG_IMU_CTRL_ACK -> Log.i(TAG, "IMU ctrl ack status=${if (payloadLen > 0) buf[8] else -1}")
                else -> Log.d(TAG, "gen2 msg 0x${msgId.toString(16)} len=$payloadLen")
            }
        } else {
            // Gen1: dedicated IMU reports — BE f32 euler at 18/22/26 (Z,X,Y).
            if (len < 38 || buf[0] != 0x10.toByte()) return
            val a = beFloat(buf, 18) // Z axis (yaw)
            val b = beFloat(buf, 22) // X axis (roll)
            val c = beFloat(buf, 26) // Y axis (pitch)
            if (!a.isFinite() || !b.isFinite() || !c.isFinite()) return
            if (abs(a) > 720f || abs(b) > 720f || abs(c) > 720f) return
            val yaw = Math.toRadians(a.toDouble()).toFloat()
            val roll = Math.toRadians(b.toDouble()).toFloat()
            val pitch = Math.toRadians(c.toDouble()).toFloat()
            // NWU: yaw about Z-up, pitch about Y-west, roll about X-north.
            val qz = axisAngle(0f, 0f, 1f, yaw)
            val qy = axisAngle(0f, 1f, 0f, pitch)
            val qx = axisAngle(1f, 0f, 0f, roll)
            val qNwu = normalizeQuat(mulQuat(mulQuat(qz, qy), qx))
            (poses as QuaternionPoseProvider).setOrientation(basisChangeNwuToGl(qNwu))
            poseCount++
        }
    }

    // ------------------------------------------------------------------
    // Protocol helpers
    // ------------------------------------------------------------------

    /** Gen2 frame: 0x0010 LE | MsgID LE | PayloadLen LE | sum(payload) LE | payload. */
    private fun buildGen2Frame(msgId: Int, payload: ByteArray): ByteArray {
        val out = ByteArray(8 + payload.size)
        out[0] = 0x10
        out[1] = 0x00
        putLe16(out, 2, msgId)
        putLe16(out, 4, payload.size)
        var sum = 0
        for (b in payload) sum = (sum + (b.toInt() and 0xFF)) and 0xFFFF
        putLe16(out, 6, sum)
        System.arraycopy(payload, 0, out, 8, payload.size)
        return out
    }

    /**
     * Gen1 classic MCU frame for CmdIMU (0x15), payload single byte on/off.
     * Layout (LE except CRC): header 0xFFFE | CRC16-CCITT-FALSE over [4..end]
     * | length(total-8) | reserved*8 | cmd | counter | payload | end 0x03.
     */
    private fun buildGen1ImuCommand(on: Boolean, counter: Int): ByteArray {
        val payload = byteArrayOf(if (on) 1 else 0)
        val total = 0x12 + payload.size + 1
        val out = ByteArray(total)
        putLe16(out, 0, 0xFFFE)
        putLe16(out, 4, total - 8)
        putLe16(out, 0x0E, 0x0015)
        putLe16(out, 0x10, counter)
        System.arraycopy(payload, 0, out, 0x12, payload.size)
        out[total - 1] = 0x03
        val crc = crc16CcittFalse(out, 4, total - 4)
        out[2] = ((crc shr 8) and 0xFF).toByte() // CRC is big-endian
        out[3] = (crc and 0xFF).toByte()
        return out
    }

    companion object {
        private const val TAG = "VitureHidBridge"

        const val VID = 0x35CA
        const val PERMISSION_ACTION = "com.rokid.spatialhost.USB_PERMISSION"

        // PIDs from VertoXR glasses.js (VITURE_PIDS).
        private val GEN1_PIDS = intArrayOf(
            4113, 4115, 4117, 4119, 4121, 4123, 4125, 4385, 4401, 4417, 4433,
        )

        const val MSG_IMU_CTRL = 0x0301
        const val MSG_IMU_CTRL_ACK = 0x2301
        const val MSG_POSE = 0x7308
        const val MSG_RAW = 0x7309
        private const val RATE_120HZ: Byte = 2

        fun isGen2Pid(pid: Int): Boolean {
            val prefix = pid and 0xFF00
            return prefix == 0x1200 || prefix == 0x1300
        }

        fun isVitureDevice(device: UsbDevice): Boolean {
            if (device.vendorId != VID) return false
            return isGen2Pid(device.productId) || GEN1_PIDS.contains(device.productId)
        }

        fun hasPermission(context: Context, device: UsbDevice): Boolean {
            val mgr = context.getSystemService(Context.USB_SERVICE) as UsbManager
            return mgr.hasPermission(device)
        }

        fun permissionIntent(context: Context): PendingIntent {
            val intent = Intent(PERMISSION_ACTION).setPackage(context.packageName)
            return PendingIntent.getBroadcast(
                context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
        }

        fun findDevice(context: Context): UsbDevice? {
            val mgr = context.getSystemService(Context.USB_SERVICE) as UsbManager
            return mgr.deviceList.values.firstOrNull { isVitureDevice(it) }
        }

        private fun le16(b: ByteArray, off: Int): Int =
            (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

        private fun putLe16(b: ByteArray, off: Int, v: Int) {
            b[off] = (v and 0xFF).toByte()
            b[off + 1] = ((v shr 8) and 0xFF).toByte()
        }

        private fun beFloat(b: ByteArray, off: Int): Float =
            ByteBuffer.wrap(b, off, 4).order(ByteOrder.BIG_ENDIAN).float

        private fun crc16CcittFalse(b: ByteArray, from: Int, count: Int): Int {
            var crc = 0xFFFF
            for (i in from until from + count) {
                crc = crc xor ((b[i].toInt() and 0xFF) shl 8)
                for (k in 0 until 8) {
                    crc = if ((crc and 0x8000) != 0) ((crc shl 1) xor 0x1021) and 0xFFFF
                    else (crc shl 1) and 0xFFFF
                }
            }
            return crc
        }

        private fun axisAngle(ax: Float, ay: Float, az: Float, angle: Float): FloatArray {
            val s = sin(angle / 2f)
            return floatArrayOf(ax * s, ay * s, az * s, cos(angle / 2f))
        }

        private fun mulQuat(a: FloatArray, b: FloatArray): FloatArray {
            val (ax, ay, az, aw) = a
            val (bx, by, bz, bw) = b
            return floatArrayOf(
                aw * bx + ax * bw + ay * bz - az * by,
                aw * by - ax * bz + ay * bw + az * bx,
                aw * bz + ax * by - ay * bx + az * bw,
                aw * bw - ax * bx - ay * by - az * bz,
            )
        }

        private fun normalizeQuat(q: FloatArray): FloatArray {
            val len = kotlin.math.sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3])
            if (len < 1e-9f) return floatArrayOf(0f, 0f, 0f, 1f)
            return floatArrayOf(q[0] / len, q[1] / len, q[2] / len, q[3] / len)
        }

        /**
         * Similarity transform NWU → GL: q_gl = qC * q_nwu * qC^-1 with
         * qC = [-0.5, 0.5, 0.5, 0.5] (120° rotation mapping north→-Z, west→-X,
         * up→+Y). TODO(verify): confirm signs on real glasses.
         */
        private val Q_BASIS = floatArrayOf(-0.5f, 0.5f, 0.5f, 0.5f)
        private val Q_BASIS_INV = floatArrayOf(0.5f, -0.5f, -0.5f, -0.5f)

        fun basisChangeNwuToGl(q: FloatArray): FloatArray =
            normalizeQuat(mulQuat(mulQuat(Q_BASIS, q), Q_BASIS_INV))
    }
}
