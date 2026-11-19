package com.bukovinafilip.gradey

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.bukovinafilip.gradey.ui.EmptyState
import com.bukovinafilip.gradey.ui.GradeyMetrics
import com.bukovinafilip.gradey.ui.GradeyTonalButton
import com.bukovinafilip.gradey.ui.GradeyType
import com.bukovinafilip.gradey.ui.Hairline
import com.bukovinafilip.gradey.ui.HugeIcons
import com.bukovinafilip.gradey.ui.InlineNotice
import com.bukovinafilip.gradey.ui.LoadingState
import com.bukovinafilip.gradey.ui.ModalHeader
import com.bukovinafilip.gradey.ui.PlainLink
import com.bukovinafilip.gradey.ui.SettingsActionRow
import com.bukovinafilip.gradey.ui.SettingsBackground
import com.bukovinafilip.gradey.ui.SettingsGroup
import com.bukovinafilip.gradey.ui.SettingsIcon
import com.bukovinafilip.gradey.ui.SettingsRowDivider
import com.bukovinafilip.gradey.ui.gradeyColors
import com.bukovinafilip.gradey.ui.tabular
import com.bukovinafilip.gradey.ui.GradeySpacing
import com.revenuecat.purchases.CustomerInfo
import com.revenuecat.purchases.Offerings
import com.revenuecat.purchases.ProductType
import com.revenuecat.purchases.PurchaseParams
import com.revenuecat.purchases.Purchases
import com.revenuecat.purchases.PurchasesErrorCode
import com.revenuecat.purchases.getCustomerInfoWith
import com.revenuecat.purchases.getOfferingsWith
import com.revenuecat.purchases.logInWith
import com.revenuecat.purchases.logOutWith
import com.revenuecat.purchases.purchaseWith
import com.revenuecat.purchases.restorePurchasesWith
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.DateFormat
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

private val supportPackages = linkedMapOf(
    "support_standard_monthly" to R.string.app_support_monthly,
    "support_standard_yearly" to R.string.app_support_yearly,
    "support_plus_monthly" to R.string.app_plus_monthly,
    "support_plus_yearly" to R.string.app_plus_yearly,
)
private val tipPackages = listOf("tip_small", "tip_medium", "tip_large")
private class SupportRequestOwner(var active: Boolean = true, var job: Job? = null)
private class PurchaseCancelled : Exception()
private class PurchasePending : Exception()

/**
 * Support Gradey: plans and one-off tips through Google Play, the active plan, and restoring
 * purchases. A full-screen surface with its own background; [onBack] leaves it.
 */
