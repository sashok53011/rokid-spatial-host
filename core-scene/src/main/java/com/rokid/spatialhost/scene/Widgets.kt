package com.rokid.spatialhost.scene

/**
 * Widget rectangle in panel UV space: u grows right, **v grows UP**
 * (v=0 is the panel's bottom edge — the same convention as [PanelHit]).
 */
data class UvRect(val x: Float, val y: Float, val w: Float, val h: Float) {
    fun contains(u: Float, v: Float): Boolean =
        u >= x && u <= x + w && v >= y && v <= y + h
}

/**
 * Elements drawn into a panel's texture and hit-tested by the gaze ray.
 *
 * Widgets carry their mutable state (checked/value) so the app can both
 * dispatch clicks and rebuild the panel image from current state.
 */
sealed class Widget {
    abstract val id: String
    abstract val rect: UvRect

    /** Press → the app dispatches an action by [id]. */
    data class Button(
        override val id: String,
        override val rect: UvRect,
        val label: String,
    ) : Widget()

    /** Press → toggles (the app flips its own flag and redraws). */
    data class Toggle(
        override val id: String,
        override val rect: UvRect,
        val label: String,
        var checked: Boolean = false,
    ) : Widget()

    /**
     * Gaze-friendly slider: a click on the track sets [value] to the
     * clicked position (no drag needed), scaled into [min]..[max].
     * [valueText] is the readout drawn next to the label.
     */
    data class Slider(
        override val id: String,
        override val rect: UvRect,
        val label: String,
        var value: Float = 0f,
        val min: Float = 0f,
        val max: Float = 1f,
        var valueText: String = "",
    ) : Widget() {
        /** Value for a click at panel-u coordinate [u]. */
        fun valueAt(u: Float): Float {
            val t = ((u - rect.x) / rect.w).coerceIn(0f, 1f)
            return min + t * (max - min)
        }
    }

    /** Read-only text row (not clickable). */
    data class Label(
        override val id: String,
        override val rect: UvRect,
        val text: String,
    ) : Widget()
}

/** Topmost interactive widget under the UV point, or null (labels ignored). */
fun SpatialPanel.widgetAt(u: Float, v: Float): Widget? =
    widgets.lastOrNull { it !is Widget.Label && it.rect.contains(u, v) }
