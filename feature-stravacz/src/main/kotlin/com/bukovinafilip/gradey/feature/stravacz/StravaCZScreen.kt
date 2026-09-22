package com.bukovinafilip.gradey.feature.stravacz

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
            is UnknownHostException -> "You appear to be offline. Reconnect and refresh your meals."
            is SocketTimeoutException -> "The canteen is taking too long to respond. Please try again."
            else -> failure.message?.takeIf { it.isNotBlank() && it.length <= 400 }
                ?: "We couldn’t reach Strava.cz. Please try again."
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
                success = if (ordered) "Meal ordered." else "Meal order cancelled."
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
    var orderedOnly by rememberSaveable { mutableStateOf(false) }
    var pendingMeal by remember { mutableStateOf<StravaCZMeal?>(null) }
    var confirmDisconnect by remember { mutableStateOf(false) }
    val visibleDays = menu?.days.orEmpty().map { day ->
        day.copy(meals = if (orderedOnly) day.meals.filter { it.ordered } else day.meals)
    }.filter { it.meals.isNotEmpty() }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().imePadding(),
            contentPadding = PaddingValues(GradeySpacing.lg),
            verticalArrangement = Arrangement.spacedBy(GradeySpacing.lg),
        ) {
            item {
                GradeyHero("Meals", "Your canteen, one less thing to think about.")
            }
            if (isDemo) item {
                Text("Demo canteen · Changes stay in this demo.", style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary)
            }
            if (busy) item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
            error?.let { message -> item {
                GradeySectionCard {
                    Text(message, color = MaterialTheme.colorScheme.error)
                    if (session != null) TextButton(onClick = onRefresh, enabled = !busy) { Text("Retry refresh") }
                }
            } }
            success?.let { message -> item {
                Row(horizontalArrangement = Arrangement.spacedBy(GradeySpacing.sm), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text(message, color = MaterialTheme.colorScheme.primary)
                }
            } }
            if (session == null && menu == null) {
                item { ConnectCanteenCard(isDemo, busy, onLogin) }
            } else {
                item {
                    GradeySectionCard {
                        Text(session?.canteenName?.takeIf { it.isNotBlank() } ?: "Strava.cz canteen ${session?.canteenNumber.orEmpty()}",
                            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        session?.let { account ->
                            Text(account.fullName?.takeIf { it.isNotBlank() } ?: account.username,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically) {
                                Column {
                                    Text("Account balance", style = MaterialTheme.typography.labelMedium)
                                    Text(account.balance?.let { "$it ${account.currency}" } ?: "Unavailable",
                                        style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                                }
                                IconButton(onClick = onRefresh, enabled = !busy) {
                                    Icon(Icons.Default.Refresh, contentDescription = "Refresh meals and order status")
                                }
                            }
                        }
                        if (cached) Text("Saved menu · Refresh to check the latest orders and availability.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = { confirmDisconnect = true }, enabled = !busy) { Text("Disconnect canteen") }
                    }
                }
                if (menu != null) item {
                    Row(horizontalArrangement = Arrangement.spacedBy(GradeySpacing.sm)) {
                        FilterChip(selected = !orderedOnly, onClick = { orderedOnly = false }, label = { Text("All meals") })
                        FilterChip(selected = orderedOnly, onClick = { orderedOnly = true },
                            label = { Text("Ordered (${menu.days.sumOf { day -> day.meals.count { it.ordered } }})") })
                    }
                }
                if (visibleDays.isEmpty() && !busy) item {
                    GradeySectionCard {
                        Icon(Icons.Default.Restaurant, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Text(if (orderedOnly) "No meals ordered yet" else "No meals available", style = MaterialTheme.typography.titleMedium)
                        Text(if (orderedOnly) "Choose All meals to see what’s on the menu." else "Your canteen hasn’t published any meals. Check back later.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (orderedOnly) TextButton(onClick = { orderedOnly = false }) { Text("Browse all meals") }
                        else TextButton(onClick = onRefresh, enabled = !busy) { Text("Refresh") }
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
            title = { Text(if (meal.ordered) "Cancel this meal?" else if (replacement) "Replace your meal?" else "Order this meal?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(GradeySpacing.sm)) {
                    Text(meal.description ?: meal.title)
                    if (replacement) Text("This will replace ${existing?.description ?: existing?.title}.")
                    meal.price?.let { Text("Price: ${formatPrice(it, session?.currency ?: "Kč")}") }
                    Text(if (isDemo) "This change affects the demo menu only." else "Your change will be sent to your canteen immediately.")
                }
            },
            confirmButton = {
                TextButton(onClick = { pendingMeal = null; onSetMeal(meal, !meal.ordered) }, enabled = !busy) {
                    Text(if (meal.ordered) "Cancel meal" else if (replacement) "Replace meal" else "Order meal")
                }
            },
            dismissButton = { TextButton(onClick = { pendingMeal = null }) { Text("Keep current order") } },
        )
    }
    if (confirmDisconnect) AlertDialog(
        onDismissRequest = { confirmDisconnect = false },
        title = { Text("Disconnect your canteen?") },
        text = { Text("Your saved sign-in and menu will be removed from this device. Existing meal orders will stay with your canteen.") },
        confirmButton = { TextButton(onClick = { confirmDisconnect = false; onDisconnect() }, enabled = !busy) { Text("Disconnect") } },
        dismissButton = { TextButton(onClick = { confirmDisconnect = false }) { Text("Keep connected") } },
    )
}

@Composable
private fun ConnectCanteenCard(isDemo: Boolean, busy: Boolean, onLogin: (String, String, String) -> Unit) {
    var canteen by rememberSaveable { mutableStateOf(if (isDemo) "0000" else "") }
    var username by rememberSaveable { mutableStateOf(if (isDemo) "demo" else "") }
    // Passwords are kept only in memory, never in saved UI state.
    var password by remember { mutableStateOf(if (isDemo) "demo" else "") }
    var showPassword by remember { mutableStateOf(false) }
    GradeySectionCard(title = "Connect your canteen") {
        Text("Use the same details you use to sign in to Strava.cz.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(value = canteen, onValueChange = { canteen = it.filter(Char::isDigit).take(10) },
            label = { Text("Canteen number") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next))
        OutlinedTextField(value = username, onValueChange = { username = it }, label = { Text("Username") },
            singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next))
        OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("Password") },
            singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth(),
            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            trailingIcon = {
                IconButton(onClick = { showPassword = !showPassword }) {
                    Icon(if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = if (showPassword) "Hide password" else "Show password")
                }
            })
        Button(onClick = { onLogin(canteen.trim(), username.trim(), password); password = "" },
            enabled = !busy && canteen.isNotBlank() && username.isNotBlank() && password.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
            Text(if (busy) "Connecting…" else "Connect canteen")
        }
    }
}

@Composable
private fun MealRow(meal: StravaCZMeal, currency: String, disabled: Boolean, onToggle: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(GradeySpacing.sm)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(GradeySpacing.sm), verticalAlignment = Alignment.CenterVertically) {
            Text(meal.title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (meal.ordered) {
                Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Text("Ordered", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
        }
        Text(meal.description ?: meal.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        meal.price?.let { Text(formatPrice(it, currency), style = MaterialTheme.typography.bodyMedium) }
        if (meal.allergens.isNotEmpty()) Text("Allergens: ${meal.allergens.joinToString(", ")}", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        meal.forbiddenAllergens?.takeIf { it.isNotBlank() }?.let {
            Text("Allergen alert: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        if (meal.canModify) {
            OutlinedButton(onClick = onToggle, enabled = !disabled, modifier = Modifier.fillMaxWidth()) {
                Text(if (meal.ordered) "Cancel order" else "Order meal")
            }
        } else {
            Text("Changes unavailable", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun formatPrice(value: Double, currency: String): String =
    if (value == 0.0) "Included" else "${NumberFormat.getNumberInstance(Locale.getDefault()).apply {
        minimumFractionDigits = 2
        maximumFractionDigits = 2
    }.format(value)} $currency"
