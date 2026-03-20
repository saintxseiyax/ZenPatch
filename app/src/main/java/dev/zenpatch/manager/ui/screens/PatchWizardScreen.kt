package dev.zenpatch.manager.ui.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.zenpatch.manager.data.InstalledModule
import dev.zenpatch.manager.data.PatchOptions
import dev.zenpatch.manager.data.PatchProgress
import dev.zenpatch.manager.data.PatchStep
import dev.zenpatch.manager.viewmodel.PatchWizardState
import dev.zenpatch.manager.viewmodel.PatchWizardViewModel
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PatchWizardScreen(
    onNavigateBack: () -> Unit,
    viewModel: PatchWizardViewModel = viewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val availableModules by viewModel.availableModules.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Patch Wizard") },
                navigationIcon = {
                    IconButton(onClick = {
                        val s = state
                        if (s is PatchWizardState.SelectApk) {
                            onNavigateBack()
                        } else {
                            viewModel.goBack()
                        }
                    }) {
                        Icon(Icons.Filled.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (val s = state) {
                is PatchWizardState.SelectApk -> SelectApkStep(
                    onApkSelected = { uri, name -> viewModel.selectApk(uri, name) }
                )
                is PatchWizardState.SelectModules -> SelectModulesStep(
                    apkName = s.apkName,
                    availableModules = availableModules,
                    onNext = { viewModel.selectModules(it) }
                )
                is PatchWizardState.ConfigureOptions -> ConfigureOptionsStep(
                    apkName = s.apkName,
                    selectedModules = s.selectedModules,
                    onPatch = { viewModel.startPatching(it) }
                )
                is PatchWizardState.Patching -> PatchingStep(progress = s.progress)
                is PatchWizardState.ReadyToInstall -> ReadyToInstallStep(
                    apkPath = s.patchedApkPath,
                    onDone = { viewModel.reset(); onNavigateBack() }
                )
                is PatchWizardState.Error -> ErrorStep(
                    message = s.message,
                    onRetry = { viewModel.reset() }
                )
            }
        }
    }
}

@Composable
private fun SelectApkStep(onApkSelected: (android.net.Uri, String) -> Unit) {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { onApkSelected(it, it.lastPathSegment ?: "selected.apk") }
    }
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Filled.FolderOpen, null, modifier = Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(24.dp))
        Text("Select APK to Patch", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("Choose an APK file from storage", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(32.dp))
        Button(onClick = { launcher.launch(arrayOf("application/vnd.android.package-archive")) }) {
            Text("Browse Files")
        }
    }
}

@Composable
private fun SelectModulesStep(
    apkName: String,
    availableModules: List<InstalledModule>,
    onNext: (List<InstalledModule>) -> Unit
) {
    val selected = remember { mutableStateListOf<InstalledModule>() }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Select Modules for $apkName", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        if (availableModules.isEmpty()) {
            Text("No modules available. Install Xposed modules first.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(availableModules) { module ->
                    val isSelected = module in selected
                    Card(
                        onClick = { if (isSelected) selected.remove(module) else selected.add(module) },
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = isSelected, onCheckedChange = null)
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(module.name, fontWeight = FontWeight.SemiBold)
                                Text(module.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Button(modifier = Modifier.fillMaxWidth(), onClick = { onNext(selected.toList()) }) {
            Text("Next (${selected.size} selected)")
        }
    }
}

@Composable
private fun ConfigureOptionsStep(
    apkName: String,
    selectedModules: List<InstalledModule>,
    onPatch: (PatchOptions) -> Unit
) {
    var signatureSpoof by remember { mutableStateOf(true) }
    var debuggable by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Patch Options", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("APK: $apkName", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("${selectedModules.size} modules selected", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Signature Spoofing", fontWeight = FontWeight.SemiBold)
                        Text("Return original signature to the app", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = signatureSpoof, onCheckedChange = { signatureSpoof = it })
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Debuggable", fontWeight = FontWeight.SemiBold)
                        Text("Make patched app debuggable", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = debuggable, onCheckedChange = { debuggable = it })
                }
            }
        }
        Spacer(modifier = Modifier.weight(1f))
        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                onPatch(PatchOptions(
                    enableSignatureSpoof = signatureSpoof,
                    debuggable = debuggable,
                    selectedModules = selectedModules.map { it.packageName }
                ))
            }
        ) {
            Text("Start Patching")
        }
    }
}

@Composable
private fun PatchingStep(progress: PatchProgress) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Patching…", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(24.dp))
        Text(
            progress.step.displayName(),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(16.dp))
        LinearProgressIndicator(
            progress = { progress.progress },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Step ${progress.stepIndex + 1} / ${progress.totalSteps}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))
        Text(progress.message, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ReadyToInstallStep(apkPath: String, onDone: () -> Unit) {
    val context = LocalContext.current
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Filled.CheckCircle, null, modifier = Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(24.dp))
        Text("Patching Complete!", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("The patched APK is ready to install.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(32.dp))
        Button(modifier = Modifier.fillMaxWidth(), onClick = {
            val file = File(apkPath)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }) {
            Text("Install Patched APK")
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = onDone) {
            Text("Done")
        }
    }
}

@Composable
private fun ErrorStep(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Patching Failed", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(16.dp))
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
            Text(message, modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
        }
        Spacer(Modifier.height(24.dp))
        Button(onClick = onRetry) { Text("Try Again") }
    }
}

private fun PatchStep.displayName() = when (this) {
    PatchStep.ANALYZING -> "Analyzing APK"
    PatchStep.MERGING_SPLITS -> "Merging Splits"
    PatchStep.INJECTING_DEX -> "Injecting DEX"
    PatchStep.INJECTING_NATIVE -> "Injecting Native Libraries"
    PatchStep.PATCHING_MANIFEST -> "Patching Manifest"
    PatchStep.SIGNING -> "Signing APK"
    PatchStep.FINISHED -> "Finished"
}
