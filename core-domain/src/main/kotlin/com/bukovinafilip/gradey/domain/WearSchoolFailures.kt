package com.bukovinafilip.gradey.domain

enum class WearSchoolFailure {
    DISABLED, LEASE_EXPIRED, LOCK_REQUIRED, UNLOCK_REQUIRED, PHONE_UNREACHABLE,
    INVALID_RESPONSE, IDENTITY_MISMATCH, RECONNECT_REQUIRED, NETWORK, TWO_FACTOR,
    CAPTCHA, INVALID_CODE, NOT_APPROVED,
}
class WearSchoolException(val reason: WearSchoolFailure) : IllegalStateException("Watch school connection: ${reason.name}")
