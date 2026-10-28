package com.flowreader.app.ui.screens.reader.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Velocity

/** One chapter transition per physical gesture; only a fresh DOWN can re-arm it. */
internal class ReaderBoundaryScrollConnection(
    val horizontal: Boolean,
    private val threshold: Float,
    private val canEnterPrevious: () -> Boolean,
    private val canEnterNext: () -> Boolean,
    private val onPrevious: () -> Unit,
    private val onNext: () -> Unit
) : NestedScrollConnection {
    private var distance = 0f
    private var switched = false

    fun beginGesture() {
        distance = 0f
        switched = false
    }

    fun canStart(delta: Float): Boolean = (delta < 0f && canEnterNext()) || (delta > 0f && canEnterPrevious())

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        if (source != NestedScrollSource.UserInput) return Offset.Zero
        val delta = if (horizontal) available.x else available.y
        val consumed = if (horizontal) Offset(delta, 0f) else Offset(0f, delta)
        if (switched) return consumed
        val outward = (delta < 0f && canEnterNext()) || (delta > 0f && canEnterPrevious())
        if (!outward) {
            distance = 0f
            return Offset.Zero
        }
        if (distance * delta < 0f) distance = 0f
        distance += delta
        if (kotlin.math.abs(distance) >= threshold) {
            switched = true
            if (distance < 0f) onNext() else onPrevious()
        }
        // Own outward movement before the child's overscroll effect can absorb it.
        return consumed
    }

    override suspend fun onPreFling(available: Velocity): Velocity {
        val claimed = switched || distance != 0f
        distance = 0f
        return if (!claimed) Velocity.Zero else if (horizontal) Velocity(available.x, 0f) else Velocity(0f, available.y)
    }
}

internal fun Modifier.readerBoundaryGestures(connection: ReaderBoundaryScrollConnection): Modifier = pointerInput(connection) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        connection.beginGesture()
        if (awaitPointerEvent(PointerEventPass.Final).changes.any { it.isConsumed }) return@awaitEachGesture
        var claimed = false
        var previous = down.position
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (event.changes.size != 1 || change.isConsumed) break
            if (!change.pressed) {
                if (claimed) change.consume()
                break
            }
            val total = change.position - down.position
            val along = if (connection.horizontal) total.x else total.y
            val across = if (connection.horizontal) total.y else total.x
            if (!claimed && total.getDistance() > viewConfiguration.touchSlop) {
                if (kotlin.math.abs(along) <= kotlin.math.abs(across) || !connection.canStart(along)) break
                claimed = true
            }
            if (claimed) {
                val delta = change.position - previous
                connection.onPreScroll(delta, NestedScrollSource.UserInput)
                previous = change.position
                change.consume()
            } else {
                // A child selection or ordinary scroll owns the sequence once it consumes it.
                if (awaitPointerEvent(PointerEventPass.Final).changes.any { it.isConsumed }) break
            }
        }
    }
}
