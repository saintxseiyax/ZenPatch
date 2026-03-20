package dev.zenpatch.manager.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import dev.zenpatch.manager.ui.theme.AppTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AppSettings(
    val privilegeProvider: String = "auto",
    val appTheme: AppTheme = AppTheme.SYSTEM,
    val dynamicColors: Boolean = true,
    val keystorePath: String? = null,
    val keystoreAlias: String = "zenpatch",
    val defaultSignatureSpoof: Boolean = true,
    val debuggablePatching: Boolean = false,
    val keepOriginalSignature: Boolean = true
)

class SettingsRepository(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private fun loadSettings(): AppSettings {
        return AppSettings(
            privilegeProvider = prefs.getString(KEY_PRIVILEGE_PROVIDER, "auto") ?: "auto",
            appTheme = AppTheme.valueOf(prefs.getString(KEY_THEME, AppTheme.SYSTEM.name) ?: AppTheme.SYSTEM.name),
            dynamicColors = prefs.getBoolean(KEY_DYNAMIC_COLORS, true),
            keystorePath = prefs.getString(KEY_KEYSTORE_PATH, null),
            keystoreAlias = prefs.getString(KEY_KEYSTORE_ALIAS, "zenpatch") ?: "zenpatch",
            defaultSignatureSpoof = prefs.getBoolean(KEY_DEFAULT_SIG_SPOOF, true),
            debuggablePatching = prefs.getBoolean(KEY_DEBUGGABLE, false),
            keepOriginalSignature = prefs.getBoolean(KEY_KEEP_ORIGINAL_SIG, true)
        )
    }

    fun updatePrivilegeProvider(provider: String) {
        prefs.edit { putString(KEY_PRIVILEGE_PROVIDER, provider) }
        _settings.value = _settings.value.copy(privilegeProvider = provider)
    }

    fun updateTheme(theme: AppTheme) {
        prefs.edit { putString(KEY_THEME, theme.name) }
        _settings.value = _settings.value.copy(appTheme = theme)
    }

    fun updateDynamicColors(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_DYNAMIC_COLORS, enabled) }
        _settings.value = _settings.value.copy(dynamicColors = enabled)
    }

    fun updateKeystorePath(path: String?) {
        prefs.edit { putString(KEY_KEYSTORE_PATH, path) }
        _settings.value = _settings.value.copy(keystorePath = path)
    }

    fun updateSignatureSpoofDefault(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_DEFAULT_SIG_SPOOF, enabled) }
        _settings.value = _settings.value.copy(defaultSignatureSpoof = enabled)
    }

    companion object {
        private const val PREFS_NAME = "app_settings"
        private const val KEY_PRIVILEGE_PROVIDER = "privilege_provider"
        private const val KEY_THEME = "theme"
        private const val KEY_DYNAMIC_COLORS = "dynamic_colors"
        private const val KEY_KEYSTORE_PATH = "keystore_path"
        private const val KEY_KEYSTORE_ALIAS = "keystore_alias"
        private const val KEY_DEFAULT_SIG_SPOOF = "default_sig_spoof"
        private const val KEY_DEBUGGABLE = "debuggable_patching"
        private const val KEY_KEEP_ORIGINAL_SIG = "keep_original_sig"
    }
}
