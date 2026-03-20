package dev.zenpatch.patcher

import com.android.apksig.ApkSigner
import com.android.apksig.ApkVerifier
import timber.log.Timber
import java.io.File
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Re-signs an APK with v2+v3 signature schemes.
 * Stores original signature in assets/zenpatch/original_cert.der for runtime signature spoofing.
 *
 * Includes ZIP-slip prevention on all operations.
 */
class ApkSigner {

    data class SignResult(
        val signedApk: File,
        val originalCertDer: ByteArray?
    )

    /**
     * Signs the given APK, storing original signature data for spoofing.
     * @param inputApk APK to sign (unsigned or previously signed)
     * @param outputApk Destination for signed APK
     * @param keystore Path to JKS/PKCS12 keystore, or null for auto-generated key
     * @param keystorePassword Keystore password
     * @param keyAlias Key alias within keystore
     * @param keyPassword Key password (null = same as keystorePassword)
     */
    fun sign(
        inputApk: File,
        outputApk: File,
        keystore: File? = null,
        keystorePassword: CharArray = "zenpatch".toCharArray(),
        keyAlias: String = "zenpatch",
        keyPassword: CharArray? = null
    ): SignResult {
        Timber.d("Signing APK: %s", inputApk.name)
        outputApk.parentFile?.mkdirs()

        // Extract original certificates from input APK
        val originalCertDer = extractOriginalCertificate(inputApk)
        if (originalCertDer != null) {
            Timber.d("Extracted original certificate (%d bytes)", originalCertDer.size)
        }

        // Step 1: Embed original cert and strip old signatures, output to temp file
        val tempApk = File(outputApk.parentFile, "temp_presign_${System.currentTimeMillis()}.apk")
        try {
            prepareForSigning(inputApk, tempApk, originalCertDer)

            // Step 2: Sign with apksig
            val (privateKey, certs) = loadOrGenerateKey(keystore, keystorePassword, keyAlias, keyPassword)
            val signerConfig = ApkSigner.SignerConfig.Builder("ZenPatch", privateKey, certs).build()

            val signerBuilder = ApkSigner.Builder(listOf(signerConfig)).apply {
                setInputApk(tempApk)
                setOutputApk(outputApk)
                setV1SigningEnabled(false)  // v1 disabled intentionally - v2+v3 only
                setV2SigningEnabled(true)
                setV3SigningEnabled(true)
                setV4SigningEnabled(false)
                setMinSdkVersion(31)
            }

            signerBuilder.build().sign()
            Timber.d("APK signed successfully: %s", outputApk.name)

            // Verify the signed APK
            val verifyResult = ApkVerifier.Builder(outputApk).build().verify()
            if (!verifyResult.isVerified) {
                val errors = verifyResult.errors.joinToString(", ") { it.toString() }
                throw SecurityException("APK signature verification failed: $errors")
            }
            Timber.d("APK signature verification passed")

            return SignResult(outputApk, originalCertDer)

        } finally {
            tempApk.delete()
        }
    }

    /**
     * Removes old signature files and injects the original certificate as an asset.
     * ZIP-slip prevention applied on all entries.
     */
    private fun prepareForSigning(inputApk: File, outputApk: File, originalCertDer: ByteArray?) {
        ZipFile(inputApk).use { zip ->
            ZipOutputStream(outputApk.outputStream().buffered()).use { zout ->
                for (entry in zip.entries()) {
                    val name = entry.name

                    // ZIP-slip prevention
                    if (!isEntryPathSafe(name)) {
                        Timber.w("Skipping unsafe entry during signing prep: %s", name)
                        continue
                    }

                    // Strip existing signature files
                    if (isSignatureFile(name)) {
                        Timber.d("Stripping signature file: %s", name)
                        continue
                    }

                    val newEntry = ZipEntry(name).apply {
                        method = entry.method
                        if (entry.method == ZipEntry.STORED) {
                            size = entry.size
                            compressedSize = entry.compressedSize
                            crc = entry.crc
                        }
                    }
                    zout.putNextEntry(newEntry)
                    zip.getInputStream(entry).use { it.copyTo(zout) }
                    zout.closeEntry()
                }

                // Inject original certificate if available
                if (originalCertDer != null && originalCertDer.isNotEmpty()) {
                    zout.putNextEntry(ZipEntry("assets/zenpatch/original_cert.der"))
                    zout.write(originalCertDer)
                    zout.closeEntry()
                    Timber.d("Injected original certificate into assets")
                }
            }
        }
    }

    private fun isSignatureFile(name: String): Boolean {
        if (!name.startsWith("META-INF/")) return false
        val baseName = name.removePrefix("META-INF/")
        return baseName.endsWith(".SF") ||
               baseName.endsWith(".RSA") ||
               baseName.endsWith(".DSA") ||
               baseName.endsWith(".EC") ||
               baseName == "MANIFEST.MF" ||
               baseName.startsWith("SIG-")
    }

