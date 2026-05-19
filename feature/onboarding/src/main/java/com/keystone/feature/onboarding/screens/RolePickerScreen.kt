package com.keystone.feature.onboarding.screens

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.keystone.core.ui.KeystoneAccent
import com.keystone.core.ui.components.KeystonePanel
import com.keystone.feature.onboarding.OnboardingViewModel

/**
 * Entry screen for first-time onboarding. Combines the brand moment
 * (the previous standalone WelcomeScreen) with the join-or-start
 * decision so the user spends one tap fewer before doing anything
 * meaningful.
 *
 * The two roles map to the underlying Noise XX initiator / responder
 * distinction, but the copy is framed in user-action terms — "start
 * a new community" vs "join with a QR code" — so the choice doesn't
 * require any protocol vocabulary.
 */
@Composable
fun RolePickerScreen(
    onPick: (OnboardingViewModel.Role) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier.padding(top = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            KeystoneMark()
            Text(
                "KEYSTONE",
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                "A private network for people who don't trust the platform.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }

        Text(
            "How are you joining?",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(top = 8.dp),
        )

        RoleCard(
            title = "Start a new community",
            description = "Set up Keystone for the first time, then invite people you " +
                "trust face-to-face. You'll be the founder.",
            accent = KeystoneAccent.Inviter,
            onClick = { onPick(OnboardingViewModel.Role.Inviter) },
        )
        RoleCard(
            title = "Join with a QR code",
            description = "Someone you trust is already in Keystone and is sitting " +
                "next to you with their device ready.",
            accent = KeystoneAccent.Invitee,
            onClick = { onPick(OnboardingViewModel.Role.Invitee) },
        )
    }
}

@Composable
private fun RoleCard(
    title: String,
    description: String,
    accent: Color,
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

/**
 * Concentric ring mark — three rings, the inner dots animating slowly
 * to suggest a live cryptographic process without being decorative.
 * Lifted from the deleted WelcomeScreen so the brand moment survives.
 */
@Composable
private fun KeystoneMark() {
    val transition = rememberInfiniteTransition(label = "mark")
    val rotate by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 18_000),
            repeatMode = RepeatMode.Restart,
        ),
        label = "rotate",
    )
    Canvas(modifier = Modifier.size(80.dp)) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val outer = size.minDimension * 0.45f
        drawCircle(
            color = KeystoneAccent.Verified.copy(alpha = 0.25f),
            radius = outer,
            style = Stroke(width = 1.5f),
        )
        drawCircle(
            color = KeystoneAccent.Verified.copy(alpha = 0.6f),
            radius = outer * 0.72f,
            style = Stroke(width = 1.5f),
        )
        val arcRadius = outer * 0.45f
        for (i in 0 until 3) {
            val angle = rotate + i * 120f
            val rad = Math.toRadians(angle.toDouble())
            val dx = (arcRadius * Math.cos(rad)).toFloat()
            val dy = (arcRadius * Math.sin(rad)).toFloat()
            drawCircle(
                color = KeystoneAccent.Verified,
                radius = 3f,
                center = Offset(center.x + dx, center.y + dy),
            )
        }
        drawCircle(
            color = KeystoneAccent.Verified,
            radius = 4f,
            center = center,
        )
    }
}
