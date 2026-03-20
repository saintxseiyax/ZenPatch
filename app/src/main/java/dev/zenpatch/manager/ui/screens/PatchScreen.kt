// SPDX-License-Identifier: GPL-3.0-only
package dev.zenpatch.manager.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import dev.zenpatch.manager.data.ModuleInfo
import dev.zenpatch.manager.viewmodel.PatchWizardViewModel
import dev.zenpatch.patcher.model.PatchResult

// ---------------------------------------------------------------------------
// Wizard step labels
// ---------------------------------------------------------------------------

private val STEP_LABELS = listOf("Select APK", "Modules", "Configure", "Patching", "Result")

/**
 * Multi-step patch wizard screen.
 *
 * Guides the user through the full patching pipeline:
 *  1. Select source APK (file picker or installed app)
 *  2. Choose which Xposed modules to embed
 *  3. Configure patch options (signature spoof, debuggable, logging)
 *  4. Execute patching with a progress indicator
 *  5. Display result and offer to install the output APK
 *
 * State is owned by [PatchWizardViewModel]. The patching pipeline is executed via
 * [PatchWizardViewModel.startPatching] which runs [PatcherEngine] on the IO dispatcher.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PatchScreen(
    navController: NavController,
    viewModel: PatchWizardViewModel = viewModel(),
) {
    val currentStep by viewModel.currentStep.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Patch App") },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (currentStep > 0 && currentStep < 4) {
                                viewModel.previousStep()
                            } else {
                                navController.popBackStack()
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            // Stepper indicator
            WizardStepper(
                steps = STEP_LABELS,
                currentStep = currentStep,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            )

            HorizontalDivider()

            // Step content
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                when (currentStep) {
                    0 -> StepSelectApk(
                        viewModel = viewModel,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    1 -> StepSelectModules(
                        viewModel = viewModel,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    2 -> StepConfigure(
                        viewModel = viewModel,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    3 -> StepPatching(
                        viewModel = viewModel,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    4 -> StepResult(
                        viewModel = viewModel,
                        modifier = Modifier.fillMaxWidth(),
                        onDone = { navController.popBackStack() },
                    )
                }
            }

            // Bottom navigation buttons (hidden during patching and result)
            if (currentStep < 3) {
                WizardNavRow(
                    currentStep = currentStep,
                    totalSteps = STEP_LABELS.size - 2, // exclude patching + result
                    onBack = {
                        if (currentStep > 0) viewModel.previousStep()
                        else navController.popBackStack()
                    },
                    onNext = {
                        if (currentStep == STEP_LABELS.size - 3) {
                            // Last user-facing step before patching: start the engine
                            viewModel.startPatching()
                        } else {
                            viewModel.nextStep()
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Stepper indicator
// ---------------------------------------------------------------------------

@Composable
private fun WizardStepper(
    steps: List<String>,
    currentStep: Int,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        steps.forEachIndexed { index, label ->
            val isCompleted = index < currentStep
            val isActive = index == currentStep
            val circleColor = when {
                isCompleted || isActive -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.outlineVariant
            }
            val textColor = when {
                isActive -> MaterialTheme.colorScheme.primary
                isCompleted -> MaterialTheme.colorScheme.onSurfaceVariant
                else -> MaterialTheme.colorScheme.outlineVariant
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.weight(1f),
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(if (isCompleted || isActive) circleColor else MaterialTheme.colorScheme.surface)
                        .border(
                            width = 2.dp,
                            color = circleColor,
                            shape = CircleShape,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = (index + 1).toString(),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (isCompleted || isActive) MaterialTheme.colorScheme.onPrimary else circleColor,
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = textColor,
                    textAlign = TextAlign.Center,
                )
            }

            // Connecting line (skip after last item)
            if (index < steps.lastIndex) {
                HorizontalDivider(
                    modifier = Modifier
                        .weight(0.5f)
                        .padding(bottom = 20.dp),
                    color = if (index < currentStep) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outlineVariant,
                    thickness = 1.5.dp,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Bottom navigation row
// ---------------------------------------------------------------------------

@Composable
private fun WizardNavRow(
    currentStep: Int,
    totalSteps: Int,
    onBack: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        OutlinedButton(onClick = onBack) {
            Text(if (currentStep == 0) "Cancel" else "Back")
        }
        Button(onClick = onNext) {
            Text(if (currentStep == totalSteps) "Start Patching" else "Next")
        }
    }
}

// ---------------------------------------------------------------------------
// Step 0: Select APK
// ---------------------------------------------------------------------------

@Composable
private fun StepSelectApk(
    viewModel: PatchWizardViewModel,
    modifier: Modifier = Modifier,
) {
    val selectedApkPath by viewModel.selectedApkPath.collectAsStateWithLifecycle()

    Column(
        modifier = modifier.padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Select source application",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "Choose an APK file from storage or pick an installed application.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (selectedApkPath != null) {
            Text(
                text = "Selected: $selectedApkPath",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        FilledTonalButton(
            onClick = { /* TODO: launch SAF file picker, then viewModel.selectApk(path) */ },
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(vertical = 14.dp),
        ) {
            Icon(
                imageVector = Icons.Default.FolderOpen,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Select APK file")
        }
        FilledTonalButton(
            onClick = { /* TODO: show installed app picker, then viewModel.selectApk(path) */ },
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(vertical = 14.dp),
        ) {
            Icon(
                imageVector = Icons.Default.PhoneAndroid,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Select installed app")
        }
    }
}

