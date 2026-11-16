package com.rokid.spatialhost.scene.stl

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** STL import: binary and ASCII paths of [StlParser]. */
class StlParserTest {

    /** Binary cube:12 CCW-outward triangles at ±0.5 (50 B each). */
    private fun binaryCube(): ByteArray {
        val tris = arrayOf(
            floatArrayOf(-0.5f, -0.5f, 0.5f, 0.5f, -0.5f, 0.5f, 0.5f, 0.5f, 0.5f),
            floatArrayOf(-0.5f, -0.5f, 0.5f, 0.5f, 0.5f, 0.5f, -0.5f, 0.5f, 0.5f),
            floatArrayOf(0.5f, -0.5f, -0.5f, -0.5f, -0.5f, -0.5f, -0.5f, 0.5f, -0.5f),
            floatArrayOf(0.5f, -0.5f, -0.5f, -0.5f, 0.5f, -0.5f, 0.5f, 0.5f, -0.5f),
            floatArrayOf(0.5f, -0.5f, 0.5f, 0.5f, -0.5f, -0.5f, 0.5f, 0.5f, -0.5f),
            floatArrayOf(0.5f, -0.5f, 0.5f, 0.5f, 0.5f, -0.5f, 0.5f, 0.5f, 0.5f),
            floatArrayOf(-0.5f, -0.5f, -0.5f, -0.5f, -0.5f, 0.5f, -0.5f, 0.5f, 0.5f),
            floatArrayOf(-0.5f, -0.5f, -0.5f, -0.5f, 0.5f, 0.5f, -0.5f, 0.5f, -0.5f),
            floatArrayOf(-0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, -0.5f),
            floatArrayOf(-0.5f, 0.5f, 0.5f, 0.5f, 0.5f, -0.5f, -0.5f, 0.5f, -0.5f),
            floatArrayOf(-0.5f, -0.5f, -0.5f, 0.5f, -0.5f, -0.5f, 0.5f, -0.5f, 0.5f),
            floatArrayOf(-0.5f, -0.5f, -0.5f, 0.5f, -0.5f, 0.5f, -0.5f, -0.5f, 0.5f),
        )
        val bb = ByteBuffer
            .allocate(84 + tris.size * 50)
            .order(ByteOrder.LITTLE_ENDIAN)
        repeat(80) { bb.put(0) } // header
        bb.putInt(tris.size)
        for (t in tris) {
            bb.putFloat(0f); bb.putFloat(0f); bb.putFloat(0f) // facet normal
            for (v in t) bb.putFloat(v)
            bb.putShort(0) // attribute byte count
        }
        return bb.array()
    }

    private val asciiTriangle = """
        solid t
          facet normal 0 0 1
            outer loop
              vertex 0 0 0
              vertex 1 0 0
              vertex 0 1 0
            endloop
          endfacet
        endsolid t
    """.trimIndent()

    @Test
    fun parsesBinaryCube() {
        val model = StlParser.parse(binaryCube(), "cube")
        assertEquals(1, model.primitiveCount)
        assertEquals(1, model.nodes.size)
        val prim = model.primitives.getValue(0).single()
        assertEquals(36, prim.positions.size / 3) //12 tris × 3 verts, non-indexed
        assertEquals(36, prim.indices.size)
        // first triangle vertices arrive verbatim
        assertEquals(-0.5f, prim.positions[0], 1e-6f)
        assertEquals(0.5f, prim.positions[2], 1e-6f)
        // exact AABB ±0.5
        for (k in 0..2) {
            assertEquals(-0.5f, model.boundsMin[k], 1e-6f)
            assertEquals(0.5f, model.boundsMax[k], 1e-6f)
        }
        // STL carries no normals/uvs/materials
        assertEquals(null, prim.normals)
        assertEquals(null, prim.uvs)
        assertEquals(null, prim.material)
    }

    @Test
    fun parsesAsciiTriangle() {
        val model = StlParser.parse(asciiTriangle.toByteArray(), "tri")
        assertEquals(1, model.primitiveCount)
        val prim = model.primitives.getValue(0).single()
        assertEquals(9, prim.positions.size)
        assertArrayEquals(
            floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f),
            prim.positions,
            1e-6f,
        )
        assertEquals(1f, model.boundsMax[0], 1e-6f)
        assertEquals(1f, model.boundsMax[1], 1e-6f)
        assertEquals(0f, model.boundsMax[2], 1e-6f)
    }

    @Test
    fun rejectsGarbage() {
        val thrown = try {
            StlParser.parse(ByteArray(100), "junk")
            false
        } catch (e: IllegalArgumentException) {
            true
        } catch (e: java.nio.BufferUnderflowException) {
            true
        }
        assertTrue("garbage input must not parse", thrown)
    }
}
