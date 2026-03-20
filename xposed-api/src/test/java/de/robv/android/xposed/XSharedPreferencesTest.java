package de.robv.android.xposed;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import java.util.Set;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for XSharedPreferences ContentProvider-based implementation.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class XSharedPreferencesTest {

    private XSharedPreferences prefs;

    @BeforeEach
    public void setUp() {
        prefs = new XSharedPreferences("com.test.module");
        // Populate local cache for testing (without ContentProvider)
        prefs.putInCache("boolean_true", "true");
        prefs.putInCache("boolean_false", "false");
        prefs.putInCache("int_42", "42");
        prefs.putInCache("long_999", "999");
        prefs.putInCache("float_3_14", "3.14");
        prefs.putInCache("string_hello", "hello");
        prefs.putInCache("string_set", "a,b,c");
    }

    @Test
    public void testGetBoolean_true() {
        assertTrue(prefs.getBoolean("boolean_true", false));
    }

    @Test
    public void testGetBoolean_false() {
        assertFalse(prefs.getBoolean("boolean_false", true));
    }

    @Test
    public void testGetBoolean_missing_returnsDefault() {
        assertTrue(prefs.getBoolean("missing_key", true));
        assertFalse(prefs.getBoolean("missing_key", false));
    }

    @Test
    public void testGetInt_valid() {
        assertEquals(42, prefs.getInt("int_42", 0));
    }

    @Test
    public void testGetInt_missing_returnsDefault() {
        assertEquals(-1, prefs.getInt("missing_int", -1));
    }

    @Test
    public void testGetLong_valid() {
        assertEquals(999L, prefs.getLong("long_999", 0L));
    }

    @Test
    public void testGetLong_missing_returnsDefault() {
        assertEquals(Long.MAX_VALUE, prefs.getLong("missing_long", Long.MAX_VALUE));
    }

    @Test
    public void testGetFloat_valid() {
        assertEquals(3.14f, prefs.getFloat("float_3_14", 0f), 0.001f);
    }

    @Test
    public void testGetFloat_missing_returnsDefault() {
        assertEquals(2.718f, prefs.getFloat("missing_float", 2.718f), 0.001f);
    }

    @Test
    public void testGetString_valid() {
        assertEquals("hello", prefs.getString("string_hello", null));
    }

    @Test
    public void testGetString_missing_returnsDefault() {
        assertEquals("default_val", prefs.getString("missing_string", "default_val"));
    }

    @Test
    public void testGetString_missing_returnsNullDefault() {
        assertNull(prefs.getString("missing_string", null));
    }

    @Test
    public void testGetStringSet_valid() {
        Set<String> result = prefs.getStringSet("string_set", null);
        assertNotNull(result);
        assertEquals(3, result.size());
        assertTrue(result.contains("a"));
        assertTrue(result.contains("b"));
        assertTrue(result.contains("c"));
    }

    @Test
    public void testGetStringSet_missing_returnsDefault() {
        Set<String> defaultSet = new HashSet<>();
        defaultSet.add("x");
        Set<String> result = prefs.getStringSet("missing_set", defaultSet);
        assertEquals(defaultSet, result);
    }

    @Test
    public void testContains_existing() {
        assertTrue(prefs.contains("boolean_true"));
    }

    @Test
    public void testContains_missing() {
        assertFalse(prefs.contains("nonexistent_key_xyz"));
    }

    @Test
    public void testGetAll_returnsAllCachedValues() {
        var all = prefs.getAll();
        assertTrue(all.containsKey("boolean_true"));
        assertTrue(all.containsKey("int_42"));
        assertTrue(all.containsKey("string_hello"));
    }

    @Test
    public void testIsWorldReadable_alwaysFalse() {
        assertFalse(prefs.isWorldReadable(), "XSharedPreferences should never be world-readable in ZenPatch");
    }

    @Test
    public void testHasFileChanged_alwaysFalse() {
        assertFalse(prefs.hasFileChanged());
    }

    @Test
    public void testInit_withNullResolver_doesNotCrash() {
        XSharedPreferences newPrefs = new XSharedPreferences("test.module", "test_prefs");
        assertDoesNotThrow(() -> newPrefs.init(null));
    }

    @Test
    public void testPutInCache_updatesExistingValue() {
        prefs.putInCache("int_42", "100");
        assertEquals(100, prefs.getInt("int_42", 0));
    }

    @Test
    public void testConstructor_withPrefFileName() {
        XSharedPreferences p = new XSharedPreferences("com.test.module", "custom_prefs");
        assertDoesNotThrow(() -> {});
    }
}
