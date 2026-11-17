package com.rokid.spatialhost.scene.gltf

import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * One renderable chunk of geometry (triangle list, indexed).
 * Coordinates in model space; [uvs] optional (equirect/2D UV0).
 * [material] is an index into [GltfModel.materials] (null = default).
 * [tangents] is the glTF TANGENT attribute (xyz + bitangent-sign w).
 */
data class GltfPrimitive(
    val positions: FloatArray, // xyz triplets
    val normals: FloatArray?,  // xyz triplets or null
    val uvs: FloatArray?,      // uv pairs or null
    val indices: IntArray,
    val material: Int? = null,
    val tangents: FloatArray? = null, // xyzw per vertex or null
    /** JOINTS_0:4 bone indices per vertex (into the skin's joints), or null. */
    val joints: IntArray? = null,
    /** WEIGHTS_0: 4 weights per vertex (sum ≈ 1), or null. */
    val weights: FloatArray? = null,
) {
    /** Local-space AABB of this primitive. */
    val boundsMin: FloatArray
    val boundsMax: FloatArray

    init {
        var minx = Float.MAX_VALUE; var miny = Float.MAX_VALUE; var minz = Float.MAX_VALUE
        var maxx = -Float.MAX_VALUE; var maxy = -Float.MAX_VALUE; var maxz = -Float.MAX_VALUE
        var i = 0
        while (i + 2 < positions.size) {
            val x = positions[i]; val y = positions[i + 1]; val z = positions[i + 2]
            if (x < minx) minx = x; if (y < miny) miny = y; if (z < minz) minz = z
            if (x > maxx) maxx = x; if (y > maxy) maxy = y; if (z > maxz) maxz = z
            i += 3
        }
        boundsMin = floatArrayOf(minx, miny, minz)
        boundsMax = floatArrayOf(maxx, maxy, maxz)
    }
}

/** Scene-graph node: local transform + optional mesh reference. */
data class GltfNode(
    val name: String,
    val matrix: FloatArray, // local transform, column-major (glTF convention)
    val children: IntArray,
    val mesh: Int?,
    /**
     * Base TRS (raw glTF values) when the node is defined by translation/
     * rotation/scale — animation targets override these components at
     * runtime. Null for matrix-defined nodes (animating those falls back
     * to identity for non-animated components).
     */
    val translation: FloatArray? = null,
    val rotation: FloatArray? = null, // quat x,y,z,w
    val scale: FloatArray? = null,
    /** Index into [GltfModel.skins] when this node instantiates a skinned mesh. */
    val skin: Int? = null,
)

/**
 * One animation channel: which node property a sampler drives.
 * path ∈ "translation" | "rotation" | "scale".
 */
data class GltfChannel(
    val node: Int,
    val path: String,
    val sampler: Int,
)

/**
 * Animation sampler: keyframe times (seconds) → interleaved values
 * (3 floats per key for translation/scale, 4 for rotation) with an
 * interpolation mode ("LINEAR" | "STEP" | "CUBICSPLINE").
 */
data class GltfSampler(
    val times: FloatArray,
    val values: FloatArray,
    val interpolation: String,
)

/** A named glTF animation (clips play in order, looping). */
data class GltfAnimation(
    val name: String,
    val channels: List<GltfChannel>,
    val samplers: List<GltfSampler>,
)

/**
 * glTF texture: encoded image bytes (PNG/JPEG, decoded by the renderer)
 * plus sampler wrap modes (gl codes: 10497 REPEAT, 33071 CLAMP_TO_EDGE,
 * 33648 MIRRORED_REPEAT).
 */
data class GltfTexture(
    val image: ByteArray,
    val wrapS: Int = 10497,
    val wrapT: Int = 10497,
)

/**
 * A glTF skin: joint nodes + inverse bind matrices (16 floats each,
 * column-major — the bind-pose inverse used by skinning).
 */
data class GltfSkin(
    /** Node indices driven by this skin, in JOINTS_n index order. */
    val joints: IntArray,
    /** Packed inverse bind matrices, 16 floats per joint. */
    val inverseBindMatrices: FloatArray,
    val name: String = "",
) {
    val jointCount: Int get() = joints.size
}

