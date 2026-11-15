package com.rokid.spatialhost.bridge

import kotlinx.coroutines.flow.StateFlow

/**
 * Source of head orientation for the render loop.
 *
 * The render thread polls [latest] once per frame; producers may update from
 * any thread (sensor thread, USB reader thread, main thread).
 */
interface HeadPoseProvider {
    val latest: StateFlow<HeadPose>

    /** Reset the session frame so the current head orientation becomes identity. */
    fun recenter()

    var dof: DoF
}
