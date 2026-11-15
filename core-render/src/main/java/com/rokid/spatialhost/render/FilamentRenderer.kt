package com.rokid.spatialhost.render

import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.google.android.filament.Box
import com.google.android.filament.Camera
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.Filament
import com.google.android.filament.IndexBuffer
import com.google.android.filament.LightManager
import com.google.android.filament.Material
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager
import com.google.android.filament.Renderer
import com.google.android.filament.Scene
import com.google.android.filament.Stream
import com.google.android.filament.SwapChain
import com.google.android.filament.Texture
import com.google.android.filament.TextureSampler
import com.google.android.filament.View
import com.google.android.filament.Viewport
import com.rokid.spatialhost.scene.gltf.GltfModel

/** Panel description pushed from the app (mirrors core-scene's SpatialPanel). */
data class PanelSpec(
    val id: String,
    val widthM: Float,
    val heightM: Float,
    val x: Float,
    val y: Float,
    val z: Float,
)

/**
 * Filament render engine driving the [SurfaceView].
 *
 * Owns a dedicated render thread with explicit frame pacing (interval set by
 * the battery governor via [targetFps]). The head pose is pulled each frame
 * from [poseSupplier] — a `() -> FloatArray` returning `[x,y,z,w]` — so this
 * module has no dependency on the bridge layer.
 *
 * Scene content:
 *  - 360° backdrop sphere (30 m, solid tint — equirect texture in Phase B2)
 *  - spatial panels (shared unit quad, per-panel transform + tint instance)
 */
class FilamentRenderer(private val surfaceView: SurfaceView) : SurfaceHolder.Callback {

    /** Returns head orientation quaternion [x,y,z,w] in the session frame. */
    var poseSupplier: (() -> FloatArray)? = null

    /** Frames per second target; updated by ThermalGovernor. */
    @Volatile
    var targetFps: Int = 90

    /** Measured FPS, updated once per second; read from any thread. */
    @Volatile
    var measuredFps: Float = 0f
        private set

    var onStats: ((fps: Float, frameMs: Float) -> Unit)? = null

    // Panel/focus state (written from any thread, consumed on render thread)
    @Volatile
    private var pendingPanels: List<PanelSpec>? = null

    @Volatile
    private var focusedId: String? = null

    /** Video-sphere request: invoked ONCE on the main thread with the feed Surface. */
    @Volatile
    private var pendingVideo: ((Surface) -> Unit)? = null

    /** Revert-sphere request: swap the backdrop back to the flat tinted sphere. */
    @Volatile
    private var pendingColor: (() -> Unit)? = null

    fun setPanels(specs: List<PanelSpec>) {
        pendingPanels = specs
    }

    fun setFocusedPanel(id: String?) {
        focusedId = id
    }

    /**
     * Switch the 360° sphere to the video material and hand [onReady] the
     * Surface to push frames into (call on main thread — media3 needs a
     * Looper). Uses the Filament Stream API: SurfaceTexture(0) +
     * detachFromGLContext, Filament latches frames itself.
     */
    fun requestVideoSphere(onReady: (Surface) -> Unit) {
        pendingVideo = onReady
    }

    /**
     * Swap the sphere back to the flat tinted material and release all video
     * resources. The player must have detached its Surface BEFORE calling
     * (stopVideo does setSurface(null) first).
     */
    fun requestColorSphere(onDone: () -> Unit) {
        pendingColor = onDone
    }

    /** Load a parsed glTF model (D1) with its placement matrix. */
    @Volatile
    private var pendingModel: Pair<GltfModel, FloatArray>? = null

    fun requestModel(model: GltfModel, matrix: FloatArray) {
        pendingModel = model to matrix
    }

    private var thread: Thread? = null

    @Volatile
    private var running = false

    @Volatile
    private var surface: Surface? = null

    @Volatile
    private var width: Int = 0

    @Volatile
    private var height: Int = 0

    init {
        surfaceView.holder.addCallback(this)
    }

    // ------------------------------------------------------------------
    // SurfaceHolder.Callback
    // ------------------------------------------------------------------