/**
 * glTF PBR metallic-roughness material (the subset the renderer consumes).
 * Texture indices point into [GltfModel.textures].
 */
data class GltfMaterial(
    val name: String,
    val baseColorFactor: FloatArray,      // rgba, default 1,1,1,1
    val baseColorTexture: Int?,            // → textures[i] or null
    val metallicFactor: Float,             // default 1
    val roughnessFactor: Float,            // default 1
    val metallicRoughnessTexture: Int?,
    val normalTexture: Int?,
    /** Emissive light rgb (glTF emissiveFactor, default 0,0,0). */
    val emissiveFactor: FloatArray = floatArrayOf(0f, 0f, 0f),
    val emissiveTexture: Int? = null,
    /** Ambient-occlusion map (affects indirect/IBL light only). */
    val occlusionTexture: Int? = null,
    val occlusionStrength: Float = 1f,      // default 1
)

/**
 * Parsed glTF2.0 model (MVP scope: static geometry).
 *
 * Supported: GLB containers, embedded `data:` buffers and external buffers
 * resolved via [basePathResolver]; POSITION/NORMAL/TEXCOORD_0 attributes;
 * SCALAR/VEC2/VEC3 accessors (f32/u16/u32, strided or packed); node
 * matrix | TRS transforms with hierarchy.
 *
 * Out of scope for now (Phase D2+): materials/textures/samplers, skins,
 * morph targets, animations — every primitive renders with the app's lit
 * material (textures land in D2).
 */
data class GltfModel(
    /** mesh index → primitives */
    val primitives: Map<Int, List<GltfPrimitive>>,
    val nodes: List<GltfNode>,
    val sceneRootNodes: IntArray,
    /** Model-space bounds after node hierarchy transforms (before app placement). */
    val boundsMin: FloatArray,
    val boundsMax: FloatArray,
    val name: String,
    /** PBR materials (index referenced by primitives). */
    val materials: List<GltfMaterial> = emptyList(),
    /** Textures referenced by materials (decoded at load time). */
    val textures: List<GltfTexture> = emptyList(),
    /** Named animation clips (empty for static models). */
    val animations: List<GltfAnimation> = emptyList(),
    /** Skins (joint sets + inverse bind matrices); empty when static. */
    val skins: List<GltfSkin> = emptyList(),
) {
    /** Total primitive count (progress/logging). */
    val primitiveCount: Int get() = primitives.values.sumOf { it.size }
}

object GltfParser {

    private const val TAG = "GltfParser"

    /** Node paths a rigid channel may animate (weights → morph, skipped). */
    private val ANIMATED_PATHS = setOf("translation", "rotation", "scale")

