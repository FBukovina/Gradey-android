package com.bukovinafilip.gradey.feature.stravacz

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.bukovinafilip.gradey.ui.BrandTextField
import com.bukovinafilip.gradey.ui.GradeyIcon
import com.bukovinafilip.gradey.ui.GradeyMetrics
import com.bukovinafilip.gradey.ui.GradeyPrimaryButton
import com.bukovinafilip.gradey.ui.GradeySpacing
import com.bukovinafilip.gradey.ui.GradeyType
import com.bukovinafilip.gradey.ui.HugeIcons
import com.bukovinafilip.gradey.ui.gradeyColors

/**
 * Canteen sign-in (iOS `StravaCZConnectView`): a display title with the privacy note, then canteen
 * number, username and password fields and the one primary button. The password lives only in
 * memory and leaves with this form once the canteen is connected.
 */
@Composable
internal fun StravaCZConnect(isDemo: Boolean, connecting: Boolean, onLogin: (String, String, String) -> Unit, modifier: Modifier = Modifier) {
    var canteen by rememberSaveable { mutableStateOf(if (isDemo) "0000" else "") }
    var username by rememberSaveable { mutableStateOf(if (isDemo) "demo" else "") }
    // Never in saved UI state.
    var password by remember { mutableStateOf(if (isDemo) "demo" else "") }
    var showPassword by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val canSubmit = !connecting && canteen.isNotBlank() && username.isNotBlank() && password.isNotEmpty()
    val submit = { if (canSubmit) onLogin(canteen.trim(), username.trim(), password) }
    val next = { focus.moveFocus(FocusDirection.Down); Unit }

    Box(modifier.fillMaxWidth().testTag("stravaCZConnectView"), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = GradeyMetrics.narrowContentMaxWidth).fillMaxWidth()
                .padding(start = GradeySpacing.xs, end = GradeySpacing.xs, top = GradeySpacing.lg),
            verticalArrangement = Arrangement.spacedBy(GradeySpacing.xl),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(GradeySpacing.xs)) {
                Text(
                    stringResource(R.string.stravacz_connect_title), Modifier.semantics { heading() },
                    style = GradeyType.display(36), color = MaterialTheme.colorScheme.onSurface,
                )
                Text(stringResource(R.string.stravacz_connect_message), style = GradeyType.callout, color = MaterialTheme.gradeyColors.secondaryLabel)
            }
            Column(verticalArrangement = Arrangement.spacedBy(GradeySpacing.lg)) {
                BrandTextField(
                    value = canteen, onValueChange = { canteen = it.filter(Char::isDigit).take(10) },
                    modifier = Modifier.fillMaxWidth().testTag("stravaCZCanteenField"),
                    placeholder = stringResource(R.string.stravacz_canteen_number), enabled = !connecting,
                    keyboardType = KeyboardType.Number, imeAction = ImeAction.Next, onImeAction = next,
                )
                BrandTextField(
                    value = username, onValueChange = { username = it },
                    modifier = Modifier.fillMaxWidth().testTag("stravaCZUsernameField"),
                    placeholder = stringResource(R.string.stravacz_username), enabled = !connecting,
                    imeAction = ImeAction.Next, onImeAction = next,
                )
                BrandTextField(
                    value = password, onValueChange = { password = it },
                    modifier = Modifier.fillMaxWidth().testTag("stravaCZPasswordField"),
                    placeholder = stringResource(R.string.stravacz_password), enabled = !connecting,
                    keyboardType = KeyboardType.Password, imeAction = ImeAction.Done, onImeAction = submit,
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    trailing = {
                        val label = stringResource(if (showPassword) R.string.stravacz_hide_password else R.string.stravacz_show_password)
                        // Compose widens this small target to the minimum touch size when hit-testing.
                        Box(
                            Modifier.size(24.dp).clip(CircleShape)
                                .clickable(role = Role.Button) { showPassword = !showPassword }
                                .semantics { contentDescription = label },
                            contentAlignment = Alignment.Center,
                        ) { GradeyIcon(if (showPassword) HugeIcons.ViewOff else HugeIcons.View, size = 18.dp, tint = MaterialTheme.gradeyColors.secondaryLabel) }
                    },
                )
                GradeyPrimaryButton(
                    stringResource(R.string.stravacz_connect_button), submit,
                    Modifier.testTag("stravaCZConnectButton"), enabled = canSubmit || connecting, loading = connecting,
                )
            }
        }
    }
}
