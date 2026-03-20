package dev.zenpatch.manager.viewmodel

import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.zenpatch.manager.data.PatchStatus
import dev.zenpatch.manager.data.PatchedApp
import dev.zenpatch.manager.data.PatchedAppsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = PatchedAppsRepository(application)
    private val pm = application.packageManager

    val patchedApps: StateFlow<List<PatchedApp>> = repository.patchedApps
        .map { apps -> apps.map { it.withCurrentStatus() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch {
            refreshStatuses()
        }
    }

    fun refresh() {
        viewModelScope.launch {
            refreshStatuses()
        }
    }

    fun unpatch(packageName: String) {
        viewModelScope.launch {
            repository.remove(packageName)
        }
    }

    private suspend fun refreshStatuses() {
        repository.patchedApps.collect { apps ->
            apps.forEach { app ->
                val newStatus = computeStatus(app)
                if (newStatus != app.status) {
                    repository.updateStatus(app.packageName, newStatus)
                }
            }
        }
    }

    private fun computeStatus(app: PatchedApp): PatchStatus {
        return try {
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(app.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(app.packageName, 0)
            }
            if (info.longVersionCode > app.patchedVersionCode) {
                PatchStatus.OUTDATED
            } else {
                PatchStatus.ACTIVE
            }
        } catch (e: PackageManager.NameNotFoundException) {
            PatchStatus.NOT_INSTALLED
        } catch (e: Exception) {
            Timber.e(e, "Error computing status for %s", app.packageName)
            PatchStatus.ERROR
        }
    }

    private fun PatchedApp.withCurrentStatus(): PatchedApp {
        val currentStatus = computeStatus(this)
        return if (currentStatus != status) copy(status = currentStatus) else this
    }
}
