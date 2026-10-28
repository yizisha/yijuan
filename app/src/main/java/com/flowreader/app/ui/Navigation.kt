package com.flowreader.app.ui

import androidx.annotation.StringRes
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.flowreader.app.R
import com.flowreader.app.core.designsystem.component.FlowIcons
import com.flowreader.app.core.designsystem.theme.FlowLocaleProvider
import com.flowreader.app.core.designsystem.theme.FlowTheme
import com.flowreader.app.core.designsystem.token.FlowMotion
import com.flowreader.app.ui.screens.bookdetail.BookDetailScreen
import com.flowreader.app.ui.screens.bookdetail.BookStatsScreen
import com.flowreader.app.ui.screens.continue_reading.ContinueScreen
import com.flowreader.app.ui.screens.library.LibraryScreen
import com.flowreader.app.ui.screens.notes.NotesScreen
import com.flowreader.app.ui.screens.opds.OpdsScreen
import com.flowreader.app.ui.screens.reader.ReaderScreen
import com.flowreader.app.ui.screens.readinglist.ReadingListsScreen
import com.flowreader.app.ui.screens.search.SearchScreen
import com.flowreader.app.ui.screens.settings.AboutSettingsScreen
import com.flowreader.app.ui.screens.settings.AppearanceSettingsScreen
import com.flowreader.app.ui.screens.settings.DataSettingsScreen
import com.flowreader.app.ui.screens.settings.EyeCareSettingsScreen
import com.flowreader.app.ui.screens.settings.PageTurnSettingsScreen
import com.flowreader.app.ui.screens.settings.ReadingCategory
import com.flowreader.app.ui.screens.settings.ReadingMenuScreen
import com.flowreader.app.ui.screens.settings.ReminderSettingsScreen
import com.flowreader.app.ui.screens.settings.SecuritySettingsScreen
import com.flowreader.app.ui.screens.settings.SettingsCategory
import com.flowreader.app.ui.screens.settings.SettingsScreen
import com.flowreader.app.ui.screens.settings.SettingsViewModel
import com.flowreader.app.ui.screens.settings.ShelfSettingsScreen
import com.flowreader.app.ui.screens.settings.ThemeSettingsScreen
import com.flowreader.app.ui.screens.settings.TxtSettingsScreen
import com.flowreader.app.ui.screens.settings.TypographySettingsScreen
import com.flowreader.app.ui.screens.stats.StatsScreen

/**
 * Route titles are resource ids, not literals: the bottom bar has to follow the v53 in-app
 * language switch, and a `String` baked into a sealed object would freeze at first composition.
 */
sealed class Screen(val route: String, @StringRes val titleRes: Int, val icon: ImageVector? = null) {
    object Library : Screen("library", R.string.nav_shelf, FlowIcons.Shelf)
    object Continue : Screen("continue", R.string.continue_title, Icons.Default.History)
    object Stats : Screen("stats", R.string.stats_title, FlowIcons.Statistics)
    object Settings : Screen("settings", R.string.settings_title, FlowIcons.Settings)

    /** Notes is primary; the list and OPDS implementations remain for stored-data compatibility. */
    object Notes : Screen("notes", R.string.ui_nav_notes, FlowIcons.Notes)
    object ReadingLists : Screen("reading_lists", R.string.reading_lists_title)
    object Opds : Screen("opds", R.string.opds_title)

    object Search : Screen("search?query={query}", R.string.search_title) {
        fun createRoute(query: String = "") = if (query.isBlank()) "search" else "search?query=${android.net.Uri.encode(query)}"
    }

    object BookDetail : Screen("book_detail/{bookId}", R.string.book_detail_title) {
        fun createRoute(bookId: Long) = "book_detail/$bookId"
    }

    object Reader : Screen(
        "reader/{bookId}?chapterIndex={chapterIndex}&locatorOffset={locatorOffset}",
        R.string.reader_title
    ) {
        /**
         * @param locatorOffset 搜索结果命中的**章内 UTF-16 偏移**；负数表示只需跳到章首。
         *
         * 为什么要带偏移：旧实现只传 chapterIndex，点搜索结果永远跳到章首，
         * 用户还得自己找那一句（主计划第 9 节要求结果包含 documentKey + UTF-16 范围）。
         * 该偏移来自 [SearchHitProjector] 的**已验证**投影，不是 FTS 分词位置。
         */
        fun createRoute(bookId: Long, chapterIndex: Int = -1, locatorOffset: Int = -1): String {
            if (chapterIndex < 0) return "reader/$bookId"
            val base = "reader/$bookId?chapterIndex=$chapterIndex"
            return if (locatorOffset >= 0) "$base&locatorOffset=$locatorOffset" else base
        }
    }
}

