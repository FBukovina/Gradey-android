package com.bukovinafilip.gradey.feature.stravacz

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.bukovinafilip.gradey.domain.StravaCZRepository
import com.bukovinafilip.gradey.model.StravaCZMeal
import com.bukovinafilip.gradey.model.StravaCZMenu
import com.bukovinafilip.gradey.model.StravaCZStoredSession
import com.bukovinafilip.gradey.ui.BrandTextField
import com.bukovinafilip.gradey.ui.DestructiveLink
import com.bukovinafilip.gradey.ui.EmptyState
import com.bukovinafilip.gradey.ui.GradeyColors
import com.bukovinafilip.gradey.ui.GradeyDialog
import com.bukovinafilip.gradey.ui.GradeyIcon
import com.bukovinafilip.gradey.ui.GradeyLazyColumn
import com.bukovinafilip.gradey.ui.GradeyMetrics
import com.bukovinafilip.gradey.ui.GradeyPrimaryButton
import com.bukovinafilip.gradey.ui.GradeyRadius
import com.bukovinafilip.gradey.ui.GradeySpacing
import com.bukovinafilip.gradey.ui.GradeyTopBar
import com.bukovinafilip.gradey.ui.GradeyType
import com.bukovinafilip.gradey.ui.HugeIcons
import com.bukovinafilip.gradey.ui.InlineNotice
import com.bukovinafilip.gradey.ui.LoadingState
import com.bukovinafilip.gradey.ui.PlainLink
import com.bukovinafilip.gradey.ui.PlainRow
import com.bukovinafilip.gradey.ui.PlainSectionTitle
import com.bukovinafilip.gradey.ui.SegmentedControl
import com.bukovinafilip.gradey.ui.StatDivider
import com.bukovinafilip.gradey.ui.StatTile
import com.bukovinafilip.gradey.ui.gradeyColors
import com.bukovinafilip.gradey.ui.isScrolled
import com.bukovinafilip.gradey.ui.tabular
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.text.NumberFormat

/** Meals tab with its own loading: the saved menu first, then a refresh from Strava.cz. */
@Composable
fun StravaCZRoute(
    repository: StravaCZRepository,
    onOpenAI: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    isDemo: Boolean = false,
) {
    val strings = androidx.compose.ui.platform.LocalResources.current
    var session by remember(repository) { mutableStateOf<StravaCZStoredSession?>(null) }
    var menu by remember(repository) { mutableStateOf<StravaCZMenu?>(null) }
    var busy by remember(repository) { mutableStateOf(true) }
    var error by remember(repository) { mutableStateOf<String?>(null) }
    var cached by remember(repository) { mutableStateOf(false) }
    var success by remember(repository) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun refresh() {
        val result = repository.loadMenu(forceRefresh = true)
        session = result.first
        menu = result.second
        cached = false
    }

    suspend fun handleError(failure: Exception) {
        if (failure is CancellationException) throw failure
        session = repository.bootstrapSession()
        if (session == null) menu = null else menu = repository.loadCachedMenu()
        cached = menu != null
        error = when (failure) {
            is UnknownHostException -> strings.getString(R.string.stravacz_you_appear_to_be_offline_reconnect_and)
            is SocketTimeoutException -> strings.getString(R.string.stravacz_the_canteen_is_taking_too_long_to)
            else -> failure.message?.takeIf { it.isNotBlank() && it.length <= 400 }
                ?: strings.getString(R.string.stravacz_we_couldn_t_reach_strava_cz_please)
        }
    }

    fun runAction(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        error = null
        success = null
        scope.launch {
            try {
                action()
            } catch (failure: Exception) {
                handleError(failure)
            } finally {
                busy = false
            }
        }
    }

    LaunchedEffect(repository) {
        try {
            session = repository.bootstrapSession()
            if (session != null) {
                menu = repository.loadCachedMenu()
                cached = menu != null
                refresh()
            }
        } catch (failure: Exception) {
            handleError(failure)
        } finally {
            busy = false
        }
    }

    StravaCZScreen(
        menu = menu,
        onOpenAI = onOpenAI,
        onOpenSettings = onOpenSettings,
        modifier = modifier,
        session = session,
        busy = busy,
        error = error,
        success = success,
        cached = cached,
        isDemo = isDemo,
        onLogin = { canteen, username, password ->
            runAction {
                session = repository.login(canteen, username, password)
                menu = null
                refresh()
            }
        },
        onRefresh = { runAction { refresh() } },
        onSetMeal = { meal, ordered ->
            runAction {
                val result = repository.setMeal(meal, ordered)
                session = result.first
                menu = result.second
                cached = false
                success = if (ordered) strings.getString(R.string.stravacz_meal_ordered) else strings.getString(R.string.stravacz_meal_order_cancelled)
            }
        },
        onDisconnect = {
            runAction {
                repository.logout()
                session = null
                menu = null
                cached = false
            }
        },
    )
}

