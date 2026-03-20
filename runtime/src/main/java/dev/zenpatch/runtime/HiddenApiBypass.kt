package dev.zenpatch.runtime

import timber.log.Timber
import java.lang.reflect.Method

/**
 * Bypasses Android's hidden API restrictions on API 28+.
 * Uses the setHiddenApiExemptions approach via reflection + JNI tricks.
 */
object HiddenApiBypass {

    private var bypassed = false

    /**
     * Installs the hidden API bypass.
     * Must be called early in attachBaseContext before any hidden API access.
     * @return true if bypass was successfully installed
     */
    fun install(): Boolean {
        if (bypassed) return true
        bypassed = tryReflectionBypass() || tryNativeBypass()
        Timber.d("HiddenApiBypass installed: %b", bypassed)
        return bypassed
    }

    /**
     * Exempt all hidden APIs for this process.
     * Uses VMRuntime.setHiddenApiExemptions(["L"]) to exempt all classes.
     */
    private fun tryReflectionBypass(): Boolean {
        return try {
            // Get VMRuntime via double reflection
            val forNameMethod: Method = Class::class.java.getDeclaredMethod("forName", String::class.java)
            val vmRuntimeClass = forNameMethod.invoke(null, "dalvik.system.VMRuntime") as Class<*>

            val getRuntimeMethod: Method = vmRuntimeClass.getDeclaredMethod("getRuntime")
            val vmRuntime = getRuntimeMethod.invoke(null)

            val setHiddenApiExemptionsMethod: Method = vmRuntimeClass.getDeclaredMethod(
                "setHiddenApiExemptions",
                Array<String>::class.java
            )

            // Exempt all APIs matching "L" prefix (= all classes)
            setHiddenApiExemptionsMethod.invoke(vmRuntime, arrayOf("L"))
            Timber.d("Hidden API bypass via VMRuntime.setHiddenApiExemptions succeeded")
            true
        } catch (e: Exception) {
            Timber.w(e, "VMRuntime.setHiddenApiExemptions approach failed")
            false
        }
    }

    /**
     * Alternative bypass via native method invocation.
     * Calls the JNI method directly, bypassing the Java-level restriction check.
     */
    private fun tryNativeBypass(): Boolean {
        return try {
            if (!NativeBridge.isReady()) return false
            nativeBypassHiddenApis()
            true
        } catch (e: Exception) {
            Timber.w(e, "Native hidden API bypass failed")
            false
        }
    }

    @JvmStatic
    private external fun nativeBypassHiddenApis()

    /**
     * Checks whether a specific class or method would be blocked by hidden API restrictions.
     * Returns true if access is permitted (either not hidden, or bypass is active).
     */
    fun isAccessible(className: String): Boolean {
        if (bypassed) return true
        return try {
            Class.forName(className)
            true
        } catch (e: ClassNotFoundException) {
            false
        }
    }
}
