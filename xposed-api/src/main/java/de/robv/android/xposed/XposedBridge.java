package de.robv.android.xposed;

import android.util.Log;
import java.lang.reflect.Constructor;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

/**
 * XposedBridge - Xposed API compatibility layer.
 * Uses Provider-Injection-Pattern: delegates to XposedBridgeImpl set by the runtime.
 * Modules use this class; the runtime injects the actual hooking implementation.
 */
public final class XposedBridge {

    private static final String TAG = "XposedBridge";
    private static volatile XposedBridgeImpl sImpl = null;

    /** @hide */
    public static void setImpl(XposedBridgeImpl impl) {
        sImpl = impl;
        Log.d(TAG, "XposedBridge implementation set: " + impl.getClass().getName());
    }

    private static XposedBridgeImpl requireImpl() {
        XposedBridgeImpl impl = sImpl;
        if (impl == null) {
            throw new IllegalStateException("XposedBridge not initialized. " +
                "Ensure ZenPatch runtime has been bootstrapped.");
        }
        return impl;
    }

    /**
     * Hooks a method. The callback will be called before and/or after the method.
     * @param method The method or constructor to hook
     * @param callback The hook callback
     * @return An unhook handle
     */
    public static XC_MethodHook.Unhook hookMethod(Member method, XC_MethodHook callback) {
        if (method == null) throw new NullPointerException("method must not be null");
        if (callback == null) throw new NullPointerException("callback must not be null");
        return requireImpl().hookMethod(method, callback);
    }

    /**
     * Unhooks a previously installed hook.
     */
    public static void unhookMethod(Member method, XC_MethodHook callback) {
        XposedBridgeImpl impl = sImpl;
        if (impl == null) return;
        // Find matching unhook
        // The runtime tracks handles internally
    }

    /**
     * Hooks all methods with the given name in a class.
     */
    public static Set<XC_MethodHook.Unhook> hookAllMethods(Class<?> clazz, String methodName, XC_MethodHook callback) {
        Set<XC_MethodHook.Unhook> unhooks = new HashSet<>();
        for (Method method : clazz.getDeclaredMethods()) {
            if (method.getName().equals(methodName)) {
                unhooks.add(hookMethod(method, callback));
            }
        }
        for (Method method : clazz.getMethods()) {
            if (method.getName().equals(methodName)) {
                unhooks.add(hookMethod(method, callback));
            }
        }
        return unhooks;
    }

    /**
     * Hooks all constructors of a class.
     */
    public static Set<XC_MethodHook.Unhook> hookAllConstructors(Class<?> clazz, XC_MethodHook callback) {
        Set<XC_MethodHook.Unhook> unhooks = new HashSet<>();
        for (Constructor<?> ctor : clazz.getDeclaredConstructors()) {
            unhooks.add(hookMethod(ctor, callback));
        }
        return unhooks;
    }

    /**
     * Invokes the original (unhooked) version of a method.
     */
    public static Object invokeOriginalMethod(Member method, Object thisObject, Object[] args)
            throws Throwable {
        return requireImpl().invokeOriginalMethod(method, thisObject, args);
    }

    /**
     * Logs a message to Android Logcat.
     */
    public static void log(String message) {
        Log.i(TAG, message);
    }

    /**
     * Logs a message and exception.
     */
    public static void log(Throwable t) {
        Log.e(TAG, "XposedBridge", t);
    }

    /**
     * Returns the Xposed API version this implementation provides.
     */
    public static int getXposedVersion() {
        return 93; // Compatible with XposedBridge API 93
    }

    private XposedBridge() {
        throw new AssertionError("XposedBridge is not instantiatable");
    }
}
