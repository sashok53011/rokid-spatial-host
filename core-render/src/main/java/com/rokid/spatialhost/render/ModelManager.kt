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

    class LoadedModel(val groupEntity: Int, val groupTransform: Int) {
        val primitiveEntities = mutableListOf<Int>()
        val nodeEntities = mutableMapOf<Int, Int>()
        val meshBuffers = mutableListOf<Pair<VertexBuffer, IndexBuffer>>()
        val instances = mutableListOf<MaterialInstance>()
        val textures = mutableListOf<Texture>()
        var matrix: FloatArray = IDENT
    }

    var current: LoadedModel? = null
        private set

    /** Build GPU resources for [model]; replaces any previous model. */
    fun load(model: GltfModel, matrix: FloatArray): LoadedModel? {
        val lit = litMaterial
        if (lit == null) {
            Log.e(TAG, "load skipped — lit material unavailable")
            return null
        }
        release()

        val tm = engine.transformManager
        val groupEntity = EntityManager.get().create()
        val groupTf = tm.create(groupEntity)
        scene.addEntity(groupEntity)
        tm.setTransform(groupTf, matrix)

        val lm = LoadedModel(groupEntity, groupTf)
        lm.matrix = matrix

        // Textures are decoded once per image and shared across primitives.
        val texCache = mutableMapOf<Int, Pair<Texture, TextureSampler>>()
        val white = buildSolidTexture() // fallback when a primitive has no map
        lm.textures.add(white.first)

        fun textureFor(texIdx: Int?): Pair<Texture, TextureSampler> {
            if (texIdx == null || texIdx !in model.textures.indices) return white
            return texCache.getOrPut(texIdx) {
                val built = decodeTexture(model.textures[texIdx])
                if (built == null) white else {
                    lm.textures.add(built.first)
                    built
                }
            }
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
                val (tex, sampler) = textureFor(matDef?.baseColorTexture)

                // Per-primitive instance: tint × texture, PBR factors.
                val instance = lit.createInstance()
                val tint = matDef?.baseColorFactor ?: MODEL_COLOR
                instance.setParameter("color", tint[0], tint[1], tint[2], tint[3])
                instance.setParameter(
                    "mr",
                    matDef?.metallicFactor ?: 0.05f,
                    matDef?.roughnessFactor ?: 0.55f,
                )
                instance.setParameter("baseColor", tex, sampler)
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

        current = lm
        Log.i(
            TAG,
            "model loaded: nodes=${model.nodes.size} prims=${model.primitiveCount} " +
                "renderables=${lm.primitiveEntities.size} " +
                "materials=${model.materials.size} textures=${model.textures.size} " +
                "uploaded=${lm.textures.size}",
        )
        return lm
    }

    /** Apply placement (render-thread only). */
    fun setMatrix(matrix: FloatArray) {
        val lm = current ?: return
        lm.matrix = matrix
        engine.transformManager.setTransform(lm.groupTransform, matrix)
    }

    fun release() {
        val lm = current ?: return
        current = null
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
        for (t in lm.textures) engine.destroyTexture(t)
        scene.removeEntity(lm.groupEntity)
        engine.destroyEntity(lm.groupEntity)
        lm.primitiveEntities.clear()
        lm.nodeEntities.clear()
        lm.meshBuffers.clear()
        lm.instances.clear()
        lm.textures.clear()
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
        // FLOAT4): pack (tangent, bitangent, normal) per vertex.
        val tang = alloc(vertexCount * 4)
        val q = FloatArray(4)
        val t = FloatArray(3)
        val b = FloatArray(3)
        for (v in 0 until vertexCount) {
            val nx = normals[v * 3]
            val ny = normals[v * 3 + 1]
            val nz = normals[v * 3 + 2]
            // tangent: any unit vector perpendicular to the normal
            val refX: Float; val refY: Float; val refZ: Float
            if (kotlin.math.abs(nz) < 0.9f) { refX = 0f; refY = 0f; refZ = 1f }
            else { refX = 1f; refY = 0f; refZ = 0f }
            var tx = ny * refZ - nz * refY
            var ty = nz * refX - nx * refZ
            var tz = nx * refY - ny * refX
            val tl = kotlin.math.sqrt(tx * tx + ty * ty + tz * tz)
            if (tl > 1e-12f) { tx /= tl; ty /= tl; tz /= tl } else { tx = 1f; ty = 0f; tz = 0f }
            // bitangent = n × t keeps (t, b, n) right-handed
            b[0] = ny * tz - nz * ty
            b[1] = nz * tx - nx * tz
            b[2] = nx * ty - ny * tx
            t[0] = tx; t[1] = ty; t[2] = tz
            com.google.android.filament.MathUtils.packTangentFrame(
                t[0], t[1], t[2], b[0], b[1], b[2], nx, ny, nz, q,
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

    /** Decode a glTF image and upload it as a Filament texture. */
    private fun decodeTexture(gt: GltfTexture): Pair<Texture, TextureSampler>? {
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
        val tex = uploadRGBA(w, h, px, srgb = true) ?: return null
        val sampler = TextureSampler(
            TextureSampler.MinFilter.LINEAR,
            TextureSampler.MagFilter.LINEAR,
            glWrap(gt.wrapS),
            glWrap(gt.wrapT),
            glWrap(gt.wrapS),
        )
        Log.i(TAG, "texture ${w}x$h decoded (${gt.image.size}B)")
        return tex to sampler
    }

    /** 1×1 white fallback so primitives without a map still bind the sampler. */
    private fun buildSolidTexture(): Pair<Texture, TextureSampler> {
        val tex = uploadRGBA(1, 1, byteArrayOf(-1, -1, -1, -1), srgb = true)
            ?: error("white texture upload failed")
        val sampler = TextureSampler(
            TextureSampler.MinFilter.LINEAR,
            TextureSampler.MagFilter.LINEAR,
            TextureSampler.WrapMode.REPEAT,
            TextureSampler.WrapMode.REPEAT,
            TextureSampler.WrapMode.REPEAT,
        )
        return tex to sampler
    }

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
