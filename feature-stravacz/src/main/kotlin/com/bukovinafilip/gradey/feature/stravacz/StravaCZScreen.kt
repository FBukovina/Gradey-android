package com.bukovinafilip.gradey.feature.stravacz

import com.bukovinafilip.gradey.ui.HugeIcons
import com.bukovinafilip.gradey.ui.HugeIcon

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.bukovinafilip.gradey.domain.StravaCZRepository
import com.bukovinafilip.gradey.model.StravaCZMeal
import com.bukovinafilip.gradey.model.StravaCZMenu
import com.bukovinafilip.gradey.model.StravaCZStoredSession
import com.bukovinafilip.gradey.ui.GradeyHero
import com.bukovinafilip.gradey.ui.GradeySectionCard
import com.bukovinafilip.gradey.ui.GradeySpacing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.text.NumberFormat
import java.util.Locale

@Composable
fun StravaCZRoute(
    repository: StravaCZRepository,
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

@Composable
fun StravaCZScreen(
    menu: StravaCZMenu?,
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
    val strings = androidx.compose.ui.platform.LocalResources.current
    var orderedOnly by rememberSaveable { mutableStateOf(false) }
    var pendingMeal by remember { mutableStateOf<StravaCZMeal?>(null) }
    var confirmDisconnect by remember { mutableStateOf(false) }
    val visibleDays = menu?.days.orEmpty().map { day ->
        day.copy(meals = if (orderedOnly) day.meals.filter { it.ordered } else day.meals)
    }.filter { it.meals.isNotEmpty() }

    Surface(modifier = modifier.fillMaxSize(), color = androidx.compose.ui.graphics.Color.Transparent, contentColor = MaterialTheme.colorScheme.onBackground) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().imePadding(),
            contentPadding = PaddingValues(GradeySpacing.lg),
            verticalArrangement = Arrangement.spacedBy(GradeySpacing.lg),
        ) {
            item {
                GradeyHero(strings.getString(R.string.stravacz_meals), strings.getString(R.string.stravacz_your_canteen_one_less_thing_to_think))
            }
            if (isDemo) item {
                Text(strings.getString(R.string.stravacz_demo_canteen_changes_stay_in_this_demo), style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary)
            }
            if (busy) item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
            error?.let { message -> item {
                GradeySectionCard {
                    Text(message, color = MaterialTheme.colorScheme.error)
                    if (session != null) TextButton(onClick = onRefresh, enabled = !busy) { Text(strings.getString(R.string.stravacz_retry_refresh)) }
                }
            } }
            success?.let { message -> item {
                Row(horizontalArrangement = Arrangement.spacedBy(GradeySpacing.sm), verticalAlignment = Alignment.CenterVertically) {
                    HugeIcon(HugeIcons.CheckmarkCircle02, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text(message, color = MaterialTheme.colorScheme.primary)
                }
            } }
            if (session == null && menu == null) {
                item { ConnectCanteenCard(isDemo, busy, onLogin) }
            } else {
                item {
                    GradeySectionCard {
                        Text(session?.canteenName?.takeIf { it.isNotBlank() } ?: strings.getString(R.string.stravacz_strava_cz_canteen_1_s, session?.canteenNumber.orEmpty()),
                            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        session?.let { account ->
                            Text(account.fullName?.takeIf { it.isNotBlank() } ?: account.username,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically) {
                                Column {
                                    Text(strings.getString(R.string.stravacz_account_balance), style = MaterialTheme.typography.labelMedium)
                                    Text(account.balance?.let { formatBalance(it, account.currency, strings) } ?: strings.getString(R.string.stravacz_unavailable),
                                        style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                                }
                                IconButton(onClick = onRefresh, enabled = !busy) {
                                    HugeIcon(HugeIcons.Refresh04, contentDescription = strings.getString(R.string.stravacz_refresh_meals_and_order_status))
                                }
                            }
                        }
                        if (cached) Text(strings.getString(R.string.stravacz_saved_menu_refresh_to_check_the_latest),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = { confirmDisconnect = true }, enabled = !busy) { Text(strings.getString(R.string.stravacz_disconnect_canteen)) }
                    }
                }
                if (menu != null) item {
                    Row(horizontalArrangement = Arrangement.spacedBy(GradeySpacing.sm)) {
                        FilterChip(selected = !orderedOnly, onClick = { orderedOnly = false }, label = { Text(strings.getString(R.string.stravacz_all_meals)) })
                        FilterChip(selected = orderedOnly, onClick = { orderedOnly = true },
                            label = { Text(strings.getString(R.string.stravacz_ordered_1_d, menu.days.sumOf { day -> day.meals.count { it.ordered } })) })
                    }
                }
                if (visibleDays.isEmpty() && !busy) item {
                    GradeySectionCard {
                        HugeIcon(HugeIcons.Restaurant02, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Text(if (orderedOnly) strings.getString(R.string.stravacz_no_meals_ordered_yet) else strings.getString(R.string.stravacz_no_meals_available), style = MaterialTheme.typography.titleMedium)
                        Text(if (orderedOnly) strings.getString(R.string.stravacz_choose_all_meals_to_see_what_s) else strings.getString(R.string.stravacz_your_canteen_hasn_t_published_any_meals),
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (orderedOnly) TextButton(onClick = { orderedOnly = false }) { Text(strings.getString(R.string.stravacz_browse_all_meals)) }
                        else TextButton(onClick = onRefresh, enabled = !busy) { Text(strings.getString(R.string.stravacz_refresh)) }
                    }
                }
                items(visibleDays, key = { it.id }) { day ->
                    GradeySectionCard(title = day.title) {
                        day.meals.forEachIndexed { index, meal ->
                            if (index > 0) HorizontalDivider()
                            MealRow(meal, session?.currency ?: "Kč", busy || cached, onToggle = { pendingMeal = meal })
                        }
                    }
                }
            }
        }
    }

    pendingMeal?.let { meal ->
        val existing = menu?.days?.firstOrNull { day -> day.meals.any { it.id == meal.id } }
            ?.meals?.firstOrNull { it.id != meal.id && it.ordered && it.canModify }
        val replacement = !meal.ordered && existing != null
        AlertDialog(
            onDismissRequest = { pendingMeal = null },
            title = { Text(if (meal.ordered) strings.getString(R.string.stravacz_cancel_this_meal) else if (replacement) strings.getString(R.string.stravacz_replace_your_meal) else strings.getString(R.string.stravacz_order_this_meal)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(GradeySpacing.sm)) {
                    Text(meal.description ?: meal.title)
                    if (replacement) Text(strings.getString(R.string.stravacz_this_will_replace_1_s, existing?.description ?: existing?.title.orEmpty()))
                    meal.price?.let { Text(strings.getString(R.string.stravacz_price_1_s, formatPrice(it, session?.currency ?: "Kč", strings))) }
                    Text(if (isDemo) strings.getString(R.string.stravacz_this_change_affects_the_demo_menu_only) else strings.getString(R.string.stravacz_your_change_will_be_sent_to_your))
                }
            },
            confirmButton = {
                TextButton(onClick = { pendingMeal = null; onSetMeal(meal, !meal.ordered) }, enabled = !busy) {
                    Text(if (meal.ordered) strings.getString(R.string.stravacz_cancel_meal) else if (replacement) strings.getString(R.string.stravacz_replace_meal) else strings.getString(R.string.stravacz_order_meal))
                }
            },
            dismissButton = { TextButton(onClick = { pendingMeal = null }) { Text(strings.getString(R.string.stravacz_keep_current_order)) } },
        )
    }
    if (confirmDisconnect) AlertDialog(
        onDismissRequest = { confirmDisconnect = false },
        title = { Text(strings.getString(R.string.stravacz_disconnect_your_canteen)) },
        text = { Text(strings.getString(R.string.stravacz_your_saved_sign_in_and_menu_will)) },
        confirmButton = { TextButton(onClick = { confirmDisconnect = false; onDisconnect() }, enabled = !busy) { Text(strings.getString(R.string.stravacz_disconnect)) } },
        dismissButton = { TextButton(onClick = { confirmDisconnect = false }) { Text(strings.getString(R.string.stravacz_keep_connected)) } },
    )
}

@Composable
private fun ConnectCanteenCard(isDemo: Boolean, busy: Boolean, onLogin: (String, String, String) -> Unit) {
    val strings = androidx.compose.ui.platform.LocalResources.current
    var canteen by rememberSaveable { mutableStateOf(if (isDemo) "0000" else "") }
    var username by rememberSaveable { mutableStateOf(if (isDemo) "demo" else "") }
    // Passwords are kept only in memory, never in saved UI state.
    var password by remember { mutableStateOf(if (isDemo) "demo" else "") }
    var showPassword by remember { mutableStateOf(false) }
    GradeySectionCard(title = strings.getString(R.string.stravacz_connect_your_canteen)) {
        Text(strings.getString(R.string.stravacz_use_the_same_details_you_use_to), color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(value = canteen, onValueChange = { canteen = it.filter(Char::isDigit).take(10) },
            label = { Text(strings.getString(R.string.stravacz_canteen_number)) }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next))
        OutlinedTextField(value = username, onValueChange = { username = it }, label = { Text(strings.getString(R.string.stravacz_username)) },
            singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next))
        OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text(strings.getString(R.string.stravacz_password)) },
            singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth(),
            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            trailingIcon = {
                IconButton(onClick = { showPassword = !showPassword }) {
                    HugeIcon(if (showPassword) HugeIcons.ViewOff else HugeIcons.View,
                        contentDescription = if (showPassword) strings.getString(R.string.stravacz_hide_password) else strings.getString(R.string.stravacz_show_password))
                }
            })
        Button(onClick = { onLogin(canteen.trim(), username.trim(), password); password = "" },
            enabled = !busy && canteen.isNotBlank() && username.isNotBlank() && password.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
            Text(if (busy) strings.getString(R.string.stravacz_connecting) else strings.getString(R.string.stravacz_connect_canteen))
        }
    }
}

@Composable
private fun MealRow(meal: StravaCZMeal, currency: String, disabled: Boolean, onToggle: () -> Unit) {
    val strings = androidx.compose.ui.platform.LocalResources.current
    Column(verticalArrangement = Arrangement.spacedBy(GradeySpacing.sm)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(GradeySpacing.sm), verticalAlignment = Alignment.CenterVertically) {
            Text(meal.title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (meal.ordered) {
                HugeIcon(HugeIcons.CheckmarkCircle02, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Text(strings.getString(R.string.stravacz_ordered), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
        }
        Text(meal.description ?: meal.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        meal.price?.let { Text(formatPrice(it, currency, strings), style = MaterialTheme.typography.bodyMedium) }
        if (meal.allergens.isNotEmpty()) Text(strings.getString(R.string.stravacz_allergens_1_s, meal.allergens.joinToString(", ")), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        meal.forbiddenAllergens?.takeIf { it.isNotBlank() }?.let {
            Text(strings.getString(R.string.stravacz_allergen_alert_1_s, it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        if (meal.canModify) {
            OutlinedButton(onClick = onToggle, enabled = !disabled, modifier = Modifier.fillMaxWidth()) {
                Text(if (meal.ordered) strings.getString(R.string.stravacz_cancel_order) else strings.getString(R.string.stravacz_order_meal))
            }
        } else {
            Text(strings.getString(R.string.stravacz_changes_unavailable), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