@Composable
internal fun SupportScreen(accountID: String?, isDemo: Boolean, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val configuration = LocalConfiguration.current
    val activity = remember(context) { context.activity() }
    val scope = rememberCoroutineScope()
    val owner = remember(accountID, isDemo) { SupportRequestOwner() }
    var packages by remember(owner) { mutableStateOf<List<com.revenuecat.purchases.Package>>(emptyList()) }
    var busy by remember(owner) { mutableStateOf(false) }
    var error by remember(owner) { mutableStateOf<String?>(null) }
    var message by remember(owner) { mutableStateOf<String?>(null) }
    var customerInfo by remember(owner) { mutableStateOf<CustomerInfo?>(null) }
    val configured = !isDemo && !accountID.isNullOrBlank() && BuildConfig.REVENUECAT_ANDROID_KEY.isNotBlank() && Purchases.isConfigured
    val entitlement = customerInfo?.entitlements?.active?.let { it["support_plus"] ?: it["support"] }

    DisposableEffect(owner) {
        onDispose { owner.active = false; owner.job?.cancel() }
    }

    fun action(block: suspend () -> Unit) {
        if (!configured || busy || !owner.active) return
        busy = true; error = null; message = null
        owner.job = scope.launch {
            try {
                SupportPurchaseIdentity.withAccount(checkNotNull(accountID)) { block() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: PurchaseCancelled) {
                // User cancellation is not an error.
            } catch (_: PurchasePending) {
                if (owner.active) message = resources.getString(R.string.app_purchase_pending)
            } catch (failure: Exception) {
                if (owner.active) error = failure.message ?: resources.getString(R.string.app_purchase_error)
            } finally {
                if (owner.active) busy = false
            }
        }
    }

    fun load() = action {
        val info = revenueCatResult<CustomerInfo> { success, failure ->
            Purchases.sharedInstance.getCustomerInfoWith(onError = { failure(resources.getString(R.string.app_support_status_error)) }, onSuccess = success)
        }
        val offerings = revenueCatResult<Offerings> { success, failure ->
            Purchases.sharedInstance.getOfferingsWith(onError = { failure(resources.getString(R.string.app_support_options_error)) }, onSuccess = success)
        }
        currentCoroutineContext().ensureActive()
        if (owner.active) {
            customerInfo = info
            // Packages abstract platform product IDs. Only expose Gradey's configured support catalog.
            val plans = offerings.all["support"]?.availablePackages.orEmpty()
            val tips = (offerings.all["tips"] ?: offerings.current)?.availablePackages.orEmpty()
            packages = supportPackages.keys.mapNotNull { id -> plans.firstOrNull { it.identifier == id && it.product.type == ProductType.SUBS } } +
                tipPackages.mapNotNull { id -> tips.firstOrNull { it.identifier == id && it.product.type == ProductType.INAPP } }
        }
    }

    fun manageSubscriptions() {
        val url = customerInfo?.managementURL ?: Uri.parse("https://play.google.com/store/account/subscriptions")
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url)) }
            .onFailure { error = resources.getString(R.string.app_subscription_browser) }
    }

    LaunchedEffect(owner, configured) { load() }
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val secondary = MaterialTheme.gradeyColors.secondaryLabel
    fun purchase(item: com.revenuecat.purchases.Package) = action {
        val info = suspendCoroutine<CustomerInfo> { continuation ->
            Purchases.sharedInstance.purchaseWith(PurchaseParams.Builder(checkNotNull(activity), item).build(),
                onError = { failure, cancelled -> continuation.resumeWithException(when {
                    cancelled -> PurchaseCancelled()
                    failure.code == PurchasesErrorCode.PaymentPendingError -> PurchasePending()
                    else -> IllegalStateException(resources.getString(R.string.app_purchase_failed))
                }) },
                onSuccess = { _, info -> continuation.resume(info) })
        }
        currentCoroutineContext().ensureActive()
        if (owner.active) { customerInfo = info; message = resources.getString(R.string.app_purchase_thanks) }
    }
    fun restore() = action {
        val info = revenueCatResult<CustomerInfo> { success, failure ->
            Purchases.sharedInstance.restorePurchasesWith(onError = { failure(resources.getString(R.string.app_restore_error)) }, onSuccess = success)
        }
        currentCoroutineContext().ensureActive()
        if (owner.active) {
            customerInfo = info
            message = if (info.entitlements.active.keys.any { it == "support" || it == "support_plus" }) resources.getString(R.string.app_restore_success)
                else resources.getString(R.string.app_restore_empty)
        }
    }
    SettingsBackground(modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.align(Alignment.TopCenter).widthIn(max = GradeyMetrics.contentMaxWidth).fillMaxSize().windowInsetsPadding(WindowInsets.statusBars).testTag("supportTipsScreen"),
            verticalArrangement = Arrangement.spacedBy(GradeySpacing.xl),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = GradeySpacing.lg, bottom = GradeySpacing.xxl + bottomInset),
        ) {
            item(key = "header", contentType = "header") {
                ModalHeader(stringResource(R.string.app_support_title), onBack, closeDescription = stringResource(R.string.app_dismiss))
            }
            item(key = "intro", contentType = "intro") {
                Row(Modifier.fillMaxWidth().padding(horizontal = GradeySpacing.xs), horizontalArrangement = Arrangement.spacedBy(GradeySpacing.md)) {
                    SettingsIcon(HugeIcons.Favourite)
                    Text(stringResource(R.string.app_support_subtitle), Modifier.weight(1f), style = GradeyType.subheadline, color = secondary)
                }
            }
            if (!configured) item(key = "unavailable", contentType = "notice") {
                InlineNotice(
                    stringResource(when {
                        isDemo -> R.string.app_support_demo
                        accountID.isNullOrBlank() -> R.string.app_support_signin
                        else -> R.string.app_support_unconfigured
                    }),
                    color = secondary, icon = HugeIcons.InformationCircle,
                )
            }
            if (busy) item(key = "busy", contentType = "state") { LoadingState() }
            error?.let { text -> item(key = "error", contentType = "notice") {
                InlineNotice(text) { PlainLink(stringResource(R.string.app_retry), ::load, enabled = !busy, showsArrow = false) }
            } }
            message?.let { text -> item(key = "message", contentType = "notice") {
                InlineNotice(text, color = MaterialTheme.colorScheme.primary, icon = HugeIcons.CheckmarkCircle02)
            } }
            entitlement?.let { active -> item(key = "entitlement", contentType = "group") {
                SettingsGroup(title = stringResource(if (active.identifier == "support_plus") R.string.app_support_plus_active else R.string.app_support_active)) {
                    Column(Modifier.padding(horizontal = GradeySpacing.xs, vertical = GradeySpacing.md), verticalArrangement = Arrangement.spacedBy(GradeySpacing.sm)) {
                        active.expirationDate?.let { date ->
                            Text(
                                stringResource(if (active.willRenew) R.string.app_renews else R.string.app_active_until, DateFormat.getDateInstance(DateFormat.MEDIUM, configuration.locales[0]).format(date)),
                                style = GradeyType.subheadline, color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        if (active.billingIssueDetectedAt != null) InlineNotice(stringResource(R.string.app_billing_issue))
                        PlainLink(stringResource(R.string.app_manage_subscription), ::manageSubscriptions, enabled = !busy)
                    }
                }
            } }
            if (configured && packages.isEmpty() && !busy && error == null) item(key = "empty", contentType = "state") {
                EmptyState(stringResource(R.string.app_support_empty), icon = HugeIcons.Favourite)
            }
            items(packages, key = { it.identifier }, contentType = { "package" }) { item ->
                val subscription = item.product.type == ProductType.SUBS
                val period = item.product.period
                val billing = if (subscription && period != null) {
                    val unit = period.unit.name.lowercase()
                    pluralStringResource(
                        when (unit) { "day" -> R.plurals.app_price_days; "week" -> R.plurals.app_price_weeks; "month" -> R.plurals.app_price_months; "year" -> R.plurals.app_price_years; else -> R.plurals.app_price_periods },
                        period.value, period.value, item.product.price.formatted,
                    )
                } else item.product.price.formatted
                Column(Modifier.fillMaxWidth()) {
                    Hairline(Modifier.padding(horizontal = GradeySpacing.xs))
                    Column(Modifier.padding(horizontal = GradeySpacing.xs, vertical = GradeySpacing.md), verticalArrangement = Arrangement.spacedBy(GradeySpacing.sm)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GradeySpacing.md)) {
                            Text(
                                supportPackages[item.identifier]?.let { stringResource(it) } ?: item.product.title, Modifier.weight(1f),
                                style = GradeyType.headline, color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(billing, style = GradeyType.subheadlineBold.tabular(), color = MaterialTheme.colorScheme.primary)
                        }
                        if (item.product.description.isNotBlank()) Text(item.product.description, style = GradeyType.subheadline, color = secondary)
                        Text(stringResource(if (subscription) R.string.app_subscription_terms else R.string.app_tip_terms), style = GradeyType.caption, color = secondary)
                        if (subscription && entitlement != null) PlainLink(stringResource(R.string.app_manage_plan), ::manageSubscriptions, enabled = !busy)
                        else GradeyTonalButton(
                            stringResource(if (subscription) R.string.app_continue_play else R.string.app_tip_play), { purchase(item) },
                            Modifier.fillMaxWidth(), enabled = !busy && activity != null && configured,
                        )
                    }
                }
            }
            if (configured) item(key = "purchases", contentType = "group") {
                SettingsGroup(title = stringResource(R.string.app_purchases), footer = stringResource(R.string.app_purchase_disclosure)) {
                    SettingsActionRow(HugeIcons.Reload, stringResource(R.string.app_restore), ::restore, enabled = !busy)
                    SettingsRowDivider()
                    SettingsActionRow(HugeIcons.Refresh04, stringResource(R.string.app_purchase_refresh), ::load, enabled = !busy)
                    SettingsRowDivider()
                    SettingsActionRow(HugeIcons.CreditCard, stringResource(R.string.app_manage_subscriptions), ::manageSubscriptions, enabled = !busy)
                }
            }
        }
    }
}

