package com.rokid.spatialhost.scene.gltf

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Skinning pipeline (D6): JOINTS_0/WEIGHTS_0 + skins parse → GLB
 * round-trip → bone-matrix math ([Mat4] mirrors gltfio's formula).
 *
 * Fixture: one skinned triangle (3 verts, all bound to joint 0 with weight
 * 1), a mesh node referencing skin 0, and one joint node at (0,1,0).
 *
 * Buffer layout (all LE):
 *   0: positions  3×VEC3 f32   (36 B)
 *  36: indices    3×u16        ( 6 B) +2 pad
 *  44: JOINTS_0   3×VEC4 u16   (24 B)
 *  68: WEIGHTS_0  3×VEC4 f32   (48 B)
 * 116: IBM        1×MAT4 f32   (64 B) → total 180
 */
class SkinTest {

    private fun buildSkinnedGlb(): ByteArray {
        val bin = ByteBuffer.allocate(180).order(ByteOrder.LITTLE_ENDIAN)
        // positions: triangle in the z=0 plane
        floatArrayOf(
            0f, 0f, 0f,
            1f, 0f, 0f,
            0f, 1f, 0f,
        ).forEach { bin.putFloat(it) }
        // indices (offset 36): 6 B + 2 B pad → JOINTS at 44
        bin.putShort(0); bin.putShort(1); bin.putShort(2)
        bin.putShort(0) // pad → 44
        // JOINTS_0 (offset 44): every vertex → joint 0 (12 × u16 = 24 B)
        repeat(12) { bin.putShort(0) }
        // WEIGHTS_0 (offset 68): (1,0,0,0) per vertex
        repeat(3) { floatArrayOf(1f, 0f, 0f, 0f).forEach { bin.putFloat(it) } }
        // IBM (offset 116): identity
        for (r in 0..3) for (c in 0..3) bin.putFloat(if (r == c) 1f else 0f)

        val json = """
        {"asset":{"version":"2.0"},
         "buffers":[{"byteLength":180}],
         "bufferViews":[
           {"buffer":0,"byteOffset":0,"byteLength":36},
           {"buffer":0,"byteOffset":36,"byteLength":6},
           {"buffer":0,"byteOffset":44,"byteLength":24},
           {"buffer":0,"byteOffset":68,"byteLength":48},
           {"buffer":0,"byteOffset":116,"byteLength":64}],
         "accessors":[
           {"bufferView":0,"componentType":5126,"count":3,"type":"VEC3","min":[0,0,0],"max":[1,1,0]},
           {"bufferView":1,"componentType":5123,"count":3,"type":"SCALAR"},
           {"bufferView":2,"componentType":5123,"count":3,"type":"VEC4"},
           {"bufferView":3,"componentType":5126,"count":3,"type":"VEC4"},
           {"bufferView":4,"componentType":5126,"count":1,"type":"MAT4"}],
         "meshes":[{"primitives":[
           {"attributes":{"POSITION":0,"JOINTS_0":2,"WEIGHTS_0":3},"indices":1}]}],
         "nodes":[
           {"name":"meshNode","mesh":0,"skin":0},
           {"name":"joint","translation":[0,1,0]}],
         "skins":[{"name":"skin0","joints":[1],"inverseBindMatrices":4}],
         "scenes":[{"nodes":[0,1]}],"scene":0}
        """.trimIndent()

        val jsonBytes = json.toByteArray(Charsets.UTF_8)
        val jsonPad = (4 - jsonBytes.size % 4) % 4
        val binBytes = bin.array()
        val out = ByteBuffer
            .allocate(12 + 8 + jsonBytes.size + jsonPad + 8 + binBytes.size)
            .order(ByteOrder.LITTLE_ENDIAN)
        out.putInt(0x46546C67)
        out.putInt(2)
        out.putInt(out.capacity())
        out.putInt(jsonBytes.size + jsonPad)
        out.putInt(0x4E4F534A)
        out.put(jsonBytes)
        repeat(jsonPad) { out.put(0x20) }
        out.putInt(binBytes.size)
        out.putInt(0x004E4942)
        out.put(binBytes)
        return out.array()
    }

    @Test
    fun parsesSkinAttributes() {
        val model = GltfParser.parse(buildSkinnedGlb(), "skin")

        // skin: 1 joint (node 1), identity IBM
        assertEquals(1, model.skins.size)
        val skin = model.skins[0]
        assertEquals("skin0", skin.name)
        assertEquals(1, skin.jointCount)
        assertArrayEquals(intArrayOf(1), skin.joints)
        assertEquals(16, skin.inverseBindMatrices.size)
        assertEquals(1f, skin.inverseBindMatrices[0], 0f)
        assertEquals(0f, skin.inverseBindMatrices[1], 0f)

        // mesh node references the skin
        assertEquals(0, model.nodes[0].skin)

        // per-vertex attributes: 3 verts × 4 components
        val prim = model.primitives.getValue(0).single()
        assertNotNull(prim.joints)
        assertNotNull(prim.weights)
        assertArrayEquals(IntArray(12), prim.joints!!)
        val w = prim.weights!!
        assertEquals(12, w.size)
        assertEquals(1f, w[0], 0f)
        assertEquals(0f, w[1], 0f)
    }

    @Test
    fun skinSurvivesGlbRoundTrip() {
        val original = GltfParser.parse(buildSkinnedGlb(), "skin")
        val back = GltfParser.parse(GltfExporter.toGlb(original), "back")

        assertEquals(1, back.skins.size)
        assertArrayEquals(original.skins[0].joints, back.skins[0].joints)
        assertArrayEquals(
            original.skins[0].inverseBindMatrices,
            back.skins[0].inverseBindMatrices,
            0f,
        )
        assertEquals(original.nodes[0].skin, back.nodes[0].skin)

        val a = original.primitives.getValue(0).single()
        val b = back.primitives.getValue(0).single()
        assertArrayEquals(a.joints!!, b.joints!!)
        assertArrayEquals(a.weights!!, b.weights!!, 0f)
    }

    @Test
    fun inverseRoundTripsTrsMatrices() {
        // A realistic node matrix: translate + rotate 90° about Z + scale.
        val t = GltfParser.trsMatrix(
            floatArrayOf(1f, 2f, 3f),
            floatArrayOf(0f, 0f, kotlin.math.sin(Math.PI / 4).toFloat(), kotlin.math.cos(Math.PI / 4).toFloat()),
            floatArrayOf(2f, 3f, 4f),
        )
        val inv = Mat4.inverse(t)
        assertNotNull(inv)
        val prod = Mat4.mul(t, inv!!)
        val id = Mat4.identity()
        for (i in 0..15) {
            assertEquals("element $i", id[i], prod[i], 1e-4f)
        }
    }

    @Test
    fun singularMatrixReturnsNull() {
        val zero = FloatArray(16)
        assertEquals(null, Mat4.inverse(zero))
    }

    @Test
    fun boneMatrixMatchesGltfioFormula() {
        // Mesh node at (0,0,-2), joint world at (0,1,-2) (i.e. 1 m above the
        // mesh), IBM = inverse of the joint's bind pose (0,-1,0 translation
        // inverted → T(0,-1,0) is already the inverse of T(0,1,0)).
        val meshWorld = GltfParser.trsMatrix(
            floatArrayOf(0f, 0f, -2f), floatArrayOf(0f, 0f, 0f, 1f), floatArrayOf(1f, 1f, 1f),
        )
        val jointWorld = GltfParser.trsMatrix(
            floatArrayOf(0f, 1f, -2f), floatArrayOf(0f, 0f, 0f, 1f), floatArrayOf(1f, 1f, 1f),
        )
        val ibm = GltfParser.trsMatrix(
            floatArrayOf(0f, -1f, 0f), floatArrayOf(0f, 0f, 0f, 1f), floatArrayOf(1f, 1f, 1f),
        )
        val invMesh = Mat4.inverse(meshWorld)!!
        val bone = Mat4.boneMatrix(invMesh, jointWorld, ibm)

        // Expected: T(0,0,2)·T(0,1,-2)·T(0,-1,0) = T(0,0,0) = identity.
        val id = Mat4.identity()
        for (i in 0..15) {
            assertEquals("element $i", id[i], bone[i], 1e-5f)
        }
    }
}
