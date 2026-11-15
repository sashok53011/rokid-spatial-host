package com.rokid.spatialhost.bridge

/**
 * Glasses-side display controls (brightness, 2D/3D, volume) where the bridge
 * speaks a device protocol. Emulator/unavailable bridges return false.
 */
interface DisplayController {
    val supported: Boolean
    suspend fun setBrightness(level: Int): Boolean
    suspend fun set3dMode(enabled: Boolean): Boolean
}

object UnavailableDisplay : DisplayController {
    override val supported: Boolean = false
    override suspend fun setBrightness(level: Int): Boolean = false
    override suspend fun set3dMode(enabled: Boolean): Boolean = false
}
