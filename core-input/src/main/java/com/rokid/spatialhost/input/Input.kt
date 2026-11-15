package com.rokid.spatialhost.input

import com.rokid.spatialhost.bridge.HeadPose
import com.rokid.spatialhost.bridge.Quat
import com.rokid.spatialhost.scene.LayoutEngine
import com.rokid.spatialhost.scene.PanelHit

/** Normalized input events fed by the activity (touch, keys, gamepad). */
sealed class InputEvent {
    /** Pointer drag in pixels. */
    data class Drag(val dxPx: Float, val dyPx: Float) : InputEvent()
    data object Tap : InputEvent()
    data object DoubleTap : InputEvent()
    /** Drag finished without a tap (UP after movement) — reset drag state. */
    data object DragEnd : InputEvent()
    data object Back : InputEvent()
    data class Key(val keyCode: Int) : InputEvent()
}

enum class Gesture {
    SELECT,
    RECENTER,
    SWIPE_LEFT,
    SWIPE_RIGHT,
    TOGGLE_HUD,
    BACK,
}

/**
 * Converts raw input events into navigation gestures with basic thresholds.
 * Hand tracking (Max Pro) will plug in here later as another [InputEvent]
 * producer — recognizers stay unchanged.
 */
class GestureRecognizer(
    private val swipeThresholdPx: Float = 120f,
) {
    private var accumulatingDrag = 0f
    private var dragging = false

    /** Returns a gesture or null (event consumed without navigation meaning). */
    fun onEvent(e: InputEvent): Gesture? = when (e) {
        is InputEvent.Drag -> {
            accumulatingDrag += e.dxPx
            dragging = true
            if (kotlin.math.abs(accumulatingDrag) >= swipeThresholdPx) {
                val g = if (accumulatingDrag > 0) Gesture.SWIPE_RIGHT else Gesture.SWIPE_LEFT
                accumulatingDrag = 0f
                dragging = false
                g
            } else {
                null
            }
        }

        InputEvent.Tap -> {
            val g: Gesture? = if (dragging) {
                dragging = false
                accumulatingDrag = 0f
                null
            } else {
                Gesture.SELECT
            }
            g
        }

        InputEvent.DragEnd -> {
            // A finished drag must not swallow the NEXT real click.
            dragging = false
            accumulatingDrag = 0f
            null
        }

        InputEvent.DoubleTap -> Gesture.RECENTER
        InputEvent.Back -> Gesture.BACK
        is InputEvent.Key -> when (e.keyCode) {
            android.view.KeyEvent.KEYCODE_DPAD_LEFT,
            android.view.KeyEvent.KEYCODE_BUTTON_L1,
            -> Gesture.SWIPE_LEFT

            android.view.KeyEvent.KEYCODE_DPAD_RIGHT,
            android.view.KeyEvent.KEYCODE_BUTTON_R1,
            -> Gesture.SWIPE_RIGHT

            android.view.KeyEvent.KEYCODE_DPAD_CENTER,
            android.view.KeyEvent.KEYCODE_ENTER,
            android.view.KeyEvent.KEYCODE_BUTTON_A,
            -> Gesture.SELECT

            android.view.KeyEvent.KEYCODE_R -> Gesture.RECENTER
            android.view.KeyEvent.KEYCODE_H -> Gesture.TOGGLE_HUD
            else -> null
        }
    }
}

/**
 * 3DoF ray from the head through the session frame → nearest panel.
 * Eye is the session origin (3DoF: no translational offset).
 */
class RayCursor(private val layout: LayoutEngine) {

    var lastHit: PanelHit? = null
        private set

    fun update(pose: HeadPose): PanelHit? {
        val q = floatArrayOf(pose.qx, pose.qy, pose.qz, pose.qw)
        val dirArray = Quat.forward(q)
        val dir = com.rokid.spatialhost.scene.Vec3(dirArray[0], dirArray[1], dirArray[2])
        val hit = layout.hitTest(dir)
        lastHit = hit
        return hit
    }
}

/** Focus/selection state shared by HUD and renderer. */
class NavigationState {
    var focusedPanelId: String? = null
    var selectedPanelId: String? = null
    var hudVisible: Boolean = true
}
