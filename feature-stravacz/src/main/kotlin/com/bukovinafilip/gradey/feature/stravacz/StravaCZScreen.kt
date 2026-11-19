package com.bukovinafilip.gradey.feature.stravacz

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.bukovinafilip.gradey.domain.StravaCZRepository
import com.bukovinafilip.gradey.model.StravaCZMeal
import com.bukovinafilip.gradey.model.StravaCZMenu
import com.bukovinafilip.gradey.model.StravaCZStoredSession
import com.bukovinafilip.gradey.ui.EmptyState
import com.bukovinafilip.gradey.ui.GradeyColors
import com.bukovinafilip.gradey.ui.GradeyDialog
import com.bukovinafilip.gradey.ui.GradeyIcon
import com.bukovinafilip.gradey.ui.GradeyLazyColumn
import com.bukovinafilip.gradey.ui.GradeyMetrics
import com.bukovinafilip.gradey.ui.GradeyRadius
import com.bukovinafilip.gradey.ui.GradeySpacing
import com.bukovinafilip.gradey.ui.GradeyTopBar
import com.bukovinafilip.gradey.ui.GradeyType
import com.bukovinafilip.gradey.ui.HugeIcons
import com.bukovinafilip.gradey.ui.InlineNotice
import com.bukovinafilip.gradey.ui.LoadingState
import com.bukovinafilip.gradey.ui.PlainLink
import com.bukovinafilip.gradey.ui.PlainRow
import com.bukovinafilip.gradey.ui.StatTile
import com.bukovinafilip.gradey.ui.gradeyColors
import com.bukovinafilip.gradey.ui.isScrolled
import com.bukovinafilip.gradey.ui.tabular
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Meals tab (iOS `StravaCZView` + `StravaCZViewModel`): checks the saved Strava.cz session, shows the
 * saved menu at once and refreshes it from the canteen. Signed out, it shows the canteen sign-in form.
 * Disconnecting lives in Settings; this tab follows the stored session every time it is shown.
 */
@Composable
fun StravaCZRoute(
    repository: StravaCZRepository,
    onOpenAI: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    isDemo: Boolean = false,
) {
    val strings = LocalResources.current
    val scope = rememberCoroutineScope()
    var session by remember(repository) { mutableStateOf<StravaCZStoredSession?>(null) }
    var menu by remember(repository) { mutableStateOf<StravaCZMenu?>(null) }
    var checking by remember(repository) { mutableStateOf(true) }
    var connecting by remember(repository) { mutableStateOf(false) }
    var refreshing by remember(repository) { mutableStateOf(false) }
    var submittingMealID by remember(repository) { mutableStateOf<Int?>(null) }
    var cached by remember(repository) { mutableStateOf(false) }
    var error by remember(repository) { mutableStateOf<String?>(null) }
    // Read at call time, not from the last composition, so a double tap cannot start two requests.
    fun isBusy() = checking || connecting || refreshing || submittingMealID != null

    suspend fun loadMenu() {
        val (fresh, freshMenu) = repository.loadMenu(forceRefresh = true)
        session = fresh
        menu = freshMenu
        cached = false
    }

    // A failure falls back to whatever is stored: the saved menu (read-only until a refresh
    // succeeds) or, when the session is gone, the sign-in form.
    suspend fun handleError(failure: Exception) {
        if (failure is CancellationException) throw failure
        val stored = try { repository.bootstrapSession() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }
        session = stored
        menu = if (stored == null) null else try { repository.loadCachedMenu() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }
        cached = menu != null
        error = when (failure) {
            is UnknownHostException -> strings.getString(R.string.stravacz_you_appear_to_be_offline_reconnect_and)
            is SocketTimeoutException -> strings.getString(R.string.stravacz_the_canteen_is_taking_too_long_to)
            else -> failure.message?.takeIf { it.isNotBlank() && it.length <= 400 }
                ?: strings.getString(R.string.stravacz_we_couldn_t_reach_strava_cz_please)
        }
    }

    fun refresh() {
        if (isBusy() || session == null) return
        error = null
        refreshing = true
        scope.launch {
            try { loadMenu() } catch (failure: Exception) { handleError(failure) } finally { refreshing = false }
        }
    }

    fun login(canteen: String, username: String, password: String) {
        if (isBusy()) return
        error = null
        connecting = true
        scope.launch {
            try {
                session = repository.login(canteen, username, password)
                menu = null
                cached = false
                connecting = false
                refreshing = true
                loadMenu()
            } catch (failure: Exception) {
                handleError(failure)
            } finally {
                connecting = false
                refreshing = false
            }
        }
    }

    fun setMeal(meal: StravaCZMeal, ordered: Boolean) {
        if (isBusy() || cached) return
        error = null
        submittingMealID = meal.id
        scope.launch {
            try {
                val (fresh, freshMenu) = repository.setMeal(meal, ordered)
                session = fresh
                menu = freshMenu
                cached = false
            } catch (failure: Exception) {
                handleError(failure)
            } finally {
                submittingMealID = null
            }
        }
    }

    LaunchedEffect(repository) {
        try {
            val stored = repository.bootstrapSession()
            session = stored
            if (stored != null) {
                menu = repository.loadCachedMenu()
                cached = menu != null
                checking = false
                refreshing = true
                loadMenu()
            }
        } catch (failure: Exception) {
            handleError(failure)
        } finally {
            checking = false
            refreshing = false
        }
    }

    StravaCZScreen(
        menu = menu,
        onOpenAI = onOpenAI,
        onOpenSettings = onOpenSettings,
        modifier = modifier,
        session = session,
        checking = checking,
        connecting = connecting,
        isRefreshing = refreshing,
        submittingMealID = submittingMealID,
        error = error,
        cached = cached,
        isDemo = isDemo,
        onLogin = ::login,
        onRefresh = ::refresh,
        onSetMeal = ::setMeal,
    )
}

