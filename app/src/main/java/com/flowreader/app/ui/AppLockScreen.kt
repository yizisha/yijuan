package com.flowreader.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.flowreader.app.R
import com.flowreader.app.core.designsystem.token.FlowSpacing
import com.flowreader.app.util.AppLockManager
import javax.inject.Inject

/**
 * Full-screen lock shown before the library when the app lock is on.
 *
 * Fingerprint is offered first (when the device has one enrolled); the PIN field is always present
 * as the fallback, so a device without biometrics — or a finger that will not read — is never a
 * dead end.
 */
@Composable
fun AppLockScreen(
    pinHash: String?,
    lockManager: AppLockManager,
    onUnlocked: () -> Unit
) {
    val activity = LocalContext.current as? FragmentActivity
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val biometricAvailable = remember { lockManager.canUseBiometric() }

    LaunchedEffect(biometricAvailable) {
        if (biometricAvailable && activity != null) {
            lockManager.promptBiometric(
                activity = activity,
                onSuccess = onUnlocked,
                onFailure = { message -> if (message != null) error = message }
            )
        }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(FlowSpacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(56.dp)
            )
            Spacer(modifier = Modifier.height(FlowSpacing.md))
            Text(
                text = stringResource(R.string.app_lock_title),
                style = MaterialTheme.typography.headlineSmall
            )
            Spacer(modifier = Modifier.height(FlowSpacing.xs))
            Text(
                text = stringResource(R.string.app_lock_message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(FlowSpacing.xl))

            if (pinHash != null) {
                OutlinedTextField(
                    value = pin,
                    onValueChange = {
                        pin = it.filter { char -> char.isDigit() }.take(8)
                        error = null
                    },
                    label = { Text(stringResource(R.string.settings_app_lock_pin_hint)) },
                    singleLine = true,
                    isError = error != null,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    supportingText = error?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(FlowSpacing.md))
                Button(
                    onClick = {
                        if (lockManager.verifyPin(pin, pinHash)) {
                            onUnlocked()
                        } else {
                            error = "PIN 不正确"
                            pin = ""
                        }
                    },
                    enabled = pin.length >= 4,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.app_lock_unlock))
                }
            }

            if (biometricAvailable && activity != null) {
                Spacer(modifier = Modifier.height(FlowSpacing.sm))
                TextButton(onClick = {
                    lockManager.promptBiometric(
                        activity = activity,
                        onSuccess = onUnlocked,
                        onFailure = { message -> if (message != null) error = message }
                    )
                }) {
                    Icon(Icons.Default.Fingerprint, contentDescription = null)
                    Spacer(modifier = Modifier.size(FlowSpacing.sm))
                    Text(stringResource(R.string.app_lock_use_biometric))
                }
            }
        }
    }
}