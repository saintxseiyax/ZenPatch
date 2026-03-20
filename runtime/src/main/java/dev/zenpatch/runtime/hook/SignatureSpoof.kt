// SPDX-License-Identifier: GPL-3.0-only
package dev.zenpatch.runtime.hook

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.Signature
import android.os.Build
import android.util.Log

/**
 * Installs hooks that intercept PackageManager signature queries and return
 * the original certificate of the patched app rather than the ZenPatch re-signing cert.
 *
 * Hooked methods:
 *  - `android.app.ApplicationPackageManager#getPackageInfo(String, int)`
 *  - `android.app.ApplicationPackageManager#getPackageInfo(String, PackageInfoFlags)`
 *  - `android.app.ApplicationPackageManager#hasSigningCertificate(String, byte[], int)`
 *
 * The original certificate bytes are loaded from `assets/zenpatch/original_cert.der`
 * embedded by the patcher at signing time.
 */
object SignatureSpoof {

    private const val TAG = "ZP_SignatureSpoof"
    private const val CERT_ASSET_PATH = "zenpatch/original_cert.der"

    /** Flag values from PackageManager */
    private const val GET_SIGNATURES = 0x40
    private const val GET_SIGNING_CERTIFICATES = 0x8000000

    /**
     * Install signature-spoofing hooks for the given [context].
     *
     * @param context Application context of the patched app.
     */
    fun install(context: Context) {
        Log.d(TAG, "Installing signature spoof hooks")

        // Step 1: Load original certificate — abort gracefully if absent
        val originalCertBytes = loadOriginalCert(context)
        if (originalCertBytes == null) {
            Log.w(TAG, "No original cert found in assets — signature spoofing disabled")
            return
        }

        val originalSignature = Signature(originalCertBytes)
        val hostPackageName = context.packageName

        // Resolve ApplicationPackageManager class
        val pmClass: Class<*> = try {
            Class.forName("android.app.ApplicationPackageManager")
        } catch (e: ClassNotFoundException) {
            Log.e(TAG, "Could not find ApplicationPackageManager class", e)
            return
        }

        // ---- Hook 1: getPackageInfo(String, int) ----------------------------------------
        hookGetPackageInfoInt(pmClass, hostPackageName, originalCertBytes, originalSignature)

        // ---- Hook 2: getPackageInfo(String, PackageInfoFlags) — Android 11+ (T/API 33) --
        hookGetPackageInfoFlags(pmClass, hostPackageName, originalCertBytes, originalSignature)

        // ---- Hook 3: hasSigningCertificate(String, byte[], int) — API 28+ ----------------
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            hookHasSigningCertificate(pmClass, hostPackageName, originalCertBytes)
        }