    /**
     * @param bytes        .glb file bytes (or .gltf JSON bytes)
     * @param defaultName  label used when the asset has none
     * @param basePathResolver resolves relative external .bin uris (file access)
     */
    fun parse(
        bytes: ByteArray,
        defaultName: String = "model",
        basePathResolver: ((String) -> ByteArray?)? = null,
    ): GltfModel {
        val (jsonStr, binChunk) = splitContainer(bytes)
        val root = JSONObject(jsonStr)

        val buffersJson = root.optJSONArray("buffers")
        val buffers = arrayOfNulls<ByteArray>(buffersJson?.length() ?: 0)
        buffersJson?.let { arr ->
            for (i in 0 until arr.length()) {
                val b = arr.getJSONObject(i)
                val uri = b.optString("uri")
                buffers[i] = when {
                    uri.isEmpty() -> binChunk
                        ?: throw IllegalArgumentException("buffer[$i] has no uri and no GLB BIN chunk")

                    uri.startsWith("data:") -> {
                        val idx = uri.indexOf("base64,")
                        require(idx > 0) { "non-base64 data uri unsupported" }
                        java.util.Base64.getDecoder().decode(uri.substring(idx + 7))
                    }

                    else -> basePathResolver?.invoke(uri)
                        ?: throw IllegalArgumentException("external buffer not resolvable: $uri")
                }
            }
        }

        val bufferViews = root.optJSONArray("bufferViews")?.let { arr ->
            Array(arr.length()) { i ->
                val v = arr.getJSONObject(i)
                BufferView(
                    buffer = v.getInt("buffer"),
                    byteOffset = v.optInt("byteOffset", 0),
                    byteStride = v.optInt("byteStride", 0),
                    byteLength = v.getInt("byteLength"),
                )
            }
        } ?: emptyArray()

        // --- images: bytes either from a data/external uri or a bufferView ---
        fun readUri(uri: String): ByteArray = when {
            uri.startsWith("data:") -> {
                val idx = uri.indexOf("base64,")
                require(idx > 0) { "non-base64 data uri unsupported" }
                java.util.Base64.getDecoder().decode(uri.substring(idx + 7))
            }

            else -> basePathResolver?.invoke(uri)
                ?: throw IllegalArgumentException("external image not resolvable: $uri")
        }

        fun sliceView(viewIdx: Int): ByteArray {
            val view = bufferViews[viewIdx]
            val buf = buffers[view.buffer]
                ?: throw IllegalArgumentException("buffer ${view.buffer} missing")
            return buf.copyOfRange(view.byteOffset, view.byteOffset + view.byteLength)
        }

        val imagesJson = root.optJSONArray("images")
        val images = Array(imagesJson?.length() ?: 0) { i ->
            val img = imagesJson!!.getJSONObject(i)
            when {
                img.has("uri") -> readUri(img.getString("uri"))
                img.has("bufferView") -> sliceView(img.getInt("bufferView"))
                else -> throw IllegalArgumentException("image[$i] has neither uri nor bufferView")
            }
        }

        // --- samplers (wrap modes) + textures (image + sampler) ---
        val samplersJson = root.optJSONArray("samplers")
        fun samplerWrap(samplerIdx: Int, key: String): Int {
            if (samplerIdx < 0 || samplersJson == null || samplerIdx >= samplersJson.length()) return 10497
            return samplersJson.getJSONObject(samplerIdx).optInt(key, 10497)
        }

        val texturesJson = root.optJSONArray("textures")
        val textures = List(texturesJson?.length() ?: 0) { i ->
            val t = texturesJson!!.getJSONObject(i)
            val src = t.getInt("source")
            val sampler = t.optInt("sampler", -1)
            GltfTexture(
                image = images.getOrElse(src) {
                    throw IllegalArgumentException("texture[$i] source $src out of range")
                },
                wrapS = samplerWrap(sampler, "wrapS"),
                wrapT = samplerWrap(sampler, "wrapT"),
            )
        }

        // --- materials (PBR metallic-roughness subset) ---
        val materialsJson = root.optJSONArray("materials")
        val materials = List(materialsJson?.length() ?: 0) { i ->
            val m = materialsJson!!.getJSONObject(i)
            val pbr = m.optJSONObject("pbrMetallicRoughness")
            val baseColor = pbr?.optJSONArray("baseColorFactor")
            val emissive = m.optJSONArray("emissiveFactor")
            GltfMaterial(
                name = m.optString("name", "material$i"),
                baseColorFactor = FloatArray(4) {
                    when {
                        baseColor != null && it < baseColor.length() ->
                            baseColor.getDouble(it).toFloat()

                        it == 3 -> 1f
                        else -> 1f
                    }
                },
                baseColorTexture = pbr?.optJSONObject("baseColorTexture")?.optInt("index"),
                metallicFactor = pbr?.optDouble("metallicFactor", 1.0)?.toFloat() ?: 1f,
                roughnessFactor = pbr?.optDouble("roughnessFactor", 1.0)?.toFloat() ?: 1f,
                metallicRoughnessTexture =
                    pbr?.optJSONObject("metallicRoughnessTexture")?.optInt("index"),
                normalTexture = m.optJSONObject("normalTexture")?.optInt("index"),
                emissiveFactor = FloatArray(3) {
                    if (emissive != null && it < emissive.length()) emissive.getDouble(it).toFloat()
                    else 0f
                },
                emissiveTexture = m.optJSONObject("emissiveTexture")?.optInt("index"),
                occlusionTexture = m.optJSONObject("occlusionTexture")?.optInt("index"),
                occlusionStrength = m.optJSONObject("occlusionTexture")
                    ?.optDouble("strength", 1.0)?.toFloat() ?: 1f,
            )
        }

        val accessors = root.optJSONArray("accessors")?.let { arr ->
            Array(arr.length()) { i ->
                val a = arr.getJSONObject(i)
                Accessor(
                    bufferView = a.optInt("bufferView", -1),
                    byteOffset = a.optInt("byteOffset", 0),
                    componentType = a.getInt("componentType"),
                    count = a.getInt("count"),
                    type = a.getString("type"),
                )
            }
        } ?: emptyArray()

        // --- meshes ---
        val primitives = mutableMapOf<Int, List<GltfPrimitive>>()
        root.optJSONArray("meshes")?.let { meshes ->
            for (m in 0 until meshes.length()) {
                val mesh = meshes.getJSONObject(m)
                val prims = mesh.optJSONArray("primitives") ?: continue
                val out = mutableListOf<GltfPrimitive>()
                for (p in 0 until prims.length()) {
                    val prim = prims.getJSONObject(p)
                    val attrs = prim.getJSONObject("attributes")
                    if (!attrs.has("POSITION")) continue
                    val pos = readFloats(accessors[attrs.getInt("POSITION")], buffers, bufferViews, 3)
                    val nrm = if (attrs.has("NORMAL"))
                        readFloats(accessors[attrs.getInt("NORMAL")], buffers, bufferViews, 3) else null
                    val uv = if (attrs.has("TEXCOORD_0"))
                        readFloats(accessors[attrs.getInt("TEXCOORD_0")], buffers, bufferViews, 2) else null
                    val tan = if (attrs.has("TANGENT"))
                        readFloats(accessors[attrs.getInt("TANGENT")], buffers, bufferViews, 4) else null
                    // Skin attributes: JOINTS_0 = 4 u8/u16 indices, WEIGHTS_0
                    // = 4 floats (or normalized u8/u16 — readFloats converts).
                    val jIdx = if (attrs.has("JOINTS_0")) attrs.getInt("JOINTS_0") else -1
                    val joints = if (jIdx >= 0 && jIdx < accessors.size) {
                        readFloats(accessors[jIdx], buffers, bufferViews, 4)
                            .map { it.toInt() }.toIntArray()
                    } else null
                    val wIdx = if (attrs.has("WEIGHTS_0")) attrs.getInt("WEIGHTS_0") else -1
                    val weights = if (wIdx >= 0 && wIdx < accessors.size) {
                        val wAcc = accessors[wIdx]
                        val raw = readFloats(wAcc, buffers, bufferViews, 4)
                        // Integer weights are normalized per spec (u8 → /255,
                        // u16 → /65535); floats pass through.
                        when (wAcc.componentType) {
                            5121 -> FloatArray(raw.size) { raw[it] / 255f }
                            5123 -> FloatArray(raw.size) { raw[it] / 65535f }
                            else -> raw
                        }
                    } else null
                    val idx = if (prim.has("indices"))
                        readIndices(accessors[prim.getInt("indices")], buffers, bufferViews)
                    else IntArray(pos.size / 3) { it }
                    if (pos.isEmpty() || idx.isEmpty()) continue
                    val matIdx = if (prim.has("material")) prim.getInt("material") else null
                    out.add(GltfPrimitive(pos, nrm, uv, idx, matIdx, tan, joints, weights))
                }
                if (out.isNotEmpty()) primitives[m] = out
            }
        }

        // --- nodes ---
        val nodesJson = root.optJSONArray("nodes")
        val nodes = nodesJson?.let { arr ->
            Array(arr.length()) { i ->
                val n = arr.getJSONObject(i)
                val children = n.optJSONArray("children")?.let { c ->
                    IntArray(c.length()) { c.getInt(it) }
                } ?: IntArray(0)
                val matrix: FloatArray
                val t: FloatArray?
                val r: FloatArray?
                val s: FloatArray?
                if (n.has("matrix")) {
                    val m = n.getJSONArray("matrix")
                    matrix = FloatArray(16) { m.getDouble(it).toFloat() }
                    t = null; r = null; s = null
                } else {
                    t = jsonArrayFloats(n, "translation", 0f, 3)
                    r = jsonArrayFloats(n, "rotation", 0f, 4, wDefault = 1f)
                    s = jsonArrayFloats(n, "scale", 1f, 3)
                    matrix = trsMatrix(t, r, s)
                }
                GltfNode(
                    n.optString("name", "node$i"), matrix, children,
                    if (n.has("mesh")) n.getInt("mesh") else null,
                    translation = t, rotation = r, scale = s,
                    skin = n.optInt("skin", -1).takeIf { it >= 0 },
                )
            }
        } ?: emptyArray()

        // --- skins (joints + inverse bind matrices) ---
        val skins = root.optJSONArray("skins")?.let { arr ->
            List(arr.length()) { i ->
                val sk = arr.getJSONObject(i)
                val jointsArr = sk.optJSONArray("joints") ?: JSONArray()
                val joints = IntArray(jointsArr.length()) { jointsArr.getInt(it) }
                val ibmAcc = sk.optInt("inverseBindMatrices", -1)
                // MAT4 accessor → 16 floats per joint, verbatim.
                val ibm = if (ibmAcc in accessors.indices) {
                    readFloats(accessors[ibmAcc], buffers, bufferViews, 16)
                } else {
                    // Identity fallback: 1 on the diagonal of each 16-float block.
                    FloatArray(joints.size * 16) { idx ->
                        if (idx % 16 in intArrayOf(0, 5, 10, 15)) 1f else 0f
                    }
                }
                GltfSkin(joints, ibm, sk.optString("name", "skin$i"))
            }
        } ?: emptyList()

        // --- animations (rigid channels; skins/morphs are a later step) ---
        // A channel whose target node/path we cannot honor is dropped, and
        // a sampler shared by channels is read once (glTF allows reuse).
        val animations = mutableListOf<GltfAnimation>()
        root.optJSONArray("animations")?.let { anims ->
            for (a in 0 until anims.length()) {
                val anim = anims.getJSONObject(a)
                val samplersJson = anim.optJSONArray("samplers") ?: continue
                val samplers = List(samplersJson.length()) { si ->
                    val sm = samplersJson.getJSONObject(si)
                    // input: SCALAR f32 times; output: VEC3/VEC4 values
                    val inAcc = accessors[sm.getInt("input")]
                    val outAcc = accessors[sm.getInt("output")]
                    val comps = when (outAcc.type) {
                        "VEC4" -> 4
                        else -> 3
                    }
                    GltfSampler(
                        times = readFloats(inAcc, buffers, bufferViews, 1),
                        values = readFloats(outAcc, buffers, bufferViews, comps),
                        interpolation = sm.optString("interpolation", "LINEAR"),
                    )
                }
                val channelsJson = anim.optJSONArray("channels") ?: continue
                val channels = mutableListOf<GltfChannel>()
                for (ci in 0 until channelsJson.length()) {
                    val ch = channelsJson.getJSONObject(ci)
                    val target = ch.optJSONObject("target") ?: continue
                    val path = target.optString("path")
                    val nodeIdx = target.optInt("node", -1)
                    val samplerIdx = ch.optInt("sampler", -1)
                    if (path !in ANIMATED_PATHS) continue // weights (morph) unsupported for now
                    if (nodeIdx !in nodes.indices) continue
                    if (samplerIdx !in samplers.indices) continue
                    channels.add(GltfChannel(nodeIdx, path, samplerIdx))
                }
                if (channels.isNotEmpty()) {
                    animations.add(
                        GltfAnimation(
                            anim.optString("name", "clip$a"),
                            channels,
                            samplers,
                        ),
                    )
                }
            }
        }

        val roots = root.optJSONArray("scenes")?.let { scenes ->
            val s = scenes.getJSONObject(root.optInt("scene", 0))
            val nodesArr = s.optJSONArray("nodes") ?: return@let IntArray(0)
            IntArray(nodesArr.length()) { nodesArr.getInt(it) }
        } ?: if (nodes.isNotEmpty()) intArrayOf(0) else IntArray(0)

        // --- model-space bounds (walk hierarchy, transform primitive bounds) ---
        var min = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE)
        var max = floatArrayOf(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        fun walk(nodeIdx: Int, parent: FloatArray) {
            if (nodeIdx !in nodes.indices) return
            val node = nodes[nodeIdx]
            val world = matMul(parent, node.matrix)
            node.mesh?.let { meshIdx ->
                primitives[meshIdx]?.forEach { prim ->
                    // transform the 8 AABB corners
                    val bmin = prim.boundsMin; val bmax = prim.boundsMax
                    for (cx in 0..1) for (cy in 0..1) for (cz in 0..1) {
                        val lx = if (cx == 0) bmin[0] else bmax[0]
                        val ly = if (cy == 0) bmin[1] else bmax[1]
                        val lz = if (cz == 0) bmin[2] else bmax[2]
                        val w = matTransform(world, lx, ly, lz)
                        for (k in 0..2) {
                            if (w[k] < min[k]) min[k] = w[k]
                            if (w[k] > max[k]) max[k] = w[k]
                        }
                    }
                }
            }
            for (c in node.children) walk(c, world)
        }
        val identity = floatArrayOf(
            1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f,
        )
        for (r in roots) walk(r, identity)

        if (min[0] > max[0]) { // no geometry bounds (empty model)
            min = floatArrayOf(-1f, -1f, -1f); max = floatArrayOf(1f, 1f, 1f)
        }

        val name = root.optJSONObject("asset")?.optString("generator")?.takeIf { it.isNotBlank() }
            ?: defaultName
        return GltfModel(
            primitives = primitives,
            nodes = nodes.toList(),
            sceneRootNodes = roots,
            boundsMin = min,
            boundsMax = max,
            name = name,
            materials = materials,
            textures = textures,
            animations = animations,
            skins = skins,
        )
    }

