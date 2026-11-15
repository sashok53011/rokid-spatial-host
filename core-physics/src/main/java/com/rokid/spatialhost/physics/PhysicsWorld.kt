package com.rokid.spatialhost.physics

import android.util.Log
import com.github.stephengold.joltjni.BodyInterface
import com.github.stephengold.joltjni.BodyCreationSettings
import com.github.stephengold.joltjni.BoxShape
import com.github.stephengold.joltjni.BroadPhaseLayerInterfaceTable
import com.github.stephengold.joltjni.JobSystemThreadPool
import com.github.stephengold.joltjni.Jolt
import com.github.stephengold.joltjni.JoltPhysicsObject
import com.github.stephengold.joltjni.ObjectLayerPairFilterTable
import com.github.stephengold.joltjni.ObjectVsBroadPhaseLayerFilterTable
import com.github.stephengold.joltjni.PhysicsSystem
import com.github.stephengold.joltjni.Quat
import com.github.stephengold.joltjni.RVec3
import com.github.stephengold.joltjni.TempAllocatorMalloc
import com.github.stephengold.joltjni.enumerate.EActivation
import com.github.stephengold.joltjni.enumerate.EMotionType

/**
 * D3: rigid-body physics world (Jolt Physics through the jolt-jni bindings).
 *
 * Single-threaded by design: init/step/read must all happen on the same
 * thread (the app's main thread — a few bodies are far below its budget).
 *
 * Scene convention: the model body is an axis-aligned BOX approximating the
 * imported model's bounds; the floor is a static slab. Body transforms come
 * back as position (m) + quaternion (xyzw) and are applied by the app as
 * T(pos)·R(quat)·S·T(−center).
 */
class PhysicsWorld {

    private var tempAllocator: TempAllocatorMalloc? = null
    private var jobSystem: JobSystemThreadPool? = null
    private var system: PhysicsSystem? = null
    private var bodies: BodyInterface? = null
    private var modelBody = -1
    private var floorBody = -1

    val isInitialized: Boolean get() = system != null
    val hasModelBody: Boolean get() = modelBody >= 0

    /** Full Jolt bring-up (idempotent; follows the jolt-jni tutorial). */
    fun ensureInit(): Boolean {
        if (system != null) return true
        return try {
            ensureNatives()
            val temp = TempAllocatorMalloc()
            val jobs = JobSystemThreadPool(
                Jolt.cMaxPhysicsJobs,
                Jolt.cMaxPhysicsBarriers,
                Runtime.getRuntime().availableProcessors(),
            )
            // Moving and static bodies in separate object layers; both map
            // to broadphase layer 0; static-static pairs are skipped.
            val ovo = ObjectLayerPairFilterTable(NUM_OBJ_LAYERS)
            ovo.enableCollision(LAYER_MOVING, LAYER_MOVING)
            ovo.enableCollision(LAYER_MOVING, LAYER_STATIC)
            ovo.disableCollision(LAYER_STATIC, LAYER_STATIC)
            val layerMap = BroadPhaseLayerInterfaceTable(NUM_OBJ_LAYERS, NUM_BP_LAYERS)
            layerMap.mapObjectToBroadPhaseLayer(LAYER_MOVING, 0)
            layerMap.mapObjectToBroadPhaseLayer(LAYER_STATIC, 0)
            val ovb = ObjectVsBroadPhaseLayerFilterTable(
                layerMap, NUM_BP_LAYERS, ovo, NUM_OBJ_LAYERS,
            )
            val ps = PhysicsSystem()
            ps.init(MAX_BODIES, 0, MAX_BODY_PAIRS, MAX_CONTACTS, layerMap, ovb, ovo)
            tempAllocator = temp
            jobSystem = jobs
            system = ps
            bodies = ps.bodyInterface
            Log.i(TAG, "Jolt ready (maxBodies=$MAX_BODIES)")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Jolt init failed", t)
            false
        }
    }

