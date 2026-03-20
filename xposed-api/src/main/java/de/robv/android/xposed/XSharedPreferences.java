package de.robv.android.xposed;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

/**
 * ContentProvider-based SharedPreferences replacement.
 * Traditional file-based XSharedPreferences fails under SELinux in non-root environments.
 * This implementation queries the ZenPatch Manager's ConfigProvider via IPC instead.
 *
 * Modules should use this class instead of direct SharedPreferences access
 * when they need to store configuration accessible to the Manager app.
 */
public class XSharedPreferences {

    private static final String TAG = "XSharedPreferences";
    private static final String MANAGER_AUTHORITY = "dev.zenpatch.manager.config";
    private static final String PREFS_PATH = "prefs";

    private final String modulePkg;
    private final String prefFile;
    private ContentResolver contentResolver;
    private final Map<String, Object> localCache = new HashMap<>();
    private boolean useContentProvider = false;

    /**
     * Creates an XSharedPreferences for the given module package.
     * @param modulePkg The module's package name
     */
    public XSharedPreferences(String modulePkg) {
        this(modulePkg, modulePkg + "_preferences");
    }

    /**
     * Creates an XSharedPreferences for a specific preference file.
     * @param modulePkg The module's package name
     * @param prefFile The preference file name
     */
    public XSharedPreferences(String modulePkg, String prefFile) {
        this.modulePkg = modulePkg;
        this.prefFile = prefFile;
    }

    /**
     * Initialize with a ContentResolver for IPC access.
     * Called by ZenPatch runtime when setting up module communication.
     */
    public void init(ContentResolver resolver) {
        this.contentResolver = resolver;
        this.useContentProvider = resolver != null;
        reload();
    }

    /**
     * Reloads all preferences from the ContentProvider.
     */
    public void reload() {
        if (!useContentProvider || contentResolver == null) return;
        try {
            Uri uri = Uri.parse("content://" + MANAGER_AUTHORITY + "/" + PREFS_PATH + "/" + modulePkg + "/" + prefFile);
            Cursor cursor = contentResolver.query(uri, null, null, null, null);
            if (cursor != null) {
                try {
                    localCache.clear();
                    while (cursor.moveToNext()) {
                        String key = cursor.getString(0);
                        String value = cursor.getString(1);
                        localCache.put(key, value);
                    }
                } finally {
                    cursor.close();
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to reload preferences from ContentProvider: " + e.getMessage());
        }
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        Object val = localCache.get(key);
        if (val == null) return defaultValue;
        if (val instanceof Boolean) return (Boolean) val;
        return "true".equals(String.valueOf(val));
    }

    public int getInt(String key, int defaultValue) {
        Object val = localCache.get(key);
        if (val == null) return defaultValue;
        try {
            return Integer.parseInt(String.valueOf(val));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public long getLong(String key, long defaultValue) {
        Object val = localCache.get(key);
        if (val == null) return defaultValue;
        try {
            return Long.parseLong(String.valueOf(val));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public float getFloat(String key, float defaultValue) {
        Object val = localCache.get(key);
        if (val == null) return defaultValue;
        try {
            return Float.parseFloat(String.valueOf(val));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public String getString(String key, String defaultValue) {
        Object val = localCache.get(key);
        if (val == null) return defaultValue;
        return String.valueOf(val);
    }

    public Set<String> getStringSet(String key, Set<String> defaultValue) {
        Object val = localCache.get(key);
        if (val == null) return defaultValue;
        // Stored as comma-separated in ContentProvider
        String[] parts = String.valueOf(val).split(",");
        Set<String> result = new HashSet<>();
        for (String part : parts) {
            if (!part.isEmpty()) result.add(part);
        }
        return result.isEmpty() ? defaultValue : result;
    }

    public boolean contains(String key) {
        return localCache.containsKey(key);
    }

    public Map<String, ?> getAll() {
        return new HashMap<>(localCache);
    }

    /**
     * Adds a value to the local cache (used for testing).
     */
    public void putInCache(String key, Object value) {
        localCache.put(key, value);
    }

    /**
     * Checks if the file exists and is accessible. Always returns true in non-root mode.
     */
    public boolean hasFileChanged() {
        return false;
    }

    /**
     * Returns whether the preferences file is world-readable.
     * Always false in ZenPatch (we use ContentProvider, not file access).
     */
    public boolean isWorldReadable() {
        return false;
    }
}