    // ------------------------------------------------------------------
    // container / accessor helpers
    // ------------------------------------------------------------------

    private class BufferView(
        val buffer: Int,
        val byteOffset: Int,
        val byteStride: Int,
        val byteLength: Int,
    )
    private class Accessor(
        val bufferView: Int,
        val byteOffset: Int,
        val componentType: Int,
        val count: Int,
        val type: String,
    )

    private fun componentsOf(type: String): Int = when (type) {
        "SCALAR" -> 1
        "VEC2" -> 2
        "VEC3" -> 3
        "VEC4" -> 4
        "MAT4" -> 16 // inverse bind matrices (skinning)
        else -> throw IllegalArgumentException("accessor type $type unsupported")
    }

    private fun componentBytes(componentType: Int): Int = when (componentType) {
        5120 -> 1 // BYTE
        5121 -> 1 // UBYTE
        5122 -> 2 // SHORT
        5123 -> 2 // USHORT
        5125 -> 4 // UINT
        5126 -> 4 // FLOAT
        else -> throw IllegalArgumentException("componentType $componentType unsupported")
    }

    private fun readFloats(
        acc: Accessor,
        buffers: Array<ByteArray?>,
        views: Array<BufferView>,
        wantComponents: Int,
    ): FloatArray {
        if (acc.bufferView < 0) return FloatArray(acc.count * wantComponents)
        val view = views[acc.bufferView]
        val buf = buffers[view.buffer]
            ?: throw IllegalArgumentException("buffer ${view.buffer} missing")
        val comps = componentsOf(acc.type)
        val elemBytes = componentBytes(acc.componentType) * comps
        val stride = if (view.byteStride > 0) view.byteStride else elemBytes
        val base = view.byteOffset + acc.byteOffset
        val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
        val out = FloatArray(acc.count * wantComponents)
        for (i in 0 until acc.count) {
            val elem = base + i * stride
            for (c in 0 until wantComponents) {
                val off = elem + c * componentBytes(acc.componentType)
                out[i * wantComponents + c] = when (acc.componentType) {
                    5126 -> bb.getFloat(off)
                    5125 -> bb.getInt(off).toFloat()
                    5123 -> (bb.getShort(off).toInt() and 0xFFFF).toFloat()
                    5121 -> (bb.get(off).toInt() and 0xFF).toFloat()
                    else -> bb.getFloat(off)
                }
            }
        }
        // scissor accessors (component count > requested) handled by `wantComponents`
        return out
    }

