package com.bukovinafilip.gradey

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.bukovinafilip.gradey.ui.GradeyHero
import com.bukovinafilip.gradey.ui.GradeyScreen
import com.bukovinafilip.gradey.ui.GradeySectionCard

@Composable
internal fun GuestCloudGate(onSignIn: () -> Unit, modifier: Modifier = Modifier) {
    GradeyScreen(modifier) {
        GradeyHero(stringResource(R.string.app_guest_ai_title), stringResource(R.string.app_guest_ai_body))
        GradeySectionCard {
            Text(stringResource(R.string.app_guest_ai_privacy))
            Button(onClick = onSignIn, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.app_guest_signin)) }
        }
    }
}
