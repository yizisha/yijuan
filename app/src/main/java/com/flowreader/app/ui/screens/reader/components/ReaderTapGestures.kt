package com.flowreader.app.ui.screens.reader.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.toSize
import com.flowreader.app.domain.model.GestureSettings

/**
 * How long a second tap still counts as a double tap. Deliberately shorter than the platform's
 * 300 ms default: when double tap is enabled at all, the wait is pure latency for the *first* tap.
 */
private const val DOUBLE_TAP_WINDOW_MS = 260L

/**
 * Tap detection for the reading surface, with the single tap dispatched on finger-up.
 *
 * `detectTapGestures(onDoubleTap = …)` cannot do this: it always waits out the double-tap timeout
 * before it can decide that a tap was single, so every tap that shows or hides the control bar cost
 * the user ~300 ms — and an impatient second tap was reinterpreted as a double tap, which *cancelled*
 * the first action entirely. That is why the bar could be hidden but felt impossible to bring back.
 *
 * Here the first tap always fires immediately. A configured double tap is applied on top: the
 * second tap within [DOUBLE_TAP_WINDOW_MS] runs the double-tap action *instead of* a second single
 * action. Because double tap is opt-in (`GestureSettings.doubleTapEnabled`), the default reading
 * experience has no tap delay at all.
 *
 * The down is intentionally left unconsumed so the parent reading surface keeps its own gestures
 * (vertical scroll, pager drag) working; callers must therefore be the only tap detector in their
 * subtree.
 */
internal fun Modifier.readerTapGestures(
    gestures: GestureSettings,
    onTap: (Offset, Size) -> Unit,
    onDoubleTap: () -> Unit
): Modifier = composed {
    val latestTap by rememberUpdatedState(onTap)
    val latestDoubleTap by rememberUpdatedState(onDoubleTap)
    pointerInput(gestures) {
        detectReaderTaps(
            waitForDoubleTap = gestures.waitsForDoubleTap,
            onTap = { offset, size -> latestTap(offset, size) },
            onDoubleTap = { latestDoubleTap() }
        )
    }
}

private suspend fun PointerInputScope.detectReaderTaps(
    waitForDoubleTap: Boolean,
    onTap: (Offset, Size) -> Unit,
    onDoubleTap: () -> Unit
) {
    var lastTapUptime = 0L
    var lastTapPosition = Offset.Zero
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        if (down.isConsumed) return@awaitEachGesture
        var up = down
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
            if (event.changes.size != 1 || change.isConsumed ||
                (change.position - down.position).getDistance() > viewConfiguration.touchSlop ||
                change.uptimeMillis - down.uptimeMillis >= viewConfiguration.longPressTimeoutMillis
            ) {
                lastTapUptime = 0L
                return@awaitEachGesture
            }
            if (!change.pressed) {
                up = change
                break
            }
            if (awaitPointerEvent(PointerEventPass.Final).changes.any { it.isConsumed }) {
                lastTapUptime = 0L
                return@awaitEachGesture
            }
        }
        val isDoubleTap = waitForDoubleTap && lastTapUptime != 0L &&
            up.uptimeMillis - lastTapUptime <= DOUBLE_TAP_WINDOW_MS &&
            (up.position - lastTapPosition).getDistance() <= viewConfiguration.touchSlop * 2
        if (isDoubleTap) {
            lastTapUptime = 0L
            up.consume()
            onDoubleTap()
        } else {
            lastTapUptime = if (waitForDoubleTap) up.uptimeMillis else 0L
            lastTapPosition = up.position
            up.consume()
            onTap(up.position, size.toSize())
        }
    }
}