    private fun readIndices(
        acc: Accessor,
        buffers: Array<ByteArray?>,
        views: Array<BufferView>,
    ): IntArray {
        val view = views[acc.bufferView]
        val buf = buffers[view.buffer]
            ?: throw IllegalArgumentException("buffer ${view.buffer} missing")
        val elemBytes = componentBytes(acc.componentType)
        val stride = if (view.byteStride > 0) view.byteStride else elemBytes
        val base = view.byteOffset + acc.byteOffset
        val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
        val out = IntArray(acc.count)
        for (i in 0 until acc.count) {
            val off = base + i * stride
            out[i] = when (acc.componentType) {
                5123 -> bb.getShort(off).toInt() and 0xFFFF
                5125 -> bb.getInt(off)
                5121 -> bb.get(off).toInt() and 0xFF
                else -> bb.getShort(off).toInt() and 0xFFFF
            }
        }
        return out
    }

    private data class Chunk(val type: Int, val data: ByteArray)

    private fun splitContainer(bytes: ByteArray): Pair<String, ByteArray?> {
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        // GLB: magic 'glTF' | version | length | chunks
        if (bytes.size > 12 && bb.int == 0x46546C67) { // 'glTF'
            bb.int // version
            bb.int // total length
            var json: String? = null
            var bin: ByteArray? = null
            while (bb.remaining() >= 8) {
                val len = bb.int
                val type = bb.int
                val data = ByteArray(len)
                bb.get(data)
                if (len % 4 != 0) bb.position(bb.position() + (4 - len % 4)) // pad
                when (type) {
                    0x4E4F534A -> json = String(data, Charsets.UTF_8) // 'JSON'
                    0x004E4942 -> bin = data // 'BIN\0'
                }
            }
            return Pair(json ?: throw IllegalArgumentException("GLB has no JSON chunk"), bin)
        }
        // Plain .gltf JSON (embedded base64 buffers resolved later)
        return Pair(String(bytes, Charsets.UTF_8), null)
    }

