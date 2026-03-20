package dev.zenpatch.manager.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

/**
 * Repository for persisting and retrieving patched app records.
 * Backed by SharedPreferences with JSON serialization.
 */
class PatchedAppsRepository(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(
        PREFS_NAME, Context.MODE_PRIVATE
    )

    private val _patchedApps = MutableStateFlow<List<PatchedApp>>(emptyList())
    val patchedApps: Flow<List<PatchedApp>> = _patchedApps.asStateFlow()

    init {
        _patchedApps.value = loadAll()
    }

    suspend fun add(app: PatchedApp) = withContext(Dispatchers.IO) {
        val current = _patchedApps.value.toMutableList()
        val existing = current.indexOfFirst { it.packageName == app.packageName }
        if (existing >= 0) {
            current[existing] = app
        } else {
            current.add(app)
        }
        saveAll(current)
        _patchedApps.value = current
    }

    suspend fun remove(packageName: String) = withContext(Dispatchers.IO) {
        val current = _patchedApps.value.filter { it.packageName != packageName }
        saveAll(current)
        _patchedApps.value = current
    }

    suspend fun updateStatus(packageName: String, status: PatchStatus) = withContext(Dispatchers.IO) {
        val current = _patchedApps.value.map { app ->
            if (app.packageName == packageName) app.copy(status = status) else app
        }
        saveAll(current)
        _patchedApps.value = current
    }

    fun getByPackageName(packageName: String): PatchedApp? {
        return _patchedApps.value.firstOrNull { it.packageName == packageName }
    }

    private fun loadAll(): List<PatchedApp> {
        val json = prefs.getString(KEY_APPS, null) ?: return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).mapNotNull { i ->
                runCatching { array.getJSONObject(i).toPatchedApp() }.getOrNull()
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to load patched apps")
            emptyList()
        }
    }

    private fun saveAll(apps: List<PatchedApp>) {
        val array = JSONArray()
        apps.forEach { array.put(it.toJson()) }
        prefs.edit { putString(KEY_APPS, array.toString()) }
    }

    private fun PatchedApp.toJson(): JSONObject = JSONObject().apply {
        put("packageName", packageName)
        put("appName", appName)
        put("originalVersionCode", originalVersionCode)
        put("patchedVersionCode", patchedVersionCode)
        put("patchDate", patchDate)
        put("modules", JSONArray(modules))
        put("status", status.name)
        put("originalApkPath", originalApkPath)
        put("patchedApkPath", patchedApkPath)
        put("signatureSpoof", signatureSpoof)
    }

    private fun JSONObject.toPatchedApp(): PatchedApp {
        val modulesArray = getJSONArray("modules")
        val modules = (0 until modulesArray.length()).map { modulesArray.getString(it) }
        return PatchedApp(
            packageName = getString("packageName"),
            appName = getString("appName"),
            originalVersionCode = getLong("originalVersionCode"),
            patchedVersionCode = getLong("patchedVersionCode"),
            patchDate = getLong("patchDate"),
            modules = modules,
            status = PatchStatus.valueOf(getString("status")),
            originalApkPath = getString("originalApkPath"),
            patchedApkPath = getString("patchedApkPath"),
            signatureSpoof = optBoolean("signatureSpoof", true)
        )
    }

    companion object {
        private const val PREFS_NAME = "patched_apps"
        private const val KEY_APPS = "apps"
    }
}
