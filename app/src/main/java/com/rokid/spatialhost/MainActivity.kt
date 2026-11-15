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
import com.rokid.spatialhost.scene.widgetAt
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
    private var totalMoveX = 0f // CUMULATIVE since DOWN — a slow drag (small
    private var totalMoveY = 0f // per-event deltas) must not read as a tap
    private var lastTapTime = 0L

    // Manual look offset on top of head pose (mouse drag with glasses active).
    @Volatile
    private var lookYaw = 0f

    @Volatile
    private var lookPitch = 0f

    @Volatile
    private var useLookOffset = false

    // ------------------------------------------------------------------
    // Model interaction (D1b): pick / rotate / scale / switch
    // ------------------------------------------------------------------

    /**
     * One manipulable scene model. [matrix] = T(pos)·Ry(yaw)·Rx(pitch)·S·T(−c):
     * auto-fit to ~1.1 m ([baseScale]) with a user scale multiplier.
     */
    private data class ModelObject(
        val label: String,
        val boundsMin: FloatArray,
        val boundsMax: FloatArray,
        val pos: FloatArray = floatArrayOf(0f, 0.1f, -2.2f),
        var yawRad: Float = 0f,
        var pitchRad: Float = 0f,
        var userScale: Float = 1f,
    ) {
        val center: FloatArray = floatArrayOf(
            (boundsMin[0] + boundsMax[0]) / 2f,
            (boundsMin[1] + boundsMax[1]) / 2f,
            (boundsMin[2] + boundsMax[2]) / 2f,
        )

        val baseScale: Float = 1.1f / maxOf(
            boundsMax[0] - boundsMin[0],
            boundsMax[1] - boundsMin[1],
            boundsMax[2] - boundsMin[2],
        ).coerceAtLeast(1e-3f)

        fun matrix(): FloatArray {
            val s = baseScale * userScale
            val cy = kotlin.math.cos(yawRad)
            val sy = kotlin.math.sin(yawRad)
            val cp = kotlin.math.cos(pitchRad)
            val sp = kotlin.math.sin(pitchRad)
            // R = Ry(yaw)·Rx(pitch), column-major columns:
            val c0 = floatArrayOf(cy, 0f, -sy)
            val c1 = floatArrayOf(sy * sp, cp, cy * sp)
            val c2 = floatArrayOf(sy * cp, -sp, cy * cp)
            // translation = pos − s·R·c(center)
            val rc = floatArrayOf(
                c0[0] * center[0] + c1[0] * center[1] + c2[0] * center[2],
                c0[1] * center[0] + c1[1] * center[1] + c2[1] * center[2],
                c0[2] * center[0] + c1[2] * center[1] + c2[2] * center[2],
            )
            return floatArrayOf(
                c0[0] * s, c0[1] * s, c0[2] * s, 0f,
                c1[0] * s, c1[1] * s, c1[2] * s, 0f,
                c2[0] * s, c2[1] * s, c2[2] * s, 0f,
                pos[0] - s * rc[0], pos[1] - s * rc[1], pos[2] - s * rc[2], 1f,
            )
        }

        /** Rᵀ — inverse of the yaw·pitch rotation. */
        private fun invRot(v: FloatArray): FloatArray {
            val cy = kotlin.math.cos(yawRad)
            val sy = kotlin.math.sin(yawRad)
            val cp = kotlin.math.cos(pitchRad)
            val sp = kotlin.math.sin(pitchRad)
            val x1 = v[0]
            val y1 = v[1] * cp + v[2] * sp
            val z1 = -v[1] * sp + v[2] * cp // Rxᵀ
            return floatArrayOf(x1 * cy - z1 * sy, y1, x1 * sy + z1 * cy) // Ryᵀ
        }

        /** World point → model-local space (for the ray/AABB slab test). */
        fun worldToLocal(p: FloatArray): FloatArray {
            val s = baseScale * userScale
            val l = invRot(floatArrayOf((p[0] - pos[0]) / s, (p[1] - pos[1]) / s, (p[2] - pos[2]) / s))
            return floatArrayOf(l[0] + center[0], l[1] + center[1], l[2] + center[2])
        }

        /** World direction → model-local (scaled) direction. */
        fun worldDirToLocal(d: FloatArray): FloatArray {
            val s = baseScale * userScale
            return invRot(floatArrayOf(d[0] / s, d[1] / s, d[2] / s))
        }

        /**
         * Placement from an EXTERNAL transform (physics body): column-major
         * T(pos)·R(quat xyzw)·S·T(−center) — same layout as [matrix].
         */
        fun matrixWith(pos: FloatArray, quat: FloatArray): FloatArray {
            val s = baseScale * userScale
            val qx = quat[0]
            val qy = quat[1]
            val qz = quat[2]
            val qw = quat[3]
            val m00 = 1f - 2f * (qy * qy + qz * qz)
            val m01 = 2f * (qx * qy - qz * qw)
            val m02 = 2f * (qx * qz + qy * qw)
            val m10 = 2f * (qx * qy + qz * qw)
            val m11 = 1f - 2f * (qx * qx + qz * qz)
            val m12 = 2f * (qy * qz - qx * qw)
            val m20 = 2f * (qx * qz - qy * qw)
            val m21 = 2f * (qy * qz + qx * qw)
            val m22 = 1f - 2f * (qx * qx + qy * qy)
            val rc0 = m00 * center[0] + m01 * center[1] + m02 * center[2]
            val rc1 = m10 * center[0] + m11 * center[1] + m12 * center[2]
            val rc2 = m20 * center[0] + m21 * center[1] + m22 * center[2]
            return floatArrayOf(
                m00 * s, m10 * s, m20 * s, 0f,
                m01 * s, m11 * s, m21 * s, 0f,
                m02 * s, m12 * s, m22 * s, 0f,
                pos[0] - s * rc0, pos[1] - s * rc1, pos[2] - s * rc2, 1f,
            )
        }
    }

    private data class ModelFile(val label: String, val path: String)

    private var modelSelected = false
    private val sceneModels = mutableListOf<ModelObject>()
    private val localModelFiles = mutableListOf<ModelFile>()
    private var modelSourceIndex = 0 // 0 = bundled demo, 1.. = local files
    private var currentModel: com.rokid.spatialhost.scene.gltf.GltfModel? = null // D4 export

    // ------------------------------------------------------------------
    // D3: rigid-body physics (Jolt). While active the physics body drives
    // the model transform; manual rotate/scale stand down.
    // ------------------------------------------------------------------

    private val physics = com.rokid.spatialhost.physics.PhysicsWorld()
    private var physicsActive = false
    private var grabbing = false // D3b: the mouse holds the model
    private var grabDistance = 0f // eye → grab point, world meters
    private var grabOffX = 0f // mouse-driven target offset (world meters)
    private var grabOffY = 0f

    /**
     * D3b: press while the crosshair is on the model during simulation →
     * engage the grab spring at the hit point.
     */
    private fun tryGrabAtCrosshair(): Boolean {
        val obj = sceneModels.firstOrNull() ?: return false
        val f = rotateVec(currentCameraQuat(), 0f, 0f, -1f)
        val localOrigin = obj.worldToLocal(floatArrayOf(0f, 0f, 0f))
        val localDir = obj.worldDirToLocal(f)
        val t = com.rokid.spatialhost.scene.hitBox(
            localOrigin, localDir, obj.boundsMin, obj.boundsMax,
        )
        if (t == null) {
            Log.i(
                TAG,
                "grab miss: lookPitch=${"%.3f".format(lookPitch)} " +
                    "fwd=${f.joinToString(",") { "%.2f".format(it) }} " +
                    "objPos=${obj.pos.joinToString(",") { "%.2f".format(it) }} " +
                    "yaw=${"%.2f".format(obj.yawRad)} pitch=${"%.2f".format(obj.pitchRad)}",
            )
            return false
        }
        grabbing = true
        grabDistance = t
        grabOffX = 0f
        grabOffY = 0f
        // The eye sits at the world origin, so the grab point is dir × t.
        physics.dragTo(f[0] * t, f[1] * t, f[2] * t)
        Log.i(TAG, "grab ${obj.label} at ${"%.2f".format(t)} m")
        return true
    }

    /**
     * Re-aim the held model: base point along the current crosshair ray,
     * plus a mouse-driven offset in the camera's right/up plane (this is
     * what a flick of the mouse throws with).
     */
    private fun dragGrabbedModel(dx: Float, dy: Float) {
        val q = currentCameraQuat()
        val f = rotateVec(q, 0f, 0f, -1f)
        val r = rotateVec(q, 1f, 0f, 0f)
        val u = rotateVec(q, 0f, 1f, 0f)
        // pixels → radians (radPerPx) → meters at the grab distance
        val scale = grabDistance * 0.004f
        grabOffX = (grabOffX + dx * scale).coerceIn(-3f, 3f)
        grabOffY = (grabOffY - dy * scale).coerceIn(-3f, 3f)
        physics.dragTo(
            f[0] * grabDistance + r[0] * grabOffX + u[0] * grabOffY,
            f[1] * grabDistance + r[1] * grabOffX + u[1] * grabOffY,
            f[2] * grabDistance + r[2] * grabOffX + u[2] * grabOffY,
        )
    }

    private val physicsTick = object : Runnable {
        override fun run() {
            if (!physicsActive) return
            try {
                physics.step(FIXED_PHYSICS_DT)
                val obj = sceneModels.firstOrNull()
                val pos = physics.modelPosition()
                val rot = physics.modelRotation()
                if (obj != null && pos != null && rot != null) {
                    obj.pos[0] = pos[0]
                    obj.pos[1] = pos[1]
                    obj.pos[2] = pos[2]
                    renderer.setModelTransform(obj.matrixWith(pos, rot))
                }
            } catch (t: Throwable) {
                Log.e(TAG, "physics tick failed — physics OFF", t)
                physicsActive = false
                return
            }
            mainHandler.postDelayed(this, 16)
        }
    }

    /** P: the model becomes a dynamic body and drops onto the floor. */
    private fun togglePhysics() {
        if (physicsActive) {
            physicsActive = false
            grabbing = false
            physics.releaseGrab()
            mainHandler.removeCallbacks(physicsTick)
            // Keep the model where it landed.
            val obj = sceneModels.firstOrNull()
            val pos = physics.modelPosition()
            val rot = physics.modelRotation()
            if (obj != null && pos != null && rot != null) {
                obj.pos[0] = pos[0]
                obj.pos[1] = pos[1]
                obj.pos[2] = pos[2]
                val e = com.rokid.spatialhost.bridge.Quat.toEulerYXZ(rot)
                obj.yawRad = e[0]
                obj.pitchRad = e[1]
                renderer.setModelTransform(obj.matrix())
            }
            physics.removeModel()
            Log.i(TAG, "physics OFF — model kept at ${pos?.joinToString { "%.2f".format(it) }}")
        } else {
            val obj = sceneModels.firstOrNull()
            if (obj == null) {
                Log.i(TAG, "physics: no model loaded")
                return
            }
            if (!physics.ensureInit()) return
            physics.addFloor(FLOOR_TOP_Y, obj.pos[0], obj.pos[2])
            val s = obj.baseScale * obj.userScale
            physics.addModelBox(
                obj.pos[0], obj.pos[1], obj.pos[2],
                (obj.boundsMax[0] - obj.boundsMin[0]) / 2f * s,
                (obj.boundsMax[1] - obj.boundsMin[1]) / 2f * s,
                (obj.boundsMax[2] - obj.boundsMin[2]) / 2f * s,
            )
            physicsActive = true
            mainHandler.post(physicsTick)
            Log.i(TAG, "physics ON — drop from y=${"%.2f".format(obj.pos[1])} to floor $FLOOR_TOP_Y")
        }
    }

    /** Camera orientation = head pose ⊗ manual look offset. */
    private fun currentCameraQuat(): FloatArray {
        val p = activeBridge?.poses?.latest?.value
            ?: return floatArrayOf(0f, 0f, 0f, 1f)
        var q = floatArrayOf(p.qx, p.qy, p.qz, p.qw)
        if (useLookOffset) {
            val offset = com.rokid.spatialhost.bridge.Quat
                .fromEulerYXZ(lookYaw, lookPitch, 0f)
            q = com.rokid.spatialhost.bridge.Quat.mul(q, offset)
        }
        return q
    }

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
        layoutEngine = LayoutEngine().apply {
            defaultArc(count = 3)
            add(
                com.rokid.spatialhost.scene.SpatialPanel(
                    id = VIDEO_PANEL_ID,
                    widthM = 1.6f,
                    heightM = 0.9f,
                    position = com.rokid.spatialhost.scene.Vec3(0f, -1.05f, -2.45f),
                    label = "Video",
                    widgets = videoPanelWidgets(),
                    persistent = true, // stays visible while the video plays
                ),
            )
        }
        renderer.setPanels(
            layoutEngine.panels.values.map {
                com.rokid.spatialhost.render.PanelSpec(
                    it.id, it.widthM, it.heightM,
                    it.position.x, it.position.y, it.position.z,
                    persistent = it.persistent,
                )
            },
        )
        pushAllPanelUi() // cards instead of the white fallback texture
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
        scanLocalModelFiles()
        loadCurrentModel()
        requestStorageIfNeeded()

        Log.i(TAG, UsbDiagnostics.describe(getSystemService(Context.USB_SERVICE) as UsbManager))
        connectChain()
    }

    /** Scanning /sdcard needs READ_EXTERNAL_STORAGE below Android 13. */
    private fun requestStorageIfNeeded() {
        if (android.os.Build.VERSION.SDK_INT > 32) return
        if (checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return
        requestPermissions(
            arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE),
            REQ_STORAGE_PERMISSION,
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_STORAGE_PERMISSION &&
            grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            Log.i(TAG, "storage granted — rescanning local sources")
            scanLocalVideos()
            scanLocalModelFiles()
        }
    }

    /**
     * Phase D1b: load the model at [modelSourceIndex] (0 = bundled demo,
     * 1.. = scanned local .glb/.gltf) and place it in front of the user.
     * The active [ModelObject] mirrors the placement sent to the renderer.
     */
    private fun loadCurrentModel() {
        val label: String
        val readBytes: () -> ByteArray
        val resolver: ((String) -> ByteArray?)?
        if (modelSourceIndex == 0) {
            label = "DamagedHelmet"
            readBytes = { assets.open("models/DamagedHelmet.glb").use { it.readBytes() } }
            resolver = null
        } else {
            val file = localModelFiles.getOrNull(modelSourceIndex - 1)
            if (file == null) {
                modelSourceIndex = 0
                loadCurrentModel()
                return
            }
            label = file.label
            val base = java.io.File(file.path).parentFile
            readBytes = { java.io.File(file.path).readBytes() }
            // .gltf with external .bin next to the JSON.
            resolver = { uri ->
                try {
                    java.io.File(base, uri).readBytes()
                } catch (_: Throwable) {
                    null
                }
            }
        }
        scope.launch {
            try {
                val bytes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    readBytes()
                }
                val model = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    com.rokid.spatialhost.scene.gltf.GltfParser.parse(bytes, label, resolver)
                }
                Log.i(
                    TAG,
                    "parsed ${model.name}: nodes=${model.nodes.size} prims=${model.primitiveCount} " +
                        "bounds=${model.boundsMin.joinToString("/") { "%.2f".format(it) }}.." +
                        "${model.boundsMax.joinToString("/") { "%.2f".format(it) }}",
                )
                val obj = ModelObject(
                    label = label,
                    boundsMin = model.boundsMin,
                    boundsMax = model.boundsMax,
                )
                sceneModels.clear()
                sceneModels.add(obj)
                modelSelected = false
                currentModel = model
                renderer.requestModel(model, obj.matrix())
            } catch (t: Throwable) {
                Log.e(TAG, "model load failed ($label)", t)
            }
        }
    }

    /** M: cycle demo → local models → demo. */
    private fun nextModelSource() {
        val count = 1 + localModelFiles.size
        modelSourceIndex = (modelSourceIndex + 1) % count
        modelSelected = false
        val name = if (modelSourceIndex == 0) "demo:DamagedHelmet"
        else localModelFiles[modelSourceIndex - 1].label
        Log.i(TAG, "model source → $name")
        loadCurrentModel()
    }

    /**
     * Collect local .glb/.gltf files (USB stick / internal storage) for M.
     * IO off the main thread; the list is only mutated on main.
     */
    private fun scanLocalModelFiles() {
        scope.launch {
            val found = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val out = mutableListOf<ModelFile>()
                for (dirPath in LOCAL_MODEL_DIRS) {
                    val root = java.io.File(dirPath)
                    if (!root.isDirectory) continue
                    try {
                        root.walkTopDown()
                            .onFail { _, _ -> }
                            .filter { f -> f.isFile && f.extension.lowercase() in MODEL_EXTENSIONS }
                            .take(50 - out.size)
                            .forEach { f -> out.add(ModelFile(f.nameWithoutExtension, f.absolutePath)) }
                    } catch (t: Throwable) {
                        Log.w(TAG, "model scan $dirPath failed: ${t.message}")
                    }
                    if (out.size >= 50) break
                }
                out
            }
            localModelFiles.clear()
            localModelFiles.addAll(found)
            if (found.isNotEmpty()) {
                Log.i(TAG, "local models: +${found.size} (${found.take(3).joinToString { it.label }})")
            } else {
                Log.i(TAG, "local models: none in $LOCAL_MODEL_DIRS")
            }
        }
    }

    // ------------------------------------------------------------------
    // Phase D4: export (E) — .glb + .obj into Downloads, with an in-memory
    // round-trip check (re-parse the GLB and compare structure counts).
    // ------------------------------------------------------------------

    private fun exportCurrentModel() {
        val model = currentModel
        val obj = sceneModels.firstOrNull()
        if (model == null || obj == null) {
            Log.i(TAG, "export: no model loaded")
            return
        }
        scope.launch {
            try {
                val label = obj.label
                val placement = obj.matrix()
                val glb = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    com.rokid.spatialhost.scene.gltf.GltfExporter.toGlb(model)
                }
                val objText = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    com.rokid.spatialhost.scene.gltf.GltfExporter.toObj(model, placement, label)
                }
                val back = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    com.rokid.spatialhost.scene.gltf.GltfParser.parse(glb, label)
                }
                val ok = back.primitiveCount == model.primitiveCount &&
                    back.nodes.size == model.nodes.size &&
                    back.textures.size == model.textures.size &&
                    back.materials.size == model.materials.size
                val glbPath = saveToDownloads("$label.glb", glb, "model/gltf-binary")
                val objPath = saveToDownloads(
                    "$label.obj", objText.toByteArray(Charsets.UTF_8), "model/obj",
                )
                Log.i(
                    TAG,
                    "export ${if (ok) "ROUND-TRIP OK" else "ROUND-TRIP MISMATCH"}: " +
                        "glb=$glbPath (${glb.size}B) obj=$objPath " +
                        "prims=${model.primitiveCount}/${back.primitiveCount} " +
                        "nodes=${model.nodes.size}/${back.nodes.size} " +
                        "tex=${model.textures.size}/${back.textures.size}",
                )
            } catch (t: Throwable) {
                Log.e(TAG, "export failed", t)
            }
        }
    }

    /** Write into the shared Downloads collection (MediaStore on Q+). */
    private fun saveToDownloads(name: String, bytes: ByteArray, mime: String): String? {
        return try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(
                        android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                        android.os.Environment.DIRECTORY_DOWNLOADS,
                    )
                    put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = contentResolver.insert(
                    android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values,
                ) ?: return null
                contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                values.clear()
                values.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0)
                contentResolver.update(uri, values, null, null)
                uri.toString()
            } else {
                val dir = android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS,
                )
                val f = java.io.File(dir, name)
                f.writeBytes(bytes)
                f.absolutePath
            }
        } catch (t: Throwable) {
            Log.e(TAG, "save $name failed", t)
            null
        }
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
        renderer.poseSupplier = { currentCameraQuat() }
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
                    totalMoveX = 0f
                    totalMoveY = 0f
                    // Pressing on the model during simulation grabs it.
                    if (physicsActive) tryGrabAtCrosshair()
                    true
                }

                MotionEvent.ACTION_SCROLL -> {
                    val wheel = ev.getAxisValue(MotionEvent.AXIS_VSCROLL)
                    val obj = if (modelSelected && !physicsActive) sceneModels.firstOrNull() else null
                    if (obj != null && wheel != 0f) {
                        // Selected model: wheel scales it (else wheel = next video).
                        val step = if (wheel > 0f) 1.12f else 1f / 1.12f
                        obj.userScale = (obj.userScale * step).coerceIn(0.2f, 6f)
                        renderer.setModelTransform(obj.matrix())
                    } else if (wheel > 0.5f) {
                        nextVideoSource()
                    }
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.x - lastX
                    val dy = ev.y - lastY
                    totalMoveX += dx
                    totalMoveY += dy
                    if (hypot(totalMoveX, totalMoveY) > 8f) moved = true
                    if (moved && grabbing) {
                        // Held model follows crosshair + mouse offset (throw).
                        dragGrabbedModel(dx, dy)
                    } else if (moved) {
                        val obj = if (modelSelected && !physicsActive) sceneModels.firstOrNull() else null
                        if (obj != null) {
                            // Drag rotates the selected model (grab-the-surface
                            // feel) instead of steering the view.
                            obj.yawRad += dx * 0.005f
                            obj.pitchRad = (obj.pitchRad + dy * 0.005f)
                                .coerceIn(-1.4f, 1.4f)
                            renderer.setModelTransform(obj.matrix())
                        } else {
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
                    }
                    lastX = ev.x
                    lastY = ev.y
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (grabbing) {
                        grabbing = false
                        physics.releaseGrab() // keeps velocity → the throw
                        Log.i(TAG, "release — model thrown")
                    }
                    if (!moved) {
                        val now = System.currentTimeMillis()
                        val gesture =
                            if (now - lastTapTime < 300) InputEvent.DoubleTap else InputEvent.Tap
                        lastTapTime = now
                        handleGesture(recognizer.onEvent(gesture))
                    } else {
                        // End of a drag: without this the recognizer keeps its
                        // dragging flag and swallows the NEXT real click.
                        recognizer.onEvent(InputEvent.DragEnd)
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

            KeyEvent.KEYCODE_M -> {
                nextModelSource()
                return true
            }

            KeyEvent.KEYCODE_P -> {
                togglePhysics()
                return true
            }

            KeyEvent.KEYCODE_E -> {
                exportCurrentModel()
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
    private var videoLooping = false
    private var videoUiBitmap: android.graphics.Bitmap? = null
    private var videoUiPixels: ByteArray? = null
    private var lastVideoUiSig: String? = null

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
            player.setLooping(videoLooping)
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

    private fun nextVideoSource() = stepVideoSource(+1)

    private fun prevVideoSource() = stepVideoSource(-1)

    private fun stepVideoSource(delta: Int) {
        if (videoSources.isEmpty()) return
        sourceIndex = (sourceIndex + delta + videoSources.size) % videoSources.size
        Log.i(TAG, "video source → ${videoSources[sourceIndex].first}")
        if (videoActive) {
            // Rapid stop→start resolves correctly: the render loop processes
            // the color (detach) request before the video (attach) request.
            stopVideo()
            startVideo()
        }
    }

    // ------------------------------------------------------------------
    // Video control deck: widgets on a persistent panel (see Widgets.kt)
    // ------------------------------------------------------------------

    /** Current video state as widgets — rebuilt on every redraw. */
    private fun videoPanelWidgets(): List<com.rokid.spatialhost.scene.Widget> {
        val srcName = videoSources.getOrNull(sourceIndex)?.first ?: "—"
        val player = streamPlayer
        val durMs = player?.durationMs ?: 0L
        val posMs = player?.positionMs ?: 0L
        val playing = player?.playing == true
        val timeText = when {
            durMs > 0 -> "${fmtMs(posMs)} / ${fmtMs(durMs)}"
            videoActive -> "LIVE"
            else -> "—"
        }
        return listOf(
            com.rokid.spatialhost.scene.Widget.Label("vid.src", uv(0.04f, 0.72f, 0.66f, 0.14f), srcName),
            com.rokid.spatialhost.scene.Widget.Toggle("vid.loop", uv(0.74f, 0.72f, 0.22f, 0.14f), "LOOP", videoLooping),
            com.rokid.spatialhost.scene.Widget.Button("vid.play", uv(0.04f, 0.44f, 0.26f, 0.20f), if (playing) "PAUSE" else "PLAY"),
            com.rokid.spatialhost.scene.Widget.Button("vid.prev", uv(0.34f, 0.44f, 0.28f, 0.20f), "< SRC"),
            com.rokid.spatialhost.scene.Widget.Button("vid.next", uv(0.66f, 0.44f, 0.30f, 0.20f), "SRC >"),
            com.rokid.spatialhost.scene.Widget.Label("vid.time", uv(0.04f, 0.26f, 0.60f, 0.12f), timeText),
            com.rokid.spatialhost.scene.Widget.Slider(
                "vid.seek", uv(0.04f, 0.06f, 0.92f, 0.14f), "SEEK",
                value = if (durMs > 0) posMs.toFloat() / durMs else 0f,
            ),
        )
    }

    private fun uv(x: Float, y: Float, w: Float, h: Float) =
        com.rokid.spatialhost.scene.UvRect(x, y, w, h)

    private fun fmtMs(ms: Long): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }

    /** Render a panel's current state and hand the pixels to the renderer. */
    private fun pushPanelUi(panelId: String) {
        val panel = layoutEngine.panels[panelId] ?: return
        val w = com.rokid.spatialhost.render.PanelUiDrawer.WIDTH
        val h = com.rokid.spatialhost.render.PanelUiDrawer.HEIGHT
        val bmp = videoUiBitmap ?: android.graphics.Bitmap
            .createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
            .also { videoUiBitmap = it }
        // The deck rebuilds its widgets from live state; other panels are
        // static cards (title only).
        val widgets = if (panelId == VIDEO_PANEL_ID) videoPanelWidgets() else panel.widgets
        com.rokid.spatialhost.render.PanelUiDrawer.draw(panel.copy(widgets = widgets), bmp)
        val px = videoUiPixels ?: ByteArray(w * h * 4).also { videoUiPixels = it }
        bmp.copyPixelsToBuffer(java.nio.ByteBuffer.wrap(px))
        renderer.updatePanelUi(panelId, w, h, px)
    }

    /** All panels get a card (arc panels: title-only) — no white slabs. */
    private fun pushAllPanelUi() {
        layoutEngine.panels.keys.forEach { pushPanelUi(it) }
    }

    private fun pushVideoPanelUi() = pushPanelUi(VIDEO_PANEL_ID)

    /** Dispatch a widget click under the crosshair ([u] localizes sliders). */
    private fun handleWidget(w: com.rokid.spatialhost.scene.Widget, u: Float) {
        Log.i(TAG, "widget: ${w.id}")
        when (w) {
            is com.rokid.spatialhost.scene.Widget.Button -> when (w.id) {
                "vid.play" -> playPauseVideo()
                "vid.prev" -> prevVideoSource()
                "vid.next" -> nextVideoSource()
            }

            is com.rokid.spatialhost.scene.Widget.Toggle -> when (w.id) {
                "vid.loop" -> {
                    videoLooping = !videoLooping
                    streamPlayer?.setLooping(videoLooping)
                }
            }

            is com.rokid.spatialhost.scene.Widget.Slider -> when (w.id) {
                "vid.seek" -> {
                    val dur = streamPlayer?.durationMs ?: 0L
                    if (dur > 0) {
                        val target = (w.valueAt(u) * dur).toLong()
                        streamPlayer?.seekTo(target)
                        Log.i(TAG, "seek → ${fmtMs(target)}")
                    } else {
                        Log.i(TAG, "seek ignored (live source, no duration)")
                    }
                }
            }

            is com.rokid.spatialhost.scene.Widget.Label -> Unit
        }
        pushVideoPanelUi()
    }

    /** Deck PLAY/PAUSE: starts the sphere if it is off, else toggles. */
    private fun playPauseVideo() {
        if (!videoActive) {
            startVideo()
            return
        }
        val p = streamPlayer ?: return
        if (p.playing) {
            p.pause()
            videoStatus = "paused"
            Log.i(TAG, "video paused")
        } else {
            p.resume()
            videoStatus = "streaming"
            Log.i(TAG, "video resumed")
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

    /** 3DoF ray from current head pose → model first, then panel under crosshair. */
    private fun selectAtCrosshair() {
        val q = currentCameraQuat()
        val f = rotateVec(q, 0f, 0f, -1f)

        // Model pick: camera-space ray → model-local space, slab test vs AABB.
        val obj = sceneModels.firstOrNull()
        if (obj != null) {
            val localOrigin = obj.worldToLocal(floatArrayOf(0f, 0f, 0f)) // eye at world origin
            val localDir = obj.worldDirToLocal(f)
            val t = com.rokid.spatialhost.scene.hitBox(
                localOrigin, localDir, obj.boundsMin, obj.boundsMax,
            )
            if (t != null) {
                modelSelected = true
                Log.i(TAG, "selected model ${obj.label} (t=${"%.2f".format(t)})")
                return
            }
        }
        modelSelected = false

        val dir = com.rokid.spatialhost.scene.Vec3(f[0], f[1], f[2])
        val hit = layoutEngine.hitTest(dir)
        if (hit != null) {
            navState.selectedPanelId = hit.panel.id
            navState.focusedPanelId = hit.panel.id
            renderer.setFocusedPanel(hit.panel.id)
            // Interactive widgets win over plain panel selection.
            val widget = hit.panel.widgetAt(hit.u, hit.v)
            if (widget != null) {
                handleWidget(widget, hit.u)
                return
            }
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

        // Video deck: redraw when its state (or the playhead) changes.
        val deckSig = "$videoStatus|$sourceIndex|${streamPlayer?.playing}" +
            "|${(streamPlayer?.positionMs ?: 0L) / 1000}" +
            "|${streamPlayer?.durationMs ?: 0L}|$videoLooping|${streamPlayer != null}"
        if (deckSig != lastVideoUiSig) {
            lastVideoUiSig = deckSig
            pushVideoPanelUi()
        }

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
            val m = sceneModels.firstOrNull()
            if (m != null) {
                append(
                    "model  : ${m.label} · ${if (modelSelected) "SELECTED" else "idle"} " +
                        "· ×${"%.2f".format(m.userScale)} · yaw ${Math.toDegrees(m.yawRad.toDouble()).toInt()}° " +
                        "(tap=pick drag=rotate wheel=scale M=switch)\n",
                )
            } else {
                append("model  : loading…\n")
            }
            if (physicsActive || physics.isInitialized) {
                val p = physics.modelPosition()
                append(
                    "physics: ${if (physicsActive) "ON" else "OFF"}" +
                        (if (grabbing) " · GRABBED" else "") +
                        (p?.let { " · y=${"%.2f".format(it[1])}" } ?: "") +
                        " · floor $FLOOR_TOP_Y m (P=toggle)\n",
                )
            }
            append(
                "power  : ${stats.mahUsed.toInt()} mAh · ${"%.0f".format(stats.drainMa)} mA · " +
                    (if (stats.batteryPercent in 0..100) "${stats.batteryPercent}%" else "?") +
                    " · ${stats.elapsedSeconds}s\n",
            )
            append("touch  : drag=look/rotate tap=select 2x-tap=recenter swipe/←→=focus H=hud V=video M=model")
            append("\n")
            append(
                "video  : $videoStatus · ${videoSources.getOrNull(sourceIndex)?.first ?: "—"} " +
                    "(V=on/off N=source · deck panel below)\n",
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

        /** Persistent control-deck panel id (widgets, see PanelUiDrawer). */
        private const val VIDEO_PANEL_ID = "video-deck"

        /** D3: fixed physics step (the tick runs every 16 ms). */
        private const val FIXED_PHYSICS_DT = 1f / 60f
        private const val FLOOR_TOP_Y = -1.0f // floor surface, world meters

        private const val REQ_STORAGE_PERMISSION = 1001

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

        /** D1b: local glTF sources for the M key (push files via adb). */
        private val MODEL_EXTENSIONS = setOf("glb", "gltf")
        private val LOCAL_MODEL_DIRS = listOf(
            "/sdcard/Download", "/sdcard/models", "/sdcard/Documents", "/sdcard/Movies",
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
