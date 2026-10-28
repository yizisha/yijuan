package com.flowreader.app.core.util

import com.flowreader.app.domain.model.GestureAction
import com.flowreader.app.domain.model.ReadingSettings
import com.flowreader.app.domain.model.TapZoneMode

/**
 * Pure reader behaviour rules.
 *
 * Both helpers here back settings that were persisted but never consulted before v52: the tap
 * zones ignored everything except `tapZoneRatio`, and auto night mode was sampled once during
 * composition so 19:00 never actually arrived while the reader was open.
 */
object ReaderBehavior {

    const val NIGHT_START_HOUR = 19
    const val NIGHT_END_HOUR = 7

    /** Zone fractions for the one-handed layouts, as a share of the screen width. */
    const val ONE_HAND_EDGE_BAND = 0.15f
    const val ONE_HAND_BULK_BAND = 0.85f

    /** True inside the 19:00–07:00 window that auto night mode switches on. */
    fun isNightHour(hourOfDay: Int): Boolean {
        val hour = hourOfDay.coerceIn(0, 23)
        return hour >= NIGHT_START_HOUR || hour < NIGHT_END_HOUR
    }

    /**
     * Which configured action a tap at [offsetX] maps to.
     *
     * [TapZoneMode.LEFT_HAND] / [TapZoneMode.RIGHT_HAND] hand most of the width to "next page" and
     * keep the menu band on the far edge of the holding hand, so a one-handed reader never has to
     * stretch. [TapZoneMode.SYMMETRIC] keeps the historic behaviour: edge hot zones sized by the
     * configured edge widths, or symmetric bands sized by `tapZoneRatio` when edge gestures are off.
     */
    fun tapAction(offsetX: Float, width: Float, settings: ReadingSettings): GestureAction {
        if (width <= 0f) return settings.gestureSettings.middleTapAction
        val gestures = settings.gestureSettings
        val fraction = (offsetX / width).coerceIn(0f, 1f)
        when (gestures.tapZoneMode) {
            // Thumb edge = previous page, the whole bulk = next page, the far edge opens the menu.
            TapZoneMode.LEFT_HAND -> return when {
                fraction < ONE_HAND_EDGE_BAND -> gestures.leftTapAction
                fraction > ONE_HAND_BULK_BAND -> gestures.middleTapAction
                else -> gestures.rightTapAction
            }

            TapZoneMode.RIGHT_HAND -> return when {
                fraction > ONE_HAND_BULK_BAND -> gestures.leftTapAction
                fraction < ONE_HAND_EDGE_BAND -> gestures.middleTapAction
                else -> gestures.rightTapAction
            }

            TapZoneMode.SYMMETRIC -> Unit
        }
        val leftBound: Float
        val rightBound: Float
        if (gestures.edgeGestureEnabled) {
            leftBound = width * (gestures.leftEdgeWidth.coerceIn(0, 45) / 100f)
            rightBound = width * (1f - gestures.rightEdgeWidth.coerceIn(0, 45) / 100f)
        } else {
            val halfZone = width * settings.tapZoneRatio.coerceIn(0.1f, 0.45f)
            leftBound = width / 2f - halfZone
            rightBound = width / 2f + halfZone
        }
        return when {
            offsetX < leftBound -> gestures.leftTapAction
            offsetX > rightBound -> gestures.rightTapAction
            else -> gestures.middleTapAction
        }
    }

    /** Horizontal fling to action. Negative [dragAmount] means a leftward swipe. */
    fun swipeAction(dragAmount: Float, settings: ReadingSettings, threshold: Float = 80f): GestureAction {
        val gestures = settings.gestureSettings
        return when {
            dragAmount <= -threshold -> gestures.swipeLeftAction
            dragAmount >= threshold -> gestures.swipeRightAction
            else -> GestureAction.NONE
        }
    }

    /**
     * Warm-overlay opacity for the 色温 setting (0–100).
     *
     * Capped at [MAX_COLOR_TEMPERATURE_ALPHA]: beyond that the warm wash starts to cost text
     * contrast, which the palettes are otherwise asserted to keep above WCAG AA. Zero is off.
     */
    fun colorTemperatureAlpha(value: Int): Float {
        val clamped = value.coerceIn(0, 100)
        return if (clamped == 0) 0f else (clamped / 100f) * MAX_COLOR_TEMPERATURE_ALPHA
    }

    const val MAX_COLOR_TEMPERATURE_ALPHA = 0.28f
}