/**
 * Meals tab (iOS 2.8.1 `StravaCZView`): the balance in a plain header with two stats, then one plain
 * section per day whose meals are ordered or cancelled from a check square. Signed out, it is the
 * canteen sign-in form. The shell draws the background and the bottom bar.
 */
@Composable
fun StravaCZScreen(
    menu: StravaCZMenu?,
    onOpenAI: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    session: StravaCZStoredSession? = null,
    busy: Boolean = false,
    error: String? = null,
    success: String? = null,
    cached: Boolean = false,
    isDemo: Boolean = false,
    onLogin: (String, String, String) -> Unit = { _, _, _ -> },
    onRefresh: () -> Unit = {},
    onSetMeal: (StravaCZMeal, Boolean) -> Unit = { _, _ -> },
    onDisconnect: () -> Unit = {},
) {
    val strings = LocalResources.current
    var filterIndex by rememberSaveable { mutableIntStateOf(0) }
    var pendingMeal by remember { mutableStateOf<StravaCZMeal?>(null) }
    var confirmDisconnect by remember { mutableStateOf(false) }
    val orderedOnly = filterIndex == 1
    val orderedCount = remember(menu) { menu?.days.orEmpty().sumOf { day -> day.meals.count { it.ordered } } }
    val visibleDays = remember(menu, orderedOnly) {
        menu?.days.orEmpty().map { day -> if (orderedOnly) day.copy(meals = day.meals.filter { it.ordered }) else day }.filter { it.meals.isNotEmpty() }
    }
    val signedOut = session == null && menu == null
    val listState = rememberLazyListState()
    val scrolled by remember { derivedStateOf { listState.isScrolled } }
    val currency = session?.currency ?: "Kč"

    Column(modifier.fillMaxSize()) {
        GradeyTopBar(
            title = stringResource(R.string.stravacz_meals), scrolled = scrolled,
            onOpenAI = onOpenAI, aiDescription = stringResource(R.string.stravacz_gradey_ai_title),
            onRefresh = if (signedOut) null else onRefresh, isRefreshing = busy,
            refreshDescription = stringResource(R.string.stravacz_refresh_meals_and_order_status),
            onOpenSettings = onOpenSettings, settingsDescription = stringResource(R.string.stravacz_settings_title),
        )
        GradeyLazyColumn(
            Modifier.weight(1f).then(if (signedOut) Modifier.imePadding() else Modifier).testTag("mealsList"), state = listState, itemSpacing = 0.dp,
        ) {
            if (isDemo) item(key = "demo", contentType = "notice") {
                InlineNotice(
                    stringResource(R.string.stravacz_demo_canteen_changes_stay_in_this_demo), Modifier.padding(bottom = GradeySpacing.lg),
                    color = MaterialTheme.colorScheme.primary, icon = HugeIcons.InformationCircle,
                )
            }
            error?.let { message -> item(key = "error", contentType = "notice") {
                InlineNotice(message, Modifier.padding(bottom = GradeySpacing.lg)) {
                    if (session != null) PlainLink(stringResource(R.string.stravacz_retry_refresh), onRefresh, enabled = !busy, showsArrow = false)
                }
            } }
            success?.let { message -> item(key = "success", contentType = "notice") {
                InlineNotice(message, Modifier.padding(bottom = GradeySpacing.lg), color = MaterialTheme.colorScheme.primary, icon = HugeIcons.CheckmarkCircle02)
            } }
            if (signedOut) {
                if (busy) item(key = "checking", contentType = "state") { LoadingState(Modifier.padding(top = GradeySpacing.xxl)) }
                else item(key = "connect", contentType = "connect") { ConnectCanteen(isDemo, busy, onLogin) }
            } else {
                item(key = "header", contentType = "header") {
                    Column(Modifier.fillMaxWidth().padding(horizontal = GradeySpacing.xs), verticalArrangement = Arrangement.spacedBy(GradeySpacing.lg)) {
                        Column(Modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(stringResource(R.string.stravacz_account_balance), style = GradeyType.subheadlineSemibold, color = MaterialTheme.gradeyColors.secondaryLabel)
                            Text(
                                session?.balance?.let { formatBalance(it, currency, strings) } ?: stringResource(R.string.stravacz_unavailable),
                                style = GradeyType.display(42).tabular(), color = MaterialTheme.colorScheme.onSurface, maxLines = 1,
                            )
                        }
                        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(GradeySpacing.md)) {
                            StatTile(stringResource(R.string.stravacz_ordered), orderedCount.toString(), Modifier.weight(1f), icon = HugeIcons.CheckmarkCircle02)
                            StatDivider()
                            StatTile(
                                stringResource(R.string.stravacz_canteen),
                                session?.canteenName?.takeIf { it.isNotBlank() } ?: session?.canteenNumber.orEmpty(),
                                Modifier.weight(2f), icon = HugeIcons.Building02,
                            )
                        }
                        if (cached) Text(
                            stringResource(R.string.stravacz_saved_menu_refresh_to_check_the_latest),
                            style = GradeyType.caption, color = MaterialTheme.gradeyColors.secondaryLabel,
                        )
                    }
                }
                if (menu != null) item(key = "filter", contentType = "filter") {
                    SegmentedControl(
                        options = listOf(stringResource(R.string.stravacz_all_meals), stringResource(R.string.stravacz_ordered_1_d, orderedCount)),
                        selectedIndex = filterIndex, onSelect = { filterIndex = it },
                        modifier = Modifier.fillMaxWidth().padding(top = GradeySpacing.xl, start = GradeySpacing.xs, end = GradeySpacing.xs),
                    )
                }
                if (visibleDays.isEmpty() && !busy) item(key = "empty", contentType = "state") {
                    EmptyState(
                        title = stringResource(if (orderedOnly) R.string.stravacz_no_meals_ordered_yet else R.string.stravacz_no_meals_available),
                        message = stringResource(if (orderedOnly) R.string.stravacz_choose_all_meals_to_see_what_s else R.string.stravacz_your_canteen_hasn_t_published_any_meals),
                        icon = HugeIcons.Restaurant02, modifier = Modifier.padding(top = GradeySpacing.lg),
                    ) {
                        if (orderedOnly) PlainLink(stringResource(R.string.stravacz_browse_all_meals), { filterIndex = 0 }, showsArrow = false)
                        else PlainLink(stringResource(R.string.stravacz_refresh), onRefresh, enabled = !busy, showsArrow = false)
                    }
                }
                visibleDays.forEach { day ->
                    item(key = "day-${day.id}", contentType = "dayTitle") {
                        PlainSectionTitle(day.title, Modifier.padding(top = GradeySpacing.xl, bottom = GradeySpacing.sm))
                    }
                    items(day.meals, key = { "meal-${day.id}-${it.id}" }, contentType = { "meal" }) { meal ->
                        MealRow(meal, currency, disabled = busy || cached, onToggle = { pendingMeal = meal })
                    }
                }
                item(key = "disconnect", contentType = "action") {
                    DestructiveLink(
                        stringResource(R.string.stravacz_disconnect_canteen), { confirmDisconnect = true },
                        Modifier.padding(top = GradeySpacing.xl), enabled = !busy,
                    )
                }
            }
        }
    }
    pendingMeal?.let { meal ->
        val existing = menu?.days?.firstOrNull { day -> day.meals.any { it.id == meal.id } }
            ?.meals?.firstOrNull { it.id != meal.id && it.ordered && it.canModify }
        val replacement = !meal.ordered && existing != null
        GradeyDialog(
            title = stringResource(if (meal.ordered) R.string.stravacz_cancel_this_meal else if (replacement) R.string.stravacz_replace_your_meal else R.string.stravacz_order_this_meal),
            onDismiss = { pendingMeal = null },
            confirmLabel = stringResource(if (meal.ordered) R.string.stravacz_cancel_meal else if (replacement) R.string.stravacz_replace_meal else R.string.stravacz_order_meal),
            onConfirm = { pendingMeal = null; onSetMeal(meal, !meal.ordered) },
            dismissLabel = stringResource(R.string.stravacz_keep_current_order), destructive = meal.ordered, confirmEnabled = !busy,
        ) {
            Text(meal.description ?: meal.title, style = GradeyType.subheadlineSemibold, color = MaterialTheme.colorScheme.onSurface)
            if (replacement) Text(stringResource(R.string.stravacz_this_will_replace_1_s, existing?.description ?: existing?.title.orEmpty()), style = GradeyType.subheadline)
            meal.price?.let { Text(stringResource(R.string.stravacz_price_1_s, formatPrice(it, currency, strings)), style = GradeyType.subheadline.tabular()) }
            Text(
                stringResource(if (isDemo) R.string.stravacz_this_change_affects_the_demo_menu_only else R.string.stravacz_your_change_will_be_sent_to_your),
                style = GradeyType.footnote,
            )
        }
    }
    if (confirmDisconnect) GradeyDialog(
        title = stringResource(R.string.stravacz_disconnect_your_canteen), text = stringResource(R.string.stravacz_your_saved_sign_in_and_menu_will),
        onDismiss = { confirmDisconnect = false }, confirmLabel = stringResource(R.string.stravacz_disconnect),
        onConfirm = { confirmDisconnect = false; onDisconnect() }, dismissLabel = stringResource(R.string.stravacz_keep_connected),
        destructive = true, confirmEnabled = !busy,
    )
}

