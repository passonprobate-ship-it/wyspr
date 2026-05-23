package com.wyspr.feature.messaging.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wyspr.core.identity.GroupId
import com.wyspr.feature.messaging.CreateGroupViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateGroupScreen(
    onBack: () -> Unit,
    onCreated: (GroupId) -> Unit,
    viewModel: CreateGroupViewModel = hiltViewModel(),
) {
    LaunchedEffect(Unit) { viewModel.start() }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val result by viewModel.result.collectAsStateWithLifecycle()

    var name by remember { mutableStateOf("") }
    val selected = remember { mutableStateMapOf<List<Byte>, Boolean>() }

    LaunchedEffect(result) {
        val r = result
        if (r is CreateGroupViewModel.CreateResult.Ok) {
            onCreated(r.groupId)
            viewModel.consumeResult()
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("New group", style = MaterialTheme.typography.titleLarge) },
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Group name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Text(
                "Members",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )

            when (val s = state) {
                CreateGroupViewModel.UiState.Loading -> {
                    Box(modifier = Modifier.fillMaxWidth().padding(top = 24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                    }
                }
                CreateGroupViewModel.UiState.NoPeers -> {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            "You have no paired peers yet. Complete an onboarding handshake to add someone you can include in a group.",
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                is CreateGroupViewModel.UiState.Ready -> {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(vertical = 4.dp),
                    ) {
                        items(s.peers, key = { it.peer.bytes.toList() }) { peer ->
                            val key = peer.peer.bytes.toList()
                            val checked = selected[key] ?: false
                            PeerRow(
                                peer = peer,
                                checked = checked,
                                onCheckedChange = { selected[key] = it },
                            )
                        }
                    }
                }
            }

            val canCreate = name.isNotBlank() &&
                selected.values.any { it } &&
                state is CreateGroupViewModel.UiState.Ready
            Button(
                onClick = {
                    val peers = (state as? CreateGroupViewModel.UiState.Ready)?.peers ?: return@Button
                    val pickedPubs = peers
                        .filter { selected[it.peer.bytes.toList()] == true }
                        .map { it.peer }
                    viewModel.create(name.trim(), pickedPubs)
                },
                enabled = canCreate,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Create group") }

            val failed = (result as? CreateGroupViewModel.CreateResult.Failed)
            if (failed != null) {
                Text(
                    failed.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun PeerRow(
    peer: CreateGroupViewModel.PeerOption,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) },
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(
                        MaterialTheme.colorScheme.primaryContainer,
                        RoundedCornerShape(8.dp),
                    )
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outline,
                        shape = RoundedCornerShape(8.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    peer.fingerprint.take(2).uppercase(),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                val name = peer.displayName
                if (name != null) {
                    Text(name, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    Text(
                        peer.fingerprint,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                } else {
                    Text(
                        peer.fingerprint,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        maxLines = 1,
                    )
                }
            }
            Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}
