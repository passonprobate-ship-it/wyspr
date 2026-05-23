package com.wyspr.core.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * The standard "panel" used for grouped content. One subtle 1dp outline
 * line, slightly raised background tone — no shadows. Mimics a terminal
 * frame more than a Material card.
 */
@Composable
fun WysprPanel(
    modifier: Modifier = Modifier,
    accent: Color? = null,
    contentPadding: androidx.compose.ui.unit.Dp = 16.dp,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier
            .border(
                width = 1.dp,
                color = accent ?: MaterialTheme.colorScheme.outline,
                shape = RoundedCornerShape(12.dp),
            ),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
    ) {
        Box(modifier = Modifier.padding(contentPadding)) { content() }
    }
}