/**
 * Meals tab layout (iOS 2.8.1 `StravaCZView`): the balance with two stats, then one plain section
 * per day whose main courses are ordered or cancelled from a check square. Signed out, it is the
 * canteen sign-in form. The shell draws the background and the bottom bar.
 */
@Composable
fun StravaCZScreen(
    menu: StravaCZMenu?,
    onOpenAI: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    session: StravaCZStoredSession? = null,
    checking: Boolean = false,
    connecting: Boolean = false,
    isRefreshing: Boolean = false,
    submittingMealID: Int? = null,
    error: String? = null,
    cached: Boolean = false,
    isDemo: Boolean = false,
    onLogin: (String, String, String) -> Unit = { _, _, _ -> },
    onRefresh: () -> Unit = {},
    onSetMeal: (StravaCZMeal, Boolean) -> Unit = { _, _ -> },
) {
    val locale = LocalConfiguration.current.locales[0]
    val currency = session?.currency ?: stringResource(R.string.stravacz_default_currency)
    val labels = MealLabels(
        soup = stringResource(R.string.stravacz_meal_type_soup),
        main = stringResource(R.string.stravacz_meal_type_main),
        other = stringResource(R.string.stravacz_meal_type_unknown),
        included = stringResource(R.string.stravacz_included),
    )
    val days = remember(menu, locale, currency, labels) { mealDays(menu, locale, currency, labels, LocalDate.now()) }
    val orderedCount = remember(menu) { menu?.days.orEmpty().sumOf { day -> day.meals.count { it.ordered } } }
    val balance = remember(session?.balance, currency, locale) { session?.balance?.let { formatBalance(it, currency, locale) } }
    var pendingMeal by remember { mutableStateOf<StravaCZMeal?>(null) }
    val signedIn = session != null
    val busy = checking || connecting || isRefreshing || submittingMealID != null
    val listState = rememberLazyListState()
    val scrolled by remember { derivedStateOf { listState.isScrolled } }

    Column(modifier.fillMaxSize()) {
        GradeyTopBar(
            title = stringResource(R.string.stravacz_meals), scrolled = scrolled,
            onOpenAI = onOpenAI, aiDescription = stringResource(R.string.stravacz_gradey_ai_title),
            onRefresh = if (signedIn) onRefresh else null, isRefreshing = isRefreshing,
            refreshDescription = stringResource(R.string.stravacz_refresh_meals_and_order_status),
            onOpenSettings = onOpenSettings, settingsDescription = stringResource(R.string.stravacz_settings_title),
        )
        GradeyLazyColumn(
            Modifier.weight(1f).then(if (!signedIn && !checking) Modifier.imePadding() else Modifier).testTag("mealsList"),
            state = listState, itemSpacing = 0.dp,
        ) {
            if (isDemo) item(key = "demo", contentType = "notice") {
                InlineNotice(
                    stringResource(R.string.stravacz_demo_canteen_changes_stay_in_this_demo), Modifier.padding(bottom = GradeySpacing.lg),
                    color = MaterialTheme.colorScheme.primary, icon = HugeIcons.InformationCircle,
                )
            }
            error?.let { message ->
                item(key = "error", contentType = "notice") {
                    InlineNotice(message, Modifier.padding(bottom = GradeySpacing.lg)) {
                        if (signedIn) PlainLink(stringResource(R.string.stravacz_retry_refresh), onRefresh, enabled = !busy, showsArrow = false)
                    }
                }
            }
            when {
                checking -> item(key = "checking", contentType = "state") {
                    LoadingState(Modifier.padding(top = GradeySpacing.xxl), text = stringResource(R.string.stravacz_checking))
                }
                !signedIn -> item(key = "connect", contentType = "connect") { StravaCZConnect(isDemo, connecting, onLogin) }
                menu == null && isRefreshing -> item(key = "loading", contentType = "state") {
                    LoadingState(Modifier.padding(top = GradeySpacing.xxl), text = stringResource(R.string.stravacz_menu_loading))
                }
                else -> {
                    item(key = "header", contentType = "header") {
                        MealsHeader(
                            balance = balance ?: stringResource(R.string.stravacz_unavailable),
                            orderedCount = orderedCount,
                            canteen = session?.canteenName?.takeIf { it.isNotBlank() } ?: session?.canteenNumber.orEmpty(),
                        )
                    }
                    if (isRefreshing) item(key = "refreshing", contentType = "status") { RefreshingRow() }
                    else if (cached) item(key = "cached", contentType = "status") {
                        Text(
                            stringResource(R.string.stravacz_saved_menu_refresh_to_check_the_latest),
                            Modifier.fillMaxWidth().padding(start = GradeySpacing.xs, end = GradeySpacing.xs, top = GradeySpacing.md),
                            style = GradeyType.caption, color = MaterialTheme.gradeyColors.secondaryLabel,
                        )
                    }
                    if (days.isEmpty()) item(key = "empty", contentType = "state") {
                        EmptyState(
                            title = stringResource(R.string.stravacz_menu_empty_title),
                            message = stringResource(R.string.stravacz_menu_empty_message),
                            icon = HugeIcons.Restaurant02, modifier = Modifier.padding(top = GradeySpacing.lg),
                        )
                    }
                    days.forEach { day ->
                        item(key = "day-${day.id}", contentType = "dayTitle") { MealDayTitle(day) }
                        items(day.meals, key = { it.key }, contentType = { "meal" }) { row ->
                            MealRow(
                                row, submitting = submittingMealID == row.meal.id, enabled = !busy && !cached, stale = cached,
                                onToggle = { pendingMeal = row.meal },
                            )
                        }
                    }
                }
            }
        }
    }

    pendingMeal?.let { meal ->
        // iOS `orderedMainMeal`: ordering a second main course on the same day replaces the first.
        val existing = remember(days, meal.id) {
            days.firstOrNull { day -> day.meals.any { it.meal.id == meal.id } }?.meals
                ?.firstOrNull { it.kind == MealKind.MAIN && it.meal.ordered && it.meal.id != meal.id }
        }
        val replacement = !meal.ordered && existing != null
        val name = meal.description ?: meal.title
        GradeyDialog(
            title = stringResource(
                when {
                    meal.ordered -> R.string.stravacz_cancel_this_meal
                    replacement -> R.string.stravacz_replace_title
                    else -> R.string.stravacz_order_this_meal
                },
            ),
            onDismiss = { pendingMeal = null },
            confirmLabel = stringResource(
                when {
                    meal.ordered -> R.string.stravacz_cancel_meal
                    replacement -> R.string.stravacz_replace_confirm
                    else -> R.string.stravacz_order_meal
                },
            ),
            onConfirm = { pendingMeal = null; onSetMeal(meal, !meal.ordered) },
            dismissLabel = stringResource(if (meal.ordered || replacement) R.string.stravacz_keep_current_order else R.string.stravacz_cancel),
            destructive = meal.ordered, confirmEnabled = !busy,
        ) {
            if (replacement) Text(stringResource(R.string.stravacz_replace_message, existing?.name.orEmpty(), name), style = GradeyType.subheadline)
            else Text(name, style = GradeyType.subheadlineSemibold, color = MaterialTheme.colorScheme.onSurface)
            val price = days.firstNotNullOfOrNull { day -> day.meals.firstOrNull { it.meal.id == meal.id } }?.price
            price?.let { Text(stringResource(R.string.stravacz_price_1_s, it), style = GradeyType.subheadline.tabular()) }
            Text(
                stringResource(if (isDemo) R.string.stravacz_this_change_affects_the_demo_menu_only else R.string.stravacz_your_change_will_be_sent_to_your),
                style = GradeyType.footnote,
            )
        }
    }
}

