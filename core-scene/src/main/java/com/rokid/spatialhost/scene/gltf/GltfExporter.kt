package com.rokid.spatialhost.scene.gltf

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * D4: glTF/GLB + OBJ export (round-trip with [GltfParser]).
 *
 * The exporter re-emits what the parser consumed: node hierarchy (as
 * `matrix`), meshes with POSITION/NORMAL/UV0 + indices, PBR materials and
 * the ORIGINAL encoded images (copied verbatim into the BIN chunk, so
 * textures survive the round-trip without re-encoding).
 *
 * OBJ is a lossy side format: materials are dropped and [toObj] bakes the
 * given placement matrix into the vertices (OBJ has no transforms).
 */
object GltfExporter {

    private const val TARGET_ARRAY_BUFFER = 34962
    private const val TARGET_ELEMENT_ARRAY_BUFFER = 34963

    /** Serialize [model] as a binary GLB (2.0). */
    fun toGlb(model: GltfModel): ByteArray {
        val bin = ByteArrayOutputStream(4 shl 20)

        fun align4() {
            while (bin.size() % 4 != 0) bin.write(0)
        }

        fun putFloats(a: FloatArray): Pair<Int, Int> {
            align4()
            val off = bin.size()
            val bb = ByteBuffer.allocate(a.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            for (v in a) bb.putFloat(v)
            bin.write(bb.array())
            return off to a.size * 4
        }

        fun putIndices(idx: IntArray, u16: Boolean): Pair<Int, Int> {
            align4()
            val off = bin.size()
            if (u16) {
                val bb = ByteBuffer.allocate(idx.size * 2).order(ByteOrder.LITTLE_ENDIAN)
                for (v in idx) bb.putShort(v.toShort())
                bin.write(bb.array())
                return off to idx.size * 2
            }
            val bb = ByteBuffer.allocate(idx.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            for (v in idx) bb.putInt(v)
            bin.write(bb.array())
            return off to idx.size * 4
        }

        fun putBytes(data: ByteArray): Pair<Int, Int> {
            align4()
            val off = bin.size()
            bin.write(data)
            return off to data.size
        }

        val bufferViews = JSONArray_()
        val accessors = JSONArray_()
        val meshesJson = JSONArray_()

        // --- geometry: meshes in ascending original order -------------
        val meshOrder = model.primitives.keys.sorted()
        val meshRemap = HashMap<Int, Int>(meshOrder.size)
        for (oldIdx in meshOrder) {
            val newMeshIdx = meshesJson.length()
            meshRemap[oldIdx] = newMeshIdx
            val primsJson = JSONArray_()
            for (prim in model.primitives[oldIdx] ?: emptyList()) {
                val attrs = JSONObject_()
                val (po, pl) = putFloats(prim.positions)
                val pAcc = accessors.length()
                accessors.put(
                    JSONObject_()
                        .put("bufferView", bufferViews.length())
                        .put("componentType", 5126)
                        .put("count", prim.positions.size / 3)
                        .put("type", "VEC3")
                        .put("min", floatList(prim.positions, 0))
                        .put("max", floatList(prim.positions, 1)),
                )
                bufferViews.put(JSONObject_().put("buffer", 0).put("byteOffset", po).put("byteLength", pl).put("target", TARGET_ARRAY_BUFFER))
                attrs.put("POSITION", pAcc)

                prim.normals?.let { nrm ->
                    val (no, nl) = putFloats(nrm)
                    val nAcc = accessors.length()
                    accessors.put(
                        JSONObject_()
                            .put("bufferView", bufferViews.length())
                            .put("componentType", 5126)
                            .put("count", nrm.size / 3)
                            .put("type", "VEC3"),
                    )
                    bufferViews.put(JSONObject_().put("buffer", 0).put("byteOffset", no).put("byteLength", nl).put("target", TARGET_ARRAY_BUFFER))
                    attrs.put("NORMAL", nAcc)
                }

                prim.uvs?.let { uv ->
                    val (uo, ul) = putFloats(uv)
                    val uAcc = accessors.length()
                    accessors.put(
                        JSONObject_()
                            .put("bufferView", bufferViews.length())
                            .put("componentType", 5126)
                            .put("count", uv.size / 2)
                            .put("type", "VEC2"),
                    )
                    bufferViews.put(JSONObject_().put("buffer", 0).put("byteOffset", uo).put("byteLength", ul).put("target", TARGET_ARRAY_BUFFER))
                    attrs.put("TEXCOORD_0", uAcc)
                }

                val maxIdx = prim.indices.maxOrNull() ?: 0
                val u16 = maxIdx <= 65535
                val (io, il) = putIndices(prim.indices, u16)
                val iAcc = accessors.length()
                accessors.put(
                    JSONObject_()
                        .put("bufferView", bufferViews.length())
                        .put("componentType", if (u16) 5123 else 5125)
                        .put("count", prim.indices.size)
                        .put("type", "SCALAR"),
                )
                bufferViews.put(JSONObject_().put("buffer", 0).put("byteOffset", io).put("byteLength", il).put("target", TARGET_ELEMENT_ARRAY_BUFFER))

                val primJson = JSONObject_().put("attributes", attrs).put("indices", iAcc)
                prim.material?.let { if (it in model.materials.indices) primJson.put("material", it) }
                primsJson.put(primJson)
            }
            meshesJson.put(JSONObject_().put("primitives", primsJson))
        }

        // --- images (verbatim bytes) + samplers + textures ------------
        val imagesJson = JSONArray_()
        val imageViews = ArrayList<Pair<Int, Int>>(model.textures.size)
        for (tex in model.textures) {
            val (o, l) = putBytes(tex.image)
            imageViews.add(o to l)
            imagesJson.put(
                JSONObject_()
                    .put("bufferView", bufferViews.length())
                    .put("mimeType", sniffMime(tex.image)),
            )
            bufferViews.put(JSONObject_().put("buffer", 0).put("byteOffset", o).put("byteLength", l))
        }

        val samplerRemap = HashMap<String, Int>()
        val samplersJson = JSONArray_()
        val texturesJson = JSONArray_()
        for (tex in model.textures) {
            val key = "${tex.wrapS}:${tex.wrapT}"
            val sIdx = samplerRemap.getOrPut(key) {
                val i = samplersJson.length()
                samplersJson.put(JSONObject_().put("wrapS", tex.wrapS).put("wrapT", tex.wrapT))
                i
            }
            // one image per texture in our model representation → source
            // index equals the texture index
            texturesJson.put(
                JSONObject_().put("source", texturesJson.length()).put("sampler", sIdx),
            )
        }

        // --- materials ------------------------------------------------
        val materialsJson = JSONArray_()
        for (mat in model.materials) {
            val pbr = JSONObject_()
                .put("baseColorFactor", floatList(mat.baseColorFactor, -1))
                .put("metallicFactor", mat.metallicFactor.toDouble())
                .put("roughnessFactor", mat.roughnessFactor.toDouble())
            mat.baseColorTexture?.let { if (it in model.textures.indices) pbr.put("baseColorTexture", JSONObject_().put("index", it)) }
            mat.metallicRoughnessTexture?.let { if (it in model.textures.indices) pbr.put("metallicRoughnessTexture", JSONObject_().put("index", it)) }
            val m = JSONObject_().put("name", mat.name).put("pbrMetallicRoughness", pbr)
            mat.normalTexture?.let { if (it in model.textures.indices) m.put("normalTexture", JSONObject_().put("index", it)) }
            m.put("emissiveFactor", floatList(mat.emissiveFactor, -1))
            mat.emissiveTexture?.let {
                if (it in model.textures.indices) m.put("emissiveTexture", JSONObject_().put("index", it))
            }
            mat.occlusionTexture?.let {
                if (it in model.textures.indices) {
                    m.put(
                        "occlusionTexture",
                        JSONObject_().put("index", it).put("strength", mat.occlusionStrength.toDouble()),
                    )
                }
            }
            materialsJson.put(m)
        }

        // --- nodes (TRS when known — animated nodes must not carry a
        // matrix per glTF; matrix verbatim otherwise) ---------------
        val nodesJson = JSONArray_()
        for (node in model.nodes) {
            val n = JSONObject_()
                .put("name", node.name)
            if (node.translation != null && node.rotation != null && node.scale != null) {
                n.put("translation", floatList(node.translation, -1))
                    .put("rotation", floatList(node.rotation, -1))
                    .put("scale", floatList(node.scale, -1))
            } else {
                n.put("matrix", floatList(node.matrix, -1))
            }
            if (node.children.isNotEmpty()) {
                n.put("children", JSONArray_().also { arr -> node.children.forEach { arr.put(it) } })
            }
            node.mesh?.let { meshRemap[it]?.let { remapped -> n.put("mesh", remapped) } }
            nodesJson.put(n)
        }

        // --- animations (rigid channels; verbatim times/values) -----
        val animationsJson = JSONArray_()
        for (anim in model.animations) {
            if (anim.channels.isEmpty() || anim.samplers.isEmpty()) continue
            val samplersOut = JSONArray_()
            val samplerRemap = HashMap<Int, Int>() // original index → emitted
            for ((origIdx, sm) in anim.samplers.withIndex()) {
                if (sm.times.isEmpty()) continue
                // output components per key (CUBICSPLINE stores 3 entries/key)
                val perKey = if (sm.interpolation == "CUBICSPLINE") 3 else 1
                val elems = sm.times.size * perKey
                if (elems == 0 || sm.values.size % elems != 0) continue
                val comps = sm.values.size / elems
                if (comps != 3 && comps != 4) continue
                val (to, tl) = putFloats(sm.times)
                val tAcc = accessors.length()
                accessors.put(
                    JSONObject_()
                        .put("bufferView", bufferViews.length())
                        .put("componentType", 5126)
                        .put("count", sm.times.size)
                        .put("type", "SCALAR")
                        .put("min", floatList(floatArrayOf(sm.times.min()), -1))
                        .put("max", floatList(floatArrayOf(sm.times.max()), -1)),
                )
                bufferViews.put(JSONObject_().put("buffer", 0).put("byteOffset", to).put("byteLength", tl))
                val (vo, vl) = putFloats(sm.values)
                val vAcc = accessors.length()
                accessors.put(
                    JSONObject_()
                        .put("bufferView", bufferViews.length())
                        .put("componentType", 5126)
                        .put("count", sm.values.size / comps)
                        .put("type", if (comps == 4) "VEC4" else "VEC3"),
                )
                bufferViews.put(JSONObject_().put("buffer", 0).put("byteOffset", vo).put("byteLength", vl))
                samplersOut.put(
                    JSONObject_()
                        .put("input", tAcc)
                        .put("output", vAcc)
                        .put("interpolation", sm.interpolation),
                )
                samplerRemap[origIdx] = samplersOut.length() - 1
            }
            if (samplersOut.length() == 0) continue
            val channelsOut = JSONArray_()
            for (ch in anim.channels) {
                if (ch.node !in model.nodes.indices) continue
                val emitted = samplerRemap[ch.sampler] ?: continue
                channelsOut.put(
                    JSONObject_()
                        .put("sampler", emitted)
                        .put("target", JSONObject_().put("node", ch.node).put("path", ch.path)),
                )
            }
            if (channelsOut.length() == 0) continue
            animationsJson.put(
                JSONObject_()
                    .put("name", anim.name)
                    .put("samplers", samplersOut)
                    .put("channels", channelsOut),
            )
        }

        val rootJson = JSONObject_()
            .put("asset", JSONObject_().put("version", "2.0").put("generator", "rokid-spatial-host D4"))
            .put("buffers", JSONArray_().put(JSONObject_().put("byteLength", bin.size())))
            .put("bufferViews", bufferViews)
            .put("accessors", accessors)
            .put("meshes", meshesJson)
            .put("nodes", nodesJson)
            .put("scenes", JSONArray_().put(JSONObject_().put("nodes", JSONArray_().also { arr -> model.sceneRootNodes.forEach { arr.put(it) } })))
            .put("scene", 0)
        if (imagesJson.length() > 0) {
            rootJson.put("images", imagesJson)
            rootJson.put("samplers", samplersJson)
            rootJson.put("textures", texturesJson)
        }
        if (materialsJson.length() > 0) rootJson.put("materials", materialsJson)
        if (animationsJson.length() > 0) rootJson.put("animations", animationsJson)

        // --- GLB container --------------------------------------------
        val jsonBytes = rootJson.toString().toByteArray(Charsets.UTF_8)
        val jsonPad = (4 - jsonBytes.size % 4) % 4
        val binBytes = bin.toByteArray()
        val binPad = (4 - binBytes.size % 4) % 4
        val total = 12 + 8 + jsonBytes.size + jsonPad + 8 + binBytes.size + binPad
        val out = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        out.putInt(0x46546C67) // 'glTF'
        out.putInt(2)
        out.putInt(total)
        out.putInt(jsonBytes.size + jsonPad)
        out.putInt(0x4E4F534A) // 'JSON'
        out.put(jsonBytes)
        repeat(jsonPad) { out.put(0x20) }
        out.putInt(binBytes.size + binPad)
        out.putInt(0x004E4942) // 'BIN\0'
        out.put(binBytes)
        repeat(binPad) { out.put(0) }
        return out.array()
    }

    /**
     * Wavefront OBJ with [matrix] baked into the vertices (column-major,
     * may be null for model space). Materials are not exported.
     */
    fun toObj(model: GltfModel, matrix: FloatArray?, name: String): String {
        val sb = StringBuilder(1 shl 20)
        sb.append("# rokid-spatial-host D4 export\n# model: ").append(name).append('\n')
        sb.append("o ").append(name.replace(Regex("\\s+"), "_")).append('\n')
        var vBase = 1
        var vnBase = 1
        var vtBase = 1
        for (meshIdx in model.primitives.keys.sorted()) {
            for (prim in model.primitives[meshIdx] ?: emptyList()) {
                // vertices
                val p = prim.positions
                var i = 0
                while (i + 2 < p.size) {
                    if (matrix != null) {
                        val w = GltfParser.matTransform(matrix, p[i], p[i + 1], p[i + 2])
                        sb.append("v ").append(f(w[0])).append(' ').append(f(w[1])).append(' ').append(f(w[2])).append('\n')
                    } else {
                        sb.append("v ").append(f(p[i])).append(' ').append(f(p[i + 1])).append(' ').append(f(p[i + 2])).append('\n')
                    }
                    i += 3
                }
                // normals (rotation part; placement scales are uniform)
                val nrm = prim.normals
                if (nrm != null) {
                    var j = 0
                    while (j + 2 < nrm.size) {
                        var nx = nrm[j]
                        var ny = nrm[j + 1]
                        var nz = nrm[j + 2]
                        if (matrix != null) {
                            val rx = matrix[0] * nx + matrix[4] * ny + matrix[8] * nz
                            val ry = matrix[1] * nx + matrix[5] * ny + matrix[9] * nz
                            val rz = matrix[2] * nx + matrix[6] * ny + matrix[10] * nz
                            val len = kotlin.math.sqrt(rx * rx + ry * ry + rz * rz)
                            if (len > 1e-12f) { nx = rx / len; ny = ry / len; nz = rz / len }
                        }
                        sb.append("vn ").append(f(nx)).append(' ').append(f(ny)).append(' ').append(f(nz)).append('\n')
                        j += 3
                    }
                }
                val uv = prim.uvs
                if (uv != null) {
                    var k = 0
                    while (k + 1 < uv.size) {
                        sb.append("vt ").append(f(uv[k])).append(' ').append(f(uv[k + 1])).append('\n')
                        k += 2
                    }
                }
                // faces (OBJ is 1-based; index into each stream separately)
                val idx = prim.indices
                var t = 0
                while (t + 2 < idx.size) {
                    val a = idx[t] + vBase
                    val b = idx[t + 1] + vBase
                    val c = idx[t + 2] + vBase
                    sb.append("f ")
                    for (v in intArrayOf(a, b, c)) {
                        sb.append(v)
                        if (uv != null) sb.append('/').append(v - vBase + vtBase)
                        if (nrm != null) {
                            sb.append(if (uv != null) "/" else "//")
                            sb.append(v - vBase + vnBase)
                        }
                        sb.append(' ')
                    }
                    sb.append('\n')
                    t += 3
                }
                val vCount = p.size / 3
                vBase += vCount
                if (nrm != null) vnBase += nrm.size / 3
                if (uv != null) vtBase += uv.size / 2
            }
        }
        return sb.toString()
    }

    // ------------------------------------------------------------------

    private fun f(v: Float): String = "%.6f".format(java.util.Locale.US, v)

    /** min/max of a packed xyz float array (for POSITION accessors). */
    private fun floatList(a: FloatArray, mode: Int): JSONArray_ {
        val out = JSONArray_()
        if (mode < 0) { // full vector (matrix / factor)
            for (v in a) out.put(v.toDouble())
            return out
        }
        val step = 3
        for (axis in 0 until step) {
            var best = if (mode == 0) Float.MAX_VALUE else -Float.MAX_VALUE
            var i = axis
            while (i < a.size) {
                best = if (mode == 0) minOf(best, a[i]) else maxOf(best, a[i])
                i += step
            }
            out.put(best.toDouble())
        }
        return out
    }

    private fun sniffMime(data: ByteArray): String = when {
        data.size > 8 && data[0] == 0x89.toByte() && data[1] == 0x50.toByte() -> "image/png"
        data.size > 3 && data[0] == 0xFF.toByte() && data[1] == 0xD8.toByte() -> "image/jpeg"
        data.size > 12 && data[0] == 'R'.code.toByte() && data[8] == 'W'.code.toByte() -> "image/webp"
        else -> "image/png"
    }
}

// Thin aliases so the writer reads compactly (top-level: typealiases may
// not live inside an object).
private typealias JSONObject_ = org.json.JSONObject
private typealias JSONArray_ = org.json.JSONArray
