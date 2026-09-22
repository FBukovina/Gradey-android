package com.bukovinafilip.gradey

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential

suspend fun googleIdentity(context: Context): GoogleIdTokenCredential {
    check(BuildConfig.GOOGLE_WEB_CLIENT_ID.isNotBlank()) {
        "Google sign-in is not configured in this build. You can explore Gradey with demo data."
    }
    val option = GetSignInWithGoogleOption.Builder(BuildConfig.GOOGLE_WEB_CLIENT_ID).build()
    val result = try {
        CredentialManager.create(context).getCredential(
            context = context,
            request = GetCredentialRequest.Builder().addCredentialOption(option).build(),
        ).credential
    } catch (error: NoCredentialException) {
        throw IllegalStateException(
            "Google couldn’t provide a sign-in account. Check that a Google account is added in Android settings, then try again.",
            error,
        )
    }
    check(result is CustomCredential && result.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
        "Google did not return a sign-in token. Please try again."
    }
    return GoogleIdTokenCredential.createFrom(result.data)
}
