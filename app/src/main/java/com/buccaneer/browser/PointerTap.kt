package com.buccaneer.browser

import android.view.InputDevice
import android.view.MotionEvent

/** Real WebView input, not JavaScript-dispatched events. Keep touch behavior as the default. */
internal object PointerTap {
    fun event(downAt: Long, at: Long, action: Int, x: Float, y: Float, mouse: Boolean): MotionEvent {
        val properties = MotionEvent.PointerProperties().apply {
            id = 0
            toolType = if (mouse) MotionEvent.TOOL_TYPE_MOUSE else MotionEvent.TOOL_TYPE_FINGER
        }
        val coordinates = MotionEvent.PointerCoords().apply {
            this.x = x; this.y = y
            pressure = if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) 0f else 1f
            size = 1f
        }
        return MotionEvent.obtain(downAt, at, action, 1, arrayOf(properties), arrayOf(coordinates),
            0, if (mouse && action == MotionEvent.ACTION_DOWN) MotionEvent.BUTTON_PRIMARY else 0,
            1f, 1f, 0, 0, if (mouse) InputDevice.SOURCE_MOUSE else InputDevice.SOURCE_TOUCHSCREEN, 0)
    }
}
