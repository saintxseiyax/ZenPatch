// SPDX-License-Identifier: GPL-3.0-only
package dev.zenpatch.runtime.ipc

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import org.json.JSONArray
import org.json.JSONObject

/**
 * ContentProvider enabling bi-directional communication between the ZenPatch Manager
 * and the runtime running inside the patched app.
 *
 * Exposed URIs:
 *  - `content://<packageName>.zenpatch/modules`        – Query/insert active modules for this app
 *  - `content://<packageName>.zenpatch/config`         – Read runtime configuration flags
 *  - `content://<packageName>.zenpatch/config/<key>`   – Read a single runtime configuration value
 *  - `content://<packageName>.zenpatch/log`            – Write/read runtime log entries
 *
 * Security: Only the ZenPatch Manager app (verified by UID) may write to this provider.
 * All other callers get read-only access.
 */
class ConfigProvider : ContentProvider() {

    companion object {
        const val AUTHORITY_SUFFIX = ".zenpatch"
        const val PATH_MODULES = "modules"
        const val PATH_CONFIG = "config"
        const val PATH_LOG = "log"

        private const val CODE_MODULES = 1
        private const val CODE_CONFIG = 2
        private const val CODE_CONFIG_ITEM = 3
        private const val CODE_LOG = 4

        private const val PREFS_NAME = "zenpatch_config"
        private const val KEY_ACTIVE_MODULES = "active_modules"
        private const val MANAGER_PACKAGE = "dev.zenpatch.manager"

        /** Internal SharedPreferences keys that should not be exposed via the config path. */
        private val INTERNAL_KEYS = setOf(KEY_ACTIVE_MODULES)
    }

    // -------------------------------------------------------------------------
    // URI Matcher (lazy so context is available when first referenced)
    // -------------------------------------------------------------------------

    private val uriMatcher = UriMatcher(UriMatcher.NO_MATCH).apply {
        addURI("*$AUTHORITY_SUFFIX", PATH_MODULES, CODE_MODULES)
        addURI("*$AUTHORITY_SUFFIX", PATH_CONFIG, CODE_CONFIG)
        addURI("*$AUTHORITY_SUFFIX", "$PATH_CONFIG/*", CODE_CONFIG_ITEM)
        addURI("*$AUTHORITY_SUFFIX", PATH_LOG, CODE_LOG)
    }

    // -------------------------------------------------------------------------
    // Internal state
    // -------------------------------------------------------------------------

    private lateinit var prefs: SharedPreferences
    private val logBuffer = LogRingBuffer()

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    override fun onCreate(): Boolean {
        prefs = context!!.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return true
    }

