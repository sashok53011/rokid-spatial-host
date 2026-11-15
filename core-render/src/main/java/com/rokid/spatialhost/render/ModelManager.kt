package com.rokid.spatialhost.render

import android.util.Log
import com.google.android.filament.Box
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.IndexBuffer
import com.google.android.filament.Material
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager
import com.google.android.filament.Scene
import com.google.android.filament.TransformManager
import com.google.android.filament.VertexBuffer
import com.rokid.spatialhost.scene.gltf.GltfModel
import com.rokid.spatialhost.scene.gltf.GltfPrimitive
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Loads a parsed glTF model into the scene as ONE movable root ("group").
 *
 * D1 scope: static geometry, a single lit material for every primitive
 * (textures/PBR maps arrive in D2). Node hierarchy maps to Filament's
 * TransformManager parent chain; each mesh primitive is a renderable child
 * of its node entity.
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

        // One tint instance shared by all primitives (D1: no textures yet).
        val instance = lit.createInstance()
        instance.setParameter("color", MODEL_COLOR[0], MODEL_COLOR[1], MODEL_COLOR[2], MODEL_COLOR[3])
        lm.instances.add(instance)

        fun walk(nodeIdx: Int, parentTf: Int) {
            if (nodeIdx !in model.nodes.indices) return
            val node = model.nodes[nodeIdx]

            val nodeEntity = EntityManager.get().create()
            val nodeTf = tm.create(nodeEntity, parentTf, node.matrix)
            scene.addEntity(nodeEntity)
            lm.nodeEntities[nodeIdx] = nodeEntity

            model.primitives[node.mesh]?.forEach { prim ->
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
                    // TEMP-D1: culling disabled while validating winding;
                    // re-enable once placement is verified on device.
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
                "renderables=${lm.primitiveEntities.size}",
        )
        return lm
    }

    /** Apply placement (render-thread only). */
    fun setMatrix(matrix: FloatArray) {
        val lm = current ?: return
        lm.matrix = matrix
        engine.transformManager.setTransform(lm.groupTransform, matrix)
    }

    /** Debug: world-space bounds of the loaded model (local bounds × group matrix). */
    fun debugBounds(localMin: FloatArray, localMax: FloatArray): String {
        val lm = current ?: return "no model"
        val m = lm.matrix
        var min = FloatArray(3) { Float.MAX_VALUE }
        var max = FloatArray(3) { -Float.MAX_VALUE }
        for (cx in 0..1) for (cy in 0..1) for (cz in 0..1) {
            val lx = if (cx == 0) localMin[0] else localMax[0]
            val ly = if (cy == 0) localMin[1] else localMax[1]
            val lz = if (cz == 0) localMin[2] else localMax[2]
            val w = com.rokid.spatialhost.scene.gltf.GltfParser.matTransform(m, lx, ly, lz)
            for (k in 0..2) {
                if (w[k] < min[k]) min[k] = w[k]
                if (w[k] > max[k]) max[k] = w[k]
            }
        }
        fun f(a: FloatArray) = a.joinToString(",") { "%.2f".format(it) }
        return "local=${f(localMin)}..${f(localMax)} world=${f(min)}..${f(max)} group=${m.joinToString(",") { "%.2f".format(it) }}"
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
        scene.removeEntity(lm.groupEntity)
        engine.destroyEntity(lm.groupEntity)
        lm.primitiveEntities.clear()
        lm.nodeEntities.clear()
        lm.meshBuffers.clear()
        lm.instances.clear()
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

        val vb = VertexBuffer.Builder()
            .bufferCount(2)
            .vertexCount(vertexCount)
            .attribute(VertexBuffer.VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.FLOAT3, 0, 0)
            .attribute(VertexBuffer.VertexAttribute.TANGENTS, 1, VertexBuffer.AttributeType.FLOAT4, 0, 0)
            .build(engine)
        vb.setBufferAt(engine, 0, pos)
        vb.setBufferAt(engine, 1, tang)
        return vb
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