// MARK: - Header

/** Balance in display 42 over the Ordered and Canteen stats (iOS `StravaCZHeader`). */
@Composable
private fun MealsHeader(balance: String, orderedCount: Int, canteen: String) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = GradeySpacing.xs, vertical = GradeySpacing.xs),
        verticalArrangement = Arrangement.spacedBy(GradeySpacing.lg),
    ) {
        Column(Modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.stravacz_balance), style = GradeyType.subheadlineSemibold, color = MaterialTheme.gradeyColors.secondaryLabel)
            Text(balance, Modifier.testTag("stravaCZBalance"), style = GradeyType.display(42).tabular(), color = MaterialTheme.colorScheme.onSurface)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(GradeySpacing.md)) {
            StatTile(stringResource(R.string.stravacz_ordered), orderedCount.toString(), Modifier.weight(1f), icon = HugeIcons.CheckmarkCircle02)
            StatTile(stringResource(R.string.stravacz_canteen), canteen, Modifier.weight(1f), icon = HugeIcons.Building02)
        }
    }
}

/** Spinner and "Refreshing menu…" while a menu is already on screen (iOS refreshing row). */
@Composable
private fun RefreshingRow() {
    Row(
        Modifier.fillMaxWidth().padding(top = GradeySpacing.md),
        horizontalArrangement = Arrangement.spacedBy(GradeySpacing.sm, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(Modifier.size(16.dp), color = MaterialTheme.colorScheme.primary, strokeWidth = 2.dp)
        Text(stringResource(R.string.stravacz_menu_refreshing), style = GradeyType.subheadline, color = MaterialTheme.gradeyColors.secondaryLabel)
    }
}

// MARK: - Days and meals

/** Weekday in title3 bold with the short date in secondary subheadline on the same baseline. */
@Composable
private fun MealDayTitle(day: MealDayUi) {
    Row(
        Modifier.fillMaxWidth()
            .padding(start = GradeySpacing.xs, end = GradeySpacing.xs, top = GradeySpacing.xl, bottom = GradeySpacing.sm)
            .semantics(mergeDescendants = true) { heading() },
        horizontalArrangement = Arrangement.spacedBy(GradeySpacing.sm),
    ) {
        Text(day.weekday, Modifier.alignByBaseline().weight(1f, fill = false), style = GradeyType.title3, color = MaterialTheme.colorScheme.onSurface)
        day.shortDate?.let { Text(it, Modifier.alignByBaseline(), style = GradeyType.subheadline.tabular(), color = MaterialTheme.gradeyColors.secondaryLabel) }
    }
}

/** One meal (iOS `StravaCZMealRow`): course pill, name, price and allergens, then the order control. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MealRow(row: MealRowUi, submitting: Boolean, enabled: Boolean, stale: Boolean, onToggle: () -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.gradeyColors.secondaryLabel
    val main = row.kind == MealKind.MAIN
    PlainRow {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(GradeySpacing.sm)) {
            MealTypePill(row.typeTitle, if (main) primary else secondary)
            Text(row.name, style = if (main) GradeyType.headline else GradeyType.subheadlineMedium, color = MaterialTheme.colorScheme.onSurface)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(GradeySpacing.sm),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                MealLabel(HugeIcons.CreditCard, row.price, secondary)
                row.allergens?.let { MealLabel(HugeIcons.Alert02, it, secondary) }
            }
            row.forbiddenAllergens?.let {
                Text(stringResource(R.string.stravacz_allergen_alert_1_s, it), style = GradeyType.captionSemibold, color = GradeyColors.Poor)
            }
        }
        when {
            row.meal.canModify -> OrderSquare(row.meal.id, row.meal.ordered, submitting, enabled, stale, onToggle)
            row.meal.ordered -> Box(Modifier.size(40.dp).testTag("mealOrdered-${row.meal.id}"), contentAlignment = Alignment.Center) {
                GradeyIcon(HugeIcons.CheckmarkCircle02, size = 28.dp, tint = primary, contentDescription = stringResource(R.string.stravacz_ordered))
            }
            else -> Spacer(Modifier.size(40.dp))
        }
    }
}

/** Uppercase course capsule: green for main courses, secondary for soups and the rest. */
@Composable
private fun MealTypePill(text: String, color: Color) {
    Text(
        text, Modifier.background(color.copy(alpha = 0.10f), CircleShape).padding(horizontal = GradeySpacing.sm, vertical = 3.dp),
        style = GradeyType.caption2Bold, color = color, maxLines = 1,
    )
}

@Composable
private fun MealLabel(icon: String, text: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GradeySpacing.xs)) {
        GradeyIcon(icon, size = 12.dp, tint = color)
        Text(text, style = GradeyType.caption.tabular(), color = color, maxLines = 2)
    }
}

