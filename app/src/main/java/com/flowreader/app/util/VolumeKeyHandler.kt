package com.flowreader.app.util

/**
 * Dispatches hardware volume key events for reader page-turning.
 */
object VolumeKeyHandler {
    @Volatile
    var onVolumeDown: (() -> Boolean)? = null

    @Volatile
    var onVolumeUp: (() -> Boolean)? = null

    fun handleVolumeDown(): Boolean = onVolumeDown?.invoke() ?: false

    fun handleVolumeUp(): Boolean = onVolumeUp?.invoke() ?: false
}
