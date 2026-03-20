package de.robv.android.xposed;

import android.util.Log;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Utility class for Xposed module development.
 * Provides convenience methods for reflection-based hooking, field access, and method calls.
 */
public final class XposedHelpers {

    private static final String TAG = "XposedHelpers";
    private static final Map<String, Class<?>> sClassCache = new HashMap<>();

    // ---- Class finding ----

    public static Class<?> findClass(String className, ClassLoader classLoader) {
        String key = (classLoader != null ? classLoader.hashCode() : 0) + ":" + className;
        synchronized (sClassCache) {
            if (sClassCache.containsKey(key)) return sClassCache.get(key);
        }
        try {
            ClassLoader cl = classLoader != null ? classLoader : ClassLoader.getSystemClassLoader();
            Class<?> clazz = cl.loadClass(className);
            synchronized (sClassCache) { sClassCache.put(key, clazz); }
            return clazz;
        } catch (ClassNotFoundException e) {
            throw new XposedHelpersException("Class not found: " + className, e);
        }
    }

    public static Class<?> findClassIfExists(String className, ClassLoader classLoader) {
        try {
            return findClass(className, classLoader);
        } catch (XposedHelpersException e) {
            return null;
        }
    }

    // ---- Method finding ----

    public static Method findMethod(Class<?> clazz, String methodName, Object... parameterTypes) {
        Class<?>[] paramTypes = resolveParamTypes(parameterTypes);
        return findMethodInternal(clazz, methodName, paramTypes);
    }

    private static Method findMethodInternal(Class<?> clazz, String methodName, Class<?>... paramTypes) {
        Class<?> current = clazz;
        while (current != null) {
            try {
                Method m = current.getDeclaredMethod(methodName, paramTypes);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException e) {
                // Try parent
            }
            current = current.getSuperclass();
        }
        // Try interfaces
        for (Class<?> iface : clazz.getInterfaces()) {
            try {
                return iface.getDeclaredMethod(methodName, paramTypes);
            } catch (NoSuchMethodException ignored) {}
        }
        throw new XposedHelpersException("Method not found: " + clazz.getName() + "#" + methodName + "(" + Arrays.toString(paramTypes) + ")");
    }

    public static Method findMethodIfExists(Class<?> clazz, String methodName, Object... parameterTypes) {
        try {
            return findMethod(clazz, methodName, parameterTypes);
        } catch (XposedHelpersException e) {
            return null;
        }
    }

    // ---- Method hooking ----

    public static XC_MethodHook.Unhook findAndHookMethod(
            String className, ClassLoader classLoader,
            String methodName, Object... parameterTypesAndCallback) {
        if (parameterTypesAndCallback.length == 0)
            throw new IllegalArgumentException("No callback provided");
        Object last = parameterTypesAndCallback[parameterTypesAndCallback.length - 1];
        if (!(last instanceof XC_MethodHook))
            throw new IllegalArgumentException("Last argument must be XC_MethodHook");
        XC_MethodHook callback = (XC_MethodHook) last;
        Object[] paramTypes = Arrays.copyOf(parameterTypesAndCallback, parameterTypesAndCallback.length - 1);
        Class<?> clazz = findClass(className, classLoader);
        return findAndHookMethod(clazz, methodName, combineArrays(paramTypes, callback));
    }

    public static XC_MethodHook.Unhook findAndHookMethod(
            Class<?> clazz, String methodName, Object... parameterTypesAndCallback) {
        if (parameterTypesAndCallback.length == 0)
            throw new IllegalArgumentException("No callback provided");
        Object last = parameterTypesAndCallback[parameterTypesAndCallback.length - 1];
        if (!(last instanceof XC_MethodHook))
            throw new IllegalArgumentException("Last argument must be XC_MethodHook");
        XC_MethodHook callback = (XC_MethodHook) last;
        Object[] paramTypeObjs = Arrays.copyOf(parameterTypesAndCallback, parameterTypesAndCallback.length - 1);
        Class<?>[] paramTypes = resolveParamTypes(paramTypeObjs);
        Method method = findMethodInternal(clazz, methodName, paramTypes);
        return XposedBridge.hookMethod(method, callback);
    }

    public static XC_MethodHook.Unhook findAndHookConstructor(
            String className, ClassLoader classLoader, Object... parameterTypesAndCallback) {
        Class<?> clazz = findClass(className, classLoader);
        return findAndHookConstructor(clazz, parameterTypesAndCallback);
    }

    public static XC_MethodHook.Unhook findAndHookConstructor(
            Class<?> clazz, Object... parameterTypesAndCallback) {
        if (parameterTypesAndCallback.length == 0)
            throw new IllegalArgumentException("No callback provided");
        Object last = parameterTypesAndCallback[parameterTypesAndCallback.length - 1];
        if (!(last instanceof XC_MethodHook))
            throw new IllegalArgumentException("Last argument must be XC_MethodHook");
        XC_MethodHook callback = (XC_MethodHook) last;
        Object[] paramTypeObjs = Arrays.copyOf(parameterTypesAndCallback, parameterTypesAndCallback.length - 1);
        Class<?>[] paramTypes = resolveParamTypes(paramTypeObjs);
        Constructor<?> ctor = findConstructorInternal(clazz, paramTypes);
        return XposedBridge.hookMethod(ctor, callback);
    }

    // ---- Field access ----

