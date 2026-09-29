package com.bukovinafilip.gradey

import com.bukovinafilip.gradey.domain.AccountFailureReason

internal fun AccountFailureReason.localizedResource(): Int = when (this) {
    AccountFailureReason.NOT_CONFIGURED -> R.string.app_error_not_configured
    AccountFailureReason.SESSION_EXPIRED -> R.string.app_error_session_expired
    AccountFailureReason.IDENTITY_TOKEN_MISSING -> R.string.app_error_identity_token_missing
    AccountFailureReason.REQUEST_REJECTED -> R.string.app_error_request_rejected
    AccountFailureReason.ACCOUNT_NOT_FOUND -> R.string.app_error_account_not_found
    AccountFailureReason.SCHOOL_RECONNECT_REQUIRED -> R.string.app_error_school_reconnect_required
    AccountFailureReason.RATE_LIMITED -> R.string.app_error_rate_limited
    AccountFailureReason.SERVICE_UNAVAILABLE -> R.string.app_error_service_unavailable
    AccountFailureReason.MALFORMED_RESPONSE -> R.string.app_error_malformed_response
    AccountFailureReason.PROFILE_NAME_INVALID -> R.string.app_error_profile_name_invalid
    AccountFailureReason.TIME_ZONE_INVALID -> R.string.app_error_time_zone_invalid
    AccountFailureReason.TIME_INVALID -> R.string.app_error_time_invalid
    AccountFailureReason.SCHOOL_PROVIDER_MISMATCH -> R.string.app_error_school_provider_mismatch
    AccountFailureReason.SCHOOL_IDENTITY_MISMATCH -> R.string.app_error_school_identity_mismatch
    AccountFailureReason.ACCOUNT_IDENTITY_MISMATCH -> R.string.app_error_account_identity_mismatch
    AccountFailureReason.SCHOOL_BAD_CREDENTIALS -> R.string.app_error_school_bad_credentials
    AccountFailureReason.SCHOOL_SESSION_EXPIRED -> R.string.app_error_school_session_expired
    AccountFailureReason.SCHOOL_ACCESS_DENIED -> R.string.app_error_school_access_denied
    AccountFailureReason.SCHOOL_ADDRESS_NOT_FOUND -> R.string.app_error_school_address_not_found
    AccountFailureReason.SCHOOL_ADDRESS_REDIRECTED -> R.string.app_error_school_address_redirected
    AccountFailureReason.SCHOOL_UNAVAILABLE -> R.string.app_error_school_unavailable
    AccountFailureReason.SCHOOL_MALFORMED_RESPONSE -> R.string.app_error_school_malformed_response
    AccountFailureReason.APPLE_SIGN_IN_UNAVAILABLE -> R.string.app_error_apple_sign_in_unavailable
    AccountFailureReason.AUTH_CALLBACK_INVALID -> R.string.app_error_auth_callback_invalid
    AccountFailureReason.AUTH_REQUEST_EXPIRED -> R.string.app_error_auth_request_expired
    AccountFailureReason.AUTH_CANCELLED -> R.string.app_error_auth_cancelled
    AccountFailureReason.BROWSER_UNAVAILABLE -> R.string.app_error_browser_unavailable
}