/**
 * 36 dp order square in a 44 dp target (iOS `StravaCZMealSelectionButton`): an outlined square when
 * free, filled green with an onAccent check when ordered, and a spinner while the change is sent.
 * A saved (stale) menu dims the square until a refresh succeeds.
 */
@Composable
private fun OrderSquare(mealID: Int, ordered: Boolean, submitting: Boolean, enabled: Boolean, stale: Boolean, onToggle: () -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    val outline = MaterialTheme.gradeyColors.secondaryLabel.copy(alpha = 0.55f)
    val label = stringResource(if (ordered) R.string.stravacz_cancel_order else R.string.stravacz_order_meal)
    val shape = RoundedCornerShape(GradeyRadius.xs)
    Box(
        Modifier.size(GradeyMetrics.minTouch).clip(shape)
            .clickable(enabled = enabled && !submitting, role = Role.Button, onClick = onToggle)
            .semantics { contentDescription = label }
            .testTag("mealToggle-$mealID"),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(36.dp).graphicsLayer { alpha = if (stale) 0.45f else 1f }
                .then(if (ordered) Modifier.background(primary, shape) else Modifier.border(2.dp, outline, shape)),
            contentAlignment = Alignment.Center,
        ) {
            when {
                submitting -> CircularProgressIndicator(Modifier.size(18.dp), color = if (ordered) GradeyColors.OnAccent else primary, strokeWidth = 2.dp)
                ordered -> GradeyIcon(HugeIcons.Tick02, size = 15.dp, tint = GradeyColors.OnAccent)
            }
        }
    }
}
