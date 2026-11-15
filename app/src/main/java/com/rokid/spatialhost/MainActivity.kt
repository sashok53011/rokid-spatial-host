package com.rokid.spatialhost

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import com.rokid.spatialhost.bridge.BridgeState
import com.rokid.spatialhost.bridge.GlassesBridge
import com.rokid.spatialhost.bridge.UsbDiagnostics
import com.rokid.spatialhost.bridge.emulator.EmulatorBridge
import com.rokid.spatialhost.bridge.rokid.RokidMaxBridge
import com.rokid.spatialhost.bridge.viture.VitureHidBridge
import com.rokid.spatialhost.battery.PowerReport
import com.rokid.spatialhost.battery.ThermalGovernor
import com.rokid.spatialhost.input.GestureRecognizer
import com.rokid.spatialhost.input.InputEvent
import com.rokid.spatialhost.input.NavigationState
import com.rokid.spatialhost.render.FilamentRenderer
import com.rokid.spatialhost.scene.LayoutEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Single-activity spatial host.
 *
 * Bridge chain (first that connects wins, later ones are fallbacks):
 *   1. RokidMaxBridge  — UXR SDK when bundled
 *   2. VitureHidBridge — USB-HID glasses
 *   3. EmulatorBridge   — touch/mouse-look (always available)
 *
 * Touch model (Phase 1): drag = look around, tap = select via3DoF ray,
 * double-tap = recenter, swipe = cycle focused panel (or arrow keys),
 * H = HUD, R = recenter.
 */
class MainActivity : Activity() {

    private lateinit var root: FrameLayout
    private lateinit var surfaceView: SurfaceView
    private lateinit var hud: TextView
    private lateinit var renderer: FilamentRenderer
    private lateinit var layoutEngine: LayoutEngine
    private lateinit var recognizer: GestureRecognizer
    private val navState = NavigationState()
    private lateinit var thermal: ThermalGovernor
    private lateinit var power: PowerReport
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var activeBridge: GlassesBridge? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // 360° video playback (Phase B2)
    private var streamPlayer: com.rokid.spatialhost.video.StreamPlayer? = null

    @Volatile
    private var videoStatus = "-" // - | loading | streaming | stopping | err:CODE
    private var sourceIndex = 0

    /** Public streams first; local files are appended by the background scan. */
    private val videoSources = mutableListOf<Pair<String, String>>()

    // touch/mouse state
    private var lastX = 0f
    private var lastY = 0f
    private var moved = false
    private var lastTapTime = 0L

    // Manual look offset on top of head pose (mouse drag with glasses active).
    @Volatile
    private var lookYaw = 0f

    @Volatile
    private var lookPitch = 0f

    @Volatile
    private var useLookOffset = false

    private val hudTick = object : Runnable {
        override fun run() {
            updateHud()
            mainHandler.postDelayed(this, 1000)
        }
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                VitureHidBridge.PERMISSION_ACTION -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    @Suppress("DEPRECATION")
                    val device: android.hardware.usb.UsbDevice? =
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    if (!granted && device != null) {
                        Log.i(TAG, "USB permission DENIED for ${device.deviceName}")
                        com.rokid.spatialhost.bridge.rokid.RokidMaxBridge
                            .markPermissionDenied(device.deviceName)
                    }
                    Log.i(TAG, "USB permission broadcast (granted=$granted) — retrying bridge chain")
                    connectChain()
                }

                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    @Suppress("DEPRECATION")
                    val device: android.hardware.usb.UsbDevice? =
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    if (device != null && device.vendorId == com.rokid.spatialhost.bridge.rokid.RokidMaxBridge.VID) {
                        Log.w(TAG, "Rokid glasses UNPLUGGED — deactivating bridge")
                        activeBridge?.disconnect()
                        activeBridge = null
                        renderer.poseSupplier = null
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enterImmersive()

        root = FrameLayout(this)
        surfaceView = SurfaceView(this)
        hud = TextView(this).apply {
            setTextColor(0xE0FFFFFF.toInt())
            setBackgroundColor(0x66000000)
            textSize = 12f
            setPadding(24, 16, 26, 16)
            typeface = android.graphics.Typeface.MONOSPACE
            isSingleLine = false
        }

        root.addView(
            surfaceView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        root.addView(
            hud,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ).apply { gravity = android.view.Gravity.TOP or android.view.Gravity.START },
        )
        setContentView(root)

        renderer = FilamentRenderer(surfaceView)
        layoutEngine = LayoutEngine().apply { defaultArc(count = 3) }
        renderer.setPanels(
            layoutEngine.panels.values.map {
                com.rokid.spatialhost.render.PanelSpec(
                    it.id, it.widthM, it.heightM,
                    it.position.x, it.position.y, it.position.z,
                )
            },
        )
        recognizer = GestureRecognizer()
        thermal = ThermalGovernor(this)
        power = PowerReport(this)

        renderer.onStats = { fps, frameMs ->
            // stats arrive on render thread; HUD text is rebuilt on main below
        }
        wireTouch()
        registerUsbReceiver()

        thermal.start()
        power.start()
        mainHandler.post(hudTick)

        // Sources must exist before the auto-start timer fires.
        videoSources.addAll(PUBLIC_SOURCES)
        scanLocalVideos()

        Log.i(TAG, UsbDiagnostics.describe(getSystemService(Context.USB_SERVICE) as UsbManager))
        connectChain()
    }