    override fun surfaceCreated(holder: SurfaceHolder) {
        surface = holder.surface
        startThread()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
        width = w
        height = h
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        stopThread()
    }

    // ------------------------------------------------------------------
    // Thread lifecycle
    // ------------------------------------------------------------------

    fun startThread() {
        if (running) return
        running = true
        thread = Thread(::renderLoop, "filament-render").apply {
            isDaemon = true
            start()
        }
    }

    fun stopThread() {
        if (!running) return
        running = false
        thread?.join(1500)
        thread = null
    }

    // ------------------------------------------------------------------
    // Scene state (render thread only)
    // ------------------------------------------------------------------

    private class SceneState(
        val engine: Engine,
        val scene: Scene,
        val colorMaterial: Material?,
        val litMaterial: Material?,
        val sphereMesh: Mesh,
        val quadMesh: Mesh,
        var sphereEntity: Int,
        var sphereInstance: MaterialInstance?,
        val lightEntity: Int,
        val modelManager: ModelManager,
        val panelEntities: MutableMap<String, Int> = mutableMapOf(),
        val panelInstances: MutableMap<String, MaterialInstance?> = mutableMapOf(),
        // video sphere resources (created lazily by requestVideoSphere)
        var videoMaterial: Material? = null,
        var videoTexture: Texture? = null,
        var videoStream: Stream? = null,
        var videoSurfaceTexture: SurfaceTexture? = null,
        var videoSurface: Surface? = null,
        var videoInstance: MaterialInstance? = null,
    )

    private var sceneState: SceneState? = null
    private var lastAppliedFocus: String? = null

    // ------------------------------------------------------------------
    // Render loop
    // ------------------------------------------------------------------

