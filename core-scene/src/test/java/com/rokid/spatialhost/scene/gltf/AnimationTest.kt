package com.rokid.spatialhost.scene.gltf

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * glTF animation pipeline: parse (D5) → [Animator] sampling → GLB
 * round-trip. The fixture is an animation-only GLB (one node, one LINEAR
 * translation clip with 3 keys) built in-memory.
 */
class AnimationTest {

    /**
     * Buffer layout (all f32 LE):
     *  0: times      [0, 1, 2]        (12 B)
     * 12: values     (0,0,0),(1,2,0),(2,0,0)  (36 B)
     * 48: rot times  [0, 1]           (8 B)
     * 56: rot values q0=(0,0,0,1), q1=(0,1,0,0) (32 B)
     */
    private fun buildAnimatedGlb(): ByteArray {
        val bin = ByteBuffer.allocate(88).order(ByteOrder.LITTLE_ENDIAN)
        floatArrayOf(0f, 1f, 2f).forEach { bin.putFloat(it) }
        floatArrayOf(
            0f, 0f, 0f,
            1f, 2f, 0f,
            2f, 0f, 0f,
        ).forEach { bin.putFloat(it) }
        floatArrayOf(0f, 1f).forEach { bin.putFloat(it) }
        // STEP rotation: hold identity, then flip 180° about Y
        floatArrayOf(0f, 0f, 0f, 1f, 0f, 1f, 0f, 0f).forEach { bin.putFloat(it) }

        val json = """
        {"asset":{"version":"2.0"},
         "buffers":[{"byteLength":88}],
         "bufferViews":[
           {"buffer":0,"byteOffset":0,"byteLength":12},
           {"buffer":0,"byteOffset":12,"byteLength":36},
           {"buffer":0,"byteOffset":48,"byteLength":8},
           {"buffer":0,"byteOffset":56,"byteLength":32}],
         "accessors":[
           {"bufferView":0,"componentType":5126,"count":3,"type":"SCALAR","min":[0],"max":[2]},
           {"bufferView":1,"componentType":5126,"count":3,"type":"VEC3"},
           {"bufferView":2,"componentType":5126,"count":2,"type":"SCALAR","min":[0],"max":[1]},
           {"bufferView":3,"componentType":5126,"count":2,"type":"VEC4"}],
         "nodes":[{"name":"target","translation":[5,0,0]}],
         "scenes":[{"nodes":[0]}],"scene":0,
         "animations":[
           {"name":"Move",
            "channels":[{"sampler":0,"target":{"node":0,"path":"translation"}}],
            "samplers":[{"input":0,"output":1,"interpolation":"LINEAR"}]},
           {"name":"Spin",
            "channels":[{"sampler":0,"target":{"node":0,"path":"rotation"}}],
            "samplers":[{"input":2,"output":3,"interpolation":"STEP"}]}]}
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
    fun parsesAnimationClips() {
        val model = GltfParser.parse(buildAnimatedGlb(), "anim")
        assertEquals(2, model.animations.size)

        val move = model.animations[0]
        assertEquals("Move", move.name)
        assertEquals(1, move.channels.size)
        assertEquals(0, move.channels[0].node)
        assertEquals("translation", move.channels[0].path)
        assertEquals(1, move.samplers.size)
        assertArrayEquals(floatArrayOf(0f, 1f, 2f), move.samplers[0].times, 0f)
        assertEquals(9, move.samplers[0].values.size)
        assertEquals("LINEAR", move.samplers[0].interpolation)

        val spin = model.animations[1]
        assertEquals("Spin", spin.name)
        assertEquals("rotation", spin.channels[0].path)
        assertEquals("STEP", spin.samplers[0].interpolation)
        assertEquals(8, spin.samplers[0].values.size)

        // Base TRS survived for animation overrides
        val node = model.nodes[0]
        assertArrayEquals(floatArrayOf(5f, 0f, 0f), node.translation!!, 0f)
    }

    @Test
    fun linearSamplingAndClamp() {
        val times = floatArrayOf(0f, 1f, 2f)
        val values = floatArrayOf(0f, 0f, 0f, 1f, 2f, 0f, 2f, 0f, 0f)
        val sampler = GltfSampler(times, values, "LINEAR")

        // midpoint of segment 0 → lerp (0,0,0)→(1,2,0)
        assertArrayEquals(
            floatArrayOf(0.5f, 1f, 0f),
            Animator.sampleChannel(sampler, "translation", 0.5f)!!,
            1e-6f,
        )
        // exact key
        assertArrayEquals(
            floatArrayOf(1f, 2f, 0f),
            Animator.sampleChannel(sampler, "translation", 1f)!!,
            1e-6f,
        )
        // clamp before / after the range
        assertArrayEquals(
            floatArrayOf(0f, 0f, 0f),
            Animator.sampleChannel(sampler, "translation", -5f)!!,
            1e-6f,
        )
        assertArrayEquals(
            floatArrayOf(2f, 0f, 0f),
            Animator.sampleChannel(sampler, "translation", 99f)!!,
            1e-6f,
        )
    }

    @Test
    fun stepSamplingHoldsKey() {
        val sampler = GltfSampler(
            floatArrayOf(0f, 1f),
            floatArrayOf(0f, 1f, 0f, 1f, 0f, 0f),
            "STEP",
        )
        // just before key 1 → key 0's value
        assertArrayEquals(
            floatArrayOf(0f, 1f, 0f),
            Animator.sampleChannel(sampler, "translation", 0.99f)!!,
            1e-6f,
        )
        // at/after key 1 → key 1's value
        assertArrayEquals(
            floatArrayOf(1f, 0f, 0f),
            Animator.sampleChannel(sampler, "translation", 1.5f)!!,
            1e-6f,
        )
    }

    @Test
    fun rotationLerpIsNormalized() {
        // 90° apart: nlerp at midpoint must stay unit-length
        val sampler = GltfSampler(
            floatArrayOf(0f, 1f),
            floatArrayOf(0f, 0f, 0f, 1f, 0f, kotlin.math.sin(Math.PI / 4).toFloat(), 0f, kotlin.math.cos(Math.PI / 4).toFloat()),
            "LINEAR",
        )
        val q = Animator.sampleChannel(sampler, "rotation", 0.5f)!!
        val len = kotlin.math.sqrt(q.sumOf { (it * it).toDouble() }).toFloat()
        assertEquals(1f, len, 1e-5f)
    }

    @Test
    fun animatorLoopsAndComposesBaseTrs() {
        val model = GltfParser.parse(buildAnimatedGlb(), "anim")
        val anim = Animator(model)
        assertEquals(2, anim.clipCount)
        assertEquals("Move", anim.clipName())
        assertEquals(2f, anim.duration(), 1e-6f)

        anim.advance(0.5f)
        assertEquals(0.5f, anim.time, 1e-6f)
        val m = anim.sample()
        assertTrue(0 in m)
        val mat = m[0]!!
        // glTF semantics: the channel REPLACES the translation component —
        // column-major translation at elements 12..14 = sampled value.
        assertEquals(0.5f, mat[12], 1e-5f)
        assertEquals(1f, mat[13], 1e-5f)
        assertEquals(0f, mat[14], 1e-5f)

        // wrap: 0.5 + 2.0 (one full duration) → 0.5 (looping)
        anim.advance(2.0f)
        assertEquals(0.5f, anim.time, 1e-5f)

        // clip switching
        anim.clipIndex = 1
        assertEquals("Spin", anim.clipName())
        assertEquals(1f, anim.duration(), 1e-6f)
    }

    @Test
    fun animationsSurviveGlbRoundTrip() {
        val original = GltfParser.parse(buildAnimatedGlb(), "anim")
        val back = GltfParser.parse(GltfExporter.toGlb(original), "back")
        assertEquals(2, back.animations.size)

        val a = original.animations[0]
        val b = back.animations[0]
        assertEquals(a.name, b.name)
        assertEquals(a.channels.size, b.channels.size)
        assertEquals(a.channels[0].node, b.channels[0].node)
        assertEquals(a.channels[0].path, b.channels[0].path)
        assertArrayEquals(a.samplers[0].times, b.samplers[0].times, 0f)
        assertArrayEquals(a.samplers[0].values, b.samplers[0].values, 0f)
        assertEquals(a.samplers[0].interpolation, b.samplers[0].interpolation)

        // TRS nodes stay TRS after export (matrix-defined would lose base)
        assertNotNull(back.nodes[0].translation)
        assertArrayEquals(floatArrayOf(5f, 0f, 0f), back.nodes[0].translation!!, 0f)

        // The reparsed model drives an animator identically
        val a1 = Animator(original).apply { advance(0.5f) }
        val a2 = Animator(back).apply { advance(0.5f) }
        assertArrayEquals(a1.sample()[0]!!, a2.sample()[0]!!, 0f)
    }
}
