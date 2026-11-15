package com.rokid.spatialhost.render

import com.google.android.filament.Engine
import com.google.android.filament.IndexBuffer
import com.google.android.filament.VertexBuffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.sin

/** GPU mesh handle pair + index count. */
class Mesh(
    val vertexBuffer: VertexBuffer,
    val indexBuffer: IndexBuffer,
    val indexCount: Int,
) {
    fun destroy(engine: Engine) {
        engine.destroyVertexBuffer(vertexBuffer)
        engine.destroyIndexBuffer(indexBuffer)
    }
}

/**
 * Procedural meshes. Two-buffer layout: [0] = positions (FLOAT3),
 * [1] = UVs (FLOAT2). Indexed with 16-bit indices.
 */
object Meshes {

    /** Equirectangular UV sphere (360° backdrop), radius in meters. */
    fun createSphere(engine: Engine, radius: Float = 30f, slices: Int = 48, stacks: Int = 32): Mesh {
        val vertexCount = (stacks + 1) * (slices + 1)
        val pos = alloc(vertexCount * 3)
        val uv = alloc(vertexCount * 2)
        for (i in 0..stacks) {
            val theta = (i.toFloat() / stacks) * Math.PI.toFloat()
            val st = sin(theta)
            val ct = cos(theta)
            for (j in 0..slices) {
                val phi = (j.toFloat() / slices) * (2f * Math.PI.toFloat())
                pos.putFloat(radius * st * cos(phi))
                pos.putFloat(radius * ct)
                pos.putFloat(radius * st * sin(phi))
                uv.putFloat(j.toFloat() / slices)
                uv.putFloat(i.toFloat() / stacks)
            }
        }
        pos.rewind()
        uv.rewind()

        val indexCount = stacks * slices * 6
        val idx = allocBytes(indexCount * 2)
        for (i in 0 until stacks) {
            for (j in 0 until slices) {
                val a = i * (slices + 1) + j
                val b = a + slices + 1
                idx.putShort(a.toShort())
                idx.putShort(b.toShort())
                idx.putShort((a + 1).toShort())
                idx.putShort((a + 1).toShort())
                idx.putShort(b.toShort())
                idx.putShort((b + 1).toShort())
            }
        }
        idx.rewind()
        return build(engine, vertexCount, pos, uv, idx, indexCount)
    }

    /** Unit quad in the XY plane (±0.5), scaled per-entity by its transform. */
    fun createQuad(engine: Engine): Mesh {
        val pos = alloc(4 * 3)
        pos.putFloat(-0.5f); pos.putFloat(-0.5f); pos.putFloat(0f)
        pos.putFloat(0.5f); pos.putFloat(-0.5f); pos.putFloat(0f)
        pos.putFloat(0.5f); pos.putFloat(0.5f); pos.putFloat(0f)
        pos.putFloat(-0.5f); pos.putFloat(0.5f); pos.putFloat(0f)
        pos.rewind()

        val uv = alloc(4 * 2)
        uv.putFloat(0f); uv.putFloat(0f)
        uv.putFloat(1f); uv.putFloat(0f)
        uv.putFloat(1f); uv.putFloat(1f)
        uv.putFloat(0f); uv.putFloat(1f)
        uv.rewind()

        val idx = allocBytes(6 * 2)
        idx.putShort(0); idx.putShort(1); idx.putShort(2)
        idx.putShort(0); idx.putShort(2); idx.putShort(3)
        idx.rewind()

        return build(engine, 4, pos, uv, idx, 6)
    }

    private fun build(
        engine: Engine,
        vertexCount: Int,
        pos: ByteBuffer,
        uv: ByteBuffer,
        idx: ByteBuffer,
        indexCount: Int,
    ): Mesh {
        val vb = VertexBuffer.Builder()
            .bufferCount(2)
            .vertexCount(vertexCount)
            .attribute(VertexBuffer.VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.FLOAT3, 0, 0)
            .attribute(VertexBuffer.VertexAttribute.UV0, 1, VertexBuffer.AttributeType.FLOAT2, 0, 0)
            .build(engine)
        vb.setBufferAt(engine, 0, pos)
        vb.setBufferAt(engine, 1, uv)

        val ib = IndexBuffer.Builder()
            .indexCount(indexCount)
            .bufferType(IndexBuffer.Builder.IndexType.USHORT)
            .build(engine)
        // MUST be exactly indexCount * 2 bytes for USHORT — Filament validates
        // the buffer size and silently drops the draw on mismatch (this was
        // the "black screen with alive fps" bug: alloc() sized it 4x too big).
        ib.setBuffer(engine, idx)

        return Mesh(vb, ib, indexCount)
    }

    /** Float buffer: count of floats, capacity = count * 4 bytes. */
    private fun alloc(floatCount: Int): ByteBuffer =
        ByteBuffer.allocateDirect(floatCount * 4).order(ByteOrder.nativeOrder())

    /** Raw byte buffer with EXACT size (for index data). */
    fun allocBytes(byteCount: Int): ByteBuffer =
        ByteBuffer.allocateDirect(byteCount).order(ByteOrder.nativeOrder())
}