/** Canteen sign-in: number, username and password; the password never enters saved state. */
@Composable
private fun ConnectCanteen(isDemo: Boolean, busy: Boolean, onLogin: (String, String, String) -> Unit) {
    var canteen by rememberSaveable { mutableStateOf(if (isDemo) "0000" else "") }
    var username by rememberSaveable { mutableStateOf(if (isDemo) "demo" else "") }
    // Passwords are kept only in memory, never in saved UI state.
    var password by remember { mutableStateOf(if (isDemo) "demo" else "") }
    var showPassword by remember { mutableStateOf(false) }
    val canSubmit = !busy && canteen.isNotBlank() && username.isNotBlank() && password.isNotEmpty()
    val submit = { if (canSubmit) { onLogin(canteen.trim(), username.trim(), password); password = "" } }
    Column(Modifier.fillMaxWidth().padding(horizontal = GradeySpacing.xs), verticalArrangement = Arrangement.spacedBy(GradeySpacing.md)) {
        Text(stringResource(R.string.stravacz_connect_your_canteen), Modifier.semantics { heading() }, style = GradeyType.title2, color = MaterialTheme.colorScheme.onSurface)
        Text(stringResource(R.string.stravacz_use_the_same_details_you_use_to), style = GradeyType.subheadline, color = MaterialTheme.gradeyColors.secondaryLabel)
        BrandTextField(
            value = canteen, onValueChange = { canteen = it.filter(Char::isDigit).take(10) }, modifier = Modifier.fillMaxWidth(),
            label = stringResource(R.string.stravacz_canteen_number), enabled = !busy, keyboardType = KeyboardType.Number, imeAction = ImeAction.Next,
        )
        BrandTextField(
            value = username, onValueChange = { username = it }, modifier = Modifier.fillMaxWidth(),
            label = stringResource(R.string.stravacz_username), enabled = !busy, imeAction = ImeAction.Next,
        )
        BrandTextField(
            value = password, onValueChange = { password = it }, modifier = Modifier.fillMaxWidth(),
            label = stringResource(R.string.stravacz_password), enabled = !busy, keyboardType = KeyboardType.Password, imeAction = ImeAction.Done,
            onImeAction = submit, visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
            trailing = {
                val label = stringResource(if (showPassword) R.string.stravacz_hide_password else R.string.stravacz_show_password)
                Box(
                    Modifier.size(GradeyMetrics.minTouch - GradeySpacing.md * 2 + GradeySpacing.md).clip(CircleShape)
                        .clickable(role = Role.Button) { showPassword = !showPassword }.semantics { contentDescription = label },
                    contentAlignment = Alignment.Center,
                ) { GradeyIcon(if (showPassword) HugeIcons.ViewOff else HugeIcons.View, size = 17.dp, tint = MaterialTheme.gradeyColors.secondaryLabel) }
            },
        )
        GradeyPrimaryButton(
            stringResource(if (busy) R.string.stravacz_connecting else R.string.stravacz_connect_canteen), submit,
            Modifier.padding(top = GradeySpacing.sm), enabled = canSubmit, loading = busy,
        )
    }
}

