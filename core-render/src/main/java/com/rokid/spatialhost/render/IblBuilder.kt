package com.rokid.spatialhost.render

import android.util.Log
import com.google.android.filament.Engine
import com.google.android.filament.IndirectLight
import com.google.android.filament.Texture
import com.google.android.filament.utils.IBLPrefilterContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * D2c: runtime image-based lighting for imported models.
 *
 * 1. Renders a procedural equirectangular environment (gradient sky +
 *    ground + sun disk) into an RGBA8 texture using EXACTLY the mapping
 *    of filament's `equirectToCube` material:
 *      u = 0.5 + atan2(x, z) / 2π,  v = 0.5 - asin(y) / π  (v=0 = zenith)
 * 2. Converts it on the GPU to a prefiltered reflections cubemap with
 *    `IBLPrefilterContext` (equirect → cube → specular roughness mips),
 *    which `IndirectLight.reflections()` expects.
 * 3. Projects the SAME analytic environment onto 3-band SH for diffuse
 *    irradiance (`IndirectLight.irradiance(3, sh)`).
 *
 * SH convention (from IndirectLight.h + CubemapSH.cpp + the shipped
 * `Irradiance_SphericalHarmonics` shader): coefficients are the orthonormal
 * projection of the radiance, band-scaled by the truncated-cos kernel
 * [π, 2π/3, π/4], premultiplied by the Sloan polynomial constants N[i] and
 * the Lambertian 1/π ("Fd_Lambert() is baked in the SH"). The runtime then
 * evaluates  sh[0] + sh[1]·y + sh[2]·z + sh[3]·x + sh[4]·yx + sh[5]·yz
 *           + sh[6]·(3z²−1) + sh[7]·zx + sh[8]·(x²−y²)
 * with NO further scaling.
 */
class IblBuilder(private val engine: Engine) {

    init {
        // The utils AAR ships libfilament-utils-jni.so, but no class in it
        // calls System.loadLibrary — the IBLPrefilterContext natives live
        // there (idempotent, safe to call repeatedly).
        try {
            System.loadLibrary("filament-utils-jni")
        } catch (t: Throwable) {
            Log.w(TAG, "loadLibrary filament-utils-jni failed: $t")
        }
    }

    /** Retained for teardown (the cubemap must outlive the prefilter). */
    var reflections: Texture? = null
        private set
    var irradianceCube: Texture? = null
        private set
    var indirectLight: IndirectLight? = null
        private set

    /**
     * Build everything and assign it to the scene's indirect light.
     * @return true on success (false keeps direct-lighting-only rendering).
     */
    fun build(): Boolean {
        return try {
            val sunDir = floatArrayOf(0.42f, 0.66f, 0.62f)
            val sunLen = sqrt(sunDir[0] * sunDir[0] + sunDir[1] * sunDir[1] + sunDir[2] * sunDir[2])
            sunDir[0] /= sunLen; sunDir[1] /= sunLen; sunDir[2] /= sunLen

            // --- 1. equirect environment texture -----------------------
            val w = 512
            val h = 256
            val px = ByteArray(w * h * 4)
            var p = 0
            for (row in 0 until h) { // row 0 = v = 0 = zenith (matches toEquirect)
                val el = (0.5f - (row + 0.5f) / h) * PI // elevation ∈ (−π/2, π/2)
                val ce = cos(el).toFloat()
                val se = sin(el).toFloat()
                for (col in 0 until w) {
                    val az = ((col + 0.5f) / w - 0.5f) * 2.0 * PI // atan2(x, z)
                    val x = (sin(az) * ce).toFloat()
                    val z = (cos(az) * ce).toFloat()
                    val rgb = skyColor(x, se.toFloat(), z, sunDir)
                    px[p++] = (rgb[0] * 255f).toInt().toByte()
                    px[p++] = (rgb[1] * 255f).toInt().toByte()
                    px[p++] = (rgb[2] * 255f).toInt().toByte()
                    px[p++] = 0xFF.toByte()
                }
            }
            val equirect = Texture.Builder()
                .width(w)
                .height(h)
                // full mip chain — EquirectangularToCubemap requires
                // levels == ilogb(width)+1 and generates them itself.
                .levels(10)
                .format(Texture.InternalFormat.RGBA8)
                .sampler(Texture.Sampler.SAMPLER_2D)
                // generateMipmaps() inside the prefilter demands this flag.
                .usage(Texture.Usage.DEFAULT or Texture.Usage.GEN_MIPMAPPABLE)
                .build(engine)
            val buf = ByteBuffer.allocateDirect(px.size).order(ByteOrder.nativeOrder())
            buf.put(px)
            buf.rewind()
            equirect.setImage(
                engine,
                0,
                Texture.PixelBufferDescriptor(buf, Texture.Format.RGBA, Texture.Type.UBYTE),
            )

            // --- 2. GPU prefilter: equirect → cube → roughness mips -----
            val ctx = IBLPrefilterContext(engine)
            val e2c = IBLPrefilterContext.EquirectangularToCubemap(ctx)
            val cube = e2c.run(equirect)
            e2c.destroy()
            engine.destroyTexture(equirect)
            val sf = IBLPrefilterContext.SpecularFilter(ctx)
            val refl = sf.run(cube)
            sf.destroy()
            ctx.destroy()
            reflections = refl
            irradianceCube = cube

            // --- 3. diffuse SH from the same analytic environment -------
            val sh = projectSh(sunDir)

            val ibl = IndirectLight.Builder()
                .reflections(refl)
                .irradiance(3, sh)
                .intensity(INTENSITY)
                .build(engine)
            indirectLight = ibl
            Log.i(
                TAG,
                "IBL ready: equirect ${w}x$h → reflections, sh[0]=${"%.3f".format(sh[0])} " +
                    "intensity=$INTENSITY",
            )
            true
        } catch (t: Throwable) {
            Log.e(TAG, "IBL build failed", t)
            false
        }
    }

