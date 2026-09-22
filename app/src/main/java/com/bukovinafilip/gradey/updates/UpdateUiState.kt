package com.bukovinafilip.gradey.updates

import com.bukovinafilip.gradey.network.UpdateRelease

enum class UpdatePhase { IDLE, CHECKING, AVAILABLE, QUEUED, DOWNLOADING, READY, ERROR, STORE_MANAGED, DISABLED }

data class UpdateUiState(
    val phase: UpdatePhase = UpdatePhase.IDLE,
    val release: UpdateRelease? = null,
    val downloadedBytes: Long = 0,
    val totalBytes: Long = 0,
    val lastCheckedAt: Long = 0,
    val message: String? = null,
    val automaticChecks: Boolean = true,
    val automaticDownloads: Boolean = true,
    val wifiOnly: Boolean = true,
    val manualDownload: Boolean = false,
)