/** One meal (iOS `StravaCZMealRow`): course pill, name, price and allergens, and the 36 dp order square. */
@Composable
private fun MealRow(meal: StravaCZMeal, currency: String, disabled: Boolean, onToggle: () -> Unit) {
    val strings = LocalResources.current
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.gradeyColors.secondaryLabel
    PlainRow {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(GradeySpacing.sm)) {
            Text(
                meal.title.uppercase(), Modifier.background(primary.copy(alpha = 0.10f), CircleShape).padding(horizontal = GradeySpacing.sm, vertical = 3.dp),
                style = GradeyType.caption2Bold, color = primary, maxLines = 1,
            )
            Text(meal.description ?: meal.title, style = GradeyType.headline, color = MaterialTheme.colorScheme.onSurface)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GradeySpacing.sm)) {
                meal.price?.let { price ->
                    GradeyIcon(HugeIcons.CreditCard, size = 12.dp, tint = secondary)
                    Text(formatPrice(price, currency, strings), style = GradeyType.caption.tabular(), color = secondary, maxLines = 1)
                }
                if (meal.allergens.isNotEmpty()) {
                    GradeyIcon(HugeIcons.Alert02, size = 12.dp, tint = secondary)
                    Text(
                        stringResource(R.string.stravacz_allergens_1_s, meal.allergens.joinToString(", ")), Modifier.weight(1f, fill = false),
                        style = GradeyType.caption.tabular(), color = secondary, maxLines = 2,
                    )
                }
            }
            meal.forbiddenAllergens?.takeIf { it.isNotBlank() }?.let {
                Text(stringResource(R.string.stravacz_allergen_alert_1_s, it), style = GradeyType.captionSemibold, color = GradeyColors.Poor)
            }
            if (!meal.canModify && !meal.ordered) Text(stringResource(R.string.stravacz_changes_unavailable), style = GradeyType.caption, color = secondary)
        }
        when {
            meal.canModify -> {
                val label = stringResource(if (meal.ordered) R.string.stravacz_cancel_order else R.string.stravacz_order_meal)
                val shape = RoundedCornerShape(GradeyRadius.sm)
                Box(
                    Modifier.size(GradeyMetrics.minTouch).clip(shape).clickable(enabled = !disabled, role = Role.Checkbox, onClick = onToggle)
                        .semantics { contentDescription = label }.testTag("mealToggle-${meal.id}"),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier.size(36.dp).then(
                            if (meal.ordered) Modifier.background(primary.copy(alpha = if (disabled) 0.45f else 1f), shape)
                            else Modifier.border(1.5.dp, secondary.copy(alpha = if (disabled) 0.25f else 0.5f), shape),
                        ),
                        contentAlignment = Alignment.Center,
                    ) { if (meal.ordered) GradeyIcon(HugeIcons.Tick02, size = 18.dp, tint = GradeyColors.OnAccent) }
                }
            }
            meal.ordered -> GradeyIcon(HugeIcons.CheckmarkCircle02, size = 22.dp, tint = primary, contentDescription = stringResource(R.string.stravacz_ordered))
        }
    }
}

private fun formatPrice(value: Double, currency: String, strings: android.content.res.Resources): String =
    if (value == 0.0) strings.getString(R.string.stravacz_included) else "${NumberFormat.getNumberInstance(strings.configuration.locales[0]).apply {
        minimumFractionDigits = 2
        maximumFractionDigits = 2
    }.format(value)} $currency"


private fun formatBalance(value: String, currency: String, strings: android.content.res.Resources): String {
    val numeric = value.trim().replace(',', '.').toBigDecimalOrNull()
    val display = numeric?.let {
        NumberFormat.getNumberInstance(strings.configuration.locales[0]).apply {
            minimumFractionDigits = 2
            maximumFractionDigits = 2
        }.format(it)
    } ?: value
    return "$display $currency"
}