// ---------------------------------------------------------------------------
// Step 1: Select Modules
// ---------------------------------------------------------------------------

@Composable
private fun StepSelectModules(
    viewModel: PatchWizardViewModel,
    modifier: Modifier = Modifier,
) {
    val availableModules by viewModel.availableModules.collectAsStateWithLifecycle()
    val selectedModules by viewModel.selectedModules.collectAsStateWithLifecycle()

    Column(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = "Select modules",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
        )
        if (availableModules.isEmpty()) {
            Text(
                text = "No modules installed. Go to the Modules tab to install some.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(8.dp),
            )
        } else {
            availableModules.forEachIndexed { index, module ->
                val isSelected = selectedModules.any { it.packageName == module.packageName }
                ModuleSelectionItem(
                    module = module,
                    selected = isSelected,
                    onCheckedChange = { viewModel.toggleModule(module) },
                )
                if (index < availableModules.lastIndex) {
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                }
            }
        }
    }
}

@Composable
private fun ModuleSelectionItem(
    module: ModuleInfo,
    selected: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    ListItem(
        headlineContent = { Text(module.name) },
        supportingContent = {
            Text(
                text = module.packageName,
                style = MaterialTheme.typography.bodySmall,
            )
        },
        trailingContent = {
            Checkbox(
                checked = selected,
                onCheckedChange = onCheckedChange,
            )
        },
    )
}

// ---------------------------------------------------------------------------
// Step 2: Configure
// ---------------------------------------------------------------------------

@Composable
private fun StepConfigure(
    viewModel: PatchWizardViewModel,
    modifier: Modifier = Modifier,
) {
    val signatureSpoof by viewModel.enableSigSpoof.collectAsStateWithLifecycle()
    val keepDebuggable by viewModel.keepDebuggable.collectAsStateWithLifecycle()
    val verboseLogging by viewModel.verbose.collectAsStateWithLifecycle()

    Column(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = "Patch options",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
        )

        PatchOptionSwitch(
            title = "Enable Signature Spoof",
            description = "Spoofs the app's signature to match the original. Required for modules that check signatures.",
            checked = signatureSpoof,
            onCheckedChange = { viewModel.setEnableSigSpoof(it) },
        )
        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
        PatchOptionSwitch(
            title = "Keep Debuggable",
            description = "Keeps the android:debuggable flag set in the manifest.",
            checked = keepDebuggable,
            onCheckedChange = { viewModel.setKeepDebuggable(it) },
        )
        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
        PatchOptionSwitch(
            title = "Verbose Logging",
            description = "Outputs detailed ZenPatch runtime logs to logcat.",
            checked = verboseLogging,
            onCheckedChange = { viewModel.setVerbose(it) },
        )
    }
}

@Composable
private fun PatchOptionSwitch(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    ListItem(
        modifier = modifier,
        headlineContent = { Text(title) },
        supportingContent = {
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingContent = {
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        },
    )
}

// ---------------------------------------------------------------------------
// Step 3: Patching (driven by ViewModel)
// ---------------------------------------------------------------------------

@Composable
private fun StepPatching(
    viewModel: PatchWizardViewModel,
    modifier: Modifier = Modifier,
) {
    val progress by viewModel.patchProgress.collectAsStateWithLifecycle()
    val statusText by viewModel.patchStatus.collectAsStateWithLifecycle()
    val patchResult by viewModel.patchResult.collectAsStateWithLifecycle()

    // When the engine finishes the ViewModel sets currentStep = 4 directly.
    // This LaunchedEffect is a safety guard that ensures the step advances even
    // if the UI missed the state update during recomposition.
    LaunchedEffect(patchResult) {
        if (patchResult != null) {
            viewModel.nextStep()
        }
    }

    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "Patching…",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.height(24.dp))
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = statusText,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---------------------------------------------------------------------------
// Step 4: Result
// ---------------------------------------------------------------------------

@Composable
private fun StepResult(
    viewModel: PatchWizardViewModel,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val patchResult by viewModel.patchResult.collectAsStateWithLifecycle()
    val success = patchResult is PatchResult.Success

    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Spacer(modifier = Modifier.height(24.dp))
        Icon(
            imageVector = if (success) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = if (success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = if (success) "Patch successful!" else "Patching failed",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = if (success)
                "The patched APK is ready. Install it to activate the embedded modules."
            else {
                val failure = patchResult as? PatchResult.Failure
                failure?.message ?: "An error occurred during patching. Check the logs for details."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(32.dp))
        if (success) {
            Button(
                onClick = { /* TODO: trigger PackageInstaller with result.outputPath */ },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Install patched APK")
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
        OutlinedButton(
            onClick = onDone,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (success) "Done" else "Close")
        }
    }
}
