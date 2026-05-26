package com.wyspr.feature.coordination.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.wyspr.core.database.entities.CoordinationEventEntity
import com.wyspr.core.database.entities.CoordinationRsvpEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventDetailScreen(
    event: CoordinationEventEntity?,
    rsvps: List<CoordinationRsvpEntity>,
    ownPub: ByteArray?,
    onRsvp: (Int) -> Unit,
    onCancel: () -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Event", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { padding ->
        if (event == null) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(32.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text("Event not found", style = MaterialTheme.typography.bodyLarge)
            }
            return@Scaffold
        }

        val dateFormat = remember { SimpleDateFormat("EEE, MMM d 'at' h:mm a", Locale.getDefault()) }
        val isCreator = ownPub != null && ownPub.contentEquals(event.creatorPub)
        val going = rsvps.count { it.status == 0 }
        val maybe = rsvps.count { it.status == 1 }
        val declined = rsvps.count { it.status == 2 }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
        ) {
            item {
                Spacer(Modifier.height(16.dp))
                Text(event.title, style = MaterialTheme.typography.headlineSmall)
                if (event.status == 1) {
                    Spacer(Modifier.height(4.dp))
                    Text("Cancelled", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.height(12.dp))
                Text(dateFormat.format(Date(event.startsAt * 1000)), style = MaterialTheme.typography.bodyMedium)
                val endsAt = event.endsAt
                if (endsAt != null) {
                    Text("to ${dateFormat.format(Date(endsAt * 1000))}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val loc = event.location
                if (loc != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(loc, style = MaterialTheme.typography.bodyMedium)
                }
                val desc = event.description
                if (desc != null) {
                    Spacer(Modifier.height(12.dp))
                    Text(desc, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))

                if (event.status == 0) {
                    if (!isCreator) {
                        Text("Your RSVP", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AssistChip(onClick = { onRsvp(0) }, label = { Text("Going") })
                            AssistChip(onClick = { onRsvp(1) }, label = { Text("Maybe") })
                            AssistChip(onClick = { onRsvp(2) }, label = { Text("Decline") })
                        }
                    } else {
                        OutlinedButton(onClick = onCancel) {
                            Text("Cancel event")
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(12.dp))
                }

                Text("Responses", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Text("$going going  ·  $maybe maybe  ·  $declined declined", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
            }

            items(rsvps, key = { (it.eventId.toList() + it.responderPub.toList()).hashCode() }) { rsvp ->
                RsvpRow(rsvp)
            }

            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

@Composable
private fun RsvpRow(rsvp: CoordinationRsvpEntity) {
    val statusText = when (rsvp.status) {
        0 -> "Going"
        1 -> "Maybe"
        2 -> "Declined"
        else -> "?"
    }
    val fingerprint = rsvp.responderPub.take(4).joinToString("") { "%02x".format(it) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(fingerprint, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(statusText, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
    }
}
