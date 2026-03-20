package dev.zenpatch.manager.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.zenpatch.manager.data.InstalledModule
import dev.zenpatch.manager.data.ModulesRepository
import dev.zenpatch.manager.data.PatchOptions
import dev.zenpatch.manager.data.PatchProgress
import dev.zenpatch.manager.data.PatchStep
import dev.zenpatch.manager.data.PatchedApp
import dev.zenpatch.manager.data.PatchedAppsRepository
import dev.zenpatch.manager.data.PatchStatus
import dev.zenpatch.patcher.PatcherEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File

sealed class PatchWizardState {
    object SelectApk : PatchWizardState()
    data class SelectModules(val apkUri: Uri, val apkName: String) : PatchWizardState()
    data class ConfigureOptions(
        val apkUri: Uri,
        val apkName: String,
        val selectedModules: List<InstalledModule>
    ) : PatchWizardState()
    data class Patching(val progress: PatchProgress) : PatchWizardState()
    data class ReadyToInstall(val patchedApkPath: String) : PatchWizardState()
    data class Error(val message: String) : PatchWizardState()
}

class PatchWizardViewModel(application: Application) : AndroidViewModel(application) {

    private val patchedAppsRepository = PatchedAppsRepository(application)
    private val modulesRepository = ModulesRepository(application)
    private val patcherEngine = PatcherEngine()

    private val _state = MutableStateFlow<PatchWizardState>(PatchWizardState.SelectApk)
    val state: StateFlow<PatchWizardState> = _state.asStateFlow()

    val availableModules: StateFlow<List<InstalledModule>> = modulesRepository.modules
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch {
            modulesRepository.refreshModules()
        }
    }

    fun selectApk(uri: Uri, name: String) {
        _state.value = PatchWizardState.SelectModules(uri, name)
    }

    fun selectModules(modules: List<InstalledModule>) {
        val current = _state.value as? PatchWizardState.SelectModules ?: return
        _state.value = PatchWizardState.ConfigureOptions(current.apkUri, current.apkName, modules)
    }

    fun startPatching(options: PatchOptions) {
        val current = _state.value as? PatchWizardState.ConfigureOptions ?: return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val outputFile = File(getApplication<Application>().cacheDir, "patched/${System.currentTimeMillis()}_patched.apk")
                outputFile.parentFile?.mkdirs()

                // Copy APK to temp file
                val inputApk = File(getApplication<Application>().cacheDir, "input_${System.currentTimeMillis()}.apk")
                getApplication<Application>().contentResolver.openInputStream(current.apkUri)?.use { input ->
                    inputApk.outputStream().use { output -> input.copyTo(output) }
                }

                val moduleApkPaths = current.selectedModules.map { it.apkPath }

                patcherEngine.patch(
                    inputApk = inputApk,
                    outputApk = outputFile,
                    moduleApkPaths = moduleApkPaths,
                    options = options
                ) { progress ->
                    _state.value = PatchWizardState.Patching(progress)
                }

                inputApk.delete()

                val app = PatchedApp(
                    packageName = "patched.app",
                    appName = current.apkName,
                    originalVersionCode = 1,
                    patchedVersionCode = 1,
                    patchDate = System.currentTimeMillis(),
                    modules = current.selectedModules.map { it.packageName },
                    status = PatchStatus.NOT_INSTALLED,
                    originalApkPath = current.apkUri.toString(),
                    patchedApkPath = outputFile.absolutePath,
                    signatureSpoof = options.enableSignatureSpoof
                )
                patchedAppsRepository.add(app)
                _state.value = PatchWizardState.ReadyToInstall(outputFile.absolutePath)
            }.onFailure { e ->
                Timber.e(e, "Patching failed")
                _state.value = PatchWizardState.Error(e.message ?: "Unknown error")
            }
        }
    }

    fun reset() {
        _state.value = PatchWizardState.SelectApk
    }

    fun goBack() {
        _state.value = when (val current = _state.value) {
            is PatchWizardState.SelectModules -> PatchWizardState.SelectApk
            is PatchWizardState.ConfigureOptions -> PatchWizardState.SelectModules(current.apkUri, current.apkName)
            else -> PatchWizardState.SelectApk
        }
    }
}