/** The four fixed destinations, independent of legacy navigation preferences. */
internal val primaryDestinations = listOf(Screen.Library, Screen.Stats, Screen.Notes, Screen.Settings)

/** Material guidance: 600dp is the compact/medium breakpoint (rail instead of bottom bar). */
private const val MEDIUM_MIN_WIDTH_DP = 600

@Composable
fun FlowReaderNavHost(
    initialImportUri: android.net.Uri? = null,
    viewModel: AppShellViewModel = hiltViewModel(),
    onInitialImportConsumed: () -> Unit = {}
) {
    val appSettings by viewModel.appSettings.collectAsStateWithLifecycle()

    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val visibleEntries by navController.visibleEntries.collectAsStateWithLifecycle()
    val currentDestination = navBackStackEntry?.destination

    val navItems = primaryDestinations

    fun isTabSelected(screen: Screen): Boolean = isPrimaryDestinationSelected(screen, currentDestination?.route) ||
        currentDestination?.hierarchy?.any { it.route == screen.route } == true

    val showBottomBar = shouldShowPrimaryNavigation(currentDestination?.route, visibleEntries.map { it.destination.route })

    val screenWidthDp = LocalConfiguration.current.screenWidthDp

    FlowLocaleProvider(languageTag = appSettings.language.tag) {
        FlowTheme(themeMode = appSettings.themeMode, colorSource = appSettings.colorSource, visualStyle = appSettings.visualStyle) {
            if (screenWidthDp >= MEDIUM_MIN_WIDTH_DP) {
                // Medium / expanded (v55): a navigation rail keeps the reading surface wide.
                Row(modifier = Modifier.fillMaxSize()) {
                    if (showBottomBar) {
                        FlowPrimaryRail(isSelected = ::isTabSelected, onNavigate = { screen ->
                            if (!isTabSelected(screen)) {
                                navController.navigate(screen.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        })
                    }
                    Box(modifier = Modifier.weight(1f)) {
                        FlowNavHost(navController, initialImportUri, onInitialImportConsumed)
                    }
                }
            } else {
                FlowShellScaffold(
                    bottomBar = {
                        if (showBottomBar) {
                            FlowPrimaryNavigation(isSelected = ::isTabSelected, onNavigate = { screen ->
                                if (!isTabSelected(screen)) {
                                    navController.navigate(screen.route) {
                                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            })
                        }
                    }
                ) {
                    FlowNavHost(navController, initialImportUri, onInitialImportConsumed)
                }
            }
        }
    }
}

/** Secondary settings and shelf history keep the corresponding primary destination selected. */
internal fun isPrimaryDestinationSelected(screen: Screen, route: String?): Boolean = route == screen.route ||
    (screen == Screen.Settings && route?.startsWith("${Screen.Settings.route}/") == true) ||
    (screen == Screen.Library && route == Screen.Continue.route)

/** The returning navigation must not resize a reader that NavHost still draws for a frame. */
internal fun shouldShowPrimaryNavigation(route: String?, visibleRoutes: List<String?>): Boolean =
    primaryDestinations.any { isPrimaryDestinationSelected(it, route) } && Screen.Reader.route !in visibleRoutes

/** The same fixed destinations and semantic selection colours on expanded screens. */
@Composable
internal fun FlowPrimaryRail(isSelected: (Screen) -> Boolean, onNavigate: (Screen) -> Unit) {
    NavigationRail(containerColor = MaterialTheme.colorScheme.surface) {
        primaryDestinations.forEach { screen ->
            NavigationRailItem(
                selected = isSelected(screen),
                onClick = { onNavigate(screen) },
                colors = NavigationRailItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    indicatorColor = Color.Transparent,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                ),
                icon = { screen.icon?.let { Icon(it, contentDescription = null, modifier = Modifier.size(24.dp)) } },
                label = { Text(stringResource(screen.titleRes), style = MaterialTheme.typography.labelMedium) }
            )
        }
    }
}

/** Fixed primary destinations. Shelf history and nested settings retain their parent selection. */
@Composable
internal fun FlowPrimaryNavigation(isSelected: (Screen) -> Boolean, onNavigate: (Screen) -> Unit) {
    Column(Modifier.background(MaterialTheme.colorScheme.surface)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
        Row(Modifier.navigationBarsPadding().heightIn(min = 64.dp)) {
            primaryDestinations.forEach { screen ->
                val selected = isSelected(screen)
                val color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                Column(
                    Modifier.weight(1f).heightIn(min = 64.dp)
                        .selectable(selected, role = Role.Tab, onClick = { onNavigate(screen) }).padding(vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp)
                ) {
                    screen.icon?.let { Icon(it, null, Modifier.size(22.dp), tint = color) }
                    Text(
                        stringResource(screen.titleRes),
                        style = MaterialTheme.typography.labelSmall,
                        color = color,
                        fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal
                    )
                }
            }
        }
    }
}

/**
 * Compact-layout shell: bottom navigation plus the nav-host content.
 *
 * The window-inset contract lives here, and it is the fix for issue #6. Every screen below
 * carries its own `Scaffold` + `TopAppBar`, and a `TopAppBar` already applies the status-bar
 * inset itself. With the default [ScaffoldDefaults.contentWindowInsets] this shell padded the
 * status bar away *and* left the inset unconsumed, so each screen's `TopAppBar` applied it a
 * second time: one extra status-bar band of blank space above every title, and the same
 * doubling on the navigation bar, which is what truncated content at the bottom.
 *
 * Zero insets here plus [consumeWindowInsets] means each inset is applied exactly once, by the
 * screen — and app bars draw under the transparent status bar, which is what `enableEdgeToEdge()`
 * in `MainActivity` was always for. [consumeWindowInsets] is still required for the bottom: the
 * padding the shell spends there is the measured `NavigationBar` height, which already contains
 * the navigation-bar inset, so screens must not add it again.
 *
 * Guarded by `ShellWindowInsetsTest`; keep the two in sync.
 */
@Composable
internal fun FlowShellScaffold(bottomBar: @Composable () -> Unit, content: @Composable () -> Unit) {
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = bottomBar
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .padding(paddingValues)
                .consumeWindowInsets(paddingValues)
        ) {
            content()
        }
    }
}

@Composable
private fun FlowNavHost(
    navController: androidx.navigation.NavHostController,
    initialImportUri: android.net.Uri? = null,
    onInitialImportConsumed: () -> Unit = {}
) {
    NavHost(
        navController = navController,
        startDestination = Screen.Library.route,
        enterTransition = {
            destinationEnterTransition(initialState.destination.route, targetState.destination.route)
        },
        exitTransition = {
            destinationExitTransition(initialState.destination.route, targetState.destination.route)
        }
    ) {
        composable(Screen.Library.route) {
            LibraryScreen(
                initialImportUri = initialImportUri,
                onInitialImportConsumed = onInitialImportConsumed,
                onImportSettingsClick = { navController.navigate(SettingsCategory.SHELF.route) },
                onSearchClick = { navController.navigate(Screen.Search.createRoute()) },
                onBookClick = { bookId ->
                    navController.navigate(Screen.BookDetail.createRoute(bookId))
                },
                onContinueReading = { bookId ->
                    navController.navigate(Screen.Reader.createRoute(bookId))
                },
                onSettingsClick = { navController.navigate(Screen.Settings.route) },
                onNotesClick = { navController.navigate(Screen.Notes.route) },
                onReadingListsClick = { navController.navigate(Screen.ReadingLists.route) },
                onStatsClick = { navController.navigate(Screen.Stats.route) },
                onOpdsClick = { navController.navigate(Screen.Opds.route) },
                onHistoryClick = { navController.navigate(Screen.Continue.route) }
            )
        }

        composable(Screen.Continue.route) {
            ContinueScreen(
                onBookClick = { bookId ->
                    navController.navigate(Screen.BookDetail.createRoute(bookId))
                },
                onContinueReading = { bookId ->
                    navController.navigate(Screen.Reader.createRoute(bookId))
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable(Screen.Stats.route) {
            StatsScreen(onOpenGoals = {
                navController.navigate(Screen.Settings.route) { launchSingleTop = true }
                navController.navigate(ReadingCategory.GOALS.route) { launchSingleTop = true }
            })
        }

        composable(
            route = Screen.Search.route,
            arguments = listOf(
                navArgument("query") {
                    type = NavType.StringType
                    defaultValue = ""
                }
            )
        ) { backStackEntry ->
            val query = backStackEntry.arguments?.getString("query").orEmpty()
            SearchScreen(
                initialQuery = query,
                onBackClick = { navController.popBackStack() },
                onBookClick = { bookId ->
                    navController.navigate(Screen.BookDetail.createRoute(bookId))
                },
                onChapterClick = { bookId, chapterIndex, locatorOffset ->
                    navController.navigate(Screen.Reader.createRoute(bookId, chapterIndex, locatorOffset))
                }
            )
        }

        composable(Screen.Notes.route) {
            NotesScreen(
                onBackClick = { navController.popBackStack() },
                onOpenPassage = { bookId, chapterIndex ->
                    navController.navigate(Screen.Reader.createRoute(bookId, chapterIndex))
                }
            )
        }

        composable(Screen.ReadingLists.route) {
            ReadingListsScreen(
                onBackClick = { navController.popBackStack() },
                onOpenBook = { bookId ->
                    navController.navigate(Screen.BookDetail.createRoute(bookId))
                }
            )
        }

        composable(Screen.Opds.route) {
            OpdsScreen(onBackClick = { navController.popBackStack() })
        }

        composable(
            route = Screen.BookDetail.route,
            arguments = listOf(
                navArgument("bookId") { type = NavType.LongType }
            )
        ) { backStackEntry ->
            val bookId = backStackEntry.arguments?.getLong("bookId") ?: 0L
            BookDetailScreen(
                bookId = bookId,
                onBackClick = { navController.popBackStack() },
                onReadClick = { id, chapterIndex ->
                    navController.navigate(Screen.Reader.createRoute(id, chapterIndex))
                },
                onOpenStats = { navController.navigate("book_stats/$bookId") },
                onOpenBookDetail = { navController.navigate(Screen.BookDetail.createRoute(it)) }
            )
        }

        composable(
            route = Screen.Reader.route,
            arguments = listOf(
                navArgument("bookId") { type = NavType.LongType },
                navArgument("chapterIndex") {
                    type = NavType.IntType
                    defaultValue = -1
                },
                // 搜索命中的章内 UTF-16 偏移（来自 SearchHitProjector 的已验证投影）。
                // 默认 -1 表示「只跳到章首」；阅读器会用它在进入时精准滚动到命中行。
                navArgument("locatorOffset") {
                    type = NavType.IntType
                    defaultValue = -1
                }
            )
        ) {
            ReaderScreen(onBackClick = { navController.popBackStack() }, onOpenNextVolume = { bookId ->
                navController.navigate(Screen.Reader.createRoute(bookId)) {
                    popUpTo(Screen.Reader.route) { inclusive = true }
                    launchSingleTop = true
                }
            })
        }

        composable(
            route = "book_stats/{bookId}",
            arguments = listOf(navArgument("bookId") { type = NavType.LongType })
        ) {
            BookStatsScreen(onBackClick = { navController.popBackStack() })
        }

        composable(Screen.Settings.route) {
            SettingsScreen(
                onOpenCategory = { category -> navController.navigate(category.route) }
            )
        }

        // The four shared reading pages plus goals use one SettingsViewModel.
        // Register this menu only once; legacy routes below map to the corresponding new page.
        composable(SettingsCategory.READ.route) { entry ->
            val parentEntry = remember(entry) { navController.getBackStackEntry(Screen.Settings.route) }
            val settingsViewModel: SettingsViewModel = hiltViewModel(parentEntry)
            ReadingMenuScreen(
                onBack = { navController.popBackStack() },
                onOpenCategory = { category -> navController.navigate(category.route) },
                viewModel = settingsViewModel
            )
        }

        // Every settings sub-page shares ONE SettingsViewModel scoped to the 设置 destination. Each
        // page used to build its own, so opening a sub-page started five fresh DataStore
        // collections and the first frame waited on them — the visible hitch on entering and
        // leaving a settings page.
        settingsSubRoutes.forEach { category ->
            composable(category.route) { entry ->
                val parentEntry = remember(entry) { navController.getBackStackEntry(Screen.Settings.route) }
                val settingsViewModel: SettingsViewModel = hiltViewModel(parentEntry)
                val onBack: () -> Unit = { navController.popBackStack() }
                when (category) {
                    SettingsCategory.APPEARANCE -> AppearanceSettingsScreen(onBack, settingsViewModel)
                    SettingsCategory.SHELF -> ShelfSettingsScreen(
                        onBack,
                        settingsViewModel,
                        onOpenTxt = { navController.navigate(SettingsCategory.TXT.route) }
                    )
                    SettingsCategory.READ -> Unit
                    SettingsCategory.NAVIGATION -> AppearanceSettingsScreen(onBack, settingsViewModel)
                    SettingsCategory.REMINDER -> ReminderSettingsScreen(onBack, settingsViewModel)
                    SettingsCategory.DATA -> DataSettingsScreen(onBack, settingsViewModel)
                    SettingsCategory.TXT -> TxtSettingsScreen(onBack, settingsViewModel)
                    SettingsCategory.SECURITY -> SecuritySettingsScreen(onBack, settingsViewModel)
                    SettingsCategory.ABOUT -> AboutSettingsScreen(onBack)
                }
            }
        }

        readingSubRoutes.forEach { category ->
            composable(category.route) { entry ->
                val parentEntry = remember(entry) { navController.getBackStackEntry(Screen.Settings.route) }
                val settingsViewModel: SettingsViewModel = hiltViewModel(parentEntry)
                val onBack: () -> Unit = { navController.popBackStack() }
                when (category) {
                    ReadingCategory.TYPOGRAPHY -> TypographySettingsScreen(onBack, settingsViewModel)
                    ReadingCategory.FONT -> TypographySettingsScreen(onBack, settingsViewModel)
                    ReadingCategory.PAGE_TURN -> PageTurnSettingsScreen(onBack, settingsViewModel)
                    ReadingCategory.GESTURE -> PageTurnSettingsScreen(onBack, settingsViewModel)
                    ReadingCategory.THEME -> ThemeSettingsScreen(onBack, settingsViewModel)
                    ReadingCategory.EYE_CARE -> EyeCareSettingsScreen(onBack, settingsViewModel)
                    ReadingCategory.GOALS -> ReminderSettingsScreen(onBack, settingsViewModel)
                }
            }
        }
    }
}

/** Reading text must not dissolve over the returning shelf while the main navigation reappears. */
internal fun destinationEnterTransition(from: String?, to: String?): EnterTransition =
    if (from == Screen.Reader.route || to == Screen.Reader.route ||
        primaryDestinations.any { it.route == from } && primaryDestinations.any { it.route == to }) {
        EnterTransition.None
    } else {
        fadeIn(animationSpec = tween(FlowMotion.STANDARD_MS, easing = FlowMotion.standard)) +
            slideInHorizontally(
                animationSpec = tween(FlowMotion.STANDARD_MS, easing = FlowMotion.standard),
                initialOffsetX = { it / 24 }
            )
    }

internal fun destinationExitTransition(from: String?, to: String?): ExitTransition =
    if (from == Screen.Reader.route || to == Screen.Reader.route ||
        primaryDestinations.any { it.route == from } && primaryDestinations.any { it.route == to }) {
        ExitTransition.None
    } else {
        fadeOut(animationSpec = tween(FlowMotion.STANDARD_MS, easing = FlowMotion.standard)) +
            slideOutHorizontally(
                animationSpec = tween(FlowMotion.STANDARD_MS, easing = FlowMotion.standard),
                targetOffsetX = { -it / 24 }
            )
    }

/**
 * The settings destinations that render a plain detail page, in menu order.
 *
 * `SettingsCategory.READ` is deliberately absent: it is a second-level *menu* with its own
 * composable above. Listing it here as well registered `settings/read` twice, and the second
 * (empty) registration won — which is why 设置 → 阅读 opened a blank page.
 */
private val settingsSubRoutes = listOf(
    SettingsCategory.APPEARANCE,
    SettingsCategory.SHELF,
    SettingsCategory.NAVIGATION,
    SettingsCategory.REMINDER,
    SettingsCategory.DATA,
    SettingsCategory.TXT,
    SettingsCategory.SECURITY,
    SettingsCategory.ABOUT
)

/** The reader setting pages behind 设置 → 阅读, in menu order. */
private val readingSubRoutes = listOf(
    ReadingCategory.TYPOGRAPHY,
    ReadingCategory.FONT,
    ReadingCategory.PAGE_TURN,
    ReadingCategory.GESTURE,
    ReadingCategory.THEME,
    ReadingCategory.EYE_CARE,
    ReadingCategory.GOALS
)
