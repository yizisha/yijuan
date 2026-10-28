package com.flowreader.app.ui.screens.reader.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderBoundaryGesturesTest {
    @Test
    fun bothAxesAreSymmetricAndOneGestureCannotSkipChapters() = runTest {
        for (horizontal in listOf(true, false)) {
            var index = 1
            val connection = ReaderBoundaryScrollConnection(horizontal, 56f, { index > 0 }, { index < 2 }, { index-- }, { index++ })
            fun delta(value: Float) = if (horizontal) Offset(value, 0f) else Offset(0f, value)
            connection.beginGesture()
            assertEquals(delta(-60f), connection.onPreScroll(delta(-60f), NestedScrollSource.UserInput))
            assertEquals(2, index)
            connection.onPreScroll(delta(100f), NestedScrollSource.UserInput)
            connection.onPreFling(Velocity(1000f, 1000f))
            assertEquals(2, index)
            connection.beginGesture()
            connection.onPreScroll(delta(60f), NestedScrollSource.UserInput)
            assertEquals(1, index)
        }
    }

    @Test
    fun smallPullsDoNotAccumulateAcrossGesturesAndInertiaCannotSwitch() {
        var switches = 0
        val connection = ReaderBoundaryScrollConnection(true, 56f, { true }, { true }, { switches++ }, { switches++ })
        repeat(5) {
            connection.beginGesture()
            connection.onPreScroll(Offset(-30f, 0f), NestedScrollSource.UserInput)
        }
        connection.onPreScroll(Offset(-300f, 0f), NestedScrollSource.SideEffect)
        assertEquals(0, switches)
    }

    @Test
    fun firstAndLastBookBoundariesDoNotConsumeNormalInput() {
        val connection = ReaderBoundaryScrollConnection(true, 56f, { false }, { false }, { error("previous") }, { error("next") })
        connection.beginGesture()
        assertEquals(Offset.Zero, connection.onPreScroll(Offset(-100f, 0f), NestedScrollSource.UserInput))
        assertEquals(Offset.Zero, connection.onPreScroll(Offset(100f, 0f), NestedScrollSource.UserInput))
    }
}
