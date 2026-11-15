package com.rokid.spatialhost.bridge.rokid

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.SystemClock
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
import com.rokid.spatialhost.bridge.viture.VitureHidBridge
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Rokid Max adapter — **raw USB HID path, no UXR SDK required**.
 *
 * The Rokid Max (1st gen) exposes an interrupt-IN HID interface streaming
 * 64-byte IMU reports at ~450 Hz. The layout was verified against live
 * captures (2026-11-15) and cross-checked with Rokid Spatial Studio:
 *
 * ```
 *  [0]      reportId 0x11
 *  [1..4]   i32 device timestamp (LE)
 *  [5..8]   i32 status/seq
 *  [9..20]  f32[3] accelerometer m/s²   (|a| ≈ 9.79 at rest ✓)
 *  [21..32] f32[3] gyroscope rad/s       (≈ 0.005 at rest ✓)
 *  [33..44] f32[3] magnetometer-ish (unused)
 * ```
 *
 * Feed → [MadgwickRokid] (validated frame handling + sanity gate) →
 * [QuaternionPoseProvider] (recenter + HeadPose for the renderer).
 *
 * When the Rokid UXR enterprise SDK is bundled later, [connect] can prefer it
 * for 6DoF; all SDK references stay inside this file ([UXR_ENTRY_CANDIDATES]).
 * The plain Max has no cameras, so raw-HID 3DoF is the full hardware budget.
 */
class RokidMaxBridge(private val context: Context) : GlassesBridge {

    private val _state = MutableStateFlow<BridgeState>(BridgeState.Disconnected)
    override val state: StateFlow<BridgeState> = _state

    override val poses: HeadPoseProvider = QuaternionPoseProvider()
    override val display: DisplayController = UnavailableDisplay

    private val filter = MadgwickRokid()

    private var connection: UsbDeviceConnection? = null
    private var claimedInterfaces = mutableListOf<android.hardware.usb.UsbInterface>()
    private var readerThreads = mutableListOf<Thread>()

    @Volatile
    private var running = false

    /** Stream health: last time ANY byte arrived from the glasses. */
    @Volatile
    private var lastPacketMs = 0L

    @Volatile
    private var streamStalled = false

    /** Diagnostics (HUD / logcat). */
    @Volatile
    var poseCount: Long = 0
        private set

    @Volatile
    var droppedPackets: Long = 0
        private set

    // ------------------------------------------------------------------
    // Auto-recenter (no-touch device: the OPi box has no screen input)
    // ------------------------------------------------------------------
    private var connectedAtMs = 0L
    private var stillSinceMs = 0L
    private var bootRecenterDone = false

    /**
     * Hands-free recentering, evaluated on every still sample:
     *  A) once at startup: head/glasses still for 1.5s within the first 30 s
     *     → zero the session (put the box up, wear the glasses, done);
     *  B) repeatable: still for 1.5 s while the session is clearly upside
     *     down (|pitch| or |roll| > 75°) → level it again. Normal head poses
     *     never trip (B), so this can't fight the user.
     */
    private fun maybeAutoRecenter(gyroMag: Float) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (gyroMag > STILL_GYRO_RAD_S) {
            stillSinceMs = 0L
            return
        }
        if (stillSinceMs == 0L) {
            stillSinceMs = now
            return
        }
        val stillMs = now - stillSinceMs
        if (stillMs < AUTO_REC_STILL_MS) return

        val inBootWindow = now - connectedAtMs < AUTO_REC_BOOT_WINDOW_MS
        if (!bootRecenterDone && inBootWindow) {
            poses.recenter()
            bootRecenterDone = true
            Log.i(TAG, "auto-recenter #1 (boot, still ${stillMs}ms)")
            stillSinceMs = 0L
            return
        }

