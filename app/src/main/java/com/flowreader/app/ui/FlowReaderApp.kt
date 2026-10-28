package com.flowreader.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Re-lock after this long in the background; a quick app switch should not demand a fingerprint. */
private const val RELOCK_AFTER_BACKGROUND_MS = 5 * 60 * 1000L

@Composable
fun FlowReaderRoot(
    initialImportUri: android.net.Uri? = null,
    viewModel: AppShellViewModel = hiltViewModel()
) {
    val appSettings by viewModel.appSettings.collectAsStateWithLifecycle()
    var initialImportConsumed by rememberSaveable(initialImportUri) { mutableStateOf(false) }
    var unlocked by remember { mutableStateOf(false) }
    var backgroundedAt by remember { mutableStateOf(0L) }

    // Lock again when the app has been away for a while, so "app lock" means more than "once per
    // process". A short trip to another app keeps the session.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> backgroundedAt = System.currentTimeMillis()
                Lifecycle.Event.ON_START -> {
                    if (backgroundedAt != 0L &&
                        System.currentTimeMillis() - backgroundedAt > RELOCK_AFTER_BACKGROUND_MS
                    ) {
                        unlocked = false
                    }
                    backgroundedAt = 0L
                }

                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (appSettings.appLockEnabled && !unlocked) {
        AppLockScreen(
            pinHash = appSettings.appLockPinHash,
            lockManager = viewModel.lockManager,
            onUnlocked = { unlocked = true }
        )
    } else {
        FlowReaderNavHost(
            initialImportUri = initialImportUri.takeUnless { initialImportConsumed },
            onInitialImportConsumed = { initialImportConsumed = true }
        )
    }
}