    /** Static ground slab: half extents (8, 0.5, 8), top surface at [topY]. */
    fun addFloor(topY: Float, centerX: Float, centerZ: Float): Boolean {
        val ps = system ?: return false
        if (floorBody >= 0) return true
        val shape = BoxShape(8f, 0.5f, 8f)
        val bcs = BodyCreationSettings(
            shape,
            RVec3(centerX.toDouble(), (topY - 0.5).toDouble(), centerZ.toDouble()),
            Quat(0f, 0f, 0f, 1f),
            EMotionType.Static,
            LAYER_STATIC,
        )
        floorBody = try {
            bodies!!.createAndAddBody(bcs, EActivation.DontActivate)
        } finally {
            // The body keeps its own shape/settings refs; free the transient
            // wrappers explicitly (no Cleaner below API 33).
            bcs.close()
            shape.close()
        }
        Log.i(TAG, "floor body id=$floorBody topY=$topY")
        return floorBody >= 0
    }

    /**
     * Dynamic box approximating the scene model: center at (px,py,pz),
     * half extents (hx,hy,hz). Replaces any previous model body.
     */
    fun addModelBox(
        px: Float, py: Float, pz: Float,
        hx: Float, hy: Float, hz: Float,
    ): Boolean {
        val ps = system ?: return false
        removeModel()
        val shape = BoxShape(hx, hy, hz)
        val bcs = BodyCreationSettings(
            shape,
            RVec3(px.toDouble(), py.toDouble(), pz.toDouble()),
            Quat(0f, 0f, 0f, 1f),
            EMotionType.Dynamic,
            LAYER_MOVING,
        )
        bcs.setFriction(0.6f)
        bcs.setRestitution(0.15f)
        modelBody = try {
            bodies!!.createAndAddBody(bcs, EActivation.Activate)
        } finally {
            bcs.close()
            shape.close()
        }
        Log.i(TAG, "model body id=$modelBody at ($px,$py,$pz) half=($hx,$hy,$hz)")
        return modelBody >= 0
    }

    /** Advance the simulation by [dt] seconds (fixed step,1 collision pass). */
    fun step(dt: Float) {
        val ps = system ?: return
        val temp = tempAllocator ?: return
        val jobs = jobSystem ?: return
        ps.update(dt, 1, temp, jobs)
    }

    /** World position of the model body (xyz, meters), or null. */
    fun modelPosition(): FloatArray? {
        if (modelBody < 0) return null
        val p = bodies?.getPosition(modelBody) ?: return null
        return floatArrayOf(p.get(0).toFloat(), p.get(1).toFloat(), p.get(2).toFloat())
    }

    /** World rotation of the model body (quaternion xyzw), or null. */
    fun modelRotation(): FloatArray? {
        if (modelBody < 0) return null
        val q = bodies?.getRotation(modelBody) ?: return null
        return floatArrayOf(q.x, q.y, q.z, q.w)
    }

    fun removeModel() {
        if (modelBody >= 0) {
            try {
                bodies?.removeBody(modelBody)
            } catch (t: Throwable) {
                Log.w(TAG, "removeBody failed: $t")
            }
            modelBody = -1
        }
    }

    companion object {
        private const val TAG = "PhysicsWorld"

        private const val NUM_OBJ_LAYERS = 2
        private const val NUM_BP_LAYERS = 1
        private const val LAYER_MOVING = 0
        private const val LAYER_STATIC = 1
        private const val MAX_BODIES = 5_000
        private const val MAX_BODY_PAIRS = 65_536
        private const val MAX_CONTACTS = 20_480

        private var nativesLoaded = false

        /**
         * The jolt-jni AAR ships libjoltjni.so but no Java class loads it,
         * and Jolt needs its global bring-up exactly once per process.
         */
        private fun ensureNatives() {
            if (nativesLoaded) return
            System.loadLibrary("joltjni")
            // Auto-freeing needs java.lang.ref.Cleaner (API 33+); on older
            // Android we close transient wrappers explicitly instead.
            try {
                JoltPhysicsObject.startCleaner()
            } catch (t: Throwable) {
                Log.i(TAG, "cleaner unavailable (${t.javaClass.simpleName}) — explicit close() mode")
            }
            Jolt.registerDefaultAllocator()
            Jolt.installDefaultAssertCallback()
            Jolt.installDefaultTraceCallback()
            check(Jolt.newFactory()) { "Jolt.newFactory() failed" }
            Jolt.registerTypes()
            nativesLoaded = true
            Log.i(TAG, "libjoltjni loaded, types registered")
        }
    }
}
