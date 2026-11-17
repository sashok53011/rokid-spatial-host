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
import com.github.stephengold.joltjni.Vec3
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
    private var floorBody = -1

    // Grab-and-throw (D3b): while [dragTarget] is set, a critically-damped
    // spring pulls the GRABBED body toward it every step — the same approach
    // Jolt's own drag uses. Releasing keeps the accumulated velocity.
    private var dragTarget: FloatArray? = null
    private var grabbedId: String? = null
    private val bodyMass = mutableMapOf<String, Float>() // kg, per object

    /** Live sandbox bodies by object id. */
    private val bodyIds = mutableMapOf<String, Int>()

    val isInitialized: Boolean get() = system != null
    val isGrabbing: Boolean get() = dragTarget != null
    val bodyCount: Int get() = bodyIds.size

    fun hasBody(id: String): Boolean = id in bodyIds

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
     * Dynamic box approximating one scene object: center at (px,py,pz),
     * half extents (hx,hy,hz). Replaces any body of the same id.
     */
    fun addModelBox(
        id: String,
        px: Float, py: Float, pz: Float,
        hx: Float, hy: Float, hz: Float,
    ): Boolean {
        val ps = system ?: return false
        removeBody(id)
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
        val bodyId = try {
            bodies!!.createAndAddBody(bcs, EActivation.Activate)
        } finally {
            bcs.close()
            shape.close()
        }
        if (bodyId < 0) return false
        bodyIds[id] = bodyId
        Log.i(TAG, "body[$id]=$bodyId at ($px,$py,$pz) half=($hx,$hy,$hz) objects=${bodyIds.size}")
        // Jolt's default density is 1000 kg/m3 — mirror it for the drag spring.
        bodyMass[id] = (2f * hx) * (2f * hy) * (2f * hz) * 1_000f
        return true
    }

    /**
     * D3b: point the grab-spring at a world position (called on every mouse
     * move while an object is held). Call [releaseGrab] to let go.
     */
    fun dragTo(x: Float, y: Float, z: Float) {
        dragTarget = floatArrayOf(x, y, z)
    }

    /** Start dragging the body of object [id]; false if it has no body. */
    fun grab(id: String): Boolean {
        if (id !in bodyIds) return false
        grabbedId = id
        return true
    }

    /** Let go — the body keeps its velocity and flies on (the throw). */
    fun releaseGrab() {
        dragTarget = null
        grabbedId = null
    }

    /** Advance the simulation by [dt] seconds (fixed step,1 collision pass). */
    fun step(dt: Float) {
        val ps = system ?: return
        val temp = tempAllocator ?: return
        val jobs = jobSystem ?: return
        // Grab spring: F = m·(kp·Δx − kd·v), tuned to ~1.5 Hz with slight
        // under-damping so flicks transfer into a real throw on release.
        val tgt = dragTarget
        val gid = grabbedId
        val bi = bodies
        if (tgt != null && gid != null && bi != null) {
            val bid = bodyIds[gid]
            val mass = bodyMass[gid]
            if (bid != null && mass != null) {
                val com = bi.getCenterOfMassPosition(bid)
                val vel = bi.getLinearVelocity(bid)
                var fx = mass * (DRAG_KP * (tgt[0] - com.get(0).toFloat()) - DRAG_KD * vel.x)
                var fy = mass * (DRAG_KP * (tgt[1] - com.get(1).toFloat()) - DRAG_KD * vel.y)
                var fz = mass * (DRAG_KP * (tgt[2] - com.get(2).toFloat()) - DRAG_KD * vel.z)
                // Clamp per axis (~10 g) — a target far away must not explode.
                val lim = mass * 100f
                fx = fx.coerceIn(-lim, lim)
                fy = fy.coerceIn(-lim, lim)
                fz = fz.coerceIn(-lim, lim)
                bi.addForce(bid, Vec3(fx, fy, fz))
            }
        }
        ps.update(dt, 1, temp, jobs)
    }

    /** World position of one object's body, or null. */
    fun bodyPosition(id: String): FloatArray? {
        val bid = bodyIds[id] ?: return null
        val p = bodies?.getPosition(bid) ?: return null
        return floatArrayOf(p.get(0).toFloat(), p.get(1).toFloat(), p.get(2).toFloat())
    }

    /** World rotation of one object's body (quaternion xyzw), or null. */
    fun bodyRotation(id: String): FloatArray? {
        val bid = bodyIds[id] ?: return null
        val q = bodies?.getRotation(bid) ?: return null
        return floatArrayOf(q.x, q.y, q.z, q.w)
    }

    /** Remove one object's body (e.g. on DEL). */
    fun removeBody(id: String) {
        val bid = bodyIds.remove(id) ?: return
        bodyMass.remove(id)
        if (grabbedId == id) releaseGrab()
        try {
            bodies?.removeBody(bid)
        } catch (t: Throwable) {
            Log.w(TAG, "removeBody($id) failed: $t")
        }
    }

    /** Remove ALL object bodies (floor stays). */
    fun removeAllBodies() {
        bodyIds.keys.toList().forEach { removeBody(it) }
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

        /** Grab spring: ω = 2π·1.5 Hz, ζ ≈ 0.96 (kp = ω², kd = 2ζω). */
        private const val DRAG_KP = 88.8f
        private const val DRAG_KD = 17.0f

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
