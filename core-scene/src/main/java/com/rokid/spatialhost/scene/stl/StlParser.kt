package com.rokid.spatialhost.scene.stl

import com.rokid.spatialhost.scene.gltf.GltfModel
import com.rokid.spatialhost.scene.gltf.GltfNode
import com.rokid.spatialhost.scene.gltf.GltfPrimitive
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * STL → [GltfModel] adapter.
 *
 * STL carries nothing but triangles — no UVs, no materials, no hierarchy —
 * so the result is a single mesh with sequential indices and NO normals
 * (ModelManager derives flat normals, which matches STL's faceted look).
 * Feeding it back into the existing glTF pipeline (bounds, placement,
 * physics box, export) keeps everything downstream unchanged.
 *
 * Format detection: binary if the size matches `84 + 50·count` (count =
 * u32 at byte 80), else ASCII when the text starts with "solid", else a
 * best-effort binary read (some exporters append trailing bytes).
 */
object StlParser {

    /** Parse binary or ASCII STL bytes into a single-mesh [GltfModel]. */
    fun parse(bytes: ByteArray, defaultName: String = "stl"): GltfModel {
        require(bytes.size >= 84) { "STL too small (${bytes.size} B)" }
        val triangles: List<FloatArray> = if (isBinary(bytes)) {
            readBinary(bytes)
        } else if (looksAscii(bytes)) {
            readAscii(bytes)
        } else {
            readBinary(bytes) // tolerate trailing garbage
        }
        require(triangles.isNotEmpty()) { "STL contains no triangles" }

        // Flatten: three vertices per triangle, non-indexed.
        val positions = FloatArray(triangles.size * 9)
        var min0 = Float.MAX_VALUE
        var min1 = Float.MAX_VALUE
        var min2 = Float.MAX_VALUE
        var max0 = -Float.MAX_VALUE
        var max1 = -Float.MAX_VALUE
        var max2 = -Float.MAX_VALUE
        for ((ti, t) in triangles.withIndex()) {
            System.arraycopy(t, 0, positions, ti * 9, 9)
            for (v in 0 until 3) {
                val x = t[v * 3]
                val y = t[v * 3 + 1]
                val z = t[v * 3 + 2]
                if (x < min0) min0 = x
                if (y < min1) min1 = y
                if (z < min2) min2 = z
                if (x > max0) max0 = x
                if (y > max1) max1 = y
                if (z > max2) max2 = z
            }
        }
        val prim = GltfPrimitive(
            positions = positions,
            normals = null, // flat normals derived at load
            uvs = null,
            indices = IntArray(positions.size / 3) { it },
            material = null,
            tangents = null,
        )
        val node = GltfNode(
            name = "root",
            matrix = floatArrayOf(
                1f, 0f, 0f, 0f,
                0f, 1f, 0f, 0f,
                0f, 0f, 1f, 0f,
                0f, 0f, 0f, 1f,
            ),
            children = IntArray(0),
            mesh = 0,
        )
        return GltfModel(
            primitives = mapOf(0 to listOf(prim)),
            nodes = listOf(node),
            sceneRootNodes = intArrayOf(0),
            boundsMin = floatArrayOf(min0, min1, min2),
            boundsMax = floatArrayOf(max0, max1, max2),
            name = defaultName,
        )
    }

    // ------------------------------------------------------------------

    private fun isBinary(bytes: ByteArray): Boolean {
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        bb.position(80)
        val count = bb.int.toLong() and 0xFFFFFFFFL
        return bytes.size.toLong() == 84L + count * 50L
    }

    private fun looksAscii(bytes: ByteArray): Boolean {
        val head = String(bytes, 0, minOf(64, bytes.size), Charsets.US_ASCII)
        return head.trimStart().startsWith("solid")
    }

    private fun readBinary(bytes: ByteArray): List<FloatArray> {
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        bb.position(80)
        val count = bb.int
        require(count > 0 && count < 50_000_000) { "implausible STL triangle count: $count" }
        val out = ArrayList<FloatArray>(count)
        for (i in 0 until count) {
            bb.position(84 + i * 50 + 12) // skip facet normal
            val t = FloatArray(9)
            for (k in 0..8) t[k] = bb.float
            out.add(t)
        }
        return out
    }

    private fun readAscii(bytes: ByteArray): List<FloatArray> {
        val text = String(bytes, Charsets.US_ASCII)
        val out = mutableListOf<FloatArray>()
        var current = FloatArray(9)
        var vertex = 0
        for (line in text.lineSequence()) {
            val s = line.trim()
            if (!s.startsWith("vertex")) continue
            val parts = s.split(Regex("\\s+"))
            if (parts.size < 4) continue
            current[vertex * 3] = parts[1].toFloat()
            current[vertex * 3 + 1] = parts[2].toFloat()
            current[vertex * 3 + 2] = parts[3].toFloat()
            vertex++
            if (vertex == 3) {
                out.add(current)
                current = FloatArray(9)
                vertex = 0
            }
        }
        return out
    }
}
