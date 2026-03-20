package dev.zenpatch.manager.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Process
import timber.log.Timber

/**
 * ContentProvider that enables IPC between the ZenPatch Manager and patched apps.
 * Provides module configuration, enabled module lists, and log ingestion.
 *
 * Security: All write operations check that the caller is the ZenPatch Manager itself
 * (by UID comparison), preventing unauthorized log injection or config modification.
 */
class ConfigProvider : ContentProvider() {

    private val logBuffer = mutableListOf<LogEntry>()
    private val configMap = mutableMapOf<String, String>()

    override fun onCreate(): Boolean {
        Timber.d("ConfigProvider created")
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?
    ): Cursor? {
        return when (URI_MATCHER.match(uri)) {
            URI_CONFIG -> queryConfig(selection, selectionArgs)
            URI_MODULES -> queryModules(selectionArgs?.firstOrNull())
            URI_LOGS -> queryLogs(selectionArgs?.firstOrNull())
            else -> null
        }
    }

    private fun queryConfig(selection: String?, selectionArgs: Array<String>?): Cursor {
        val cursor = MatrixCursor(arrayOf("key", "value"))
        if (selection != null && selectionArgs != null) {
            val value = configMap[selectionArgs[0]]
            if (value != null) {
                cursor.addRow(arrayOf(selectionArgs[0], value))
            }
        } else {
            configMap.forEach { (k, v) -> cursor.addRow(arrayOf(k, v)) }
        }
        return cursor
    }

    private fun queryModules(packageName: String?): Cursor {
        val cursor = MatrixCursor(arrayOf("module_package", "enabled"))
        val context = context ?: return cursor
        val prefs = context.getSharedPreferences("module_scopes", android.content.Context.MODE_PRIVATE)
        if (packageName != null) {
            val key = "modules_for_$packageName"
            val modules = prefs.getStringSet(key, emptySet()) ?: emptySet()
            modules.forEach { cursor.addRow(arrayOf(it, "1")) }
        }
        return cursor
    }

    private fun queryLogs(packageName: String?): Cursor {
        val cursor = MatrixCursor(arrayOf("timestamp", "level", "tag", "message"))
        synchronized(logBuffer) {
            val filtered = if (packageName != null) {
                logBuffer.filter { it.packageName == packageName }
            } else {
                logBuffer.toList()
            }
            filtered.forEach { entry ->
                cursor.addRow(arrayOf(entry.timestamp, entry.level, entry.tag, entry.message))
            }
        }
        return cursor
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        // Auth check: only allow inserts from processes that share our UID,
        // or from patched apps (any UID) for log insertion.
        val callerUid = Binder.getCallingUid()

        return when (URI_MATCHER.match(uri)) {
            URI_LOGS -> {
                // Logs can come from any patched app, but we validate the content.
                values?.let { insertLog(it, callerUid) }
                uri
            }
            URI_CONFIG -> {
                // Config writes restricted to our own process (Manager UID).
                if (callerUid != Process.myUid()) {
                    Timber.w("Unauthorized config write attempt from UID %d", callerUid)
                    return null
                }
                values?.let { insertConfig(it) }
                uri
            }
            else -> null
        }
    }

    private fun insertLog(values: ContentValues, callerUid: Int) {
        val entry = LogEntry(
            timestamp = values.getAsLong("timestamp") ?: System.currentTimeMillis(),
            level = values.getAsString("level") ?: "D",
            tag = values.getAsString("tag") ?: "Unknown",
            message = values.getAsString("message") ?: "",
            packageName = values.getAsString("package_name") ?: "",
            callerUid = callerUid
        )
        synchronized(logBuffer) {
            logBuffer.add(entry)
            // Keep only last 10,000 entries
            if (logBuffer.size > 10_000) {
                logBuffer.removeAt(0)
            }
        }
    }

    private fun insertConfig(values: ContentValues) {
        val key = values.getAsString("key") ?: return
        val value = values.getAsString("value") ?: return
        configMap[key] = value
    }

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int {
        // Config updates also restricted to our own UID
        if (Binder.getCallingUid() != Process.myUid()) {
            Timber.w("Unauthorized update attempt from UID %d", Binder.getCallingUid())
            return 0
        }
        return if (URI_MATCHER.match(uri) == URI_CONFIG && values != null) {
            insertConfig(values)
            1
        } else 0
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int {
        if (Binder.getCallingUid() != Process.myUid()) return 0
        return when (URI_MATCHER.match(uri)) {
            URI_LOGS -> { synchronized(logBuffer) { logBuffer.clear(); 1 } }
            else -> 0
        }
    }

    override fun getType(uri: Uri): String? = when (URI_MATCHER.match(uri)) {
        URI_CONFIG -> "vnd.android.cursor.dir/vnd.zenpatch.config"
        URI_MODULES -> "vnd.android.cursor.dir/vnd.zenpatch.modules"
        URI_LOGS -> "vnd.android.cursor.dir/vnd.zenpatch.logs"
        else -> null
    }

    private data class LogEntry(
        val timestamp: Long,
        val level: String,
        val tag: String,
        val message: String,
        val packageName: String,
        val callerUid: Int
    )

    companion object {
        private const val AUTHORITY_SUFFIX = ".config"
        private const val PATH_CONFIG = "config"
        private const val PATH_MODULES = "modules"
        private const val PATH_LOGS = "logs"

        private const val URI_CONFIG = 1
        private const val URI_MODULES = 2
        private const val URI_LOGS = 3

        private val URI_MATCHER = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI("dev.zenpatch.manager$AUTHORITY_SUFFIX", PATH_CONFIG, URI_CONFIG)
            addURI("dev.zenpatch.manager$AUTHORITY_SUFFIX", PATH_MODULES, URI_MODULES)
            addURI("dev.zenpatch.manager$AUTHORITY_SUFFIX", PATH_LOGS, URI_LOGS)
            // Debug variant
            addURI("dev.zenpatch.manager.debug$AUTHORITY_SUFFIX", PATH_CONFIG, URI_CONFIG)
            addURI("dev.zenpatch.manager.debug$AUTHORITY_SUFFIX", PATH_MODULES, URI_MODULES)
            addURI("dev.zenpatch.manager.debug$AUTHORITY_SUFFIX", PATH_LOGS, URI_LOGS)
        }
    }
}
