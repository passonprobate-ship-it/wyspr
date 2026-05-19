package com.keystone.feature.onboarding.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.keystone.core.ui.KeystoneAccent
import com.keystone.core.ui.components.KeystonePanel
import com.keystone.feature.onboarding.OnboardingViewModel

@Composable
fun RolePickerScreen(
    onPick: (OnboardingViewModel.Role) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "How are you joining?",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(top = 56.dp),
        )
        Text(
            "Both devices must complete onboarding together, in the same room. " +
                "Pick whichever role describes you right now.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        RoleCard(
            title = "I'm inviting someone",
            description = "I'll sign an invitation for the other device. " +
                "I need to already be a trusted member.",
            accent = KeystoneAccent.Inviter,
            onClick = { onPick(OnboardingViewModel.Role.Inviter) },
        )
        RoleCard(
            title = "Someone is inviting me",
            description = "I'll receive a signed invitation that adds me " +
                "to the network as a new member.",
            accent = KeystoneAccent.Invitee,
            onClick = { onPick(OnboardingViewModel.Role.Invitee) },
        )
    }
}

@Composable
private fun RoleCard(
    title: String,
    description: String,
    accent: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    KeystonePanel(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        accent = accent,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                color = accent,
            )
            Text(
                description,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