    /**
     * Add playable local files (USB stick / internal storage) to the wheel.
     * IO runs off the main thread; the list is only mutated on main.
     */
    private fun scanLocalVideos() {
        scope.launch {
            val found = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val out = mutableListOf<Pair<String, String>>()
                for (dirPath in LOCAL_VIDEO_DIRS) {
                    val root = java.io.File(dirPath)
                    if (!root.isDirectory) continue
                    try {
                        root.walkTopDown()
                            .onFail { _, _ -> }
                            .filter { f -> f.isFile && f.extension.lowercase() in VIDEO_EXTENSIONS }
                            .take(100 - out.size)
                            .forEach { f ->
                                out.add(
                                    "file: ${f.nameWithoutExtension}" to
                                        android.net.Uri.fromFile(f).toString(),
                                )
                            }
                    } catch (t: Throwable) {
                        Log.w(TAG, "scan $dirPath failed: ${t.message}")
                    }
                    if (out.size >= 100) break
                }
                out
            }
            if (found.isNotEmpty()) {
                videoSources.addAll(found)
                Log.i(TAG, "local videos: +${found.size} (${found.take(3).joinToString { it.first }})")
            } else {
                Log.i(TAG, "local videos: none found in $LOCAL_VIDEO_DIRS")
            }
        }
    }

    override fun onStart() {
        super.onStart()
        thermal.start()
    }

    override fun onStop() {
        thermal.stop()
        super.onStop()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(hudTick)
        unregisterUsbReceiver()
        scope.cancel()
        activeBridge?.disconnect()
        streamPlayer?.release()
        streamPlayer = null
        renderer.stopThread()
        power.sample()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            Log.i(TAG, "glasses attached — reconnecting bridge chain")
            connectChain()
        }
    }

    // ------------------------------------------------------------------
    // Bridge chain
    // ------------------------------------------------------------------

    private fun connectChain() {
        scope.launch {
            activeBridge?.disconnect()
            activeBridge = null

            val rokid = RokidMaxBridge(applicationContext)
            rokid.connect()
            val rokidState = rokid.state.value
            if (activateIfReady(rokid)) return@launch
            Log.i(TAG, "rokid bridge unavailable: $rokidState")

            val viture = VitureHidBridge(applicationContext)
            viture.connect()
            val vitureState = viture.state.value
            if (activateIfReady(viture)) return@launch
            Log.i(TAG, "viture bridge unavailable: $vitureState")

            val emu = EmulatorBridge(applicationContext, useDeviceSensors = false)
            emu.connect()
            activateIfReady(emu)
        }
    }

    private suspend fun activateIfReady(bridge: GlassesBridge): Boolean {
        val s = bridge.state.value
        if (s !is BridgeState.Connected) {
            bridge.disconnect()
            return false
        }
        activeBridge = bridge
        // With real glasses the IMU owns orientation; mouse drag then steers a
        // manual look offset layered on top. The emulator bakes drag into its
        // own pose, so it must NOT double-apply the offset.
        useLookOffset = bridge !is EmulatorBridge
        renderer.poseSupplier = {
            val p = bridge.poses.latest.value
            var q = floatArrayOf(p.qx, p.qy, p.qz, p.qw)
            if (useLookOffset) {
                val offset = com.rokid.spatialhost.bridge.Quat
                    .fromEulerYXZ(lookYaw, lookPitch, 0f)
                q = com.rokid.spatialhost.bridge.Quat.mul(q, offset)
            }
            q
        }
        Log.i(TAG, "active bridge: $s (lookOffset=$useLookOffset)")
        if (AUTO_START_VIDEO) {
            mainHandler.postDelayed({ if (activeBridge === bridge && !videoActive) startVideo() }, 3000)
        }
        return true
    }

    // ------------------------------------------------------------------
    // Input
    // ------------------------------------------------------------------

    private fun wireTouch() {
        val radPerPx = 0.004f
        surfaceView.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // Mouse-only affordances (the OPi box has no keyboard):
                    // right button = recenter, middle button = video toggle.
                    if (ev.buttonState == MotionEvent.BUTTON_SECONDARY) {
                        handleGesture(com.rokid.spatialhost.input.Gesture.RECENTER)
                        return@setOnTouchListener true
                    }
                    if (ev.buttonState == MotionEvent.BUTTON_TERTIARY) {
                        toggleVideo()
                        return@setOnTouchListener true
                    }
                    lastX = ev.x
                    lastY = ev.y
                    moved = false
                    true
                }

                MotionEvent.ACTION_SCROLL -> {
                    val wheel = ev.getAxisValue(MotionEvent.AXIS_VSCROLL)
                    if (wheel > 0.5f) nextVideoSource()
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.x - lastX
                    val dy = ev.y - lastY
                    if (hypot(dx, dy) > 8f) moved = true
                    if (moved) {
                        val emu = activeBridge as? EmulatorBridge
                        if (emu != null) {
                            emu.look(dx * radPerPx, -dy * radPerPx)
                        } else {
                            // Glasses own orientation — mouse steers a manual
                            // offset layered on top in the poseSupplier.
                            lookYaw += dx * radPerPx
                            lookPitch = (lookPitch - dy * radPerPx)
                                .coerceIn(-MAX_LOOK_PITCH, MAX_LOOK_PITCH)
                        }
                        recognizer.onEvent(InputEvent.Drag(dx, dy))
                    }
                    lastX = ev.x
                    lastY = ev.y
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        val now = System.currentTimeMillis()
                        val gesture =
                            if (now - lastTapTime < 300) InputEvent.DoubleTap else InputEvent.Tap
                        lastTapTime = now
                        handleGesture(recognizer.onEvent(gesture))
                    }
                    true
                }

                else -> false
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_V -> {
                toggleVideo()
                return true
            }

            KeyEvent.KEYCODE_N -> {
                nextVideoSource()
                return true
            }
        }
        handleGesture(recognizer.onEvent(InputEvent.Key(keyCode)))
        return super.onKeyDown(keyCode, event)
    }

    // ------------------------------------------------------------------
    // Phase B2: 360° video sphere (V = toggle, N = next source)
    // ------------------------------------------------------------------

    private var videoActive = false // main thread only

    /**
     * V toggles the backdrop between the flat tinted sphere and the video
     * sphere fed by [videoSources] (HLS/equirect today; any `rtsp://` URL
     * also works — the media3 RTSP module is on the classpath).
     */
    private fun toggleVideo() {
        if (videoActive) stopVideo() else startVideo()
    }

    private fun startVideo() {
        if (videoActive) return
        videoActive = true
        videoStatus = "loading"
        val url = videoSources[sourceIndex].second
        renderer.requestVideoSphere { surface ->
            // Main thread (posted by the renderer).
            val player = streamPlayer ?: com.rokid.spatialhost.video.StreamPlayer(this).also { p ->
                streamPlayer = p
                p.onError = { e ->
                    videoStatus = "err:${e.errorCodeName}"
                    Log.w(TAG, "video error: ${e.errorCodeName}")
                }
                p.onFirstFrame = {
                    videoStatus = "streaming"
                    Log.i(TAG, "video first frame")
                }
            }
            player.setSurface(surface)
            player.prepare(url, lowLatency = true)
        }
    }

    private fun stopVideo() {
        if (!videoActive) return
        videoActive = false
        videoStatus = "stopping"
        val player = streamPlayer
        player?.pause()
        player?.setSurface(null) // detach BEFORE the renderer releases the Surface
        renderer.requestColorSphere {
            videoStatus = "-"
            Log.i(TAG, "video stopped — color sphere restored")
        }
    }

    private fun nextVideoSource() {
        sourceIndex = (sourceIndex + 1) % videoSources.size
        Log.i(TAG, "video source → ${videoSources[sourceIndex].first}")
        if (videoActive) {
            // Rapid stop→start resolves correctly: the render loop processes
            // the color (detach) request before the video (attach) request.
            stopVideo()
            startVideo()
        }
    }

    private fun handleGesture(gesture: com.rokid.spatialhost.input.Gesture?) {
        when (gesture) {
            com.rokid.spatialhost.input.Gesture.RECENTER -> {
                activeBridge?.poses?.recenter()
                // Manual look offset is part of "where I'm pointing now" —
                // zero it together with the pose.
                lookYaw = 0f
                lookPitch = 0f
            }
            com.rokid.spatialhost.input.Gesture.TOGGLE_HUD -> {
                navState.hudVisible = !navState.hudVisible
                hud.visibility = if (navState.hudVisible) View.VISIBLE else View.GONE
            }

            com.rokid.spatialhost.input.Gesture.SELECT -> selectAtCrosshair()
            com.rokid.spatialhost.input.Gesture.SWIPE_LEFT -> cycleFocus(-1)
            com.rokid.spatialhost.input.Gesture.SWIPE_RIGHT -> cycleFocus(+1)
            com.rokid.spatialhost.input.Gesture.BACK -> Unit
            null -> Unit
        }
    }

    /** 3DoF ray from current head pose → select the panel under the crosshair. */
    private fun selectAtCrosshair() {
        val pose = activeBridge?.poses?.latest?.value ?: return
        val q = floatArrayOf(pose.qx, pose.qy, pose.qz, pose.qw)
        val f = rotateVec(q, 0f, 0f, -1f)
        val dir = com.rokid.spatialhost.scene.Vec3(f[0], f[1], f[2])
        val hit = layoutEngine.hitTest(dir)
        if (hit != null) {
            navState.selectedPanelId = hit.panel.id
            navState.focusedPanelId = hit.panel.id
            renderer.setFocusedPanel(hit.panel.id)
            Log.i(TAG, "selected ${hit.panel.id} at ${"%.2f".format(hit.distance)}m u=${"%.2f".format(hit.u)} v=${"%.2f".format(hit.v)}")
        } else {
            navState.selectedPanelId = null
            Log.i(TAG, "select: no panel under crosshair")
        }
    }

    private fun cycleFocus(step: Int) {
        val ids = layoutEngine.panels.keys.toList()
        if (ids.isEmpty()) return
        val current = navState.focusedPanelId
        val idx = if (current == null) -step else (ids.indexOf(current) + step + ids.size) % ids.size
        navState.focusedPanelId = ids[idx.coerceIn(0, ids.size - 1)]
        renderer.setFocusedPanel(navState.focusedPanelId)
        Log.i(TAG, "focus → ${navState.focusedPanelId}")
    }

    // ------------------------------------------------------------------
    // HUD
    // ------------------------------------------------------------------

    private fun updateHud() {
        power.sample()
        renderer.targetFps = thermal.budget.value.targetFps

        val bridgeState = activeBridge?.state?.value
        val bridgeLabel = when (bridgeState) {
            is BridgeState.Connected -> "${bridgeState.kind} · ${bridgeState.name}"
            is BridgeState.Error -> "ERR ${bridgeState.kind}: ${bridgeState.message.take(60)}"
            else -> bridgeState?.toString() ?: "none"
        }
        val pose = activeBridge?.poses?.latest?.value
        val stats = power.stats.value
        val budget = thermal.budget.value

        hud.text = buildString {
            append("Spatial Host v0.1\n")
            append("bridge : $bridgeLabel\n")
            // Live IMU stream counters — makes a dead/tugged cable obvious.
            val rb = activeBridge as? com.rokid.spatialhost.bridge.rokid.RokidMaxBridge
            if (rb != null) {
                append("stream : pkt=${rb.poseCount} drop=${rb.droppedPackets}\n")
            }
            append(
                "fps    : ${"%.0f".format(renderer.measuredFps)} " +
                    "(target ${budget.targetFps}, thermal ${budget.thermalStatus})\n",
            )
            if (pose != null) {
                append(
                    "pose   : yaw ${Math.toDegrees(pose.yaw.toDouble()).toInt()}° " +
                        "pitch ${Math.toDegrees(pose.pitch.toDouble()).toInt()}° " +
                        "roll ${Math.toDegrees(pose.roll.toDouble()).toInt()}°\n",
                )
            }
            append("panels : ${layoutEngine.panels.size} · focus=${navState.focusedPanelId ?: "-"} · sel=${navState.selectedPanelId ?: "-"}\n")
            append(
                "power  : ${stats.mahUsed.toInt()} mAh · ${"%.0f".format(stats.drainMa)} mA · " +
                    (if (stats.batteryPercent in 0..100) "${stats.batteryPercent}%" else "?") +
                    " · ${stats.elapsedSeconds}s\n",
            )
            append("touch  : drag=look tap=select 2x-tap=recenter swipe/←→=focus H=hud V=video")
            append("\n")
            append(
                "video  : $videoStatus · ${videoSources[sourceIndex].first} " +
                    "(V=on/off N=source)\n",
            )
        }
    }

    // ------------------------------------------------------------------
    // USB permission
    // ------------------------------------------------------------------

    private fun registerUsbReceiver() {
        val filter = IntentFilter().apply {
            addAction(VitureHidBridge.PERMISSION_ACTION)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(usbReceiver, filter)
        }
    }

    private fun unregisterUsbReceiver() {
        try {
            unregisterReceiver(usbReceiver)
        } catch (_: IllegalArgumentException) {
            // already unregistered
        }
    }

    // ------------------------------------------------------------------
    // Immersive mode
    // ------------------------------------------------------------------

    private fun enterImmersive() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
    }

    companion object {
        private const val TAG = "SpatialHost"

        /**
         * The control box has no keyboard — start the 360° source by itself
         * a few seconds after the glasses connect (mouse middle-button also
         * toggles it afterwards).
         */
        private const val AUTO_START_VIDEO = true
        private const val MAX_LOOK_PITCH = 1.45f // ≈83°

        /** Verified-live public sources (wheel = next). */
        private val PUBLIC_SOURCES = listOf(
            "360 equirect" to
                "https://naver.github.io/egjs-view360/pano/equirect/m3u8/equi.m3u8",
            "LIVE HLS" to
                "https://cph-p2p-msl.akamaized.net/hls/live/2000341/test/master.m3u8",
            "HLS ToS" to
                "https://test-streams.mux.dev/tos_ismc/main.m3u8",
            "HLS PTS" to
                "https://test-streams.mux.dev/pts_shift/master.m3u8",
            "HLS bipbop" to
                "https://devstreaming-cdn.apple.com/videos/streaming/examples/img_bipbop_adv_example_fmp4/master.m3u8",
            "HLS Big Buck Bunny" to
                "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8",
        )

        private val VIDEO_EXTENSIONS =
            setOf("mp4", "mkv", "webm", "mov", "ts", "avi", "m4v")
        private val LOCAL_VIDEO_DIRS = listOf(
            "/sdcard/Movies", "/sdcard/Download", "/sdcard/DCIM/Camera", "/sdcard/video",
        )

        private fun rotateVec(q: FloatArray, vx: Float, vy: Float, vz: Float): FloatArray {
            val (x, y, z, w) = q
            val tx = 2f * (y * vz - z * vy)
            val ty = 2f * (z * vx - x * vz)
            val tz = 2f * (x * vy - y * vx)
            return floatArrayOf(
                vx + w * tx + (y * tz - z * ty),
                vy + w * ty + (z * tx - x * tz),
                vz + w * tz + (x * ty - y * tx),
            )
        }
    }
}