    /** translation/rotation(quat x,y,z,w at index0)/scale → column-major matrix. */
    /** TRS → column-major matrix (public for [Animator] sampling). */
    fun trsMatrix(t: FloatArray, r: FloatArray, s: FloatArray): FloatArray {
        val (qx, qy, qz, qw) = floatArrayOf(r[0], r[1], r[2], r[3])
        // rotation matrix (column-major)
        val m00 = 1f - 2f * (qy * qy + qz * qz)
        val m01 = 2f * (qx * qy - qz * qw)
        val m02 = 2f * (qx * qz + qy * qw)
        val m10 = 2f * (qx * qy + qz * qw)
        val m11 = 1f - 2f * (qx * qx + qz * qz)
        val m12 = 2f * (qy * qz - qx * qw)
        val m20 = 2f * (qx * qz - qy * qw)
        val m21 = 2f * (qy * qz + qx * qw)
        val m22 = 1f - 2f * (qx * qx + qy * qy)
        return floatArrayOf(
            m00 * s[0], m10 * s[0], m20 * s[0], 0f,
            m01 * s[1], m11 * s[1], m21 * s[1], 0f,
            m02 * s[2], m12 * s[2], m22 * s[2], 0f,
            t[0], t[1], t[2], 1f,
        )
    }