/** Serialize identity changes with SDK callbacks so late work cannot switch the next user's identity. */
private object SupportPurchaseIdentity {
    private val mutex = Mutex()
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    suspend fun <T> withAccount(accountID: String, block: suspend () -> T): T = mutex.withLock {
        currentCoroutineContext().ensureActive()
        if (Purchases.sharedInstance.appUserID != accountID) {
            revenueCatResult<CustomerInfo> { success, failure ->
                Purchases.sharedInstance.logInWith(accountID, onError = { failure("Could not connect your support account. Please try again.") },
                    onSuccess = { info, _ -> success(info) })
            }
        }
        currentCoroutineContext().ensureActive()
        check(Purchases.sharedInstance.appUserID == accountID) { "Your account changed. Reopen Support to continue." }
        block()
    }

    suspend fun reset() {
        // A caller's timeout may stop waiting, but this queued logout retains the identity lock
        // until the SDK callback arrives. A later login cannot overtake a delayed logout.
        val cleanup = cleanupScope.async(start = CoroutineStart.UNDISPATCHED) {
            mutex.withLock {
                if (Purchases.isConfigured && !Purchases.sharedInstance.isAnonymous) {
                    revenueCatResult<CustomerInfo> { success, failure ->
                        Purchases.sharedInstance.logOutWith(onError = { failure("Support sign-out could not be completed.") }, onSuccess = success)
                    }
                }
            }
        }
        cleanup.await()
    }
}