    // -------------------------------------------------------------------------
    // Query
    // -------------------------------------------------------------------------

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        return when (uriMatcher.match(uri)) {
            CODE_MODULES -> queryModules()
            CODE_CONFIG -> queryConfig()
            CODE_CONFIG_ITEM -> queryConfigItem(uri)
            CODE_LOG -> queryLog(selection, selectionArgs, sortOrder)
            else -> throw IllegalArgumentException("Unknown URI: $uri")
        }
    }

    private fun queryModules(): Cursor {
        val cursor = MatrixCursor(arrayOf("package_name", "class_name", "enabled", "description"))
        val json = prefs.getString(KEY_ACTIVE_MODULES, null) ?: return cursor
        val array = runCatching { JSONArray(json) }.getOrNull() ?: return cursor
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            cursor.addRow(
                arrayOf(
                    obj.optString("package_name"),
                    obj.optString("class_name"),
                    if (obj.optBoolean("enabled", true)) 1 else 0,
                    obj.optString("description")
                )
            )
        }
        return cursor
    }

    private fun queryConfig(): Cursor {
        val cursor = MatrixCursor(arrayOf("key", "value"))
        prefs.all.forEach { (key, value) ->
            if (key !in INTERNAL_KEYS) {
                cursor.addRow(arrayOf(key, value?.toString() ?: ""))
            }
        }
        return cursor
    }

    private fun queryConfigItem(uri: Uri): Cursor {
        val cursor = MatrixCursor(arrayOf("key", "value"))
        val key = uri.lastPathSegment ?: return cursor
        if (key in INTERNAL_KEYS) return cursor
        if (prefs.contains(key)) {
            cursor.addRow(arrayOf(key, prefs.all[key]?.toString() ?: ""))
        }
        return cursor
    }

    private fun queryLog(
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val cursor = MatrixCursor(arrayOf("timestamp", "level", "tag", "message"))
        var entries = logBuffer.getAll()

        // Basic "level >= ?" filter support
        if (selection != null && selectionArgs != null && selection.trim() == "level >= ?") {
            val minLevel = selectionArgs.firstOrNull() ?: ""
            val levelOrder = listOf("VERBOSE", "DEBUG", "INFO", "WARN", "ERROR", "ASSERT")
            val minIdx = levelOrder.indexOf(minLevel.uppercase())
            if (minIdx >= 0) {
                entries = entries.filter { levelOrder.indexOf(it.level.uppercase()) >= minIdx }
            }
        }

        if (sortOrder?.uppercase()?.contains("DESC") == true) {
            entries = entries.reversed()
        }

        entries.forEach { entry ->
            cursor.addRow(arrayOf(entry.timestamp, entry.level, entry.tag, entry.message))
        }
        return cursor
    }

    // -------------------------------------------------------------------------
    // Insert
    // -------------------------------------------------------------------------

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        return when (uriMatcher.match(uri)) {
            CODE_LOG -> insertLog(uri, values)
            CODE_MODULES -> insertModule(uri, values)
            else -> throw IllegalArgumentException("Insert not supported for: $uri")
        }
    }

    private fun insertLog(uri: Uri, values: ContentValues?): Uri {
        // Only the ZenPatch Manager may write log entries.
        // Without this check any installed app could flood the ring buffer or inject
        // misleading log messages readable by the Manager.
        check(isManagerApp()) { "Only the ZenPatch Manager may insert log entries" }

        val timestamp = values?.getAsLong("timestamp") ?: System.currentTimeMillis()
        val level = values?.getAsString("level") ?: "INFO"
        val tag = values?.getAsString("tag") ?: ""
        val message = values?.getAsString("message") ?: ""

        val entry = LogEntry(timestamp, level, tag, message)
        logBuffer.add(entry)

        val insertUri = uri.buildUpon().appendPath(logBuffer.size().toString()).build()
        context?.contentResolver?.notifyChange(uri, null)
        return insertUri
    }

    private fun insertModule(uri: Uri, values: ContentValues?): Uri {
        check(isManagerApp()) { "Only the ZenPatch Manager may insert modules" }

        val packageName = values?.getAsString("package_name") ?: throw IllegalArgumentException("package_name required")
        val className = values?.getAsString("class_name") ?: throw IllegalArgumentException("class_name required")
        val enabled = values?.getAsInteger("enabled") ?: 1
        val description = values?.getAsString("description") ?: ""

        val array: JSONArray = prefs.getString(KEY_ACTIVE_MODULES, null)
            ?.let { runCatching { JSONArray(it) }.getOrNull() }
            ?: JSONArray()

        // Replace existing entry for the same package+class, or append
        var replaced = false
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            if (obj.optString("package_name") == packageName && obj.optString("class_name") == className) {
                array.put(i, buildModuleObject(packageName, className, enabled, description))
                replaced = true
                break
            }
        }
        if (!replaced) {
            array.put(buildModuleObject(packageName, className, enabled, description))
        }

        prefs.edit().putString(KEY_ACTIVE_MODULES, array.toString()).apply()
        context?.contentResolver?.notifyChange(uri, null)
        return uri.buildUpon().appendPath(packageName).build()
    }

    private fun buildModuleObject(
        packageName: String,
        className: String,
        enabled: Int,
        description: String
    ): JSONObject = JSONObject().apply {
        put("package_name", packageName)
        put("class_name", className)
        put("enabled", enabled != 0)
        put("description", description)
    }

    // -------------------------------------------------------------------------
    // Unsupported operations
    // -------------------------------------------------------------------------

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int {
        throw UnsupportedOperationException("Update not supported")
    }

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int {
        throw UnsupportedOperationException("Delete not supported")
    }

    override fun getType(uri: Uri): String? = null

    // -------------------------------------------------------------------------
    // Security
    // -------------------------------------------------------------------------

    private fun isManagerApp(): Boolean {
        val callingUid = Binder.getCallingUid()
        val managerUid = runCatching {
            context!!.packageManager.getPackageUid(MANAGER_PACKAGE, 0)
        }.getOrDefault(-1)
        return callingUid == managerUid
    }

    // -------------------------------------------------------------------------
    // LogRingBuffer
    // -------------------------------------------------------------------------

    private class LogRingBuffer(private val capacity: Int = 1000) {
        private val entries = ArrayDeque<LogEntry>(capacity)

        @Synchronized
        fun add(entry: LogEntry) {
            if (entries.size >= capacity) entries.removeFirst()
            entries.addLast(entry)
        }

        @Synchronized
        fun getAll(): List<LogEntry> = entries.toList()

        @Synchronized
        fun size(): Int = entries.size
    }

    private data class LogEntry(
        val timestamp: Long,
        val level: String,
        val tag: String,
        val message: String
    )
}
