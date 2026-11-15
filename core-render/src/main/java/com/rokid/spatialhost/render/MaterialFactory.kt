package com.rokid.spatialhost.render

import android.util.Log
import com.google.android.filament.Engine
import com.google.android.filament.Material
import com.google.android.filament.filamat.MaterialBuilder

/**
 * Runtime material compilation via filamat (the Android build of libfilamat).
 *
 * We compile small unlit materials on-device at engine startup instead of
 * shipping precompiled .filamat assets (keeps the build free of matc).
 * The shader snippets use the same identifiers as the .mat DSL fragment
 * block: `material.*` (MaterialInputs) and `materialParams.*` (parameters).
 *
 * Materials:
 *  - **color_unlit**: `materialParams.color` (float4) → solid tint. Used for
 *    the 360° backdrop sphere and spatial panels (tint per MaterialInstance).
 *  - **textured_unlit**: baseColor sampler × tint — added when media textures
 *    land (Phase B2).
 */
object MaterialFactory {

    private const val TAG = "MaterialFactory"

    @Volatile
    private var initialized = false

    fun ensureInitialized() {
        if (!initialized) {
            MaterialBuilder.init()
            initialized = true
            Log.i(TAG, "MaterialBuilder initialized")
        }
    }

    fun shutdown() {
        if (initialized) {
            MaterialBuilder.shutdown()
            initialized = false
            Log.i(TAG, "MaterialBuilder shut down")
        }
    }

    /** Unlit solid-color material with a `color` float4 parameter. */
    fun buildColorUnlit(engine: Engine): Material? {
        ensureInitialized()
        val pkg = try {
            MaterialBuilder()
                .name("color_unlit")
                .materialDomain(MaterialBuilder.MaterialDomain.SURFACE)
                .shading(MaterialBuilder.Shading.UNLIT)
                .blending(MaterialBuilder.BlendingMode.OPAQUE)
                .culling(MaterialBuilder.CullingMode.NONE)
                .require(MaterialBuilder.VertexAttribute.POSITION)
                .uniformParameter(MaterialBuilder.UniformType.FLOAT4, "color")
                // filamat expects a FULL function; prepareMaterial() is mandatory
                // (see MaterialBuilder.h docs for the canonical example).
                .material(
                    "void material(inout MaterialInputs material) {" +
                        "  prepareMaterial(material);" +
                        "  material.baseColor = materialParams.color;" +
                        "}",
                )
                .platform(MaterialBuilder.Platform.MOBILE)
                .targetApi(MaterialBuilder.TargetApi.ALL)
                .optimization(MaterialBuilder.Optimization.PERFORMANCE)
                .build()
        } catch (t: Throwable) {
            Log.e(TAG, "color_unlit build threw", t)
            return null
        }
        if (!pkg.isValid) {
            Log.e(TAG, "color_unlit package invalid — check shader snippet syntax")
            return null
        }
        return try {
            val buf = pkg.buffer
            val material = Material.Builder()
                .payload(buf, buf.remaining())
                .build(engine)
            Log.i(TAG, "color_unlit compiled (${buf.remaining()} bytes)")
            material
        } catch (t: Throwable) {
            Log.e(TAG, "color_unlit Material.Builder failed", t)
            null
        }
    }

    /**
     * Lit (PBR) material with a per-instance `color` — for glTF models.
     * Needs a directional light + camera exposure in the scene (D2 adds IBL
     * for the full photoreal look).
     */
    fun buildLit(engine: Engine): Material? {
        ensureInitialized()
        val pkg = try {
            MaterialBuilder()
                .name("lit_color")
                .materialDomain(MaterialBuilder.MaterialDomain.SURFACE)
                .shading(MaterialBuilder.Shading.LIT)
                .blending(MaterialBuilder.BlendingMode.OPAQUE)
                .culling(MaterialBuilder.CullingMode.BACK)
                .require(MaterialBuilder.VertexAttribute.POSITION)
                // Filament encodes normals as a quaternion TANGENTS frame
                // ("TANGENTS ... is how normals are specified").
                .require(MaterialBuilder.VertexAttribute.TANGENTS)
                .uniformParameter(MaterialBuilder.UniformType.FLOAT4, "color")
                .material(
                    "void material(inout MaterialInputs material) {" +
                        "  prepareMaterial(material);" +
                        "  material.baseColor = materialParams.color;" +
                        "  material.roughness = 0.55;" +
                        "  material.metallic = 0.05;" +
                        "}",
                )
                .platform(MaterialBuilder.Platform.MOBILE)
                .targetApi(MaterialBuilder.TargetApi.ALL)
                .optimization(MaterialBuilder.Optimization.PERFORMANCE)
                .build()
        } catch (t: Throwable) {
            Log.e(TAG, "lit_color build threw", t)
            return null
        }
        if (!pkg.isValid) {
            Log.e(TAG, "lit_color package invalid")
            return null
        }
        return try {
            val buf = pkg.buffer
            val material = Material.Builder().payload(buf, buf.remaining()).build(engine)
            Log.i(TAG, "lit_color compiled (${buf.remaining()} bytes)")
            material
        } catch (t: Throwable) {
            Log.e(TAG, "lit_color Material.Builder failed", t)
            null
        }
    }

    /**
     * Unlit textured material for the 360° video sphere: samples an EXTERNAL
     * sampler (the camera/stream SurfaceTexture wrapped by a Filament Stream).
     *
     * Sampled exactly like Google's texturedExternal.mat — SAMPLERS are
     * referenced as `materialParams_<name>` (macro), not `materialParams.name`
     * (that dot-syntax is for uniform parameters).
     */
    fun buildVideoSphere(engine: Engine): Material? {
        ensureInitialized()
        val pkg = try {
            MaterialBuilder()
                .name("video_external")
                .materialDomain(MaterialBuilder.MaterialDomain.SURFACE)
                .shading(MaterialBuilder.Shading.UNLIT)
                .blending(MaterialBuilder.BlendingMode.OPAQUE)
                .culling(MaterialBuilder.CullingMode.NONE)
                .require(MaterialBuilder.VertexAttribute.UV0)
                .samplerParameter(
                    MaterialBuilder.SamplerType.SAMPLER_EXTERNAL,
                    MaterialBuilder.SamplerFormat.FLOAT,
                    MaterialBuilder.ParameterPrecision.DEFAULT,
                    "baseColor",
                )
                .material(
                    "void material(inout MaterialInputs material) {" +
                        "  prepareMaterial(material);" +
                        "  material.baseColor = texture(materialParams_baseColor, getUV0());" +
                        "}",
                )
                .platform(MaterialBuilder.Platform.MOBILE)
                .targetApi(MaterialBuilder.TargetApi.OPENGL)
                .optimization(MaterialBuilder.Optimization.PERFORMANCE)
                .build()
        } catch (t: Throwable) {
            Log.e(TAG, "video_external build threw", t)
            return null
        }
        if (!pkg.isValid) {
            Log.e(TAG, "video_external package invalid")
            return null
        }
        return try {
            val buf = pkg.buffer
            val material = Material.Builder().payload(buf, buf.remaining()).build(engine)
            Log.i(TAG, "video_external compiled (${buf.remaining()} bytes)")
            material
        } catch (t: Throwable) {
            Log.e(TAG, "video_external Material.Builder failed", t)
            null
        }
    }
}
