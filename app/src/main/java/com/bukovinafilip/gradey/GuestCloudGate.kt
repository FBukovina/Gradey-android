package com.bukovinafilip.gradey

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bukovinafilip.gradey.ui.AIOrb
import com.bukovinafilip.gradey.ui.GradeyPrimaryButton
import com.bukovinafilip.gradey.ui.GradeySpacing
import com.bukovinafilip.gradey.ui.GradeyType
import com.bukovinafilip.gradey.ui.HugeIcons
import com.bukovinafilip.gradey.ui.ModalCloseButton
import com.bukovinafilip.gradey.ui.SettingsBackground
import com.bukovinafilip.gradey.ui.gradeyColors

/** Shown instead of Gradey AI in guest mode: what a Gradey ID adds, and the way to sign in. */
@Composable
internal fun GuestCloudGate(onSignIn: () -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    SettingsBackground(modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
            ModalCloseButton(onClose, Modifier.align(Alignment.TopEnd).padding(GradeySpacing.md), contentDescription = stringResource(R.string.app_dismiss))
            Column(
                Modifier.align(Alignment.Center).widthIn(max = 520.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = GradeySpacing.xxl),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(GradeySpacing.lg),
            ) {
                AIOrb(64.dp)
                Text(
                    stringResource(R.string.app_guest_ai_title), Modifier.semantics { heading() },
                    style = GradeyType.display(26), color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center,
                )
                Text(stringResource(R.string.app_guest_ai_body), style = GradeyType.subheadline, color = MaterialTheme.gradeyColors.secondaryLabel, textAlign = TextAlign.Center)
                Text(stringResource(R.string.app_guest_ai_privacy), style = GradeyType.footnote, color = MaterialTheme.gradeyColors.secondaryLabel, textAlign = TextAlign.Center)
                GradeyPrimaryButton(stringResource(R.string.app_guest_signin), onSignIn, icon = HugeIcons.User)
            }
        }
    }
}