    private fun renderLoop() {
        Log.i(TAG, "render loop starting")
        var engine: Engine? = null
        var renderer: Renderer? = null
        var scene: Scene? = null
        var view: View? = null
        var camera: Camera? = null
        var cameraEntity = -1
        var swapChain: SwapChain? = null
        try {
            ensureFilamentInit()
            val e = Engine.create(Engine.Backend.OPENGL)
            engine = e
            Log.i(TAG, "engine backend: ${e.backend}")
            val r = e.createRenderer()
            renderer = r
            // Backdrop color for pixels the view doesn't touch (the 360°
            // sphere normally covers everything; this is the safety net).
            val clearOpts = Renderer.ClearOptions()
            clearOpts.clear = true
            clearOpts.clearColor = doubleArrayOf(0.03, 0.04, 0.07, 1.0)
            r.setClearOptions(clearOpts)
            val s = e.createScene()
            scene = s
            val v = e.createView()
            view = v
            cameraEntity = EntityManager.get().create()
            val c = e.createCamera(cameraEntity)
            camera = c
            v.scene = s
            v.camera = c
            // Tone-mapping/exposure applies to every view pixel; our unlit
            // tints are authored in display space, so post-processing is off
            // (same approach as sample-material-builder's diagnostics note).
            v.isPostProcessingEnabled = false
            Log.i(TAG, "post-processing disabled")
            // Physical camera exposure for lit materials (sunny f/16, as in
            // Filament's material-builder sample).
            c.setExposure(16.0f, 1.0f / 125.0f, 100.0f)

            // --- content -------------------------------------------------
            val colorMaterial = MaterialFactory.buildColorUnlit(e)
            val litMaterial = MaterialFactory.buildLit(e)
            val sphereMesh = Meshes.createSphere(e, radius = 30f)
            val quadMesh = Meshes.createQuad(e)

            // Key light for lit models (D2 replaces/extends with IBL).
            // Direction points where light TRAVELS: from behind/above the
            // viewer forward so the camera-facing side of models is lit.
            val lightEntity = EntityManager.get().create()
            LightManager.Builder(LightManager.Type.DIRECTIONAL)
                .color(1.0f, 0.98f, 0.94f)
                .intensity(110_000f) // clear-day lux, as in Filament's samples
                .direction(0.2f, -0.6f, -1.0f)
                .castShadows(false)
                .build(e, lightEntity)
            s.addEntity(lightEntity)

            val sphereInstance = colorMaterial?.createInstance()
            sphereInstance?.setParameter(
                "color",
                SPHERE_COLOR[0], SPHERE_COLOR[1], SPHERE_COLOR[2], SPHERE_COLOR[3],
            )
            val sphereEntity = if (sphereInstance != null) {
                buildSphereEntity(e, s, sphereMesh, sphereInstance)
            } else {
                EntityManager.get().create()
            }

            sceneState = SceneState(
                engine = e,
                scene = s,
                colorMaterial = colorMaterial,
                litMaterial = litMaterial,
                sphereMesh = sphereMesh,
                quadMesh = quadMesh,
                sphereEntity = sphereEntity,
                sphereInstance = sphereInstance,
                lightEntity = lightEntity,
                modelManager = ModelManager(e, s, litMaterial),
            )
            Log.i(
                TAG,
                "scene after sphere: entities=${s.entityCount} renderables=${s.renderableCount} " +
                    "material=${colorMaterial != null}",
            )

            var frames = 0
            var statsStart = System.nanoTime()
            var lastFrameStart = System.nanoTime()

            while (running) {
                val surf = surface ?: run {
                    Thread.sleep(16)
                    continue
                }
                val w = width
                val h = height
                if (w <= 0 || h <= 0) {
                    Thread.sleep(16)
                    continue
                }

                if (swapChain == null) {
                    swapChain = e.createSwapChain(surf)
                    v.viewport = Viewport(0, 0, w, h)
                    c.setProjection(70.0, w.toDouble() / h.toDouble(), 0.05, 500.0, Camera.Fov.VERTICAL)
                }

                lastFrameStart = System.nanoTime()

                applyPendingPanels()
                applyFocus()

                // glTF model (D1)
                pendingModel?.let { (model, matrix) ->
                    pendingModel = null
                    sceneState?.modelManager?.load(model, matrix)
                    Log.i(TAG, "model placement: " + (sceneState?.modelManager?.debugBounds(model.boundsMin, model.boundsMax) ?: "?"))
                }

                // Video/color sphere requests (color FIRST so a rapid
                // stop→start pair resolves in the right order within a frame).
                pendingColor?.let { done ->
                    pendingColor = null
                    sceneState?.let { st -> detachVideoLocked(st) }
                    Handler(Looper.getMainLooper()).post { done() }
                }
                pendingVideo?.let { request ->
                    pendingVideo = null
                    sceneState?.let { st -> attachVideoLocked(st, request) }
                }

                // Pull head pose → camera lookAt
                val q = poseSupplier?.invoke() ?: IDENTITY_Q
                val target = rotate(q, 0f, 0f, -1f)
                val up = rotate(q, 0f, 1f, 0f)
                c.lookAt(
                    0.0, 0.0, 0.0,
                    target[0].toDouble(), target[1].toDouble(), target[2].toDouble(),
                    up[0].toDouble(), up[1].toDouble(), up[2].toDouble(),
                )

                if (r.beginFrame(swapChain, System.nanoTime())) {
                    r.render(v)
                    r.endFrame()
                }

                frames++
                val now = System.nanoTime()
                val elapsed = now - statsStart
                if (elapsed >= 1_000_000_000L) {
                    measuredFps = frames * 1_000_000_000f / elapsed
                    onStats?.invoke(measuredFps, elapsed / 1_000_000f / frames)
                    frames = 0
                    statsStart = now
                    val vp = v.viewport
                    val pq = poseSupplier?.invoke() ?: IDENTITY_Q
                    val lk = rotate(pq, 0f, 0f, -1f)
                    Log.d(
                        TAG,
                        "diag: vp=${vp.left},${vp.bottom} ${vp.width}x${vp.height} " +
                            "cam=${v.hasCamera()} entities=${s.entityCount} " +
                            "renderables=${s.renderableCount} " +
                            "look=(${"%.2f".format(lk[0])},${"%.2f".format(lk[1])},${"%.2f".format(lk[2])})",
                    )
                }

                // Frame pacing to targetFps
                val interval = 1_000_000_000L / targetFps.coerceIn(15, 240)
                val spent = System.nanoTime() - lastFrameStart
                val remaining = interval - spent
                if (remaining > 0) {
                    val ms = remaining / 1_000_000L
                    val ns = (remaining % 1_000_000L).toInt()
                    if (ms > 0 || ns > 0) Thread.sleep(ms, ns)
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "render loop crashed", t)
        } finally {
            try {
                sceneState?.let { destroySceneState(it) }
                sceneState = null
                engine?.let { e ->
                    swapChain?.let { e.destroySwapChain(it) }
                    if (cameraEntity >= 0) {
                        e.destroyCameraComponent(cameraEntity)
                        EntityManager.get().destroy(cameraEntity)
                    }
                    view?.let { e.destroyView(it) }
                    scene?.let { e.destroyScene(it) }
                    renderer?.let { e.destroyRenderer(it) }
                    e.destroy()
                }
            } catch (t: Throwable) {
                Log.w(TAG, "cleanup error: ${t.message}")
            }
            Log.i(TAG, "render loop stopped")
        }
    }

    // ------------------------------------------------------------------
    // Scene mutations (render thread)
    // ------------------------------------------------------------------

    private fun applyPendingPanels() {
        val specs = pendingPanels ?: return
        pendingPanels = null
        val st = sceneState ?: return
        val e = st.engine

        // Remove existing panels
        for (entity in st.panelEntities.values) {
            st.scene.removeEntity(entity)
            e.destroyEntity(entity)
        }
        st.panelEntities.clear()
        st.panelInstances.clear()
        lastAppliedFocus = null

        if (st.colorMaterial == null) return
        val tm = e.transformManager
        for (spec in specs) {
            val entity = EntityManager.get().create()
            val instance = st.colorMaterial.createInstance()
            instance.setParameter("color", PANEL_TINT[0], PANEL_TINT[1], PANEL_TINT[2], PANEL_TINT[3])

            RenderableManager.Builder(1)
                .geometry(
                    0,
                    RenderableManager.PrimitiveType.TRIANGLES,
                    st.quadMesh.vertexBuffer,
                    st.quadMesh.indexBuffer,
                    0,
                    st.quadMesh.indexCount,
                )
                .material(0, instance)
                .boundingBox(
                    // Renderable bbox is in LOCAL space; panelMatrix maps local
                    // origin to the world position, so center it at (0,0,0).
                    Box(0f, 0f, 0f, spec.widthM / 2f, spec.heightM / 2f, 0.05f),
                )
                .culling(false)
                .build(e, entity)
            st.scene.addEntity(entity)

            val transformInstance = tm.create(entity)
            tm.setTransform(transformInstance, panelMatrix(spec))

            st.panelEntities[spec.id] = entity
            st.panelInstances[spec.id] = instance
        }
        Log.i(
            TAG,
            "panels applied: ${specs.size} (entities=${st.scene.entityCount} " +
                "renderables=${st.scene.renderableCount})",
        )
    }

    private fun applyFocus() {
        val f = focusedId
        if (f == lastAppliedFocus) return
        lastAppliedFocus = f
        val st = sceneState ?: return
        for ((id, instance) in st.panelInstances) {
            val tint = if (id == f) FOCUS_TINT else PANEL_TINT
            instance?.setParameter("color", tint[0], tint[1], tint[2], tint[3])
        }
    }

    /** Create the backdrop sphere entity with [instance] and add it to the scene. */
    private fun buildSphereEntity(e: Engine, scene: Scene, mesh: Mesh, instance: MaterialInstance): Int {
        val entity = EntityManager.get().create()
        RenderableManager.Builder(1)
            .geometry(
                0,
                RenderableManager.PrimitiveType.TRIANGLES,
                mesh.vertexBuffer,
                mesh.indexBuffer,
                0,
                mesh.indexCount,
            )
            .material(0, instance)
            .boundingBox(Box(0f, 0f, 0f, SPHERE_RADIUS, SPHERE_RADIUS, SPHERE_RADIUS))
            .culling(false)
            .build(e, entity)
        scene.addEntity(entity)
        val tm = e.transformManager
        tm.setTransform(tm.create(entity), IDENTITY_MATRIX)
        return entity
    }

    /**
     * Rebuild the sphere with the external-stream video material.
     * Recipe from Filament's sample-stream-test: SurfaceTexture(0) created
     * WITHOUT a GL texture and detached from the GL context — the Filament
     * Stream latches new frames itself, no updateTexImage needed.
     * Render-thread only.
     */
    private fun attachVideoLocked(st: SceneState, onReady: (Surface) -> Unit) {
        if (st.videoSurfaceTexture != null) return
        val e = st.engine

        val videoMaterial = MaterialFactory.buildVideoSphere(e)
        if (videoMaterial == null) {
            Log.e(TAG, "video material unavailable — request dropped")
            return
        }

        val streamTexture = SurfaceTexture(0).apply {
            setDefaultBufferSize(1024, 512)
            detachFromGLContext()
        }
        val surface = Surface(streamTexture)
        val stream = Stream.Builder().stream(streamTexture).build(e)
        val tex = Texture.Builder()
            .sampler(Texture.Sampler.SAMPLER_EXTERNAL)
            .format(Texture.InternalFormat.RGB8)
            .build(e)
        tex.setExternalStream(e, stream)

        val instance = videoMaterial.createInstance()
        instance.setParameter(
            "baseColor",
            tex,
            TextureSampler(
                TextureSampler.MinFilter.LINEAR,
                TextureSampler.MagFilter.LINEAR,
                TextureSampler.WrapMode.CLAMP_TO_EDGE,
            ),
        )

        // Swap the sphere renderable over to the video instance.
        st.scene.removeEntity(st.sphereEntity)
        e.destroyEntity(st.sphereEntity)
        st.sphereInstance?.let { e.destroyMaterialInstance(it) }
        st.sphereEntity = buildSphereEntity(e, st.scene, st.sphereMesh, instance)
        st.sphereInstance = instance

        st.videoMaterial = videoMaterial
        st.videoTexture = tex
        st.videoStream = stream
        st.videoSurfaceTexture = streamTexture
        st.videoSurface = surface
        st.videoInstance = instance
        Log.i(TAG, "video sphere attached (external Stream)")

        // Immersive mode: the front panels would occlude the panorama —
        // pull them out of the scene (entities are kept, re-added on stop).
        for (entity in st.panelEntities.values) {
            st.scene.removeEntity(entity)
        }
        Log.i(TAG, "panels hidden while video plays (${st.panelEntities.size})")

        Handler(Looper.getMainLooper()).post { onReady(surface) }
    }

    /**
     * Swap the sphere back to the flat tinted material and free the video
     * chain. Render-thread only; caller (stopVideo) must have detached the
     * player from the Surface already.
     */
    private fun detachVideoLocked(st: SceneState) {
        val e = st.engine
        val hadVideo = st.videoSurfaceTexture != null

        st.colorMaterial?.let { colorMat ->
            st.scene.removeEntity(st.sphereEntity)
            e.destroyEntity(st.sphereEntity)
            st.sphereInstance?.let { e.destroyMaterialInstance(it) }
            val instance = colorMat.createInstance()
            instance.setParameter(
                "color",
                SPHERE_COLOR[0], SPHERE_COLOR[1], SPHERE_COLOR[2], SPHERE_COLOR[3],
            )
            st.sphereEntity = buildSphereEntity(e, st.scene, st.sphereMesh, instance)
            st.sphereInstance = instance
        }

        st.videoSurface?.release()
        st.videoSurfaceTexture?.release()
        st.videoStream?.let { e.destroyStream(it) }
        st.videoTexture?.let { e.destroyTexture(it) }
        st.videoMaterial?.let { e.destroyMaterial(it) }
        st.videoSurface = null
        st.videoSurfaceTexture = null
        st.videoStream = null
        st.videoTexture = null
        st.videoMaterial = null
        st.videoInstance = null

        // Panels back for non-video mode.
        for (entity in st.panelEntities.values) {
            st.scene.addEntity(entity)
        }
        Log.i(TAG, "video sphere detached (wasStreaming=$hadVideo), panels restored")
    }

    private fun destroySceneState(st: SceneState) {
        val e = st.engine
        st.modelManager.release()
        for (entity in st.panelEntities.values) {
            st.scene.removeEntity(entity)
            e.destroyEntity(entity)
        }
        st.panelInstances.values.forEach { it?.let { i -> e.destroyMaterialInstance(i) } }
        st.scene.removeEntity(st.sphereEntity)
        e.destroyEntity(st.sphereEntity)
        st.sphereInstance?.let { e.destroyMaterialInstance(it) }
        // video sphere resources (sphereInstance already destroyed above)
        st.videoSurface?.release()
        st.videoSurfaceTexture?.release()
        st.videoStream?.let { e.destroyStream(it) }
        st.videoTexture?.let { e.destroyTexture(it) }
        st.videoMaterial?.let { e.destroyMaterial(it) }
        st.sphereMesh.destroy(e)
        st.quadMesh.destroy(e)
        st.litMaterial?.let { e.destroyMaterial(it) }
        st.colorMaterial?.let { e.destroyMaterial(it) }
        e.destroyEntity(st.lightEntity)
        MaterialFactory.shutdown()
    }

    // ------------------------------------------------------------------
    // Math
    // ------------------------------------------------------------------

    /** Column-major matrix placing the quad (XY plane) at the panel position, facing origin. */
    private fun panelMatrix(spec: PanelSpec): FloatArray {
        val px = spec.x
        val py = spec.y
        val pz = spec.z
        val len = kotlin.math.sqrt(px * px + py * py + pz * pz)
        // normal: from panel toward origin
        val nx = if (len > 1e-5f) -px / len else 0f
        val ny = if (len > 1e-5f) -py / len else 0f
        val nz = if (len > 1e-5f) -pz / len else -1f
        // right = worldUp × normal
        var rx = 1f * nz - 0f * ny
        var ry = 0f * nx - 0f * nz
        var rz = 0f * ny - 1f * nx
        var rlen = kotlin.math.sqrt(rx * rx + ry * ry + rz * rz)
        if (rlen < 1e-4f) {
            rx = 1f; ry = 0f; rz = 0f; rlen = 1f
        }
        rx /= rlen; ry /= rlen; rz /= rlen
        // up = normal × right
        val ux = ny * rz - nz * ry
        val uy = nz * rx - nx * rz
        val uz = nx * ry - ny * rx

        return floatArrayOf(
            rx * spec.widthM, ry * spec.widthM, rz * spec.widthM, 0f,
            ux * spec.heightM, uy * spec.heightM, uz * spec.heightM, 0f,
            nx, ny, nz, 0f,
            px, py, pz, 1f,
        )
    }

    private fun rotate(q: FloatArray, vx: Float, vy: Float, vz: Float): FloatArray {
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

    companion object {
        private const val TAG = "FilamentRenderer"
        private const val SPHERE_RADIUS = 30f
        private val IDENTITY_Q = floatArrayOf(0f, 0f, 0f, 1f)

        /**
         * Filament's JNI library is NOT loaded by Engine's static init —
         * Filament 1.75.1 requires an explicit [Filament.init()] once per
         * process (otherwise Engine.create() dies with UnsatisfiedLinkError
         * on nCreateBuilder).
         */
        @Volatile
        private var filamentReady = false

        private fun ensureFilamentInit() {
            if (filamentReady) return
            synchronized(this) {
                if (filamentReady) return
                Filament.init()
                filamentReady = true
                Log.i(TAG, "Filament.init() done")
            }
        }

        private val IDENTITY_MATRIX = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f,
        )
        private val SPHERE_COLOR = floatArrayOf(0.12f, 0.17f, 0.28f, 1f)
        private val PANEL_TINT = floatArrayOf(0.12f, 0.30f, 0.40f, 1f)
        private val FOCUS_TINT = floatArrayOf(0.30f, 0.72f, 0.85f, 1f)
    }
}
