package com.rokid.spatialhost.bridge

import kotlinx.coroutines.flow.StateFlow

/**
 * A glasses device (or the emulator) providing head pose and display control.
 *
 * Lifecycle: [connect] → state Connected → [disconnect]. Failures surface as
 * [BridgeState.Error]; callers fall back to the next bridge in the chain.
 */
interface GlassesBridge {
    val state: StateFlow<BridgeState>
    val poses: HeadPoseProvider
    val display: DisplayController

    suspend fun connect()
    fun disconnect()
}