/** Call during Gradey sign-out, alongside the school and AI session cleanup. */
internal suspend fun resetSupportIdentity() { SupportPurchaseIdentity.reset() }

/** Watch access follows a currently active recurring entitlement; one-time tips do not unlock it. */
internal suspend fun hasRecurringSupport(accountID: String): Boolean {
    if (!Purchases.isConfigured) return false
    return kotlinx.coroutines.withContext(Dispatchers.Main.immediate) {
        SupportPurchaseIdentity.withAccount(accountID) {
            val info = revenueCatResult<CustomerInfo> { success, failure ->
                Purchases.sharedInstance.getCustomerInfoWith(onError = { failure("Support status unavailable.") }, onSuccess = success)
            }
            info.entitlements.active.values.any {
                it.identifier in setOf("support", "support_plus") && (it.expirationDate?.time ?: 0L) > System.currentTimeMillis()
            }
        }
    }
}

// Keep the identity lock until an SDK operation actually finishes, even if its UI was disposed.
private suspend fun <T> revenueCatResult(register: ((T) -> Unit, (String) -> Unit) -> Unit): T {
    val result = suspendCoroutine<T> { continuation ->
        register({ continuation.resume(it) }, { continuation.resumeWithException(IllegalStateException(it)) })
    }
    currentCoroutineContext().ensureActive()
    return result
}

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}
