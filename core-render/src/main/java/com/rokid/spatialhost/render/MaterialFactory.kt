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
     * Lit (PBR) material for imported glTF models: per-instance `color`
     * tint (baseColorFactor) × `baseColor` texture, metallic/roughness
     * sampled from `mrMap` (B/G channels) scaled by the `mr` factors,
     * tangent-space normal perturbation from `normalMap`.
     *
     * Per the Filament docs, `material.normal` is supplied in TANGENT space
     * and assigned BEFORE prepareMaterial(). Needs a directional light +
     * camera exposure (D2c adds IBL for correct metal response). All lit
     * renderables must supply POSITION + TANGENTS (quaternion frame) + UV0.
     *
     * Missing maps bind white / flat-normal fallbacks so every primitive
     * uses the same program (filamat has no material variants here).
     */
    fun buildLit(engine: Engine): Material? {
        ensureInitialized()
        val pkg = try {
            MaterialBuilder()
                .name("lit_pbr")
                .materialDomain(MaterialBuilder.MaterialDomain.SURFACE)
                .shading(MaterialBuilder.Shading.LIT)
                .blending(MaterialBuilder.BlendingMode.OPAQUE)
                .culling(MaterialBuilder.CullingMode.BACK)
                .require(MaterialBuilder.VertexAttribute.POSITION)
                // Filament encodes normals as a quaternion TANGENTS frame
                // ("TANGENTS ... is how normals are specified").
                .require(MaterialBuilder.VertexAttribute.TANGENTS)
                .require(MaterialBuilder.VertexAttribute.UV0)
                .uniformParameter(MaterialBuilder.UniformType.FLOAT4, "color")
                .uniformParameter(MaterialBuilder.UniformType.FLOAT2, "mr")
                .samplerParameter(
                    MaterialBuilder.SamplerType.SAMPLER_2D,
                    MaterialBuilder.SamplerFormat.FLOAT,
                    MaterialBuilder.ParameterPrecision.DEFAULT,
                    "baseColor",
                )
                .samplerParameter(
                    MaterialBuilder.SamplerType.SAMPLER_2D,
                    MaterialBuilder.SamplerFormat.FLOAT,
                    MaterialBuilder.ParameterPrecision.DEFAULT,
                    "mrMap",
                )
                .samplerParameter(
                    MaterialBuilder.SamplerType.SAMPLER_2D,
                    MaterialBuilder.SamplerFormat.FLOAT,
                    MaterialBuilder.ParameterPrecision.DEFAULT,
                    "normalMap",
                )
                .material(
                    // Normal first, exactly as in the docs' normal-mapping
                    // example (tangent space, +Z out of the surface).
                    "void material(inout MaterialInputs material) {" +
                        "  material.normal =" +
                        "      texture(materialParams_normalMap, getUV0()).xyz * 2.0 - 1.0;" +
                        "  prepareMaterial(material);" +
                        "  material.baseColor = materialParams.color *" +
                        "      texture(materialParams_baseColor, getUV0());" +
                        "  vec4 mr = texture(materialParams_mrMap, getUV0());" +
                        "  material.metallic = clamp(materialParams.mr.x * mr.b, 0.0, 1.0);" +
                        "  material.roughness = clamp(materialParams.mr.y * mr.g, 0.04, 1.0);" +
                        "}",
                )
                .platform(MaterialBuilder.Platform.MOBILE)
                .targetApi(MaterialBuilder.TargetApi.ALL)
                .optimization(MaterialBuilder.Optimization.PERFORMANCE)
                .build()
        } catch (t: Throwable) {
            Log.e(TAG, "lit_pbr build threw", t)
            return null
        }
        if (!pkg.isValid) {
            Log.e(TAG, "lit_pbr package invalid")
            return null
        }
        return try {
            val buf = pkg.buffer
            val material = Material.Builder().payload(buf, buf.remaining()).build(engine)
            Log.i(TAG, "lit_pbr compiled (${buf.remaining()} bytes)")
            material
        } catch (t: Throwable) {
            Log.e(TAG, "lit_pbr Material.Builder failed", t)
            null
        }
    }

    /**
     * Textured panel surface: per-instance tint × UI texture (Canvas-
     * rendered widgets). The bitmap is drawn in canvas orientation
     * (y down); V is flipped here so it lines up with panel UVs
     * (v up) — same flip as the video sphere.
     */
    fun buildPanelUi(engine: Engine): Material? {
        ensureInitialized()
        val pkg = try {
            MaterialBuilder()
                .name("panel_ui")
                .materialDomain(MaterialBuilder.MaterialDomain.SURFACE)
                .shading(MaterialBuilder.Shading.UNLIT)
                .blending(MaterialBuilder.BlendingMode.OPAQUE)
                .culling(MaterialBuilder.CullingMode.NONE)
                .require(MaterialBuilder.VertexAttribute.UV0)
                .uniformParameter(MaterialBuilder.UniformType.FLOAT4, "color")
                .samplerParameter(
                    MaterialBuilder.SamplerType.SAMPLER_2D,
                    MaterialBuilder.SamplerFormat.FLOAT,
                    MaterialBuilder.ParameterPrecision.DEFAULT,
                    "ui",
                )
                .material(
                    "void material(inout MaterialInputs material) {" +
                        "  prepareMaterial(material);" +
                        // NOTE: sampled RAW (no V flip): the deck test showed
                        // that setImage maps the buffer's rows so that a
                        // canvas-top draw lands at panel-top only with the
                        // identity mapping (the video sphere needs the flip,
                        // plain bitmap uploads do not).
                        "  material.baseColor = materialParams.color *" +
                        "      texture(materialParams_ui, getUV0());" +
                        "}",
                )
                .platform(MaterialBuilder.Platform.MOBILE)
                .targetApi(MaterialBuilder.TargetApi.ALL)
                .optimization(MaterialBuilder.Optimization.PERFORMANCE)
                .build()
        } catch (t: Throwable) {
            Log.e(TAG, "panel_ui build threw", t)
            return null
        }
        if (!pkg.isValid) {
            Log.e(TAG, "panel_ui package invalid")
            return null
        }
        return try {
            val buf = pkg.buffer
            val material = Material.Builder().payload(buf, buf.remaining()).build(engine)
            Log.i(TAG, "panel_ui compiled (${buf.remaining()} bytes)")
            material
        } catch (t: Throwable) {
            Log.e(TAG, "panel_ui Material.Builder failed", t)
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
                        // Equirect frames arrive GL-oriented (row0 = bottom)
                        // while the panorama convention expects v=0 = top —
                        // without this flip the video renders upside down.
                        "  vec2 uv = getUV0();" +
                        "  material.baseColor =" +
                        "      texture(materialParams_baseColor, vec2(uv.x, 1.0 - uv.y));" +
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
