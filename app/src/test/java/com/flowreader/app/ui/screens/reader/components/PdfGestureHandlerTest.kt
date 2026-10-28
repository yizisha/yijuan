package com.flowreader.app.ui.screens.reader.components

import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.compose.ui.geometry.Offset
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class PdfGestureHandlerTest {
    private class Fixture {
        var zoomed = false
        var taps = 0
        var doubleTaps = 0
        val turns = mutableListOf<Boolean>()
        val pans = mutableListOf<Offset>()
        val handler = PdfGestureHandler(RuntimeEnvironment.getApplication(), 100f, { zoomed },
            { turns += it }, { taps++ }, { doubleTaps++ }, { _, pan -> pans += pan })

        fun event(down: Long, action: Int, x: Float) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, 300f, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { handler.onTouchEvent(event) } finally { event.recycle() }
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
        }
        fun drag(from: Float, to: Float) {
            val down = SystemClock.uptimeMillis()
            event(down, MotionEvent.ACTION_DOWN, from)
            for (step in 1..12) event(down, MotionEvent.ACTION_MOVE, from + (to - from) * step / 12)
            event(down, MotionEvent.ACTION_UP, to)
        }
        fun tap() {
            val down = SystemClock.uptimeMillis()
            event(down, MotionEvent.ACTION_DOWN, 200f)
            event(down, MotionEvent.ACTION_UP, 200f)
        }
    }

    @Test fun repeatedRoundTripsTurnExactlyOncePerGesture() {
        val fixture = Fixture()
        repeat(3) { fixture.drag(600f, 100f); fixture.drag(100f, 600f) }
        assertEquals(listOf(true, false, true, false, true, false), fixture.turns)
    }

    @Test fun shortAndCancelledDragsDoNotLeakDistanceIntoTheNextGesture() {
        val fixture = Fixture()
        repeat(4) { fixture.drag(300f, 240f) }
        assertTrue(fixture.turns.isEmpty())
        val down = SystemClock.uptimeMillis()
        fixture.event(down, MotionEvent.ACTION_DOWN, 300f)
        fixture.event(down, MotionEvent.ACTION_MOVE, 250f)
        fixture.event(down, MotionEvent.ACTION_CANCEL, 250f)
        fixture.drag(600f, 100f)
        assertEquals(listOf(true), fixture.turns)
    }

    @Test fun draggingAZoomedPagePansWithoutTurningAndNormalDragWorksAfterwards() {
        val fixture = Fixture()
        fixture.zoomed = true
        fixture.drag(600f, 100f)
        assertTrue(fixture.turns.isEmpty())
        assertTrue(fixture.pans.any { it.x < 0 })
        fixture.zoomed = false
        fixture.drag(600f, 100f)
        assertEquals(listOf(true), fixture.turns)
    }

    @Test fun doubleTapDoesNotAlsoPerformASingleTap() {
        val fixture = Fixture()
        fixture.tap()
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
        assertEquals(1, fixture.taps)
        fixture.tap()
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(80))
        fixture.tap()
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
        assertEquals(1, fixture.doubleTaps)
        assertEquals(1, fixture.taps)
    }

    @Test fun closingTheViewerCancelsAPendingSingleTap() {
        val fixture = Fixture()
        fixture.tap()
        fixture.handler.cancel()
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
        assertEquals(0, fixture.taps)
    }
}
