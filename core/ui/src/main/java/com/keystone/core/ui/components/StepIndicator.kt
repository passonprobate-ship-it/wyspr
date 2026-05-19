package com.keystone.core.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.keystone.core.ui.KeystoneAccent

/**
 * Horizontal step indicator — N segments, the active one stretches
 * twice the width of the others and lights up. Used in the handshake
 * progress screen.
 */
@Composable
fun StepIndicator(
    totalSteps: Int,
    currentStep: Int,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(totalSteps) { idx ->
            val isActive = idx == currentStep
            val isPast = idx < currentStep
            val targetWidth = if (isActive) 32.dp else 16.dp
            val width by animateDpAsState(targetValue = targetWidth, animationSpec = tween(220), label = "step")
            val color = when {
                isActive -> KeystoneAccent.Verified
                isPast -> KeystoneAccent.Verified.copy(alpha = 0.5f)
                else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
            }
            Box(
                modifier = Modifier
                    .width(width)
                    .height(4.dp)
                    .background(color, RoundedCornerShape(2.dp)),
            )
        }
    }
}
