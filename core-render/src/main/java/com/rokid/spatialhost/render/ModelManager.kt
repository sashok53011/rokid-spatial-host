package com.rokid.spatialhost.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.google.android.filament.Box
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.IndexBuffer
import com.google.android.filament.Material
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager
import com.google.android.filament.Scene
import com.google.android.filament.Texture
import com.google.android.filament.TextureSampler
import com.google.android.filament.TransformManager
import com.google.android.filament.VertexBuffer
import com.rokid.spatialhost.scene.gltf.GltfModel
import com.rokid.spatialhost.scene.gltf.GltfPrimitive
import com.rokid.spatialhost.scene.gltf.GltfTexture
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Loads a parsed glTF model into the scene as ONE movable root ("group").
 *
 * D2a scope: static geometry with baseColor textures — each primitive gets
 * its own MaterialInstance (tint + metallic/roughness factors + its
 * glTF texture). Node hierarchy maps to Filament's TransformManager parent
 * chain; each mesh primitive is a renderable child of its node entity.
 *
 * The app owns placement: it sends the group matrix (position/scale/rotation)
 * via [setMatrix] or up-front in [load].
 */
class ModelManager(
    private val engine: Engine,
    private val scene: Scene,
    private val litMaterial: Material?,
) {

    class LoadedModel(val id: String, val groupEntity: Int, val groupTransform: Int) {
        val primitiveEntities = mutableListOf<Int>()
        val nodeEntities = mutableMapOf<Int, Int>()
        val meshBuffers = mutableListOf<Pair<VertexBuffer, IndexBuffer>>()
        val instances = mutableListOf<MaterialInstance>()
        var matrix: FloatArray = IDENT
    }

    /** Live scene objects by id (the sandbox holds several). */
    val models = LinkedHashMap<String, LoadedModel>()

    /**
     * GPU resources shared per [GltfModel]: clones of the same parsed
     * model bind ONE set of textures (decoding5×2048 maps per spawn would
     * hitch the render thread for ~1 s each). Freed by [shutdown].
     */
    private val modelTexCache = HashMap<GltfModel, HashMap<Int, Pair<Texture, TextureSampler>>>()
    private var fallbackWhite: Pair<Texture, TextureSampler>? = null
    private var fallbackBlack: Pair<Texture, TextureSampler>? = null
    private var fallbackFlat: Pair<Texture, TextureSampler>? = null

    /** Build GPU resources for [model] under [id]; replaces that id only. */
    fun load(id: String, model: GltfModel, matrix: FloatArray): LoadedModel? {
        val lit = litMaterial
        if (lit == null) {
            Log.e(TAG, "load skipped — lit material unavailable")
            return null
        }
        release(id)

        val tm = engine.transformManager
        val groupEntity = EntityManager.get().create()
        val groupTf = tm.create(groupEntity)
        scene.addEntity(groupEntity)
        tm.setTransform(groupTf, matrix)

        val lm = LoadedModel(id, groupEntity, groupTf)
        lm.matrix = matrix

        // Textures: decoded once per GltfModel, shared across all its
        // instances/clones. Cache key distinguishes color-space usage (the
        // same image may be sampled as sRGB or linear).
        val texCache = modelTexCache.getOrPut(model) { HashMap() }
        val white = fallbackWhite ?: buildSolidTexture().also { fallbackWhite = it }
        val black = fallbackBlack ?: buildSolidTexture(0, 0, 0).also { fallbackBlack = it }
        val flatNormal = fallbackFlat ?: buildFlatNormalTexture().also { fallbackFlat = it }

        fun textureFor(
            texIdx: Int?,
            default: Pair<Texture, TextureSampler>,
            srgb: Boolean,
        ): Pair<Texture, TextureSampler> {
            if (texIdx == null || texIdx !in model.textures.indices) return default
            val key = if (srgb) texIdx else -(texIdx + 1)
            texCache[key]?.let { return it }
            val built = decodeTexture(model.textures[texIdx], srgb) ?: return default
            texCache[key] = built
            return built
        }

        fun walk(nodeIdx: Int, parentTf: Int) {
            if (nodeIdx !in model.nodes.indices) return
            val node = model.nodes[nodeIdx]

            val nodeEntity = EntityManager.get().create()
            val nodeTf = tm.create(nodeEntity, parentTf, node.matrix)
            scene.addEntity(nodeEntity)
            lm.nodeEntities[nodeIdx] = nodeEntity

            model.primitives[node.mesh]?.forEach { prim ->
                val matDef = prim.material?.let { model.materials.getOrNull(it) }

                // Per-primitive instance: tint × maps, PBR factors.
                val instance = lit.createInstance()
                val tint = matDef?.baseColorFactor ?: MODEL_COLOR
                instance.setParameter("color", tint[0], tint[1], tint[2], tint[3])
                instance.setParameter(
                    "mr",
                    matDef?.metallicFactor ?: 0.05f,
                    matDef?.roughnessFactor ?: 0.55f,
                )
                fun bind(name: String, tex: Pair<Texture, TextureSampler>) {
                    instance.setParameter(name, tex.first, tex.second)
                }
                bind("baseColor", textureFor(matDef?.baseColorTexture, white, srgb = true))
                bind("mrMap", textureFor(matDef?.metallicRoughnessTexture, white, srgb = false))
                bind("normalMap", textureFor(matDef?.normalTexture, flatNormal, srgb = false))
                bind("emissiveMap", textureFor(matDef?.emissiveTexture, black, srgb = true))
                bind("aoMap", textureFor(matDef?.occlusionTexture, white, srgb = false))
                // emissive.rgb = emissiveFactor, emissive.w = occlusionStrength
                instance.setParameter(
                    "emissive",
                    matDef?.emissiveFactor?.getOrNull(0) ?: 0f,
                    matDef?.emissiveFactor?.getOrNull(1) ?: 0f,
                    matDef?.emissiveFactor?.getOrNull(2) ?: 0f,
                    matDef?.occlusionStrength ?: 1f,
                )
                lm.instances.add(instance)

                val normals = prim.normals ?: generateFlatNormals(prim.positions)
                val vb = buildVertexBuffer(prim, normals)
                val ib = buildIndexBuffer(prim.indices)
                val entity = EntityManager.get().create()
                RenderableManager.Builder(1)
                    .geometry(
                        0,
                        RenderableManager.PrimitiveType.TRIANGLES,
                        vb, ib, 0, prim.indices.size,
                    )
                    .material(0, instance)
                    .boundingBox(boxOf(prim))
                    // Culling stays OFF: arbitrary imported glTF may carry
                    // either winding, and a wrongly culled model looks like a
                    // loading bug (single-model scenes don't need the perf).
                    .culling(false)
                    // Shadows: the model casts onto the lit floor and
                    // self-shadows (explicit — defaults must not drift).
                    .castShadows(true)
                    .receiveShadows(true)
                    .build(engine, entity)
                // GlTF node hierarchy: primitive under its node entity, which
                // carries the node's local transform (e.g. the helmet's +90°
                // X rotation from the DCC's up-axis convention).
                tm.create(entity, nodeTf, IDENT)
                scene.addEntity(entity)
                lm.primitiveEntities.add(entity)
                lm.meshBuffers.add(vb to ib)
            }

            for (child in node.children) walk(child, nodeTf)
        }

        for (root in model.sceneRootNodes) walk(root, groupTf)

        models[id] = lm
        model.primitives.values.firstOrNull()?.firstOrNull()?.let { p ->
            Log.i(
                TAG,
                "first verts: (${p.positions[0]}, ${p.positions[1]}, ${p.positions[2]}) " +
                    "n=${p.positions.size / 3}",
            )
        }
        Log.i(
            TAG,
            "model[$id] loaded: nodes=${model.nodes.size} prims=${model.primitiveCount} " +
                "renderables=${lm.primitiveEntities.size} " +
                "materials=${model.materials.size} textures=${model.textures.size} " +
                "scene=e${scene.entityCount}/r${scene.renderableCount} " +
                "objects=${models.size}",
        )
        return lm
    }

    /** Apply placement of one object (render-thread only). */
    fun setMatrix(id: String, matrix: FloatArray) {
        val lm = models[id] ?: return
        lm.matrix = matrix
        engine.transformManager.setTransform(lm.groupTransform, matrix)
    }

    /** Remove one object's entities (textures are shared — see [shutdown]). */
    fun release(id: String) {
        val lm = models.remove(id) ?: return
        for (e in lm.primitiveEntities) {
            scene.removeEntity(e)
            engine.destroyEntity(e)
        }
        for (e in lm.nodeEntities.values) {
            scene.removeEntity(e)
            engine.destroyEntity(e)
        }
        for ((vb, ib) in lm.meshBuffers) {
            engine.destroyVertexBuffer(vb)
            engine.destroyIndexBuffer(ib)
        }
        for (i in lm.instances) engine.destroyMaterialInstance(i)
        scene.removeEntity(lm.groupEntity)
        engine.destroyEntity(lm.groupEntity)
        Log.i(TAG, "model[$id] released → objects=${models.size}")
    }

    fun releaseAll() {
        models.keys.toList().forEach { release(it) }
    }

    /** Scene teardown: all objects + shared textures. */
    fun shutdown() {
        releaseAll()
        fallbackWhite?.first?.let { engine.destroyTexture(it) }
        fallbackBlack?.first?.let { engine.destroyTexture(it) }
        fallbackFlat?.first?.let { engine.destroyTexture(it) }
        fallbackWhite = null
        fallbackBlack = null
        fallbackFlat = null
        for (perModel in modelTexCache.values) {
            for ((_, pair) in perModel) engine.destroyTexture(pair.first)
        }
        modelTexCache.clear()
    }

    // ------------------------------------------------------------------
    // mesh building
    // ------------------------------------------------------------------

    private fun buildVertexBuffer(prim: GltfPrimitive, normals: FloatArray): VertexBuffer {
        val vertexCount = prim.positions.size / 3
        val pos = alloc(prim.positions.size)
        prim.positions.forEach { pos.putFloat(it) }
        pos.rewind()

        // Filament stores normals as a quaternion tangent frame (TANGENTS,
        // FLOAT4): pack (tangent, bitangent, normal) per vertex. Prefer the
        // glTF TANGENT attribute (UV-aligned, w = bitangent sign) so normal
        // maps line up; fall back to any perpendicular when it's absent.
        val tang = alloc(vertexCount * 4)
        val q = FloatArray(4)
        val primTan = prim.tangents
        for (v in 0 until vertexCount) {
            val nx = normals[v * 3]
            val ny = normals[v * 3 + 1]
            val nz = normals[v * 3 + 2]
            var tx = 0f
            var ty = 0f
            var tz = 0f
            var sign = 1f
            var ok = false
            if (primTan != null && primTan.size >= (v + 1) * 4) {
                tx = primTan[v * 4]
                ty = primTan[v * 4 + 1]
                tz = primTan[v * 4 + 2]
                sign = if (primTan[v * 4 + 3] < 0f) -1f else 1f
                // orthogonalize against the normal, then normalize
                val d = tx * nx + ty * ny + tz * nz
                tx -= nx * d
                ty -= ny * d
                tz -= nz * d
                val tl = kotlin.math.sqrt(tx * tx + ty * ty + tz * tz)
                if (tl > 1e-12f) {
                    tx /= tl
                    ty /= tl
                    tz /= tl
                    ok = true
                }
            }
            if (!ok) {
                val refX: Float; val refY: Float; val refZ: Float
                if (kotlin.math.abs(nz) < 0.9f) { refX = 0f; refY = 0f; refZ = 1f }
                else { refX = 1f; refY = 0f; refZ = 0f }
                tx = ny * refZ - nz * refY
                ty = nz * refX - nx * refZ
                tz = nx * refY - ny * refX
                val tl = kotlin.math.sqrt(tx * tx + ty * ty + tz * tz)
                if (tl > 1e-12f) { tx /= tl; ty /= tl; tz /= tl } else { tx = 1f; ty = 0f; tz = 0f }
                sign = 1f
            }
            // bitangent = sign · (n × t); sign flips handedness for the packer
            val bx = sign * (ny * tz - nz * ty)
            val by = sign * (nz * tx - nx * tz)
            val bz = sign * (nx * ty - ny * tx)
            com.google.android.filament.MathUtils.packTangentFrame(
                tx, ty, tz, bx, by, bz, nx, ny, nz, q,
            )
            q.forEach { tang.putFloat(it) }
        }
        tang.rewind()

        // UV0 (required by lit_pbr): zero-fill when the model has none so
        // the attribute layout stays valid for every renderable.
        val uvCount = vertexCount * 2
        val uvSrc = prim.uvs
        val uvs = if (uvSrc != null && uvSrc.size == uvCount) uvSrc else FloatArray(uvCount)
        val uvBuf = alloc(uvCount)
        uvs.forEach { uvBuf.putFloat(it) }
        uvBuf.rewind()

        val vb = VertexBuffer.Builder()
            .bufferCount(3)
            .vertexCount(vertexCount)
            .attribute(VertexBuffer.VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.FLOAT3, 0, 0)
            .attribute(VertexBuffer.VertexAttribute.TANGENTS, 1, VertexBuffer.AttributeType.FLOAT4, 0, 0)
            .attribute(VertexBuffer.VertexAttribute.UV0, 2, VertexBuffer.AttributeType.FLOAT2, 0, 0)
            .build(engine)
        vb.setBufferAt(engine, 0, pos)
        vb.setBufferAt(engine, 1, tang)
        vb.setBufferAt(engine, 2, uvBuf)
        return vb
    }

    // ------------------------------------------------------------------
    // textures (D2a: baseColor PNG/JPEG → RGBA8 with sRGB internal format,
    // so the GPU linearizes on sample — normal/MR maps in D2b use RGBA8)
    // ------------------------------------------------------------------

    /**
     * Decode a glTF image and upload it as a Filament texture.
     * [srgb] selects the internal format: color maps (baseColor/emissive)
     * are sRGB (GPU linearizes on sample), data maps (normal/MR/AO) must
     * stay linear RGBA8.
     */
    private fun decodeTexture(
        gt: GltfTexture,
        srgb: Boolean,
    ): Pair<Texture, TextureSampler>? {
        val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val bmp = BitmapFactory.decodeByteArray(gt.image, 0, gt.image.size, opts)
        if (bmp == null) {
            Log.w(TAG, "texture decode failed (${gt.image.size} bytes)")
            return null
        }
        val w = bmp.width
        val h = bmp.height
        val px = ByteArray(w * h * 4)
        bmp.copyPixelsToBuffer(ByteBuffer.wrap(px))
        bmp.recycle()
        val tex = uploadRGBA(w, h, px, srgb = srgb) ?: return null
        val sampler = TextureSampler(
            TextureSampler.MinFilter.LINEAR,
            TextureSampler.MagFilter.LINEAR,
            glWrap(gt.wrapS),
            glWrap(gt.wrapT),
            glWrap(gt.wrapS),
        )
        Log.i(TAG, "texture ${w}x$h decoded (${gt.image.size}B, ${if (srgb) "sRGB" else "linear"})")
        return tex to sampler
    }

    /** 1×1 solid fallback (white/black) so samplers always bind. */
    private fun buildSolidTexture(r: Int = 255, g: Int = 255, b: Int = 255): Pair<Texture, TextureSampler> {
        val tex = uploadRGBA(1, 1, byteArrayOf(r.toByte(), g.toByte(), b.toByte(), -1), srgb = true)
            ?: error("solid texture upload failed")
        return tex to defaultSampler()
    }

    /** 1×1 flat tangent-space normal (128,128,255 → (0,0,1) after *2-1). */
    private fun buildFlatNormalTexture(): Pair<Texture, TextureSampler> {
        val tex = uploadRGBA(1, 1, byteArrayOf(128.toByte(), 128.toByte(), -1, -1), srgb = false)
            ?: error("flat-normal texture upload failed")
        return tex to defaultSampler()
    }

    private fun defaultSampler() = TextureSampler(
        TextureSampler.MinFilter.LINEAR,
        TextureSampler.MagFilter.LINEAR,
        TextureSampler.WrapMode.REPEAT,
        TextureSampler.WrapMode.REPEAT,
        TextureSampler.WrapMode.REPEAT,
    )

    private fun uploadRGBA(w: Int, h: Int, px: ByteArray, srgb: Boolean): Texture? {
        val buf = ByteBuffer.allocateDirect(px.size).order(ByteOrder.nativeOrder())
        buf.put(px)
        buf.rewind()
        val tex = Texture.Builder()
            .width(w)
            .height(h)
            .format(if (srgb) Texture.InternalFormat.SRGB8_A8 else Texture.InternalFormat.RGBA8)
            .sampler(Texture.Sampler.SAMPLER_2D)
            .levels(1)
            .build(engine)
        tex.setImage(
            engine,
            0,
            Texture.PixelBufferDescriptor(buf, Texture.Format.RGBA, Texture.Type.UBYTE),
        )
        return tex
    }

    private fun glWrap(code: Int): TextureSampler.WrapMode = when (code) {
        33071 -> TextureSampler.WrapMode.CLAMP_TO_EDGE
        33648 -> TextureSampler.WrapMode.MIRRORED_REPEAT
        else -> TextureSampler.WrapMode.REPEAT
    }

    private fun buildIndexBuffer(indices: IntArray): IndexBuffer {
        val maxIdx = indices.maxOrNull() ?: 0
        val useU32 = maxIdx > 65535
        val ib = IndexBuffer.Builder()
            .indexCount(indices.size)
            .bufferType(
                if (useU32) IndexBuffer.Builder.IndexType.UINT
                else IndexBuffer.Builder.IndexType.USHORT,
            )
            .build(engine)
        // EXACT byte size — Filament validates buffer remaining() against
        // indexCount * elementSize and silently drops the draw on mismatch
        // (alloc() would size it x4 for "floats" — the invisible-model bug).
        if (useU32) {
            val buf = ByteBuffer.allocateDirect(indices.size * 4).order(ByteOrder.nativeOrder())
            indices.forEach { buf.putInt(it) }
            buf.rewind()
            ib.setBuffer(engine, buf)
        } else {
            val buf = ByteBuffer.allocateDirect(indices.size * 2).order(ByteOrder.nativeOrder())
            indices.forEach { buf.putShort(it.toShort()) }
            buf.rewind()
            ib.setBuffer(engine, buf)
        }
        return ib
    }

    /** Per-face flat normals for models without a NORMAL attribute. */
    private fun generateFlatNormals(positions: FloatArray): FloatArray {
        val out = FloatArray(positions.size)
        var i = 0
        while (i + 8 < positions.size) {
            val ax = positions[i]; val ay = positions[i + 1]; val az = positions[i + 2]
            val bx = positions[i + 3]; val by = positions[i + 4]; val bz = positions[i + 5]
            val cx = positions[i + 6]; val cy = positions[i + 7]; val cz = positions[i + 8]
            val e1x = bx - ax; val e1y = by - ay; val e1z = bz - az
            val e2x = cx - ax; val e2y = cy - ay; val e2z = cz - az
            var nx = e1y * e2z - e1z * e2y
            var ny = e1z * e2x - e1x * e2z
            var nz = e1x * e2y - e1y * e2x
            val len = kotlin.math.sqrt(nx * nx + ny * ny + nz * nz)
            if (len > 1e-12f) { nx /= len; ny /= len; nz /= len } else { nz = 1f }
            for (v in 0..2) {
                out[i + v * 3] = nx
                out[i + v * 3 + 1] = ny
                out[i + v * 3 + 2] = nz
            }
            i += 9
        }
        return out
    }

    private fun boxOf(prim: GltfPrimitive): Box {
        val bmin = prim.boundsMin; val bmax = prim.boundsMax
        return Box(
            (bmin[0] + bmax[0]) / 2f,
            (bmin[1] + bmax[1]) / 2f,
            (bmin[2] + bmax[2]) / 2f,
            (bmax[0] - bmin[0]) / 2f + 1e-3f,
            (bmax[1] - bmin[1]) / 2f + 1e-3f,
            (bmax[2] - bmin[2]) / 2f + 1e-3f,
        )
    }

    private fun alloc(floatCount: Int): ByteBuffer =
        ByteBuffer.allocateDirect(floatCount * 4).order(ByteOrder.nativeOrder())

    companion object {
        private const val TAG = "ModelManager"
        private val MODEL_COLOR = floatArrayOf(0.78f, 0.78f, 0.82f, 1f)
        private val IDENT = floatArrayOf(
            1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f,
        )
    }
}
