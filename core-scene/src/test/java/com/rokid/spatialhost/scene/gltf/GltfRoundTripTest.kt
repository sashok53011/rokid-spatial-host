package com.rokid.spatialhost.scene.gltf

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Round-trip tests for [GltfParser] <-> [GltfExporter] — the asset-pipeline
 * core that CI guards. Fixtures are built in-memory (plus an optional
 * DamagedHelmet check when the app-module asset is on disk).
 */
class GltfRoundTripTest {

    /** Minimal GLB: one textured triangle (POSITION/NORMAL/UV0 + material). */
    private fun buildTriangleGlb(): ByteArray {
        val bin = ByteArrayOutputStream()

        fun putF(values: FloatArray): Pair<Int, Int> {
            while (bin.size() % 4 != 0) bin.write(0)
            val off = bin.size()
            val bb = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            values.forEach { bb.putFloat(it) }
            bin.write(bb.array())
            return off to values.size * 4
        }

        val (posO, posL) = putF(floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f))
        val (nrmO, nrmL) = putF(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f))
        val (uvO, uvL) = putF(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f))
        while (bin.size() % 4 != 0) bin.write(0)
        val idxO = bin.size()
        val ib = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN)
        ib.putShort(0)
        ib.putShort(1)
        ib.putShort(2)
        bin.write(ib.array())
        while (bin.size() % 4 != 0) bin.write(0)
        val imgO = bin.size()
        // PNG magic + junk is enough (parser/exporter never decode images)
        val img = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2, 3, 4)
        bin.write(img)

        val json = """
        {"asset":{"version":"2.0"},
         "buffers":[{"byteLength":${bin.size()}}],
         "bufferViews":[
           {"buffer":0,"byteOffset":$posO,"byteLength":$posL},
           {"buffer":0,"byteOffset":$nrmO,"byteLength":$nrmL},
           {"buffer":0,"byteOffset":$uvO,"byteLength":$uvL},
           {"buffer":0,"byteOffset":$idxO,"byteLength":6},
           {"buffer":0,"byteOffset":$imgO,"byteLength":${img.size}}],
         "accessors":[
           {"bufferView":0,"componentType":5126,"count":3,"type":"VEC3","min":[0,0,0],"max":[1,1,0]},
           {"bufferView":1,"componentType":5126,"count":3,"type":"VEC3"},
           {"bufferView":2,"componentType":5126,"count":3,"type":"VEC2"},
           {"bufferView":3,"componentType":5123,"count":3,"type":"SCALAR"}],
         "meshes":[{"primitives":[{"attributes":{"POSITION":0,"NORMAL":1,"TEXCOORD_0":2},"indices":3,"material":0}]}],
         "nodes":[{"name":"n0","mesh":0}],
         "scenes":[{"nodes":[0]}],"scene":0,
         "materials":[{"name":"mat","pbrMetallicRoughness":{"baseColorFactor":[1,0.5,0.25,1],"baseColorTexture":{"index":0},"metallicFactor":0.2,"roughnessFactor":0.7},"emissiveFactor":[0.1,0.2,0.3]}],
         "images":[{"bufferView":4,"mimeType":"image/png"}],
         "samplers":[{"wrapS":33071,"wrapT":10497}],
         "textures":[{"source":0,"sampler":0}]}
        """.trimIndent()

        val jsonBytes = json.toByteArray(Charsets.UTF_8)
        val jsonPad = (4 - jsonBytes.size % 4) % 4
        val binBytes = bin.toByteArray()
        val out = ByteBuffer
            .allocate(12 + 8 + jsonBytes.size + jsonPad + 8 + binBytes.size)
            .order(ByteOrder.LITTLE_ENDIAN)
        out.putInt(0x46546C67) // 'glTF'
        out.putInt(2)
        out.putInt(out.capacity())
        out.putInt(jsonBytes.size + jsonPad)
        out.putInt(0x4E4F534A) // 'JSON'
        out.put(jsonBytes)
        repeat(jsonPad) { out.put(0x20) }
        out.putInt(binBytes.size)
        out.putInt(0x004E4942) // 'BIN\0'
        out.put(binBytes)
        return out.array()
    }

    @Test
    fun parseMinimalGlb() {
        val model = GltfParser.parse(buildTriangleGlb(), "tri")
        assertEquals(1, model.nodes.size)
        assertEquals(1, model.primitiveCount)
        assertEquals(1, model.materials.size)
        assertEquals(1, model.textures.size)
        assertEquals(0, model.materials[0].baseColorTexture)
        assertEquals(0.2f, model.materials[0].metallicFactor, 1e-6f)
        assertEquals(0.7f, model.materials[0].roughnessFactor, 1e-6f)
        assertArrayEquals(
            floatArrayOf(0.1f, 0.2f, 0.3f),
            model.materials[0].emissiveFactor,
            1e-6f,
        )
        assertEquals(33071, model.textures[0].wrapS) // CLAMP_TO_EDGE survives
        assertEquals(10497, model.textures[0].wrapT) // REPEAT
        assertArrayEquals(
            floatArrayOf(0f, 0f, 0f),
            floatArrayOf(model.boundsMin[0], model.boundsMin[1], model.boundsMin[2]),
            1e-6f,
        )
        assertArrayEquals(
            floatArrayOf(1f, 1f, 0f),
            floatArrayOf(model.boundsMax[0], model.boundsMax[1], model.boundsMax[2]),
            1e-6f,
        )
    }

    @Test
    fun exportReparseRoundTrip() {
        val original = GltfParser.parse(buildTriangleGlb(), "tri")
        val back = GltfParser.parse(GltfExporter.toGlb(original), "back")
        assertEquals(original.primitiveCount, back.primitiveCount)
        assertEquals(original.nodes.size, back.nodes.size)
        assertEquals(original.materials.size, back.materials.size)
        assertEquals(original.textures.size, back.textures.size)

        val a = original.primitives.getValue(0).single()
        val b = back.primitives.getValue(0).single()
        assertArrayEquals(a.positions, b.positions, 0f) // float32 bit-exact
        assertArrayEquals(a.indices, b.indices)
        assertArrayEquals(a.uvs!!, b.uvs!!, 0f)

        assertEquals(
            original.materials[0].metallicFactor, back.materials[0].metallicFactor, 1e-6f)
        assertEquals(
            original.materials[0].baseColorFactor[1], back.materials[0].baseColorFactor[1], 1e-6f)
        assertArrayEquals(
            original.materials[0].emissiveFactor, back.materials[0].emissiveFactor, 1e-6f)
        assertEquals(original.textures[0].wrapS, back.textures[0].wrapS)
        assertArrayEquals(original.textures[0].image, back.textures[0].image) // verbatim
    }

    @Test
    fun damagedHelmetRoundTripIfPresent() {
        val f = java.io.File("../app/src/main/assets/models/DamagedHelmet.glb")
        if (!f.exists()) return // lives in the app module; skipped elsewhere
        val original = GltfParser.parse(f.readBytes(), "helmet")
        assertEquals(1, original.primitiveCount)
        assertEquals(5, original.textures.size)

        val back = GltfParser.parse(GltfExporter.toGlb(original), "back")
        assertEquals(original.primitiveCount, back.primitiveCount)
        assertEquals(original.nodes.size, back.nodes.size)
        assertEquals(original.materials.size, back.materials.size)
        assertEquals(5, back.textures.size)
        assertArrayEquals(
            original.primitives.getValue(0).single().positions,
            back.primitives.getValue(0).single().positions,
            0f,
        )
        original.textures.forEachIndexed { i, tex ->
            assertArrayEquals("texture $i", tex.image, back.textures[i].image)
        }
    }
}
