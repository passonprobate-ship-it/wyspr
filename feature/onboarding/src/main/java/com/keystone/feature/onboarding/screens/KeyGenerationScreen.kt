package com.keystone.feature.onboarding.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.keystone.core.ui.KeystoneAccent
import com.keystone.core.ui.components.BadgeStatus
import com.keystone.core.ui.components.KeystonePanel
import com.keystone.core.ui.components.TrustBadge
import com.keystone.feature.onboarding.OnboardingViewModel

@Composable
fun KeyGenerationScreen(
    status: OnboardingViewModel.KeyGenStatus,
    onStart: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier.padding(top = 56.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "Generate your identity",
                style = MaterialTheme.typography.titleLarge,
            )
            KeystonePanel(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "Hardware-backed",
                        style = MaterialTheme.typography.labelLarge,
                        color = KeystoneAccent.Verified,
                    )
                    Text(
                        "Keystone creates a private signing key inside this " +
                            "device's hardware security chip. The key never " +
                            "leaves the chip; uninstalling the app deletes it " +
                            "forever.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        when (status) {
            OnboardingViewModel.KeyGenStatus.Pending -> Button(
                onClick = onStart,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Generate identity") }

            OnboardingViewModel.KeyGenStatus.Running -> Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(bottom = 24.dp),
            ) {
                CircularProgressIndicator(
                    color = KeystoneAccent.Verified,
                    modifier = Modifier.size(48.dp),
                    strokeWidth = 3.dp,
                )
                TrustBadge(label = "PROVISIONING", status = BadgeStatus.Live)
            }

            is OnboardingViewModel.KeyGenStatus.Failed -> Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    "Could not generate identity.",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(
                    status.message,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
                Button(
                    onClick = onStart,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Retry") }
            }
        }
    }
}
