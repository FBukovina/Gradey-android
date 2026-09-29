package com.bukovinafilip.gradey

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.bukovinafilip.gradey.domain.AccountFailureException
import com.bukovinafilip.gradey.domain.AccountFailureReason
import com.bukovinafilip.gradey.network.AppleOAuthProtocol
import java.net.URI

/** Keep Apple credentials in the user's browser; only a verified PKCE code returns to Gradey. */
internal fun launchAppleSignIn(context: Context, authorizationURL: String) {
    val uri = runCatching { URI(authorizationURL) }.getOrNull()
    if (uri?.scheme != "https" || uri.host != "appleid.apple.com" || uri.path != "/auth/authorize" ||
        uri.userInfo != null || uri.port !in setOf(-1, 443)) {
        throw AccountFailureException(AccountFailureReason.AUTH_CALLBACK_INVALID)
    }
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(authorizationURL)).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    } catch (_: ActivityNotFoundException) {
        throw AccountFailureException(AccountFailureReason.BROWSER_UNAVAILABLE)
    }
}

internal fun isAppleSignInCallback(raw: String?): Boolean = AppleOAuthProtocol.isCallback(raw)
