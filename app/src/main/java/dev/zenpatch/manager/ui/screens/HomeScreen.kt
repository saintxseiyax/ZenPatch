package dev.zenpatch.manager.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.zenpatch.manager.data.PatchStatus
import dev.zenpatch.manager.data.PatchedApp
import dev.zenpatch.manager.viewmodel.HomeViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigateToPatchWizard: () -> Unit,
    viewModel: HomeViewModel = viewModel()
) {
    val patchedApps by viewModel.patchedApps.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ZenPatch") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onNavigateToPatchWizard,
                icon = { Icon(Icons.Filled.Add, contentDescription = "Patch new app") },
                text = { Text("Patch App") }
            )
        }
    ) { paddingValues ->
        if (patchedApps.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "No patched apps",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Tap + to patch your first app",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(patchedApps, key = { it.packageName }) { app ->
                    PatchedAppCard(app = app, onUnpatch = { viewModel.unpatch(it) })
                }
            }
        }
    }
}

@Composable
fun PatchedAppCard(
    app: PatchedApp,
    onUnpatch: (String) -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StatusIcon(status = app.status)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = app.appName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = app.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "${app.modules.size} module(s) • ${app.status.displayName()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(
                        imageVector = Icons.Filled.Error,
                        contentDescription = "Options"
                    )
                }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("Unpatch") },
                        onClick = {
                            showMenu = false
                            onUnpatch(app.packageName)
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun StatusIcon(status: PatchStatus) {
    val (icon, tint) = when (status) {
        PatchStatus.ACTIVE -> Icons.Filled.CheckCircle to MaterialTheme.colorScheme.primary
        PatchStatus.OUTDATED -> Icons.Filled.Warning to MaterialTheme.colorScheme.tertiary
        PatchStatus.NOT_INSTALLED -> Icons.Filled.Error to MaterialTheme.colorScheme.outline
        PatchStatus.ERROR -> Icons.Filled.Error to MaterialTheme.colorScheme.error
    }
    Icon(imageVector = icon, contentDescription = status.name, tint = tint)
}

private fun PatchStatus.displayName() = when (this) {
    PatchStatus.ACTIVE -> "Active"
    PatchStatus.OUTDATED -> "Update available"
    PatchStatus.NOT_INSTALLED -> "Not installed"
    PatchStatus.ERROR -> "Error"
}
