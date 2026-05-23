package com.wyspr.feature.marketplace.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wyspr.core.trust.TrustLevel
import com.wyspr.feature.marketplace.CommunityViewModel
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Radial visualisation of the local trust graph. Local identity at
 * the centre; paired peers arranged in a circle around it, edges
 * drawn as lines, node colour keyed to the local [TrustLevel].
 *
 * The layout is the bare-minimum "force-free" placement so the v0.5
 * cut ships without a physics engine — peers are equally spaced
 * around the circumference and connected to the central self node.
 * A future sprint can drop in a Verlet integration if denser graphs
 * make the radial layout claustrophobic.
 */
@Composable
fun CommunityGraphScreen(
    onBack: () -> Unit,
    onTapPeer: (peerPubBytes: ByteArray) -> Unit = {},
    viewModel: CommunityViewModel = hiltViewModel(),
) {
    LaunchedEffect(Unit) { viewModel.load() }
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Trust graph", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Your community at a glance. Each dot is a paired peer; " +
                "the line connects them to you. Tap a node to open " +
                "their fingerprint.",
            style = MaterialTheme.typography.bodyMedium,
        )

        Box(
            modifier = Modifier.fillMaxWidth().weight(1f),
        ) {
            when (val s = state) {
                CommunityViewModel.UiState.Loading -> {
                    Text(
                        "Loading…",
                        modifier = Modifier.padding(24.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                CommunityViewModel.UiState.NoCommunity -> {
                    Text(
                        "No community on this device yet.",
                        modifier = Modifier.padding(24.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                is CommunityViewModel.UiState.Ready -> GraphCanvas(s, onTapPeer)
            }
        }

        OutlinedButton(
            onClick = onBack,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Back") }
    }
}

@Composable
private fun GraphCanvas(
    state: CommunityViewModel.UiState.Ready,
    onTapPeer: (ByteArray) -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    val edgeColor = MaterialTheme.colorScheme.outline
    val selfColor = MaterialTheme.colorScheme.tertiary
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val textMeasurer = rememberTextMeasurer()

    val peers = state.peers
    val nodeRadius = 18.dp
    var lastSize by remember { mutableStateOf(Offset.Zero) }
    var nodeCenters by remember { mutableStateOf(emptyList<NodeHit>()) }

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(peers) {
                detectTapGestures { tap ->
                    val hit = nodeCenters.firstOrNull { entry ->
                        hypot(
                            (entry.center.x - tap.x).toDouble(),
                            (entry.center.y - tap.y).toDouble(),
                        ) <= entry.radiusPx + 6f
                    }
                    if (hit != null && hit.peerPub != null) {
                        onTapPeer(hit.peerPub)
                    }
                }
            },
    ) {
        lastSize = Offset(size.width, size.height)
        val cx = size.width / 2f
        val cy = size.height / 2f
        val nodeRadiusPx = nodeRadius.toPx()
        // Ring radius — clamp so labels can fit just outside.
        val ringRadius = (minOf(size.width, size.height) / 2f) - nodeRadiusPx * 4f
        val placed = ArrayList<NodeHit>(peers.size + 1)

        // Edges first so nodes paint over them.
        for ((i, _) in peers.withIndex()) {
            val angle = (2.0 * Math.PI * i / peers.size.coerceAtLeast(1)).toFloat()
            val px = cx + ringRadius * cos(angle.toDouble()).toFloat()
            val py = cy + ringRadius * sin(angle.toDouble()).toFloat()
            drawLine(
                color = edgeColor,
                start = Offset(cx, cy),
                end = Offset(px, py),
                strokeWidth = 2.dp.toPx(),
            )
        }

        // Self node.
        drawCircle(color = selfColor, radius = nodeRadiusPx, center = Offset(cx, cy))
        drawCircle(
            color = accent,
            radius = nodeRadiusPx,
            center = Offset(cx, cy),
            style = Stroke(width = 2.5.dp.toPx()),
        )
        placed.add(NodeHit(center = Offset(cx, cy), radiusPx = nodeRadiusPx, peerPub = null))
        drawTextCentered(
            textMeasurer = textMeasurer,
            text = "You",
            center = Offset(cx, cy + nodeRadiusPx + 14.dp.toPx()),
            color = labelColor,
        )

        // Peer nodes.
        for ((i, peer) in peers.withIndex()) {
            val angle = (2.0 * Math.PI * i / peers.size.coerceAtLeast(1)).toFloat()
            val px = cx + ringRadius * cos(angle.toDouble()).toFloat()
            val py = cy + ringRadius * sin(angle.toDouble()).toFloat()
            val color = colourFor(peer.trustLevel, accent = accent, selfColor = selfColor)
            drawCircle(color = color, radius = nodeRadiusPx, center = Offset(px, py))
            placed.add(NodeHit(center = Offset(px, py), radiusPx = nodeRadiusPx, peerPub = peer.publicKey.bytes))
            // First base32 group as a label hint, full fingerprint
            // tap-loads the chat.
            val label = peer.fingerprint.toString().take(5)
            drawTextCentered(
                textMeasurer = textMeasurer,
                text = label,
                center = Offset(px, py + nodeRadiusPx + 14.dp.toPx()),
                color = labelColor,
            )
        }
        nodeCenters = placed
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawTextCentered(
    textMeasurer: TextMeasurer,
    text: String,
    center: Offset,
    color: Color,
) {
    val style = TextStyle(
        fontSize = 11.sp,
        fontFamily = FontFamily.Monospace,
        color = color,
    )
    val measured = textMeasurer.measure(AnnotatedString(text), style = style)
    drawText(
        textMeasurer = textMeasurer,
        text = text,
        topLeft = Offset(
            center.x - measured.size.width / 2f,
            center.y - measured.size.height / 2f,
        ),
        style = style,
    )
}

private fun colourFor(level: TrustLevel, accent: Color, selfColor: Color): Color = when (level) {
    TrustLevel.Root -> selfColor
    TrustLevel.Full -> accent
    TrustLevel.Provisional -> accent.copy(alpha = 0.6f)
    TrustLevel.Quarantined -> Color(0xFFE08080)
    TrustLevel.Unknown -> Color(0xFF7C9088)
}

private data class NodeHit(
    val center: Offset,
    val radiusPx: Float,
    /** null = the self node; non-null = a peer the user can tap. */
    val peerPub: ByteArray?,
)
