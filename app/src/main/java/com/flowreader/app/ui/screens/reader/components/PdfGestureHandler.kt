package com.flowreader.app.ui.screens.reader.components

import android.content.Context
import android.os.SystemClock
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.compose.ui.geometry.Offset

/** One owner for a pointer stream, retained when the visible PDF page changes. */
internal class PdfGestureHandler(
    context: Context,
    private val swipeThreshold: Float,
    private val isZoomed: () -> Boolean,
    private val onSwipe: (forward: Boolean) -> Unit,
    private val onTap: (Offset) -> Unit,
    private val onDoubleTap: () -> Unit,
    private val onTransform: (Float, Offset) -> Unit
) {
    private var travel = 0f
    private var turned = false
    private var multiplePointers = false

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            multiplePointers = true
            return true
        }
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            onTransform(detector.scaleFactor, Offset.Zero)
            return true
        }
    })

    private val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (!multiplePointers) onTap(Offset(e.x, e.y))
            return true
        }
        override fun onDoubleTap(e: MotionEvent): Boolean {
            onDoubleTap()
            return true
        }
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (isZoomed()) onTransform(1f, Offset(-distanceX, -distanceY))
            else if (!multiplePointers && !turned) {
                travel -= distanceX
                if (kotlin.math.abs(travel) > swipeThreshold) {
                    turned = true
                    if (com.flowreader.app.BuildConfig.DEBUG) android.util.Log.d("PdfGesture", "turn travel=$travel handler=${System.identityHashCode(this@PdfGestureHandler)}")
                    onSwipe(travel < 0f)
                }
            }
            return true
        }
    }).apply { setIsLongpressEnabled(false) }

    fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { travel = 0f; turned = false; multiplePointers = false }
            MotionEvent.ACTION_POINTER_DOWN -> multiplePointers = true
        }
        scaleDetector.onTouchEvent(event)
        detector.onTouchEvent(event)
        if (com.flowreader.app.BuildConfig.DEBUG && event.actionMasked != MotionEvent.ACTION_MOVE) {
            android.util.Log.d("PdfGesture", "action=${event.actionMasked} travel=$travel turned=$turned handler=${System.identityHashCode(this)}")
        }
        return true
    }

    fun cancel() {
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
        try { onTouchEvent(event) } finally { event.recycle() }
    }
}