    fun release() {
        indirectLight?.let { engine.destroyIndirectLight(it) }
        reflections?.let { engine.destroyTexture(it) }
        irradianceCube?.let { engine.destroyTexture(it) }
        indirectLight = null
        reflections = null
        irradianceCube = null
    }

    // ------------------------------------------------------------------
    // environment + math
    // ------------------------------------------------------------------

    /** Analytic environment: soft gradient sky, dark ground, sun disk. */
    private fun skyColor(x: Float, y: Float, z: Float, sunDir: FloatArray): FloatArray {
        val rgb: FloatArray
        if (y >= 0f) {
            // horizon → zenith
            val t = y * y // bias the blend toward the horizon
            rgb = floatArrayOf(
                lerp(0.86f, 0.34f, t),
                lerp(0.89f, 0.55f, t),
                lerp(0.94f, 0.97f, t),
            )
        } else {
            // ground: warm dark, slightly brighter near the horizon
            val t = (-y).coerceAtMost(1f)
            rgb = floatArrayOf(
                lerp(0.55f, 0.30f, t),
                lerp(0.50f, 0.27f, t),
                lerp(0.45f, 0.24f, t),
            )
        }
        // sun disk with a soft rim (values stay ≤1: RGBA8 equirect)
        val d = x * sunDir[0] + y * sunDir[1] + z * sunDir[2]
        if (d > 0.985f) {
            val s = ((d - 0.985f) / 0.015f).coerceIn(0f, 1f)
            rgb[0] = lerp(rgb[0], 1f, s)
            rgb[1] = lerp(rgb[1], 1f, s)
            rgb[2] = lerp(rgb[2], 1f, s)
        }
        return rgb
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    /**
     * Project [sunDir]-based environment onto 3-band SH (27 floats,
     * RGB-interleaved). Fibonacci-sphere quadrature of the analytic sky.
     */
    private fun projectSh(sunDir: FloatArray): FloatArray {
        val samples = 4096
        val golden = (PI * (3.0 - sqrt(5.0))).toFloat()
        val c = FloatArray(9) // radiance projection, per channel
        val gr = FloatArray(9)
        val gb = FloatArray(9)
        val step = (4.0 * PI / samples).toFloat()
        for (i in 0 until samples) {
            val y = 1f - 2f * (i + 0.5f) / samples
            val r = sqrt((1f - y * y).coerceAtLeast(0f))
            val phi = i * golden
            val x = cos(phi) * r
            val z = sin(phi) * r
            val rgb = skyColor(x, y, z, sunDir)
            // orthonormal real SH basis, Sloan polynomial order
            val y0 = 0.282095f
            val y1 = 0.488602f * y
            val y2 = 0.488602f * z
            val y3 = 0.488602f * x
            val y4 = 1.092548f * x * y
            val y5 = 1.092548f * y * z
            val y6 = 0.315392f * (3f * z * z - 1f)
            val y7 = 1.092548f * z * x
            val y8 = 0.546274f * (x * x - y * y)
            val yb = floatArrayOf(y0, y1, y2, y3, y4, y5, y6, y7, y8)
            for (k in 0..8) {
                c[k] += rgb[0] * yb[k] * step
                gr[k] += rgb[1] * yb[k] * step
                gb[k] += rgb[2] * yb[k] * step
            }
        }
        // band scaling by the truncated-cos kernel, then N[i]/π
        // ("Fd_Lambert() is baked in the SH").
        val can = floatArrayOf(PI.toFloat(), (2 * PI / 3).toFloat(), (PI / 4).toFloat())
        val n = floatArrayOf(
            0.282095f, 0.488602f, 0.488602f, 0.488602f, 1.092548f,
            1.092548f, 0.315392f, 1.092548f, 0.546274f,
        )
        val band = intArrayOf(0, 1, 1, 1, 2, 2, 2, 2, 2)
        val out = FloatArray(27)
        for (i in 0..8) {
            val scale = can[band[i]] * n[i] / PI.toFloat()
            out[i * 3] = c[i] * scale
            out[i * 3 + 1] = gr[i] * scale
            out[i * 3 + 2] = gb[i] * scale
        }
        return out
    }

    companion object {
        private const val TAG = "IblBuilder"

        /**
         * Environment intensity: our equirect is LDR (0..1), so the whole
         * IBL is scaled into a daylight-ish range (≈ sky contribution of
         * ~30k lux against the 110k-lx key light).
         */
        private const val INTENSITY = 30_000f
    }
}
