package com.bukovinafilip.gradey.domain

/** Stable reasons for presentation-layer localization. No server payload is user-facing. */
interface UserFacingFailure {
    val failureReason: AccountFailureReason
}

enum class AccountFailureReason(val defaultMessage: String) {
    NOT_CONFIGURED("Gradey ID sign-in is not configured in this build."),
    SESSION_EXPIRED("Your Gradey ID session has expired. Sign in again."),
    IDENTITY_TOKEN_MISSING("The identity provider did not return a sign-in token. Please try again."),
    REQUEST_REJECTED("The account request was rejected. Check your details and try again."),
    ACCOUNT_NOT_FOUND("This account is no longer available. Refresh your accounts."),
    SCHOOL_RECONNECT_REQUIRED("This school account needs to be connected again."),
    RATE_LIMITED("Too many requests. Please try again in a moment."),
    SERVICE_UNAVAILABLE("Gradey could not complete the request. Please try again."),
    MALFORMED_RESPONSE("Gradey returned an unexpected response. Please try again."),
    PROFILE_NAME_INVALID("Your name must be between 1 and 80 characters."),
    TIME_ZONE_INVALID("Choose a valid time zone for quiet hours."),
    TIME_INVALID("Use a valid time in HH:mm format."),
    SCHOOL_PROVIDER_MISMATCH("Choose the same school provider to reconnect this account."),
    SCHOOL_IDENTITY_MISMATCH("These credentials could not be matched to the linked school account. Sign in with the same school account."),
    ACCOUNT_IDENTITY_MISMATCH("Gradey returned a different school account. Refresh your accounts."),
    SCHOOL_BAD_CREDENTIALS("Bakaláři did not accept your credentials. Check your username and password."),
    SCHOOL_SESSION_EXPIRED("Your Bakaláři session has expired. Sign in again."),
    SCHOOL_ACCESS_DENIED("Bakaláři denied access to this account. Check your school sign-in details."),
    SCHOOL_ADDRESS_NOT_FOUND("Bakaláři was not found at this address. Enter the address of your school's Bakaláři sign-in page."),
    SCHOOL_ADDRESS_REDIRECTED("The school redirected this request. Use the exact address of your school's Bakaláři sign-in page."),
    SCHOOL_UNAVAILABLE("Bakaláři could not complete the request. Please try again."),
    SCHOOL_MALFORMED_RESPONSE("The school returned an unexpected response. Check the Bakaláři address and try again."),
    APPLE_SIGN_IN_UNAVAILABLE("Sign in with Apple is not available in this build yet."),
    AUTH_CALLBACK_INVALID("This sign-in response could not be verified. Start sign-in again."),
    AUTH_REQUEST_EXPIRED("This sign-in request expired. Start sign-in again."),
    AUTH_CANCELLED("Sign-in was cancelled."),
    BROWSER_UNAVAILABLE("Install or enable a browser to sign in with Apple."),
}

class AccountFailureException(
    override val failureReason: AccountFailureReason,
    cause: Throwable? = null,
) : IllegalStateException(failureReason.defaultMessage, cause), UserFacingFailure

class AccountValidationException(
    override val failureReason: AccountFailureReason,
) : IllegalArgumentException(failureReason.defaultMessage), UserFacingFailure
