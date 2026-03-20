package dev.zenpatch.runtime

import android.content.pm.PackageInfo
import android.content.pm.Signature
import android.content.pm.SigningInfo
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import timber.log.Timber
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * Hooks PackageManager.getPackageInfo() and hasSigningCertificate() to return
 * the original certificates embedded in assets/zenpatch/original_cert.der.
 * This allows apps that verify their own signature to work normally despite re-signing.
 */
class SignatureSpoof(
    private val targetPackageName: String,
    private val originalCertDer: ByteArray?
) {

    private val originalSignature: Signature? by lazy {
        originalCertDer?.let {
            try {
                Signature(it)
            } catch (e: Exception) {
                Timber.e(e, "Failed to create Signature from cert DER")
                null
            }
        }
    }

    private val originalCert: X509Certificate? by lazy {
        originalCertDer?.let {
            try {
                CertificateFactory.getInstance("X.509")
                    .generateCertificate(it.inputStream()) as X509Certificate
            } catch (e: Exception) {
                Timber.e(e, "Failed to parse original certificate")
                null
            }
        }
    }

    /**
     * Installs all signature spoofing hooks.
     * Should be called after NativeBridge.init() and HiddenApiBypass.install().
     */
    fun install(): Boolean {
        if (originalCertDer == null) {
            Timber.w("No original cert available for signature spoofing")
            return false
        }

        var success = true
        success = hookGetPackageInfo() && success
        success = hookHasSigningCertificate() && success

        Timber.d("SignatureSpoof installed for %s (success=%b)", targetPackageName, success)
        return success
    }

    private fun hookGetPackageInfo(): Boolean {
        return try {
            // Hook ApplicationPackageManager.getPackageInfo(String, int)
            XposedHelpers.findAndHookMethod(
                "android.app.ApplicationPackageManager",
                ClassLoader.getSystemClassLoader(),
                "getPackageInfo",
                String::class.java,
                Int::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val pkgName = param.args[0] as? String ?: return
                        if (pkgName != targetPackageName) return
                        val info = param.result as? PackageInfo ?: return
                        injectOriginalSignature(info)
                    }
                }
            )

            // Also hook getPackageInfo(String, PackageInfoFlags) for API 33+
            try {
                val flagsClass = Class.forName("android.content.pm.PackageManager\$PackageInfoFlags")
                XposedHelpers.findAndHookMethod(
                    "android.app.ApplicationPackageManager",
                    ClassLoader.getSystemClassLoader(),
                    "getPackageInfo",
                    String::class.java,
                    flagsClass,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val pkgName = param.args[0] as? String ?: return
                            if (pkgName != targetPackageName) return
                            val info = param.result as? PackageInfo ?: return
                            injectOriginalSignature(info)
                        }
                    }
                )
            } catch (_: Exception) { /* API < 33, ignore */ }

            true
        } catch (e: Exception) {
            Timber.e(e, "Failed to hook getPackageInfo")
            false
        }
    }

    private fun hookHasSigningCertificate(): Boolean {
        return try {
            XposedHelpers.findAndHookMethod(
                "android.app.ApplicationPackageManager",
                ClassLoader.getSystemClassLoader(),
                "hasSigningCertificate",
                String::class.java,
                ByteArray::class.java,
                Int::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val pkgName = param.args[0] as? String ?: return
                        if (pkgName != targetPackageName) return
                        val certBytes = param.args[1] as? ByteArray ?: return
                        val cert = originalCertDer ?: return
                        // Return true if the queried cert matches the original
                        param.result = cert.contentEquals(certBytes)
                    }
                }
            )
            true
        } catch (e: Exception) {
            Timber.w(e, "Could not hook hasSigningCertificate (may not be available)")
            true // Non-fatal
        }
    }

    private fun injectOriginalSignature(info: PackageInfo) {
        val sig = originalSignature ?: return
        try {
            // Set signatures array (legacy, pre-API 28)
            val signaturesField = PackageInfo::class.java.getDeclaredField("signatures")
            signaturesField.isAccessible = true
            signaturesField.set(info, arrayOf(sig))

            // Set signingInfo for API 28+
            injectSigningInfo(info, sig)

            Timber.d("Signature spoofed for %s", targetPackageName)
        } catch (e: Exception) {
            Timber.e(e, "Failed to inject original signature")
        }
    }

    private fun injectSigningInfo(info: PackageInfo, sig: Signature) {
        try {
            // SigningInfo is available since API 28
            val signingInfoClass = Class.forName("android.content.pm.SigningInfo")
            val constructor = signingInfoClass.getDeclaredConstructors().firstOrNull() ?: return
            constructor.isAccessible = true

            // Build a SigningInfo with the original signature
            // SigningInfo(PackageParser.SigningDetails) - internal constructor
            // We use reflection to set the signingDetails field
            val signingInfoField = PackageInfo::class.java.getDeclaredField("signingInfo")
            signingInfoField.isAccessible = true

            val currentSigningInfo = signingInfoField.get(info)
            if (currentSigningInfo != null) {
                // Try to set the signatures within signingInfo
                val innerFields = signingInfoClass.declaredFields
                for (field in innerFields) {
                    field.isAccessible = true
                    val value = field.get(currentSigningInfo)
                    if (value is Array<*> && value.isArrayOf<Signature>()) {
                        field.set(currentSigningInfo, arrayOf(sig))
                        break
                    }
                }
            }
        } catch (e: Exception) {
            // Not critical - signatures array injection above covers most cases
            Timber.d("SigningInfo injection skipped: %s", e.message)
        }
    }

    companion object {
        /**
         * Creates a SignatureSpoof by loading original_cert.der from the APK's assets.
         */
        fun fromAssets(packageName: String, classLoader: ClassLoader): SignatureSpoof {
            val certDer = try {
                classLoader.getResourceAsStream("assets/zenpatch/original_cert.der")
                    ?.use { it.readBytes() }
            } catch (e: Exception) {
                Timber.w("No original cert in assets: %s", e.message)
                null
            }
            return SignatureSpoof(packageName, certDer)
        }
    }
}