    /** column-major A·B (both glTF style). */
    fun matMul(a: FloatArray, b: FloatArray): FloatArray {
        val out = FloatArray(16)
        for (col in 0..3) {
            for (row in 0..3) {
                var sum = 0f
                for (k in 0..3) sum += a[k * 4 + row] * b[col * 4 + k]
                out[col * 4 + row] = sum
            }
        }
        return out
    }

    /** Transform point by column-major matrix (w = 1). */
    fun matTransform(m: FloatArray, x: Float, y: Float, z: Float): FloatArray {
        return floatArrayOf(
            m[0] * x + m[4] * y + m[8] * z + m[12],
            m[1] * x + m[5] * y + m[9] * z + m[13],
            m[2] * x + m[6] * y + m[10] * z + m[14],
        )
    }

    /** translation[3] / rotation[4, xyzw] / scale[3] reader with defaults. */
    private fun jsonArrayFloats(
        n: JSONObject,
        key: String,
        def: Float,
        expected: Int,
        wDefault: Float = def,
    ): FloatArray {
        val arr = n.optJSONArray(key)
            ?: return FloatArray(expected) { if (expected == 4 && it == 3) wDefault else def }
        return FloatArray(expected) {
            if (it < arr.length()) arr.getDouble(it).toFloat()
            else if (expected == 4 && it == 3) wDefault
            else def
        }
    }
}
