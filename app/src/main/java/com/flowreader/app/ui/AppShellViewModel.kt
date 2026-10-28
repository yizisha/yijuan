package com.flowreader.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.flowreader.app.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject

/**
 * Supplies the app shell (theme mode + color source) to [FlowReaderNavHost].
 *
 * Before v52 `Navigation.kt` reached into the settings DataStore directly and parsed the raw
 * `theme` key itself, which made that key load-bearing in two unrelated places.
 */
@HiltViewModel
class AppShellViewModel @Inject constructor(
    settingsRepository: SettingsRepository,
    /** Exposed so the lock screen can hash/verify the PIN without a ViewModel of its own. */
    val lockManager: com.flowreader.app.util.AppLockManager
) : ViewModel() {

    // Reader sliders, widget snapshots and scan state do not change the application shell.
    // Keeping the full AppSettings here invalidated the whole navigation/theme on every edit.
    val appSettings: StateFlow<ShellSettings> = settingsRepository.appSettings
        .map { ShellSettings(it.themeMode, it.colorSource, it.visualStyle, it.language, it.appLockEnabled, it.appLockPinHash) }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ShellSettings())
}

data class ShellSettings(
    val themeMode: com.flowreader.app.domain.model.AppThemeMode = com.flowreader.app.domain.model.AppThemeMode.FOLLOW_SYSTEM,
    val colorSource: com.flowreader.app.domain.model.ColorSource = com.flowreader.app.domain.model.ColorSource.BRAND,
    val visualStyle: com.flowreader.app.domain.model.AppVisualStyle = com.flowreader.app.domain.model.AppVisualStyle.CLEAN_BLUE,
    val language: com.flowreader.app.domain.model.AppLanguage = com.flowreader.app.domain.model.AppLanguage.FOLLOW_SYSTEM,
    val appLockEnabled: Boolean = false,
    val appLockPinHash: String? = null
)
