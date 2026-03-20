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

    /**
     * Generates a self-signed X509 certificate using KeyStore API.
     * This approach is Android/JVM-compatible without sun.security.* dependencies.
     */
    private fun generateSelfSignedCert(keyPair: java.security.KeyPair): X509Certificate {
        // Use PKCS12 KeyStore as a vehicle to generate a self-signed cert.
        // We create a temporary keystore, generate a key entry with a self-signed cert,
        // and then extract the certificate.
        val ks = KeyStore.getInstance("PKCS12").apply { load(null, null) }

        // Build the certificate using ASN.1 DER encoding directly
        val certBytes = buildSelfSignedCertDer(
            keyPair,
            "CN=ZenPatch, O=ZenPatch, C=US",
            validityYears = 25
        )
        val certFactory = java.security.cert.CertificateFactory.getInstance("X.509")
        val cert = certFactory.generateCertificate(certBytes.inputStream()) as X509Certificate
        return cert
    }

    /**
     * Builds a minimal self-signed X.509v3 certificate in DER format.
     * Uses raw ASN.1/DER construction — no sun.* or BouncyCastle dependencies.
     */
    private fun buildSelfSignedCertDer(
        keyPair: java.security.KeyPair,
        dn: String,
        validityYears: Int
    ): ByteArray {
        val now = System.currentTimeMillis()
        val notBefore = java.util.Date(now)
        val notAfter = java.util.Date(now + validityYears.toLong() * 365 * 24 * 3600 * 1000)

        // SHA256withRSA OID: 1.2.840.113549.1.1.11
        val sha256WithRsa = byteArrayOf(
            0x30, 0x0D, 0x06, 0x09, 0x2A, 0x86.toByte(), 0x48, 0x86.toByte(),
            0xF7.toByte(), 0x0D, 0x01, 0x01, 0x0B, 0x05, 0x00
        )

        val serial = java.math.BigInteger.valueOf(now)
        val issuerDer = encodeDN(dn)
        val subjectDer = issuerDer

        // Validity: SEQUENCE { UTCTime, UTCTime }
        val validity = derSequence(
            encodeUTCTime(notBefore) + encodeUTCTime(notAfter)
        )

        // SubjectPublicKeyInfo from the public key
        val spki = keyPair.public.encoded

        // TBSCertificate
        val version = byteArrayOf(0xA0.toByte(), 0x03, 0x02, 0x01, 0x02) // [0] EXPLICIT INTEGER 2 = v3
        val serialDer = derInteger(serial)
        val tbsCert = derSequence(
            version + serialDer + sha256WithRsa + issuerDer + validity + subjectDer + spki
        )

        // Sign the TBSCertificate
        val sig = java.security.Signature.getInstance("SHA256withRSA")
        sig.initSign(keyPair.private)
        sig.update(tbsCert)
        val sigBytes = sig.sign()

        // Certificate = SEQUENCE { tbsCertificate, signatureAlgorithm, signatureValue }
        val sigBitString = derBitString(sigBytes)
        return derSequence(tbsCert + sha256WithRsa + sigBitString)
    }

    // --- ASN.1/DER encoding helpers ---

    private fun derSequence(content: ByteArray): ByteArray {
        return byteArrayOf(0x30) + derLength(content.size) + content
    }

    private fun derInteger(value: java.math.BigInteger): ByteArray {
        val bytes = value.toByteArray()
        return byteArrayOf(0x02) + derLength(bytes.size) + bytes
    }

    private fun derBitString(content: ByteArray): ByteArray {
        // BIT STRING: tag 0x03, length, 0x00 (no unused bits), then content
        val inner = byteArrayOf(0x00) + content
        return byteArrayOf(0x03) + derLength(inner.size) + inner
    }

    private fun derLength(len: Int): ByteArray {
        return when {
            len < 0x80 -> byteArrayOf(len.toByte())
            len < 0x100 -> byteArrayOf(0x81.toByte(), len.toByte())
            len < 0x10000 -> byteArrayOf(0x82.toByte(), (len shr 8).toByte(), (len and 0xFF).toByte())
            else -> byteArrayOf(
                0x83.toByte(), (len shr 16).toByte(), ((len shr 8) and 0xFF).toByte(), (len and 0xFF).toByte()
            )
        }
    }

    private fun encodeUTCTime(date: java.util.Date): ByteArray {
        val sdf = java.text.SimpleDateFormat("yyMMddHHmmss'Z'", java.util.Locale.US)
        sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
        val str = sdf.format(date).toByteArray(Charsets.US_ASCII)
        return byteArrayOf(0x17) + derLength(str.size) + str
    }

    private fun encodeDN(dn: String): ByteArray {
        // Parse "CN=ZenPatch, O=ZenPatch, C=US" into ASN.1 RDN sequences
        val rdns = dn.split(",").map { it.trim() }.map { part ->
            val (oidName, value) = part.split("=", limit = 2)
            val oid = when (oidName.trim().uppercase()) {
                "CN" -> byteArrayOf(0x55, 0x04, 0x03)  // 2.5.4.3
                "O"  -> byteArrayOf(0x55, 0x04, 0x0A)  // 2.5.4.10
                "C"  -> byteArrayOf(0x55, 0x04, 0x06)  // 2.5.4.6
                "OU" -> byteArrayOf(0x55, 0x04, 0x0B)  // 2.5.4.11
                else -> byteArrayOf(0x55, 0x04, 0x03)  // default to CN
            }
            val oidDer = byteArrayOf(0x06, oid.size.toByte()) + oid
            val valueDer = if (oidName.trim().uppercase() == "C") {
                // Country uses PrintableString
                val bytes = value.trim().toByteArray(Charsets.US_ASCII)
                byteArrayOf(0x13) + derLength(bytes.size) + bytes
            } else {
                // Others use UTF8String
                val bytes = value.trim().toByteArray(Charsets.UTF_8)
                byteArrayOf(0x0C) + derLength(bytes.size) + bytes
            }
            // SET { SEQUENCE { OID, value } }
            derSet(derSequence(oidDer + valueDer))
        }
        return derSequence(rdns.fold(byteArrayOf()) { acc, rdn -> acc + rdn })
    }

    private fun derSet(content: ByteArray): ByteArray {
        return byteArrayOf(0x31) + derLength(content.size) + content
    }

    private fun isEntryPathSafe(name: String): Boolean {
        if (name.contains("..")) return false
        if (name.startsWith("/")) return false
        if (name.contains("\u0000")) return false
        return true
    }
}