        Log.i(TAG, "Signature spoof hooks installed for $hostPackageName")
    }

    // ---- Private hook installers --------------------------------------------------------

    private fun hookGetPackageInfoInt(
        pmClass: Class<*>,
        hostPackageName: String,
        originalCertBytes: ByteArray,
        originalSignature: Signature
    ) {
        val method = try {
            pmClass.getDeclaredMethod("getPackageInfo", String::class.java, Int::class.javaPrimitiveType)
        } catch (e: NoSuchMethodException) {
            Log.w(TAG, "getPackageInfo(String, int) not found", e)
            return
        }

        HookEngine.hook(method, object : HookEngine.MethodCallback {
            override fun afterHookedMethod(param: HookEngine.MethodHookParam) {
                val packageName = param.args[0] as? String ?: return
                val flags = param.args[1] as? Int ?: return
                if (packageName != hostPackageName) return
                if (flags and (GET_SIGNATURES or GET_SIGNING_CERTIFICATES) == 0) return

                val packageInfo = param.result as? PackageInfo ?: return
                replaceSignatures(packageInfo, originalCertBytes, originalSignature)
            }
        }) ?: Log.w(TAG, "Failed to hook getPackageInfo(String, int)")
    }

    /**
     * Hook the `getPackageInfo(String, PackageInfoFlags)` overload added in Android T (API 33).
     * This uses reflection to locate the method — if it doesn't exist the hook is silently skipped.
     */
    private fun hookGetPackageInfoFlags(
        pmClass: Class<*>,
        hostPackageName: String,
        originalCertBytes: ByteArray,
        originalSignature: Signature
    ) {
        // PackageInfoFlags is an opaque object wrapper — its int value is accessed via getValue()
        val packageInfoFlagsClass = try {
            Class.forName("android.content.pm.PackageManager\$PackageInfoFlags")
        } catch (e: ClassNotFoundException) {
            // Pre-Android T; skip this overload
            return
        }

        val method = try {
            pmClass.getDeclaredMethod("getPackageInfo", String::class.java, packageInfoFlagsClass)
        } catch (e: NoSuchMethodException) {
            // Not present on this ROM; skip
            return
        }

        // Resolve getValue() to read the int flags from PackageInfoFlags
        val getValueMethod = try {
            packageInfoFlagsClass.getDeclaredMethod("getValue")
        } catch (e: NoSuchMethodException) {
            Log.w(TAG, "PackageInfoFlags.getValue() not found")
            return
        }

        HookEngine.hook(method, object : HookEngine.MethodCallback {
            override fun afterHookedMethod(param: HookEngine.MethodHookParam) {
                val packageName = param.args[0] as? String ?: return
                if (packageName != hostPackageName) return
                val flagsObj = param.args[1] ?: return
                val flags = try {
                    (getValueMethod.invoke(flagsObj) as? Long)?.toInt() ?: return
                } catch (e: Exception) {
                    return
                }
                if (flags and (GET_SIGNATURES or GET_SIGNING_CERTIFICATES) == 0) return

                val packageInfo = param.result as? PackageInfo ?: return
                replaceSignatures(packageInfo, originalCertBytes, originalSignature)
            }
        }) ?: Log.w(TAG, "Failed to hook getPackageInfo(String, PackageInfoFlags)")
    }

    private fun hookHasSigningCertificate(
        pmClass: Class<*>,
        hostPackageName: String,
        originalCertBytes: ByteArray
    ) {
        val method = try {
            pmClass.getDeclaredMethod(
                "hasSigningCertificate",
                String::class.java,
                ByteArray::class.java,
                Int::class.javaPrimitiveType
            )
        } catch (e: NoSuchMethodException) {
            Log.w(TAG, "hasSigningCertificate(String, byte[], int) not found", e)
            return
        }

        HookEngine.hook(method, object : HookEngine.MethodCallback {
            override fun afterHookedMethod(param: HookEngine.MethodHookParam) {
                val packageName = param.args[0] as? String ?: return
                if (packageName != hostPackageName) return
                val queryCert = param.args[1] as? ByteArray ?: return
                // Replace the result with a direct comparison against the original cert
                param.result = originalCertBytes.contentEquals(queryCert)
            }
        }) ?: Log.w(TAG, "Failed to hook hasSigningCertificate")
    }

    // ---- Signature replacement ----------------------------------------------------------

    /**
     * Replace both [PackageInfo.signatures] (legacy) and [PackageInfo.signingInfo] (API 28+)
     * with data derived from [originalCertBytes].
     */
    private fun replaceSignatures(
        packageInfo: PackageInfo,
        originalCertBytes: ByteArray,
        originalSignature: Signature
    ) {
        // Legacy field — present on all API levels
        packageInfo.signatures = arrayOf(originalSignature)

        // Modern API: SigningInfo (API 28+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            replaceSigningInfo(packageInfo, originalCertBytes)
        }
    }

    /**
     * Replace [PackageInfo.signingInfo] using reflection, since [android.content.pm.SigningInfo]
     * is final with no public constructor/setters usable from the outside.
     *
     * We write directly to the `signingInfo` field of [PackageInfo] to avoid a hard compile-time
     * dependency on hidden SDK classes.
     */
    private fun replaceSigningInfo(packageInfo: PackageInfo, originalCertBytes: ByteArray) {
        try {
            // Build a fake SigningDetails-like structure via the public SigningInfo hidden constructor.
            // On AOSP, SigningInfo has a hidden constructor: SigningInfo(PackageParser.SigningDetails).
            // The safest approach for a non-root context is to replace the field with a
            // dynamically-created instance via reflection into the no-arg constructor path,
            // then patch its internal `signatures` field.
            val signingInfoClass = Class.forName("android.content.pm.SigningInfo")

            // Try to find the internal `mSigningDetails` or direct `signatures` field
            // The field name varies by AOSP version:
            //   API 28-32: mSigningDetails (type: PackageParser.SigningDetails)
            //   API 33+  : mSigningDetails (type: android.content.pm.SigningDetails)
            // Since we cannot easily construct a SigningDetails object, we instead
            // reflect on SigningInfo's own signature accessor path and directly
            // overwrite the `PackageInfo.signingInfo` field with a crafted instance.

            // Approach: allocate a new SigningInfo without calling any constructor,
            // then set its internal signatures field via reflection.
            val newSigningInfo = try {
                // Try no-arg constructor first (may not exist)
                signingInfoClass.getDeclaredConstructor().also { it.isAccessible = true }.newInstance()
            } catch (e: NoSuchMethodException) {
                // Allocate without constructor (sun.misc.Unsafe is not available on Android;
                // use ObjectInputStream allocator trick via reflection)
                val objStreamClass = Class.forName("java.io.ObjectStreamClass")
                val newInstanceMethod = objStreamClass.getDeclaredMethod("newInstance", Class::class.java)
                newInstanceMethod.isAccessible = true
                newInstanceMethod.invoke(null, signingInfoClass)
            } ?: return

            // Try to set the internal `mSigningDetails` field to a structure that returns
            // our original cert from getApkContentsSigners() / hasMultipleSigners().
            // As a pragmatic fallback: patch the `signingInfo` field on PackageInfo directly
            // to our instance (even if its internals are still empty, we've replaced signatures[]).
            // Most signature-checking code uses `PackageInfo.signatures` (legacy array) which
            // we already patched above. signingInfo is a secondary path.

            // Attempt to patch the `signatures` field inside SigningInfo's mSigningDetails
            patchSigningInfoSignatures(signingInfoClass, newSigningInfo, originalCertBytes)

            // Write the crafted SigningInfo back to the PackageInfo
            val signingInfoField = PackageInfo::class.java.getDeclaredField("signingInfo")
            signingInfoField.isAccessible = true
            signingInfoField.set(packageInfo, newSigningInfo)
        } catch (e: Exception) {
            Log.d(TAG, "replaceSigningInfo: non-critical failure (signatures[] already patched)", e)
        }
    }

    /**
     * Best-effort: locate the `Signature[]`-typed field(s) inside the given [signingInfo]
     * instance and replace them with our original cert.
     */
    private fun patchSigningInfoSignatures(signingInfoClass: Class<*>, signingInfo: Any, originalCertBytes: ByteArray) {
        val originalSig = Signature(originalCertBytes)
        val sigArray = arrayOf(originalSig)

        // Walk the declared fields of SigningInfo (and its superclasses) looking for
        // Signature[] fields to overwrite.
        var clazz: Class<*>? = signingInfoClass
        while (clazz != null && clazz != Any::class.java) {
            for (field in clazz.declaredFields) {
                if (field.type == Array<Signature>::class.java ||
                    field.type.name == "[Landroid.content.pm.Signature;"
                ) {
                    try {
                        field.isAccessible = true
                        field.set(signingInfo, sigArray)
                        Log.d(TAG, "Patched SigningInfo field: ${field.name}")
                    } catch (e: Exception) {
                        Log.d(TAG, "Could not patch SigningInfo.${field.name}: ${e.message}")
                    }
                }
            }
            clazz = clazz.superclass
        }
    }

    /**
     * Load the original APK certificate from the app's assets.
     *
     * @param context Application context.
     * @return DER-encoded certificate bytes, or null if not found.
     */
    private fun loadOriginalCert(context: Context): ByteArray? {
        return try {
            context.assets.open(CERT_ASSET_PATH).use { it.readBytes() }
        } catch (e: Exception) {
            Log.w(TAG, "Original cert not found in assets: $CERT_ASSET_PATH", e)
            null
        }
    }
}
