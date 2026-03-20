package de.robv.android.xposed;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for XposedHelpers utility methods.
 * Tests findClass, findMethod, callMethod, field access, etc.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class XposedHelpersTest {

    // Test classes
    public static class TestClass {
        public String name;
        private int secret = 42;
        public static String STATIC_FIELD = "hello";

        public TestClass(String name) { this.name = name; }
        public String greet(String who) { return "Hello, " + who + "!"; }
        private String secret() { return "secret_" + secret; }
        public static String staticMethod(int x) { return "value_" + x; }
        public int compute(int a, int b) { return a + b; }
    }

    public static class SubClass extends TestClass {
        public SubClass(String name) { super(name); }
        public String subMethod() { return "sub_" + name; }
    }

    @Test
    public void testFindClass_systemClass() {
        Class<?> cls = XposedHelpers.findClass("java.lang.String", null);
        assertEquals(String.class, cls);
    }

    @Test
    public void testFindClass_localClass() {
        Class<?> cls = XposedHelpers.findClass(TestClass.class.getName(), TestClass.class.getClassLoader());
        assertEquals(TestClass.class, cls);
    }

    @Test
    public void testFindClass_notFound_throws() {
        assertThrows(XposedHelpers.XposedHelpersException.class, () ->
            XposedHelpers.findClass("com.nonexistent.Class", null)
        );
    }

    @Test
    public void testFindClassIfExists_notFound_returnsNull() {
        Class<?> cls = XposedHelpers.findClassIfExists("com.nonexistent.Class", null);
        assertNull(cls);
    }

    @Test
    public void testFindMethod_publicMethod() {
        java.lang.reflect.Method m = XposedHelpers.findMethod(TestClass.class, "greet", String.class);
        assertNotNull(m);
        assertEquals("greet", m.getName());
    }

    @Test
    public void testFindMethod_privateMethod() {
        java.lang.reflect.Method m = XposedHelpers.findMethod(TestClass.class, "secret");
        assertNotNull(m);
        assertTrue(m.isAccessible());
    }

    @Test
    public void testFindMethod_inherited() {
        java.lang.reflect.Method m = XposedHelpers.findMethod(SubClass.class, "greet", String.class);
        assertNotNull(m, "Should find inherited method from TestClass");
    }

    @Test
    public void testFindMethod_notFound_throws() {
        assertThrows(XposedHelpers.XposedHelpersException.class, () ->
            XposedHelpers.findMethod(TestClass.class, "nonExistentMethod")
        );
    }

    @Test
    public void testGetObjectField() {
        TestClass obj = new TestClass("Alice");
        Object val = XposedHelpers.getObjectField(obj, "name");
        assertEquals("Alice", val);
    }

    @Test
    public void testGetObjectField_private() {
        TestClass obj = new TestClass("Bob");
        Object val = XposedHelpers.getObjectField(obj, "secret");
        assertEquals(42, val);
    }

    @Test
    public void testSetObjectField() {
        TestClass obj = new TestClass("Charlie");
        XposedHelpers.setObjectField(obj, "name", "Dave");
        assertEquals("Dave", obj.name);
    }

    @Test
    public void testSetObjectField_private() {
        TestClass obj = new TestClass("Eve");
        XposedHelpers.setObjectField(obj, "secret", 99);
        assertEquals(99, XposedHelpers.getObjectField(obj, "secret"));
    }

    @Test
    public void testGetStaticObjectField() {
        Object val = XposedHelpers.getStaticObjectField(TestClass.class, "STATIC_FIELD");
        assertEquals("hello", val);
    }

    @Test
    public void testSetStaticObjectField() {
        XposedHelpers.setStaticObjectField(TestClass.class, "STATIC_FIELD", "world");
        assertEquals("world", TestClass.STATIC_FIELD);
        // Reset
        TestClass.STATIC_FIELD = "hello";
    }

    @Test
    public void testCallMethod_publicMethod() {
        TestClass obj = new TestClass("Test");
        Object result = XposedHelpers.callMethod(obj, "greet", "World");
        assertEquals("Hello, World!", result);
    }

    @Test
    public void testCallMethod_withIntArgs() {
        TestClass obj = new TestClass("Test");
        Object result = XposedHelpers.callMethod(obj, "compute", 5, 3);
        assertEquals(8, result);
    }

    @Test
    public void testCallStaticMethod() {
        Object result = XposedHelpers.callStaticMethod(TestClass.class, "staticMethod", 42);
        assertEquals("value_42", result);
    }

    @Test
    public void testNewInstance() {
        Object obj = XposedHelpers.newInstance(TestClass.class, "Frank");
        assertInstanceOf(TestClass.class, obj);
        assertEquals("Frank", ((TestClass) obj).name);
    }

    @Test
    public void testFindField_returnsAccessibleField() {
        java.lang.reflect.Field f = XposedHelpers.findField(TestClass.class, "secret");
        assertNotNull(f);
        assertTrue(f.isAccessible());
    }

    @Test
    public void testFindFieldIfExists_notFound_returnsNull() {
        java.lang.reflect.Field f = XposedHelpers.findFieldIfExists(TestClass.class, "nosuchfield");
        assertNull(f);
    }

    @Test
    public void testGetIntField() {
        TestClass obj = new TestClass("Test");
        int val = XposedHelpers.getIntField(obj, "secret");
        assertEquals(42, val);
    }

    @Test
    public void testSetIntField() {
        TestClass obj = new TestClass("Test");
        XposedHelpers.setIntField(obj, "secret", 100);
        assertEquals(100, XposedHelpers.getIntField(obj, "secret"));
    }

    @Test
    public void testFindClassIfExists_existingClass_returnsClass() {
        Class<?> cls = XposedHelpers.findClassIfExists("java.lang.Object", null);
        assertEquals(Object.class, cls);
    }
}
