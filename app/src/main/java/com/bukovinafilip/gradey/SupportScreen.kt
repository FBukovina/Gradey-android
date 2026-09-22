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
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.bukovinafilip.gradey.ui.GradeyHero
import com.bukovinafilip.gradey.ui.GradeySectionCard
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
    "support_standard_monthly" to "Support · Monthly",
    "support_standard_yearly" to "Support · Yearly",
    "support_plus_monthly" to "Support Plus · Monthly",
    "support_plus_yearly" to "Support Plus · Yearly",
)
private val tipPackages = listOf("tip_small", "tip_medium", "tip_large")
private class SupportRequestOwner(var active: Boolean = true, var job: Job? = null)
private class PurchaseCancelled : Exception()
private class PurchasePending : Exception()

@Composable
internal fun SupportScreen(accountID: String?, isDemo: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
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
                if (owner.active) message = "Your payment is awaiting Google Play approval. Refresh purchase status after it is completed."
            } catch (failure: Exception) {
                if (owner.active) error = failure.message ?: "Google Play could not complete this request. Please try again."
            } finally {
                if (owner.active) busy = false
            }
        }
    }

    fun load() = action {
        val info = revenueCatResult<CustomerInfo> { success, failure ->
            Purchases.sharedInstance.getCustomerInfoWith(onError = { failure("Could not load your support status.") }, onSuccess = success)
        }
        val offerings = revenueCatResult<Offerings> { success, failure ->
            Purchases.sharedInstance.getOfferingsWith(onError = { failure("Could not load support options. Please try again.") }, onSuccess = success)
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
            .onFailure { error = "A browser or Google Play is needed to manage your subscription." }
    }

    LaunchedEffect(owner, configured) { load() }
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(GradeySpacing.lg), contentPadding = PaddingValues(GradeySpacing.lg)) {
        item { GradeyHero("Support Gradey", "Help keep Gradey growing") }
        if (!configured) item {
            GradeySectionCard { Text(when {
                isDemo -> "Support purchases are unavailable in demo mode."
                accountID.isNullOrBlank() -> "Sign in to your Gradey account to support the app."
                else -> "Support options are not configured in this build."
            }) }
        }
        if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        error?.let { message -> item { GradeySectionCard {
            Text(message, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = ::load, enabled = !busy) { Text("Try again") }
        } } }
        message?.let { text -> item { Text(text) } }
        entitlement?.let { active -> item {
            GradeySectionCard(title = if (active.identifier == "support_plus") "Support Plus is active" else "Support is active") {
                active.expirationDate?.let { date ->
                    Text("${if (active.willRenew) "Renews" else "Active until"} ${DateFormat.getDateInstance().format(date)}")
                }
                if (active.billingIssueDetectedAt != null) Text("Your store reports a billing issue. Check your payment details.", color = MaterialTheme.colorScheme.error)
                TextButton(onClick = ::manageSubscriptions, enabled = !busy) { Text("Manage subscription") }
            }
        } }
        if (configured && packages.isEmpty() && !busy && error == null) item {
            Text("No support options are available from Google Play right now.")
        }
        items(packages, key = { it.identifier }) { item ->
            val subscription = item.product.type == ProductType.SUBS
            GradeySectionCard(title = supportPackages[item.identifier] ?: item.product.title) {
                Text(item.product.description)
                val period = item.product.period
                val billing = if (subscription && period != null) {
                    val unit = period.unit.name.lowercase()
                    "${item.product.price.formatted} every ${period.value} $unit${if (period.value == 1) "" else "s"}"
                } else item.product.price.formatted
                Text(billing)
                Text(if (subscription) "Subscription. Google Play shows renewal and cancellation terms before payment." else "One-time tip. No subscription.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (subscription && entitlement != null) {
                    TextButton(onClick = ::manageSubscriptions, enabled = !busy) { Text("Manage your existing plan") }
                } else {
                    Button(enabled = !busy && activity != null && configured, onClick = {
                        action {
                            val info = suspendCoroutine<CustomerInfo> { continuation ->
                                Purchases.sharedInstance.purchaseWith(PurchaseParams.Builder(checkNotNull(activity), item).build(),
                                    onError = { failure, cancelled -> continuation.resumeWithException(when {
                                        cancelled -> PurchaseCancelled()
                                        failure.code == PurchasesErrorCode.PaymentPendingError -> PurchasePending()
                                        else -> IllegalStateException("The purchase could not be completed. Check Google Play and try again.")
                                    }) },
                                    onSuccess = { _, info -> continuation.resume(info) })
                            }
                            currentCoroutineContext().ensureActive()
                            if (owner.active) { customerInfo = info; message = "Thank you for supporting Gradey." }
                        }
                    }) { Text(if (subscription) "Continue in Google Play" else "Leave a tip in Google Play") }
                }
            }
        }
        if (configured) item {
            GradeySectionCard(title = "Purchases") {
                Text("Google Play shows the full price, billing period and renewal terms before you confirm.")
                OutlinedButton(enabled = !busy, onClick = {
                    action {
                        val info = revenueCatResult<CustomerInfo> { success, failure ->
                            Purchases.sharedInstance.restorePurchasesWith(onError = { failure("Could not restore purchases. Please try again.") }, onSuccess = success)
                        }
                        currentCoroutineContext().ensureActive()
                        if (owner.active) {
                            customerInfo = info
                            message = if (info.entitlements.active.keys.any { it == "support" || it == "support_plus" }) "Support subscription restored."
                                else "Purchase history checked. No active support subscription was found."
                        }
                    }
                }) { Text("Restore purchases") }
                TextButton(onClick = ::load, enabled = !busy) { Text("Refresh purchase status") }
                TextButton(onClick = ::manageSubscriptions, enabled = !busy) { Text("Manage subscriptions") }
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