    public static Field findField(Class<?> clazz, String fieldName) {
        Class<?> current = clazz;
        while (current != null) {
            try {
                Field f = current.getDeclaredField(fieldName);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new XposedHelpersException("Field not found: " + clazz.getName() + "#" + fieldName);
    }

    public static Field findFieldIfExists(Class<?> clazz, String fieldName) {
        try {
            return findField(clazz, fieldName);
        } catch (XposedHelpersException e) {
            return null;
        }
    }

    public static Object getObjectField(Object obj, String fieldName) {
        try {
            return findField(obj.getClass(), fieldName).get(obj);
        } catch (IllegalAccessException e) {
            throw new XposedHelpersException("Cannot access field: " + fieldName, e);
        }
    }

    public static void setObjectField(Object obj, String fieldName, Object value) {
        try {
            findField(obj.getClass(), fieldName).set(obj, value);
        } catch (IllegalAccessException e) {
            throw new XposedHelpersException("Cannot set field: " + fieldName, e);
        }
    }

    public static Object getStaticObjectField(Class<?> clazz, String fieldName) {
        try {
            return findField(clazz, fieldName).get(null);
        } catch (IllegalAccessException e) {
            throw new XposedHelpersException("Cannot access static field: " + fieldName, e);
        }
    }

    public static void setStaticObjectField(Class<?> clazz, String fieldName, Object value) {
        try {
            findField(clazz, fieldName).set(null, value);
        } catch (IllegalAccessException e) {
            throw new XposedHelpersException("Cannot set static field: " + fieldName, e);
        }
    }

    // Typed field accessors
    public static boolean getBooleanField(Object obj, String fieldName) {
        return (Boolean) getObjectField(obj, fieldName);
    }
    public static int getIntField(Object obj, String fieldName) {
        return (Integer) getObjectField(obj, fieldName);
    }
    public static long getLongField(Object obj, String fieldName) {
        return (Long) getObjectField(obj, fieldName);
    }
    public static float getFloatField(Object obj, String fieldName) {
        return (Float) getObjectField(obj, fieldName);
    }
    public static double getDoubleField(Object obj, String fieldName) {
        return (Double) getObjectField(obj, fieldName);
    }
    public static void setBooleanField(Object obj, String fieldName, boolean value) { setObjectField(obj, fieldName, value); }
    public static void setIntField(Object obj, String fieldName, int value) { setObjectField(obj, fieldName, value); }
    public static void setLongField(Object obj, String fieldName, long value) { setObjectField(obj, fieldName, value); }

    // ---- Method invocation ----

    public static Object callMethod(Object obj, String methodName, Object... args) {
        try {
            Class<?>[] argTypes = getArgTypes(args);
            Method method = findMethodInternal(obj.getClass(), methodName, argTypes);
            return method.invoke(obj, args);
        } catch (ReflectiveOperationException e) {
            throw new XposedHelpersException("Cannot call method: " + methodName, e);
        }
    }

    public static Object callStaticMethod(Class<?> clazz, String methodName, Object... args) {
        try {
            Class<?>[] argTypes = getArgTypes(args);
            Method method = findMethodInternal(clazz, methodName, argTypes);
            return method.invoke(null, args);
        } catch (ReflectiveOperationException e) {
            throw new XposedHelpersException("Cannot call static method: " + methodName, e);
        }
    }

    public static Object newInstance(Class<?> clazz, Object... args) {
        try {
            Class<?>[] argTypes = getArgTypes(args);
            Constructor<?> ctor = findConstructorInternal(clazz, argTypes);
            return ctor.newInstance(args);
        } catch (ReflectiveOperationException e) {
            throw new XposedHelpersException("Cannot instantiate: " + clazz.getName(), e);
        }
    }

    // ---- Additional typed accessors ----
    public static int getAdditionalInstanceField(Object obj, String key, int defaultValue) {
        return defaultValue;
    }

    // ---- Private helpers ----

    private static Class<?>[] resolveParamTypes(Object[] paramTypeObjs) {
        Class<?>[] types = new Class<?>[paramTypeObjs.length];
        for (int i = 0; i < paramTypeObjs.length; i++) {
            Object o = paramTypeObjs[i];
            if (o instanceof Class) {
                types[i] = (Class<?>) o;
            } else if (o instanceof String) {
                types[i] = ClassUtils.getPrimitiveOrClass((String) o);
            } else {
                throw new IllegalArgumentException("Invalid parameter type at index " + i + ": " + o);
            }
        }
        return types;
    }

    private static Class<?>[] getArgTypes(Object[] args) {
        if (args == null) return new Class<?>[0];
        Class<?>[] types = new Class<?>[args.length];
        for (int i = 0; i < args.length; i++) {
            types[i] = args[i] != null ? args[i].getClass() : Object.class;
        }
        return types;
    }

    private static Constructor<?> findConstructorInternal(Class<?> clazz, Class<?>... paramTypes) {
        try {
            Constructor<?> c = clazz.getDeclaredConstructor(paramTypes);
            c.setAccessible(true);
            return c;
        } catch (NoSuchMethodException e) {
            throw new XposedHelpersException("Constructor not found in " + clazz.getName() + " with params " + Arrays.toString(paramTypes));
        }
    }

    private static Object[] combineArrays(Object[] first, Object last) {
        Object[] result = Arrays.copyOf(first, first.length + 1);
        result[first.length] = last;
        return result;
    }

    public static class XposedHelpersException extends RuntimeException {
        public XposedHelpersException(String message) { super(message); }
        public XposedHelpersException(String message, Throwable cause) { super(message, cause); }
    }

    private static final class ClassUtils {
        static Class<?> getPrimitiveOrClass(String name) {
            switch (name) {
                case "boolean": return boolean.class;
                case "byte": return byte.class;
                case "char": return char.class;
                case "double": return double.class;
                case "float": return float.class;
                case "int": return int.class;
                case "long": return long.class;
                case "short": return short.class;
                case "void": return void.class;
                default:
                    try {
                        return Class.forName(name);
                    } catch (ClassNotFoundException e) {
                        throw new XposedHelpersException("Class not found: " + name, e);
                    }
            }
        }
    }

    private XposedHelpers() {}
}
