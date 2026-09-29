package com.bukovinafilip.gradey.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.delay

/** School times remain in the Czech time zone when the student travels. */
val SchoolTimeZone: ZoneId = ZoneId.of("Europe/Prague")

/** Keeps current/next lesson displays fresh even while a screen stays open. */
@Composable
fun rememberSchoolTime(): State<ZonedDateTime> = produceState(ZonedDateTime.now(SchoolTimeZone)) {
    while (true) {
        value = ZonedDateTime.now(SchoolTimeZone)
        delay(30_000)
    }
}
