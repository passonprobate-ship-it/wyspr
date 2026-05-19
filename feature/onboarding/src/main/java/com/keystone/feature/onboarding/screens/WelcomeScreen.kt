package com.keystone.feature.onboarding.screens

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.keystone.core.ui.KeystoneAccent
import com.keystone.core.ui.components.KeystonePanel
import com.keystone.core.ui.components.TrustBadge
import com.keystone.core.ui.components.BadgeStatus

@Composable
fun WelcomeScreen(onContinue: () -> Unit) {
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    val titleAlpha by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = tween(durationMillis = 600),
        label = "title",
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier.padding(top = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            KeystoneMark()
            Text(
                "KEYSTONE",
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.alpha(titleAlpha),
            )
            Text(
                "A private network for people who don't trust the platform.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.alpha(titleAlpha),
            )
            TrustBadge(
                label = "OFFLINE-FIRST",
                status = BadgeStatus.Verified,
                pulsing = false,
            )
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            KeystonePanel(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "How you join",
                        style = MaterialTheme.typography.labelLarge,
                        color = KeystoneAccent.Verified,
                    )
                    Text(
                        "There is no signup. You join by being vouched for, in " +
                            "person, by someone already in the network. If no one " +
                            "has invited you yet, ask them to.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            Button(
                onClick = onContinue,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Continue") }
        }
    }
}

/**
 * Concentric ring mark — three rings, the inner two animating slowly
 * to suggest a live cryptographic process without being decorative.
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
    Canvas(modifier = Modifier.size(96.dp)) {
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
        // Arcs that rotate
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

@Suppress("unused")
private val ColorRef = Color.Unspecified