        val pose = poses.latest.value
        val badlyTilted =
            kotlin.math.abs(pose.pitch) > AUTO_REC_DEVIATION_RAD ||
                kotlin.math.abs(pose.roll) > AUTO_REC_DEVIATION_RAD
        if (badlyTilted) {
            val provider = poses as? QuaternionPoseProvider
            Log.i(
                TAG,
                "auto-recenter BEFORE: pub=" +
                    "y${Math.toDegrees(pose.yaw.toDouble()).toInt()}" +
                    "/p${Math.toDegrees(pose.pitch.toDouble()).toInt()}" +
                    "/r${Math.toDegrees(pose.roll.toDouble()).toInt()} " +
                    (provider?.debugState() ?: ""),
            )
            poses.recenter()
            val after = poses.latest.value
            Log.i(
                TAG,
                "auto-recenter AFTER: " +
                    "y${Math.toDegrees(after.yaw.toDouble()).toInt()}" +
                    "/p${Math.toDegrees(after.pitch.toDouble()).toInt()}" +
                    "/r${Math.toDegrees(after.roll.toDouble()).toInt()} " +
                    (provider?.debugState() ?: ""),
            )
            stillSinceMs = 0L
        }
    }

    /** True when a Rokid UXR SDK entry class is on the classpath (future path). */
    val uxrSdkPresent: Boolean = UXR_ENTRY_CANDIDATES.firstOrNull { exists(it) } != null

    override suspend fun connect() {
        _state.value = BridgeState.Connecting
        if (uxrSdkPresent) {
            // Future: prefer UXR session for 6DoF on Max Pro. Until the SDK is
            // wired (connectUxr), fall through to the raw-HID path below.
            Log.i(TAG, "UXR SDK detected but adapter not wired — using raw USB HID")
        }

        val mgr = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val device = mgr.deviceList.values.firstOrNull { isRokidDevice(it) }
        if (device == null) {
            _state.value = BridgeState.Error(
                BridgeKind.ROKID_MAX,
                "Rokid Max not attached (VID 0x04D2)",
            )
            return
        }
        if (!mgr.hasPermission(device)) {
            if (isDenied(device.deviceName)) {
                _state.value = BridgeState.Error(
                    BridgeKind.ROKID_MAX,
                    "USB permission denied for this device (restart the app to re-ask)",
                )
                return
            }
            // Same app-wide action the MainActivity receiver already listens on.
            mgr.requestPermission(device, VitureHidBridge.permissionIntent(context))
            _state.value = BridgeState.Error(
                BridgeKind.ROKID_MAX,
                "USB permission requested — tap Allow on the dialog",
            )
            return
        }

        val conn = mgr.openDevice(device)
        if (conn == null) {
            _state.value = BridgeState.Error(BridgeKind.ROKID_MAX, "openDevice() failed")
            return
        }

        claimedInterfaces.clear()
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            if (intf.endpointList().any { it.isInterruptIn() }) {
                if (conn.claimInterface(intf, true)) claimedInterfaces.add(intf)
            }
        }
        if (claimedInterfaces.isEmpty()) {
            conn.close()
            _state.value = BridgeState.Error(BridgeKind.ROKID_MAX, "No interrupt-IN interface claimable")
            return
        }

        connection = conn
        running = true
        filter.reset()
        connectedAtMs = android.os.SystemClock.elapsedRealtime()
        stillSinceMs = 0L
        bootRecenterDone = false
        for (intf in claimedInterfaces) {
            val inEp = intf.endpointList().firstOrNull { it.isInterruptIn() } ?: continue
            val t = Thread({ readLoop(conn, inEp) }, "rokid-hid-${inEp.address}")
            t.isDaemon = true
            t.start()
            readerThreads.add(t)
        }

        _state.value = BridgeState.Connected(
            BridgeKind.ROKID_MAX,
            device.productName ?: "Rokid Max (pid 0x${device.productId.toString(16)})",
        )
        Log.i(TAG, "Rokid Max HID connected (${device.productId.toString(16)}), uxrPresent=$uxrSdkPresent")
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
        filter.reset()
        _state.value = BridgeState.Disconnected
    }

    // ------------------------------------------------------------------
    // Reader
    // ------------------------------------------------------------------

    private fun readLoop(conn: UsbDeviceConnection, endpoint: android.hardware.usb.UsbEndpoint) {
        val buf = ByteArray(64)
        var prevNanos = 0L
        while (running) {
            val len = conn.bulkTransfer(endpoint, buf, buf.size, 100)
            if (len != null && len > 0) {
                // Any byte = the link is alive (even if the payload is junk).
                val now = SystemClock.elapsedRealtime()
                lastPacketMs = now
                if (streamStalled) {
                    streamStalled = false
                    _state.value = BridgeState.Connected(
                        BridgeKind.ROKID_MAX,
                        connection?.let { "Rokid Max (stream resumed)" } ?: "Rokid Max",
                    )
                    Log.i(TAG, "IMU stream RESUMED — link alive again")
                }
                var dt = if (prevNanos > 0L) (now * 1_000_000L - prevNanos) / 1_000_000_000f else 0.0025f
                if (dt < 0.0005f || dt > 0.05f) dt = 0.0025f
                prevNanos = now * 1_000_000L
                try {
                    handlePacket(buf, len, dt)
                } catch (t: Throwable) {
                    Log.w(TAG, "packet parse failed: ${t.message}")
                }
            } else {
                // Watchdog: a short/tugged cable kills the stream while the
                // device stays enumerated — surface it instead of freezing
                // the view silently.
                val now = SystemClock.elapsedRealtime()
                if (!streamStalled && lastPacketMs > 0 &&
                    now - lastPacketMs > STALL_TIMEOUT_MS
                ) {
                    streamStalled = true
                    _state.value = BridgeState.Error(
                        BridgeKind.ROKID_MAX,
                        "IMU stream stalled — check the USB cable",
                    )
                    Log.w(TAG, "IMU stream STALLED: no bytes for ${STALL_TIMEOUT_MS}ms (cable?)")
                }
            }
        }
    }

    private fun handlePacket(buf: ByteArray, len: Int, dt: Float) {
        if (len < 33 || buf[0] != REPORT_ID_0X11) return
        val bb = ByteBuffer.wrap(buf, 0, len).order(ByteOrder.LITTLE_ENDIAN)
        val ax = bb.getFloat(ACCEL_OFFSET)
        val ay = bb.getFloat(ACCEL_OFFSET + 4)
        val az = bb.getFloat(ACCEL_OFFSET + 8)
        val gx = bb.getFloat(GYRO_OFFSET)
        val gy = bb.getFloat(GYRO_OFFSET + 4)
        val gz = bb.getFloat(GYRO_OFFSET + 8)

        // Sanity gate — malformed packets must never reach the AHRS.
        val accelMag = kotlin.math.sqrt(ax * ax + ay * ay + az * az)
        val gyroMag = kotlin.math.sqrt(gx * gx + gy * gy + gz * gz)
        if (!accelMag.isFinite() || !gyroMag.isFinite() ||
            gyroMag > MAX_GYRO_RAD_S || accelMag < MIN_ACCEL_MPS2 || accelMag > MAX_ACCEL_MPS2
        ) {
            droppedPackets++
            if (droppedPackets <= 5) {
                Log.w(TAG, "dropped malformed packet #$droppedPackets (gyro=$gyroMag accel=$accelMag)")
            }
            return
        }

        // Level the view instantly at boot from a still accelerometer reading.
        if (!filter.isInitializedFromAccel && gyroMag < 1.0f) {
            filter.initFromAccel(ax, ay, az)
        }

        if (filter.update(gx, gy, gz, ax, ay, az, dt)) {
            (poses as QuaternionPoseProvider).setOrientation(filter.cameraQuat())
            poseCount++
            maybeAutoRecenter(gyroMag)
        }
    }

    private fun exists(name: String): Boolean = try {
        Class.forName(name)
        true
    } catch (_: ClassNotFoundException) {
        false
    }

    companion object {
        private const val TAG = "RokidMaxBridge"

        const val VID = 0x04D2 // Rokid Corporation Ltd.
        private const val REPORT_ID_0X11: Byte = 0x11
        private const val ACCEL_OFFSET = 9
        private const val GYRO_OFFSET = 21

        // Gate values validated on real traffic (see MadgwickRokid docs)
        private const val MAX_GYRO_RAD_S = 35f
        private const val MIN_ACCEL_MPS2 = 6f
        private const val MAX_ACCEL_MPS2 = 14f

        // Auto-recenter policy (see maybeAutoRecenter)
        private const val STILL_GYRO_RAD_S = 0.5f // ≈29°/s — "the head is not moving"
        private const val AUTO_REC_STILL_MS = 1500L
        private const val AUTO_REC_BOOT_WINDOW_MS = 30_000L
        // ≈52°: covers glasses left lying at an angle (observed 61…161°) while
        // never tripping for a normally worn, upright head.
        private const val AUTO_REC_DEVIATION_RAD = 0.9f

        /** No IMU bytes for this long = the (short!) cable got tugged. */
        private const val STALL_TIMEOUT_MS = 3000L

        /**
         * Devices the user explicitly denied USB permission for (process
         * lifetime) — prevents an infinite permission-dialog/retry loop when
         * the chain re-runs after a DENY.
         */
        private val deniedDeviceNames = mutableSetOf<String>()

        fun markPermissionDenied(deviceName: String) {
            synchronized(deniedDeviceNames) { deniedDeviceNames.add(deviceName) }
        }

        private fun isDenied(deviceName: String): Boolean =
            synchronized(deniedDeviceNames) { deviceName in deniedDeviceNames }

        fun isRokidDevice(device: UsbDevice): Boolean = device.vendorId == VID

        /**
         * Known Rokid product IDs (informational — we accept the whole VID so
         * unlisted revisions also work): 0x162f Max 1st Gen, 0x162d Max/Air
         * Bridge, 0x1630 Rev B, 0x1631 IMU, 0x1632 Telemetry, 0x1639 Max
         * Series, 0x2002 Air/Max Legacy.
         */

        /**
         * Candidate UXR SDK entry classes — REPLACE/EXTEND with the real
         * names from your UXR package when the enterprise SDK is bundled.
         */
        val UXR_ENTRY_CANDIDATES = arrayOf(
            "com.rokid.uxr.sdk.UxrClient",
            "com.rokid.uxr.UxrSession",
            "com.rokid.ar.uxr.UXR",
        )
    }
}
