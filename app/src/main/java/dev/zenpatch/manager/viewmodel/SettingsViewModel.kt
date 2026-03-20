package dev.zenpatch.manager.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import dev.zenpatch.manager.data.AppSettings
import dev.zenpatch.manager.data.SettingsRepository
import dev.zenpatch.manager.ui.theme.AppTheme
import kotlinx.coroutines.flow.StateFlow

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = SettingsRepository(application)
    val settings: StateFlow<AppSettings> = repository.settings

    fun updatePrivilegeProvider(provider: String) = repository.updatePrivilegeProvider(provider)
    fun updateTheme(theme: AppTheme) = repository.updateTheme(theme)
    fun updateDynamicColors(enabled: Boolean) = repository.updateDynamicColors(enabled)
    fun updateKeystorePath(path: String?) = repository.updateKeystorePath(path)
    fun updateSignatureSpoofDefault(enabled: Boolean) = repository.updateSignatureSpoofDefault(enabled)
}
