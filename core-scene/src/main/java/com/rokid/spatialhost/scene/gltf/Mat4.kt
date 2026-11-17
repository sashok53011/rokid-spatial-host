package com.rokid.spatialhost.scene.gltf

/**
 * Minimal column-major 4×4 matrix math for skinning (D6).
 *
 * glTF and Filament both use column-major layout (m[0..3] = first column),
 * so `mul(a, b)` = a·b applied as `b` first — matching [GltfParser]'s
 * matMul. Pure Kotlin: unit-tested on the JVM.
 */
object Mat4 {

    fun identity(): FloatArray = floatArrayOf(
        1f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        0f, 0f, 0f, 1f,
    )

    /** a·b (apply b first, then a). */
    fun mul(a: FloatArray, b: FloatArray): FloatArray {
        val out = FloatArray(16)
        for (col in 0..3) {
            for (row in 0..3) {
                var s = 0f
                for (k in 0..3) s += a[k * 4 + row] * b[col * 4 + k]
                out[col * 4 + row] = s
            }
        }
        return out
    }

    /**
     * General 4×4 inverse (Gauss–Jordan with partial pivoting). Returns
     * null when the matrix is singular — callers fall back to identity so
     * a degenerate node cannot crash the render thread.
     */
    fun inverse(m: FloatArray): FloatArray? = gaussJordan(m)

    /** Gauss–Jordan inversion with partial pivoting (robust, testable). */
    private fun gaussJordan(m: FloatArray): FloatArray? {
        // Augmented 4×8: [m | I], row-major working copy.
        val r = FloatArray(32)
        for (row in 0..3) {
            for (col in 0..3) r[row * 8 + col] = m[col * 4 + row]
            r[row * 8 + 4 + row] = 1f
        }
        for (col in 0..3) {
            // Pivot: largest |r[row][col]| at/below col.
            var piv = col
            var best = kotlin.math.abs(r[col * 8 + col])
            for (row in col + 1..3) {
                val v = kotlin.math.abs(r[row * 8 + col])
                if (v > best) { best = v; piv = row }
            }
            if (best < 1e-8f) return null // singular
            if (piv != col) {
                for (k in 0..7) {
                    val t = r[col * 8 + k]; r[col * 8 + k] = r[piv * 8 + k]; r[piv * 8 + k] = t
                }
            }
            val inv = 1f / r[col * 8 + col]
            for (k in 0..7) r[col * 8 + k] *= inv
            for (row in 0..3) {
                if (row == col) continue
                val factor = r[row * 8 + col]
                if (factor == 0f) continue
                for (k in 0..7) r[row * 8 + k] -= factor * r[col * 8 + k]
            }
        }
        val out = FloatArray(16)
        for (row in 0..3) for (col in 0..3) out[col * 4 + row] = r[row * 8 + 4 + col]
        return out
    }

    /**
     * glTF skinning matrix for one bone:
     * `inverse(meshNodeWorld) · jointNodeWorld · inverseBindMatrix` —
     * the exact formula gltfio's AnimatorImpl::updateBoneMatrices uses.
     * (Filament applies the renderable's model matrix after skinning, so
     * the mesh node's world transform is cancelled here — per glTF spec a
     * skinned primitive ignores the instantiating node's transform.)
     */
    fun boneMatrix(
        inverseMeshWorld: FloatArray,
        jointWorld: FloatArray,
        inverseBind: FloatArray,
    ): FloatArray = mul(mul(inverseMeshWorld, jointWorld), inverseBind)
}
