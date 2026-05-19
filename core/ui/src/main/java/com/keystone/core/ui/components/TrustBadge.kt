package com.keystone.core.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.keystone.core.ui.KeystoneAccent

/**
 * A status pill — a colored dot + a single word — used everywhere
 * Keystone surfaces a trust or connection state. Avoids icons; the
 * design language stays severe on purpose.
 */
@Composable
fun TrustBadge(
    label: String,
    status: BadgeStatus,
    modifier: Modifier = Modifier,
    pulsing: Boolean = status == BadgeStatus.Live,
) {
    val color = when (status) {
        BadgeStatus.Verified -> KeystoneAccent.Verified
        BadgeStatus.Live -> KeystoneAccent.Pending
        BadgeStatus.Quarantined -> KeystoneAccent.Quarantine
        BadgeStatus.Neutral -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
            .border(
                width = 1.dp,
                color = color.copy(alpha = 0.5f),
                shape = RoundedCornerShape(999.dp),
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        PulsingDot(color = color, pulsing = pulsing)
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = color,
        )
    }
}

@Composable
fun PulsingDot(
    color: Color,
    pulsing: Boolean = true,
    size: androidx.compose.ui.unit.Dp = 8.dp,
) {
    val alpha = if (pulsing) {
        val transition = rememberInfiniteTransition(label = "pulse")
        transition.animateFloat(
            initialValue = 0.4f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 900),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "pulse",
        ).value
    } else {
        1f
    }
    Box(
        modifier = Modifier
            .size(size)
            .alpha(alpha)
            .background(color, CircleShape),
    )
}

enum class BadgeStatus { Verified, Live, Quarantined, Neutral }
