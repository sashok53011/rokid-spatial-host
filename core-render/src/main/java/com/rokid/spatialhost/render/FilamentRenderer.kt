package com.rokid.spatialhost.render

import android.graphics.Bitmap
import android.graphics.Color
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
import com.google.android.filament.Skybox
import com.google.android.filament.Stream
import com.google.android.filament.SwapChain
import com.google.android.filament.Texture
import com.google.android.filament.TextureSampler
import com.google.android.filament.View
import com.google.android.filament.Viewport
import com.rokid.spatialhost.scene.gltf.GltfModel
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Panel description pushed from the app (mirrors core-scene's SpatialPanel). */
data class PanelSpec(
    val id: String,
    val widthM: Float,
    val heightM: Float,
    val x: Float,
    val y: Float,
    val z: Float,
    /** Survives the immersive-video panel hide (control decks). */
    val persistent: Boolean = false,
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

    /**
     * Fired on the MAIN thread whenever the scene (re)creates — the app
     * re-pushes one-shot content (panels, UI textures, model, video),
     * because the first-run settings screen destroys the surface and the
     * render loop fully rebuilds from scratch.
     */
    @Volatile
    var onSceneReady: (() -> Unit)? = null

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

    /** Canvas-rendered RGBA pixels for one panel's widget texture. */
    class PanelUiUpdate(val id: String, val width: Int, val height: Int, val pixels: ByteArray)

    @Volatile
    private var pendingPanelUi: List<PanelUiUpdate>? = null

    /** App thread: replace the widget texture of panel [id]. */
    fun updatePanelUi(id: String, width: Int, height: Int, pixels: ByteArray) {
        // Clone: callers reuse their buffer across panels, and queued
        // updates must not alias into one array (all would show the last).
        pendingPanelUi = (pendingPanelUi ?: emptyList()) +
            PanelUiUpdate(id, width, height, pixels.copyOf())
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
    sealed interface ModelOp {
        class Load(val id: String, val model: GltfModel, val matrix: FloatArray) : ModelOp
        class Transform(val id: String, val matrix: FloatArray) : ModelOp
        class Remove(val id: String) : ModelOp
        /** Toggle play/pause of model animations. */
        object AnimToggle : ModelOp
        /** Switch every object to the next animation clip. */
        object AnimClip : ModelOp
    }

    @Volatile
    private var pendingModelOps: List<ModelOp>? = null

    private fun postModelOp(op: ModelOp) {
        pendingModelOps = (pendingModelOps ?: emptyList()) + op
    }

    /** Queue loading [model] as object [id] (replaces that id only). */
    fun requestModel(id: String, model: GltfModel, matrix: FloatArray) {
        postModelOp(ModelOp.Load(id, model, matrix))
    }

    /** Queue a placement update for one object. */
    fun setModelTransform(id: String, matrix: FloatArray) {
        postModelOp(ModelOp.Transform(id, matrix))
    }

    /** Queue removal of one object. */
    fun requestRemoveModel(id: String) {
        postModelOp(ModelOp.Remove(id))
    }

    /** Queue play/pause of model animations (A key). */
    fun requestAnimationToggle() {
        postModelOp(ModelOp.AnimToggle)
    }

    /** Queue switching to the next animation clip (G key). */
    fun requestNextAnimationClip() {
        postModelOp(ModelOp.AnimClip)
    }

    /**
     * HUD snapshot of animation playback, maintained by the render loop:
     * "off" | "paused" | "clip 1/2 Walk 1.25/3.00s".
     */
    @Volatile
    var animationStatus: String = "off"
        private set

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
        val ibl: IblBuilder,
        val panelEntities: MutableMap<String, Int> = mutableMapOf(),
        val panelInstances: MutableMap<String, MaterialInstance?> = mutableMapOf(),
        /** Widget-textured panel material (null → legacy flat color panels). */
        val panelUiMaterial: Material? = null,
        val panelTextures: MutableMap<String, Texture> = mutableMapOf(),
        val panelTexSizes: MutableMap<String, Pair<Int, Int>> = mutableMapOf(),
        var whiteTexture: Texture? = null,
        var flatNormalTexture: Texture? = null,
        /** Ids kept visible while the video sphere hides the rest. */
        val panelPersistent: MutableSet<String> = mutableSetOf(),
        val panelHidden: MutableSet<String> = mutableSetOf(),
        /** IBL skybox replaces the flat color sphere as the background. */
        val hasSkybox: Boolean = false,
        var skybox: Skybox? = null,
        /** Lit floor slab (y = -1, matches the physics floor) — shadow receiver. */
        var floorEntity: Int = -1,
        var floorInstance: MaterialInstance? = null,
        var floorHidden: Boolean = false,
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
            val panelUiMaterial = MaterialFactory.buildPanelUi(e)
            val litMaterial = MaterialFactory.buildLit(e)
            val sphereMesh = Meshes.createSphere(e, radius = 30f)
            val quadMesh = Meshes.createQuad(e)

            // Key light for lit models — direction aligned with the sun of
            // the procedural IBL environment (D2c), so direct and ambient
            // light agree. Travels -Z: lights the camera-facing side.
            // castShadows: the model self-shadows and drops onto the floor.
            val lightEntity = EntityManager.get().create()
            LightManager.Builder(LightManager.Type.DIRECTIONAL)
                .color(1.0f, 0.98f, 0.94f)
                .intensity(110_000f) // clear-day lux, as in Filament's samples
                // Aligned with the IBL env sun (reflections and direct light
                // agree); the helmet's floor shadow falls back-left of it.
                .direction(-0.42f, -0.66f, -0.62f)
                .castShadows(true)
                .build(e, lightEntity)
            s.addEntity(lightEntity)

            // D2c: image-based lighting — procedural equirect → GPU-prefiltered
            // reflections + 3-band SH irradiance (see IblBuilder).
            val ibl = IblBuilder(e)
            var skybox: Skybox? = null
            if (ibl.build()) {
                s.indirectLight = ibl.indirectLight
                // Same environment as the VISIBLE background (mip0 of the
                // prefiltered reflections = the sharp environment).
                ibl.reflections?.let { refl ->
                    skybox = Skybox.Builder().environment(refl).build(e)
                    s.skybox = skybox
                    Log.i(TAG, "skybox: IBL environment visible as background")
                }
            }

            // Background: IBL skybox if available, else the flat tint sphere.
            val sphereInstance = if (skybox == null) {
                colorMaterial?.createInstance()?.also {
                    it.setParameter("color", SPHERE_COLOR[0], SPHERE_COLOR[1], SPHERE_COLOR[2], SPHERE_COLOR[3])
                }
            } else null
            val sphereEntity = when {
                sphereInstance != null -> buildSphereEntity(e, s, sphereMesh, sphereInstance)
                skybox != null -> -1 // background comes from the skybox
                else -> EntityManager.get().create()
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
                ibl = ibl,
                panelUiMaterial = panelUiMaterial,
                hasSkybox = skybox != null,
                skybox = skybox,
            )
            sceneState?.let { st ->
                if (litMaterial != null && skybox != null) buildShadowFloor(st)
            }
            Log.i(
                TAG,
                "scene after sphere: entities=${s.entityCount} renderables=${s.renderableCount} " +
                    "material=${colorMaterial != null}",
            )
            onSceneReady?.let { cb -> Handler(Looper.getMainLooper()).post(cb) }

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
                applyPendingPanelUi()
                applyFocus()

                // Scene models: queued loads/transforms/removes, in order
                pendingModelOps?.let { ops ->
                    pendingModelOps = null
                    val mm = sceneState?.modelManager
                    if (mm != null) {
                        for (op in ops) {
                            when (op) {
                                is ModelOp.Load -> mm.load(op.id, op.model, op.matrix)
                                is ModelOp.Transform -> mm.setMatrix(op.id, op.matrix)
                                is ModelOp.Remove -> mm.release(op.id)
                                is ModelOp.AnimToggle -> {
                                    val playing = mm.toggleAnimations()
                                    Log.i(TAG, "animations $playing")
                                }

                                is ModelOp.AnimClip -> {
                                    val idx = mm.nextAnimationClip()
                                    Log.i(TAG, "animation clip → $idx")
                                }
                            }
                        }
                    }
                }

                // Rigid-keyframe animation (D5) — after ops so a fresh load
                // animates in the same frame.
                sceneState?.modelManager?.let { mm ->
                    mm.tickAnimations(System.nanoTime())
                    animationStatus = mm.hudStatus()
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

        // Remove existing panels (and their widget textures)
        for (entity in st.panelEntities.values) {
            st.scene.removeEntity(entity)
            e.destroyEntity(entity)
        }
        st.panelEntities.clear()
        st.panelInstances.clear()
        st.panelPersistent.clear()
        st.panelHidden.clear()
        for (tex in st.panelTextures.values) e.destroyTexture(tex)
        st.panelTextures.clear()
        st.panelTexSizes.clear()
        lastAppliedFocus = null

        val material = st.panelUiMaterial ?: st.colorMaterial
        if (material == null) return
        val useUi = st.panelUiMaterial != null
        val white = if (useUi) ensureWhiteTexture(st) else null
        val tm = e.transformManager
        for (spec in specs) {
            val entity = EntityManager.get().create()
            val instance = material.createInstance()
            val baseTint = if (useUi) UI_TINT else PANEL_TINT
            instance.setParameter("color", baseTint[0], baseTint[1], baseTint[2], baseTint[3])
            white?.let { instance.setParameter("ui", it, PANEL_SAMPLER) }
            if (spec.persistent) st.panelPersistent += spec.id

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
                // UI cards don't cast (thin planes would smear shadows);
                // they're unlit anyway, so receiving is moot.
                .castShadows(false)
                .build(e, entity)
            st.scene.addEntity(entity)

            val transformInstance = tm.create(entity)
            tm.setTransform(transformInstance, panelMatrix(spec))

            st.panelEntities[spec.id] = entity
            st.panelInstances[spec.id] = instance
        }
        Log.i(
            TAG,
            "panels applied: ${specs.size} (persistent=${st.panelPersistent}, " +
                "entities=${st.scene.entityCount} renderables=${st.scene.renderableCount})",
        )
    }

    /** Upload widget pixels for one panel (create/resize its texture). */
    private fun applyPendingPanelUi() {
        val updates = pendingPanelUi ?: return
        pendingPanelUi = null
        val st = sceneState ?: return
        if (st.panelUiMaterial == null) return
        val e = st.engine
        for (u in updates) {
            if (u.id !in st.panelEntities) continue
            val size = u.width to u.height
            var tex = st.panelTextures[u.id]
            if (tex == null || st.panelTexSizes[u.id] != size) {
                tex?.let { e.destroyTexture(it) }
                tex = Texture.Builder()
                    .width(u.width)
                    .height(u.height)
                    .format(Texture.InternalFormat.RGBA8)
                    .sampler(Texture.Sampler.SAMPLER_2D)
                    .levels(1)
                    .build(e)
                st.panelTextures[u.id] = tex
                st.panelTexSizes[u.id] = size
                Log.i(TAG, "panel ui texture: ${u.id} ${u.width}x${u.height}")
            }
            val buf = ByteBuffer.allocateDirect(u.pixels.size).order(ByteOrder.nativeOrder())
            buf.put(u.pixels)
            buf.rewind()
            tex.setImage(e, 0, Texture.PixelBufferDescriptor(buf, Texture.Format.RGBA, Texture.Type.UBYTE))
            st.panelInstances[u.id]?.setParameter("ui", tex, PANEL_SAMPLER)
        }
    }

    /** Shared white texture so panels without widgets bind a valid sampler. */
    private fun ensureWhiteTexture(st: SceneState): Texture {
        st.whiteTexture?.let { return it }
        val bmp = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        bmp.setPixel(0, 0, Color.WHITE)
        val buf = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())
        bmp.copyPixelsToBuffer(buf)
        buf.rewind()
        bmp.recycle()
        val tex = Texture.Builder()
            .width(1)
            .height(1)
            .format(Texture.InternalFormat.RGBA8)
            .sampler(Texture.Sampler.SAMPLER_2D)
            .levels(1)
            .build(st.engine)
        tex.setImage(
            st.engine, 0,
            Texture.PixelBufferDescriptor(buf, Texture.Format.RGBA, Texture.Type.UBYTE),
        )
        st.whiteTexture = tex
        return tex
    }

    /** Flat tangent-space normal (128,128,255) — normalMap fallback. */
    private fun ensureFlatNormalTexture(st: SceneState): Texture {
        st.flatNormalTexture?.let { return it }
        val bmp = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        bmp.setPixel(0, 0, Color.rgb(128, 128, 255))
        val buf = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())
        bmp.copyPixelsToBuffer(buf)
        buf.rewind()
        bmp.recycle()
        val tex = Texture.Builder()
            .width(1)
            .height(1)
            .format(Texture.InternalFormat.RGBA8)
            .sampler(Texture.Sampler.SAMPLER_2D)
            .levels(1)
            .build(st.engine)
        tex.setImage(
            st.engine, 0,
            Texture.PixelBufferDescriptor(buf, Texture.Format.RGBA, Texture.Type.UBYTE),
        )
        st.flatNormalTexture = tex
        return tex
    }

    /**
     * Visible floor slab at the physics floor top (y = -1): LIT so it
     * receives the key light's shadow — without a lit receiver the model
     * would cast onto nothing (the skybox/panels are unlit).
     */
    private fun buildShadowFloor(st: SceneState) {
        val lit = st.litMaterial ?: return
        val white = ensureWhiteTexture(st)
        val flat = ensureFlatNormalTexture(st)
        val instance = lit.createInstance()
        instance.setParameter("color", FLOOR_COLOR[0], FLOOR_COLOR[1], FLOOR_COLOR[2], FLOOR_COLOR[3])
        instance.setParameter("mr", 0f, 0.9f) // matte dielectric
        instance.setParameter("baseColor", white, PANEL_SAMPLER)
        instance.setParameter("mrMap", white, PANEL_SAMPLER)
        instance.setParameter("normalMap", flat, PANEL_SAMPLER)

        val entity = EntityManager.get().create()
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
            .boundingBox(Box(0f, 0f, 0f, 40f, 0.1f, 40f))
            .castShadows(false)
            .receiveShadows(true)
            .culling(false)
            .build(st.engine, entity)
        st.scene.addEntity(entity)
        val tm = st.engine.transformManager
        tm.setTransform(tm.create(entity), FLOOR_MATRIX)
        st.floorEntity = entity
        st.floorInstance = instance
        Log.i(TAG, "shadow floor added at y=-1 (receiver)")
    }

    private fun applyFocus() {
        val f = focusedId
        if (f == lastAppliedFocus) return
        lastAppliedFocus = f
        val st = sceneState ?: return
        val useUi = st.panelUiMaterial != null
        for ((id, instance) in st.panelInstances) {
            val tint = when {
                id != f -> if (useUi) UI_TINT else PANEL_TINT
                useUi -> UI_FOCUS_TINT
                else -> FOCUS_TINT
            }
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
        if (st.sphereEntity >= 0) {
            st.scene.removeEntity(st.sphereEntity)
            e.destroyEntity(st.sphereEntity)
        }
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
        // Persistent panels (the video control deck) stay visible.
        st.panelHidden.clear()
        for ((id, entity) in st.panelEntities) {
            if (id in st.panelPersistent) continue
            st.scene.removeEntity(entity)
            st.panelHidden += id
        }
        if (st.floorEntity >= 0) {
            st.scene.removeEntity(st.floorEntity)
            st.floorHidden = true
        }
        Log.i(
            TAG,
            "panels hidden while video plays " +
                "(${st.panelHidden.size} hidden, ${st.panelPersistent.size} persistent)",
        )

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

        // Drop the video sphere (it may be the only sphere in skybox mode).
        if (st.sphereEntity >= 0) {
            st.scene.removeEntity(st.sphereEntity)
            e.destroyEntity(st.sphereEntity)
            st.sphereEntity = -1
        }
        st.sphereInstance?.let { e.destroyMaterialInstance(it) }
        st.sphereInstance = null

        // Restore the flat tint sphere ONLY when the skybox is absent —
        // with an IBL skybox the environment IS the background.
        if (!st.hasSkybox) {
            st.colorMaterial?.let { colorMat ->
                val instance = colorMat.createInstance()
                instance.setParameter(
                    "color",
                    SPHERE_COLOR[0], SPHERE_COLOR[1], SPHERE_COLOR[2], SPHERE_COLOR[3],
                )
                st.sphereEntity = buildSphereEntity(e, st.scene, st.sphereMesh, instance)
                st.sphereInstance = instance
            }
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

        // Panels back for non-video mode (only the ones we actually hid).
        for (id in st.panelHidden) {
            st.panelEntities[id]?.let { st.scene.addEntity(it) }
        }
        st.panelHidden.clear()
        if (st.floorHidden && st.floorEntity >= 0) {
            st.scene.addEntity(st.floorEntity)
            st.floorHidden = false
        }
        Log.i(TAG, "video sphere detached (wasStreaming=$hadVideo), panels restored")
    }

    private fun destroySceneState(st: SceneState) {
        val e = st.engine
        st.modelManager.shutdown()
        st.ibl.release()
        for (entity in st.panelEntities.values) {
            st.scene.removeEntity(entity)
            e.destroyEntity(entity)
        }
        st.panelInstances.values.forEach { it?.let { i -> e.destroyMaterialInstance(i) } }
        for (tex in st.panelTextures.values) e.destroyTexture(tex)
        st.panelTextures.clear()
        st.whiteTexture?.let { e.destroyTexture(it) }
        st.flatNormalTexture?.let { e.destroyTexture(it) }
        st.panelUiMaterial?.let { e.destroyMaterial(it) }
        if (st.floorEntity >= 0) {
            st.scene.removeEntity(st.floorEntity)
            e.destroyEntity(st.floorEntity)
        }
        st.floorInstance?.let { e.destroyMaterialInstance(it) }
        st.skybox?.let { e.destroySkybox(it) }
        if (st.sphereEntity >= 0) {
            st.scene.removeEntity(st.sphereEntity)
            e.destroyEntity(st.sphereEntity)
        }
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
        /** T(0,-1,-2.2) · Rx(-90°) · S(80): flat slab, normal +Y, ±40 m. */
        private val FLOOR_MATRIX = floatArrayOf(
            80f, 0f, 0f, 0f,
            0f, 0f, -80f, 0f,
            0f, 1f, 0f, 0f,
            0f, -1f, -2.2f, 1f,
        )
        private val FLOOR_COLOR = floatArrayOf(0.30f, 0.33f, 0.38f, 1f)
        private val PANEL_TINT = floatArrayOf(0.12f, 0.30f, 0.40f, 1f)
        private val FOCUS_TINT = floatArrayOf(0.30f, 0.72f, 0.85f, 1f)
        /** Widget panels: neutral pass-through; focus = uniform brighten. */
        private val UI_TINT = floatArrayOf(1f, 1f, 1f, 1f)
        private val UI_FOCUS_TINT = floatArrayOf(1.5f, 1.5f, 1.5f, 1f)
        // lazy: TextureSampler is native-backed and the companion <clinit>
        // runs when FilamentRenderer is constructed — BEFORE Filament.init()
        // loads libfilament-jni. First access happens on the render thread.
        private val PANEL_SAMPLER by lazy {
            TextureSampler(
                TextureSampler.MinFilter.LINEAR,
                TextureSampler.MagFilter.LINEAR,
                TextureSampler.WrapMode.CLAMP_TO_EDGE,
                TextureSampler.WrapMode.CLAMP_TO_EDGE,
                TextureSampler.WrapMode.CLAMP_TO_EDGE,
            )
        }
    }
}