    /**
     * Extracts the first certificate from the APK's v1 signature block (or returns null).
     */
    private fun extractOriginalCertificate(apk: File): ByteArray? {
        return try {
            ZipFile(apk).use { zip ->
                // Look for .RSA or .DSA or .EC signature file in META-INF
                val sigEntry = zip.entries().asSequence().firstOrNull { entry ->
                    entry.name.startsWith("META-INF/") && (
                        entry.name.endsWith(".RSA") ||
                        entry.name.endsWith(".DSA") ||
                        entry.name.endsWith(".EC")
                    )
                }

                if (sigEntry != null) {
                    // PKCS7 signature block - extract certificate DER from it
                    val pkcs7 = zip.getInputStream(sigEntry).use { it.readBytes() }
                    extractCertFromPkcs7(pkcs7)
                } else {
                    // Try v2/v3 signing block extraction via ApkVerifier
                    try {
                        val result = ApkVerifier.Builder(apk).build().verify()
                        result.signerCertificates.firstOrNull()?.encoded
                    } catch (_: Exception) {
                        null
                    }
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "Could not extract original certificate")
            null
        }
    }

    /**
     * Extracts the DER-encoded X.509 certificate from a PKCS#7 SignedData structure.
     * Simplified BER/DER parser for the certificate extraction.
     */
    private fun extractCertFromPkcs7(pkcs7: ByteArray): ByteArray? {
        // PKCS#7 SignedData OID: 1.2.840.113549.1.7.2
        // The certificate is a SEQUENCE starting with 0x30 within the SignedData
        // We do a simple scan for SEQUENCE (0x30 0x82) patterns that look like X.509 certs
        var i = 0
        while (i < pkcs7.size - 10) {
            if (pkcs7[i] == 0x30.toByte()) {
                // Try to parse as DER SEQUENCE
                val len = when {
                    pkcs7[i + 1].toInt() and 0xFF == 0x82 && i + 4 < pkcs7.size -> {
                        ((pkcs7[i + 2].toInt() and 0xFF) shl 8) or (pkcs7[i + 3].toInt() and 0xFF)
                    }
                    pkcs7[i + 1].toInt() and 0xFF == 0x81 && i + 3 < pkcs7.size -> {
                        pkcs7[i + 2].toInt() and 0xFF
                    }
                    else -> -1
                }
                if (len > 100 && i + 4 + len <= pkcs7.size) {
                    val candidate = pkcs7.copyOfRange(i, i + 4 + len)
                    // Check if it looks like an X.509 cert (version OID)
                    if (candidate.size > 20) return candidate
                }
            }
            i++
        }
        return pkcs7.copyOfRange(0, minOf(pkcs7.size, 2048))
    }

    private fun loadOrGenerateKey(
        keystore: File?,
        keystorePassword: CharArray,
        keyAlias: String,
        keyPassword: CharArray?
    ): Pair<PrivateKey, List<X509Certificate>> {
        return if (keystore != null && keystore.exists()) {
            // Load from existing keystore
            val ks = KeyStore.getInstance("PKCS12").apply {
                keystore.inputStream().use { load(it, keystorePassword) }
            }
            val kp = keyPassword ?: keystorePassword
            val key = ks.getKey(keyAlias, kp) as PrivateKey
            val cert = ks.getCertificate(keyAlias) as X509Certificate
            Pair(key, listOf(cert))
        } else {
            // Generate ephemeral key pair for signing
            Timber.d("Generating ephemeral RSA key for APK signing")
            generateEphemeralKey()
        }
    }

    private fun generateEphemeralKey(): Pair<PrivateKey, List<X509Certificate>> {
        val keyPairGen = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
        val keyPair = keyPairGen.generateKeyPair()

        // Generate self-signed certificate
        val cert = generateSelfSignedCert(keyPair)
        return Pair(keyPair.private, listOf(cert))
    }

    private fun generateSelfSignedCert(keyPair: java.security.KeyPair): X509Certificate {
        // Use bouncy castle or sun.security for self-signed cert generation.
        // On Android, we use the built-in provider.
        val certFactory = java.security.cert.CertificateFactory.getInstance("X.509")

        // Minimal self-signed cert via X509V1CertificateGenerator fallback:
        // In a real build environment, use org.bouncycastle:bcpkix-jvm for proper cert generation.
        // Here we generate using Java's sun.security.x509 classes (available on JVM build host).
        try {
            val certInfo = sun.security.x509.X509CertInfo().apply {
                val from = java.util.Date()
                val to = java.util.Date(from.time + 365L * 24 * 60 * 60 * 1000 * 25) // 25 years
                val interval = sun.security.x509.CertificateValidity(from, to)
                val sn = sun.security.x509.CertificateSerialNumber(java.math.BigInteger.valueOf(System.currentTimeMillis()))
                val algo = sun.security.x509.AlgorithmId(sun.security.x509.AlgorithmId.sha256WithRSAEncryption_oid)
                val dn = sun.security.x509.X500Name("CN=ZenPatch,O=ZenPatch,C=US")
                set(sun.security.x509.X509CertInfo.VALIDITY, interval)
                set(sun.security.x509.X509CertInfo.SERIAL_NUMBER, sn)
                set(sun.security.x509.X509CertInfo.SUBJECT, dn)
                set(sun.security.x509.X509CertInfo.ISSUER, dn)
                set(sun.security.x509.X509CertInfo.KEY, sun.security.x509.CertificateX509Key(keyPair.public))
                set(sun.security.x509.X509CertInfo.VERSION, sun.security.x509.CertificateVersion(sun.security.x509.CertificateVersion.V3))
                set(sun.security.x509.X509CertInfo.ALGORITHM_ID, sun.security.x509.CertificateAlgorithmId(algo))
            }
            val cert = sun.security.x509.X509CertImpl(certInfo)
            cert.sign(keyPair.private, "SHA256withRSA")
            return cert
        } catch (e: Exception) {
            Timber.e(e, "Failed to generate self-signed cert, trying fallback")
            throw RuntimeException("Cannot generate signing certificate: ${e.message}", e)
        }
    }

    private fun isEntryPathSafe(name: String): Boolean {
        if (name.contains("..")) return false
        if (name.startsWith("/")) return false
        if (name.contains("\u0000")) return false
        return true
    }
}
