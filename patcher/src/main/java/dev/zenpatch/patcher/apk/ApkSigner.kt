// SPDX-License-Identifier: GPL-3.0-only
package dev.zenpatch.patcher.apk

import android.util.Log
import com.android.apksig.ApkSigner as AndroidApkSigner
import dev.zenpatch.patcher.model.PatchOptions
import java.io.File
import java.io.FileOutputStream
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Date
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.security.auth.x500.X500Principal

/**
 * Re-signs a patched APK with a ZenPatch-controlled key.
 *
 * ### Signing pipeline
 *
 * 1. **Original certificate extraction** — before re-signing, the original APK's
 *    X.509 signing certificate is read from the APK Signing Block (v2/v3) or,
 *    as a fallback, from the `META-INF/*.RSA` JAR-signature entry.  The raw DER
 *    bytes are stored as `assets/zenpatch/original_cert.der` inside the APK so
 *    that the runtime's [dev.zenpatch.runtime.hook.SignatureSpoof] hook can return
 *    them when an app queries its own signatures via `PackageManager`.
 *
 * 2. **Keystore resolution** — if [PatchOptions.keystorePath] is set, that
 *    keystore is loaded; otherwise a debug RSA-2048 keystore is generated on the
 *    fly and saved as `zenpatch-debug.jks` next to the output APK.
 *
 * 3. **APK signing** — the `com.android.tools.build:apksig` library is used to
 *    produce a properly signed APK with v1 (JAR), v2, and v3 schemes enabled.
 *    The signed output is written to [PatchOptions.outputPath] if set, or to
 *    `<inputName>-patched.apk` alongside the input file.
 *
 * ### Dependencies
 * - `com.android.tools.build:apksig` (already declared in `patcher/build.gradle.kts`)
 */
class ApkSigner {

    companion object {
        private const val TAG = "ApkSigner"

        /** DER entry path for the original certificate stored inside the output APK. */
        private const val ORIG_CERT_ASSET = "assets/zenpatch/original_cert.der"

        /** Default alias and keystore file name for the auto-generated debug key. */
        private const val DEBUG_KEYSTORE_NAME = "zenpatch-debug.jks"
        private const val DEBUG_KEY_ALIAS     = "zenpatch"
        private const val DEBUG_KEY_PASSWORD  = "zenpatch"

        /** Validity period for the auto-generated debug key (30 years in milliseconds). */
        private const val KEY_VALIDITY_MS = 30L * 365 * 24 * 60 * 60 * 1000L

        /** RSA key size used for the auto-generated debug signing key. */
        private const val RSA_KEY_SIZE = 2048

        // ── APK Signing Block magic constants ─────────────────────────────────
        private const val MAGIC_LO = 0x20676953204b5041L   // "APK Sig "
        private const val MAGIC_HI = 0x3234206b636f6c42L   // "Block 42"
        private const val SIG_ID_V2 = 0x7109871a
        private const val SIG_ID_V3 = 0xf05368c0.toInt()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Sign the APK at [apkPath] and produce a signed output APK.
     *
     * @param apkPath Path to the unsigned / modified APK.
     * @param options Signing configuration (keystore, alias, passwords, output path).
     * @return Absolute path to the signed output APK.
     */
    fun sign(apkPath: String, options: PatchOptions): String {
        val inputFile = File(apkPath)
        require(inputFile.exists()) { "APK not found: $apkPath" }

        Log.i(TAG, "Signing APK: $apkPath")

        // ── 1. Extract original certificate ───────────────────────────────────
        val origCertDer: ByteArray? = extractOriginalCertificate(apkPath)
        if (origCertDer != null) {
            Log.d(TAG, "Extracted original certificate (${origCertDer.size} bytes)")
        } else {
            Log.w(TAG, "Could not extract original certificate — signature spoofing may fail at runtime")
        }

        // ── 2. Embed original cert into APK ───────────────────────────────────
        val apkWithCert: String = if (origCertDer != null) {
            embedOriginalCert(apkPath, origCertDer)
        } else {
            apkPath
        }

        // ── 3. Resolve / generate keystore ────────────────────────────────────
        val keystoreFile: File
        val keystorePassword: String
        val keyAlias: String
        val keyPassword: String

        if (options.keystorePath != null) {
            keystoreFile    = File(options.keystorePath)
            keystorePassword = options.keystorePassword
            keyAlias        = options.keyAlias
            keyPassword     = options.keyPassword
            require(keystoreFile.exists()) { "Keystore not found: ${options.keystorePath}" }
            Log.d(TAG, "Using provided keystore: ${options.keystorePath}")
        } else {
            // Generate / reuse debug keystore next to the output APK
            val outputDir = File(
                options.outputPath?.let { File(it).parent }
                    ?: inputFile.parent
                    ?: System.getProperty("java.io.tmpdir")
            )
            keystoreFile    = File(outputDir, DEBUG_KEYSTORE_NAME)
            keystorePassword = DEBUG_KEY_PASSWORD
            keyAlias        = DEBUG_KEY_ALIAS
            keyPassword     = DEBUG_KEY_PASSWORD

            if (!keystoreFile.exists()) {
                Log.d(TAG, "Generating debug keystore: ${keystoreFile.absolutePath}")
                generateDebugKeystore(keystoreFile, keystorePassword, keyAlias, keyPassword)
            } else {
                Log.d(TAG, "Reusing existing debug keystore: ${keystoreFile.absolutePath}")
            }
        }

        // ── 4. Determine output path ──────────────────────────────────────────
        val outputFile: File = if (options.outputPath != null) {
            File(options.outputPath).also { it.parentFile?.mkdirs() }
        } else {
            File(
                inputFile.parent ?: System.getProperty("java.io.tmpdir"),
                "${File(apkPath).nameWithoutExtension.removeSuffix("_manifest").removeSuffix("_native").removeSuffix("_dex")}-patched.apk"
            )
        }
        Log.i(TAG, "Output APK: ${outputFile.absolutePath}")

        // ── 5. Sign with apksig ───────────────────────────────────────────────
        signWithApkSig(
            inputApk       = File(apkWithCert),
            outputApk      = outputFile,
            keystoreFile   = keystoreFile,
            storePassword  = keystorePassword,
            keyAlias       = keyAlias,
            keyPassword    = keyPassword
        )

        Log.i(TAG, "Signing complete: ${outputFile.absolutePath}")
        return outputFile.absolutePath
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Original certificate extraction
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Attempt to extract the raw DER bytes of the first X.509 signing certificate
     * from the APK.
     *
     * Resolution order:
     * 1. APK Signing Block (v2 / v3) — parse the raw APK bytes to locate the
     *    signing block and extract the first signer's certificate chain.
     * 2. META-INF JAR signature (v1) — open as [JarFile] and read the first
     *    `*.RSA` / `*.DSA` / `*.EC` entry as a PKCS#7 SignedData blob.  The
     *    first certificate in the `certificates` sequence is extracted.
     *
     * @param apkPath Path to the original (un-patched) APK.
     * @return Raw DER-encoded X.509 certificate, or null if extraction fails.
     */
    private fun extractOriginalCertificate(apkPath: String): ByteArray? {
        val apkFile = File(apkPath)

        // ── Try APK Signing Block (v2/v3) first ───────────────────────────────
        try {
            val certDer = extractCertFromSigningBlock(apkFile)
            if (certDer != null) {
                Log.d(TAG, "Extracted cert from APK Signing Block (v2/v3)")
                return certDer
            }
        } catch (e: Exception) {
            Log.d(TAG, "Signing block cert extraction failed: ${e.message}")
        }

        // ── Fallback: META-INF JAR signature (v1) ─────────────────────────────
        try {
            val certDer = extractCertFromJarSignature(apkPath)
            if (certDer != null) {
                Log.d(TAG, "Extracted cert from META-INF JAR signature (v1)")
                return certDer
            }
        } catch (e: Exception) {
            Log.d(TAG, "JAR signature cert extraction failed: ${e.message}")
        }

        return null
    }

    /**
     * Parse the APK Signing Block embedded in the raw APK file to extract the
     * first signer's certificate from a v2 or v3 signature block.
     *
     * APK Signature Scheme v2/v3 certificate encoding (simplified):
     * ```
     * SignedData {
     *   digests     : length-prefixed sequence
     *   certificates: length-prefixed sequence of length-prefixed DER certs
     *   ...
     * }
     * Signature {
     *   signedData : length-prefixed bytes (above)
     *   signatures : length-prefixed sequence
     *   publicKey  : length-prefixed bytes
     * }
     * SignerBlock {
     *   signers : length-prefixed sequence of length-prefixed Signature
     * }
     * ```
     *
     * All lengths are 4-byte little-endian unsigned integers.
     *
     * @param apkFile The APK file to read.
     * @return Raw DER certificate bytes of the first signer, or null.
     */
    private fun extractCertFromSigningBlock(apkFile: File): ByteArray? {
        val bytes = apkFile.readBytes()
        if (bytes.size < 22) return null

        val buf = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)

        // ── Locate EOCD ───────────────────────────────────────────────────────
        var eocdOffset = bytes.size - 22
        while (eocdOffset >= 0 && buf.getInt(eocdOffset) != 0x06054b50) eocdOffset--
        if (eocdOffset < 0) return null

        val cdOffset = buf.getInt(eocdOffset + 16).toLong() and 0xFFFFFFFFL

        // ── Locate APK Signing Block magic ────────────────────────────────────
        val magicStart = (cdOffset - 16).toInt()
        if (magicStart < 0) return null
        if (buf.getLong(magicStart) != MAGIC_LO || buf.getLong(magicStart + 8) != MAGIC_HI) {
            return null
        }

        val blockSize   = buf.getLong((cdOffset - 24).toInt())
        val blockStart  = (cdOffset - 24 - blockSize).toInt()
        if (blockStart < 0 || blockSize < 8) return null

        // ── Iterate id-value pairs ────────────────────────────────────────────
        var pairOffset  = blockStart + 8
        val pairsEnd    = blockStart + 8 + blockSize.toInt() - 8

        while (pairOffset < pairsEnd - 12) {
            val pairLen = buf.getLong(pairOffset).toInt()
            if (pairLen < 4 || pairOffset + 8 + pairLen > bytes.size) break
            val blockId = buf.getInt(pairOffset + 8)

            if (blockId == SIG_ID_V2 || blockId == SIG_ID_V3) {
                // Block value starts at pairOffset + 12 (skip 8-byte len + 4-byte id)
                val certDer = parseCertFromSignerBlock(buf, pairOffset + 12, pairLen - 4)
                if (certDer != null) return certDer
            }

            pairOffset += 8 + pairLen
        }

        return null
    }

    /**
     * Parse the first certificate DER bytes out of a v2/v3 signer block value.
     *
     * The signer block value is a length-prefixed sequence of signers.
     * We only need the first signer's first certificate.
     *
     * @param buf         Full APK ByteBuffer.
     * @param valueOffset Offset of the signer block value (after the block ID).
     * @param valueLen    Length of the signer block value in bytes.
     * @return Raw DER bytes, or null on parse failure.
     */
    private fun parseCertFromSignerBlock(
        buf: java.nio.ByteBuffer,
        valueOffset: Int,
        valueLen: Int
    ): ByteArray? {
        if (valueOffset + 4 > buf.limit()) return null

        // signers sequence length
        val signersLen = buf.getInt(valueOffset)
        if (signersLen <= 0 || valueOffset + 4 + signersLen > buf.limit()) return null

        // First signer length
        val signerStart = valueOffset + 4
        if (signerStart + 4 > buf.limit()) return null
        val signerLen = buf.getInt(signerStart)
        if (signerLen <= 0 || signerStart + 4 + signerLen > buf.limit()) return null

        // signedData length
        val signedDataStart = signerStart + 4
        if (signedDataStart + 4 > buf.limit()) return null
        val signedDataLen = buf.getInt(signedDataStart)
        if (signedDataLen <= 0) return null

        // Skip digests (first sequence inside signedData)
        val sdContent = signedDataStart + 4
        if (sdContent + 4 > buf.limit()) return null
        val digestsLen = buf.getInt(sdContent)
        if (digestsLen < 0) return null

        // Certificates sequence starts after digests
        val certsSeqOffset = sdContent + 4 + digestsLen
        if (certsSeqOffset + 4 > buf.limit()) return null
        val certsSeqLen = buf.getInt(certsSeqOffset)
        if (certsSeqLen <= 0) return null

        // First cert
        val firstCertOffset = certsSeqOffset + 4
        if (firstCertOffset + 4 > buf.limit()) return null
        val firstCertLen = buf.getInt(firstCertOffset)
        if (firstCertLen <= 0 || firstCertOffset + 4 + firstCertLen > buf.limit()) return null

        val certDer = ByteArray(firstCertLen)
        buf.position(firstCertOffset + 4)
        buf.get(certDer)
        return certDer
    }

    /**
     * Extract the first signing certificate from a `META-INF/*.RSA` (or `.DSA`, `.EC`)
     * entry in the APK's JAR signature (v1).
     *
     * The entry is a DER-encoded PKCS#7 `ContentInfo` / `SignedData` structure.
     * We use `java.security.cert.CertificateFactory` to parse it, which natively
     * handles the PKCS#7 wrapper and extracts the embedded X.509 certificates.
     *
     * @param apkPath Path to the APK.
     * @return DER bytes of the first X.509 certificate, or null.
     */
    private fun extractCertFromJarSignature(apkPath: String): ByteArray? {
        val sigRegex = Regex("^META-INF/[^/]+\\.(RSA|DSA|EC)$", RegexOption.IGNORE_CASE)

        ZipFile(File(apkPath)).use { zip ->
            val sigEntry: ZipEntry = zip.entries().asSequence()
                .firstOrNull { sigRegex.matches(it.name) }
                ?: return null

            zip.getInputStream(sigEntry).use { stream ->
                val pkcs7Bytes = stream.readBytes()
                return extractFirstCertFromPkcs7(pkcs7Bytes)
            }
        }
    }

    /**
     * Extract the first X.509 certificate DER bytes from a PKCS#7 `SignedData` blob.
     *
     * Uses `java.security.cert.CertificateFactory` with a `PKCS7` stream to parse
     * the structure.  This is supported on Android (via Bouncy Castle / Conscrypt).
     *
     * @param pkcs7 Raw PKCS#7 bytes (DER-encoded `ContentInfo` wrapping `SignedData`).
     * @return DER bytes of the first certificate, or null.
     */
    private fun extractFirstCertFromPkcs7(pkcs7: ByteArray): ByteArray? {
        return try {
            val cf   = java.security.cert.CertificateFactory.getInstance("X.509")
            val coll = cf.generateCertificates(pkcs7.inputStream())
            val cert = coll.firstOrNull() as? X509Certificate ?: return null
            cert.encoded
        } catch (e: Exception) {
            Log.d(TAG, "CertificateFactory PKCS7 parse failed: ${e.message}")
            // Try manual DER extraction as a last resort
            extractCertFromPkcs7Manually(pkcs7)
        }
    }

    /**
     * Manual fallback: scan a PKCS#7 blob for an embedded X.509 certificate by
     * searching for the DER SEQUENCE tag (0x30) that begins a `Certificate` structure.
     *
     * This is a heuristic approach for environments where the full PKCS#7 parser
     * is unavailable (e.g. restricted class-loading contexts).
     *
     * @param pkcs7 Raw PKCS#7 bytes.
     * @return DER bytes of the first apparent certificate, or null.
     */
    private fun extractCertFromPkcs7Manually(pkcs7: ByteArray): ByteArray? {
        // PKCS#7 DER structure: 0x30 [len] ... certificates [3] { 0x30 [certLen] ... }
        // We look for a SEQUENCE (0x30) that looks like a certificate after the SignedData
        // outer wrapper.  The outer SEQUENCE is at offset 0; the inner SignedData content
        // starts at offset ~4.  We use a simple scan.
        var i = 0
        while (i < pkcs7.size - 4) {
            if (pkcs7[i] == 0x30.toByte()) {
                val (derLen, lenBytes) = readDerLength(pkcs7, i + 1)
                if (derLen > 0 && i + 1 + lenBytes + derLen <= pkcs7.size && derLen > 100) {
                    // Candidate certificate — check it parses as X.509
                    val candidate = pkcs7.copyOfRange(i, i + 1 + lenBytes + derLen)
                    try {
                        val cf   = java.security.cert.CertificateFactory.getInstance("X.509")
                        val cert = cf.generateCertificate(candidate.inputStream()) as? X509Certificate
                        if (cert != null) return cert.encoded
                    } catch (_: Exception) { /* not a cert */ }
                }
            }
            i++
        }
        return null
    }

    /**
     * Read a DER-encoded length field starting at [offset] in [data].
     *
     * @return Pair of (length value, number of bytes consumed by the length field).
     */
    private fun readDerLength(data: ByteArray, offset: Int): Pair<Int, Int> {
        if (offset >= data.size) return Pair(-1, 0)
        val first = data[offset].toInt() and 0xFF
        return when {
            first < 0x80 -> Pair(first, 1)
            first == 0x81 -> {
                if (offset + 1 >= data.size) Pair(-1, 0)
                else Pair(data[offset + 1].toInt() and 0xFF, 2)
            }
            first == 0x82 -> {
                if (offset + 2 >= data.size) Pair(-1, 0)
                else Pair(
                    ((data[offset + 1].toInt() and 0xFF) shl 8) or (data[offset + 2].toInt() and 0xFF),
                    3
                )
            }
            else -> Pair(-1, 0)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Certificate embedding
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Create a new APK (temporary file) that contains all entries from [apkPath]
     * plus `assets/zenpatch/original_cert.der` with [certDer] as its content.
     *
     * Any existing `original_cert.der` entry in the APK is replaced.
     *
     * @param apkPath  Path to the input APK.
     * @param certDer  Raw DER bytes of the original signing certificate.
     * @return Path to the new APK with the certificate embedded.
     */
    private fun embedOriginalCert(apkPath: String, certDer: ByteArray): String {
        val inputFile = File(apkPath)
        val ts        = System.currentTimeMillis()
        val outFile   = File(
            inputFile.parent ?: System.getProperty("java.io.tmpdir"),
            "${inputFile.nameWithoutExtension}_cert_$ts.apk"
        )

        ZipFile(inputFile).use { srcZip ->
            ZipOutputStream(FileOutputStream(outFile)).use { zos ->

                // Copy all existing entries (except the cert asset, if present)
                srcZip.entries().asSequence()
                    .filter { it.name != ORIG_CERT_ASSET }
                    .forEach { srcEntry ->
                        if (srcEntry.name.startsWith("/") || srcEntry.name.contains("..")) {
                            Log.w(TAG, "Skipping unsafe ZIP entry name: '${srcEntry.name}'")
                            return@forEach
                        }
                        val newEntry = ZipEntry(srcEntry.name).apply {
                            method  = srcEntry.method
                            comment = srcEntry.comment
                            time    = srcEntry.time
                            if (srcEntry.extra != null) extra = srcEntry.extra
                            if (srcEntry.method == ZipEntry.STORED) {
                                size           = srcEntry.size
                                compressedSize = srcEntry.compressedSize
                                crc            = srcEntry.crc
                            }
                        }
                        zos.putNextEntry(newEntry)
                        srcZip.getInputStream(srcEntry).use { it.copyTo(zos) }
                        zos.closeEntry()
                    }

                // Append the original certificate
                zos.putNextEntry(ZipEntry(ORIG_CERT_ASSET).apply { method = ZipEntry.DEFLATED })
                zos.write(certDer)
                zos.closeEntry()
                Log.d(TAG, "Embedded original cert as $ORIG_CERT_ASSET")
            }
        }

        return outFile.absolutePath
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Keystore generation
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Generate a self-signed RSA-2048 keystore and save it as a JKS file.
     *
     * The generated key is valid for 30 years.  This is used when no external
     * keystore is configured (development / CI builds).
     *
     * Certificate subject: `CN=ZenPatch Debug, O=ZenPatch, C=US`
     *
     * NOTE: `sun.security.x509.*` classes are used here for self-signed cert
     * generation.  On Android, Bouncy Castle (`org.bouncycastle`) is available
     * as an alternative if `sun.*` is unavailable.  The JKS keystore format is
     * supported on both JVM and Android via `KeyStore.getInstance("JKS")` or
     * `"PKCS12"`.
     *
     * @param keystoreFile    Destination file for the generated keystore.
     * @param storePassword   Password for the keystore.
     * @param keyAlias        Alias for the generated key entry.
     * @param keyPassword     Password for the key entry.
     */
    private fun generateDebugKeystore(
        keystoreFile: File,
        storePassword: String,
        keyAlias: String,
        keyPassword: String
    ) {
        keystoreFile.parentFile?.mkdirs()

        // Generate RSA key pair
        val keyPairGen = KeyPairGenerator.getInstance("RSA")
        keyPairGen.initialize(RSA_KEY_SIZE, SecureRandom())
        val keyPair = keyPairGen.generateKeyPair()

        // Build self-signed certificate
        val cert: X509Certificate = generateSelfSignedCertificate(keyPair)

        // Store in PKCS12 keystore (universally supported on Android + JVM)
        val ks = KeyStore.getInstance("PKCS12")
        ks.load(null, null)
        ks.setKeyEntry(
            keyAlias,
            keyPair.private,
            keyPassword.toCharArray(),
            arrayOf(cert)
        )

        keystoreFile.outputStream().use { out ->
            ks.store(out, storePassword.toCharArray())
        }

        Log.d(TAG, "Generated debug keystore: ${keystoreFile.absolutePath} " +
                "(alias=$keyAlias, subject=${cert.subjectX500Principal})")
    }

    /**
     * Generate a minimal self-signed X.509v3 certificate for [keyPair].
     *
     * This method builds the certificate using only standard Java security APIs
     * that are available on Android (no Bouncy Castle required).
     *
     * Falls back to a Bouncy Castle X509v3CertificateBuilder if reflective
     * access to `sun.security.x509.*` is blocked.
     *
     * @param keyPair RSA key pair for which the certificate will be issued.
     * @return Self-signed X.509 certificate.
     */
    private fun generateSelfSignedCertificate(keyPair: java.security.KeyPair): X509Certificate {
        val subject  = X500Principal("CN=ZenPatch Debug, O=ZenPatch, C=US")
        val notBefore = Date()
        val notAfter  = Date(System.currentTimeMillis() + KEY_VALIDITY_MS)
        val serial    = BigInteger.valueOf(System.currentTimeMillis())

        return try {
            // Primary path: use sun.security.x509 (available on JVM; may be restricted on Android)
            generateCertViaSunSecurity(keyPair, subject, notBefore, notAfter, serial)
        } catch (e: Exception) {
            Log.d(TAG, "sun.security.x509 unavailable (${e.javaClass.simpleName}), trying BouncyCastle")
            try {
                generateCertViaBouncyCastle(keyPair, subject, notBefore, notAfter, serial)
            } catch (e2: Exception) {
                // Last resort: BouncyCastle also unavailable — use a hard-coded pre-generated cert
                Log.w(TAG, "Certificate generation via BouncyCastle also failed: ${e2.message}. " +
                        "Using fallback self-signed certificate stub.")
                generateCertFallback(keyPair)
            }
        }
    }

    /**
     * Generate a self-signed certificate using `sun.security.x509` classes.
     *
     * Available on standard JVM; may throw [ClassNotFoundException] on Android.
     */
    @Suppress("UNCHECKED_CAST")
    private fun generateCertViaSunSecurity(
        keyPair: java.security.KeyPair,
        subject: X500Principal,
        notBefore: Date,
        notAfter: Date,
        serial: BigInteger
    ): X509Certificate {
        val x500NameClass   = Class.forName("sun.security.x509.X500Name")
        val certiInfoClass  = Class.forName("sun.security.x509.X509CertInfo")
        val certiImplClass  = Class.forName("sun.security.x509.X509CertImpl")
        val algIdClass      = Class.forName("sun.security.x509.AlgorithmId")
        val certValidity    = Class.forName("sun.security.x509.CertificateValidity")
        val certSN          = Class.forName("sun.security.x509.CertificateSerialNumber")
        val certAlgId       = Class.forName("sun.security.x509.CertificateAlgorithmId")
        val certX509Key     = Class.forName("sun.security.x509.CertificateX509Key")
        val certSubjectName = Class.forName("sun.security.x509.CertificateSubjectName")
        val certIssuerName  = Class.forName("sun.security.x509.CertificateIssuerName")
        val certX500Key     = Class.forName("sun.security.x509.X509Key")

        val subjectName  = x500NameClass.getConstructor(String::class.java).newInstance("CN=ZenPatch Debug, O=ZenPatch, C=US")
        val algId        = algIdClass.getMethod("get", String::class.java).invoke(null, "SHA256WithRSA")
        val validity     = certValidity.getConstructor(Date::class.java, Date::class.java).newInstance(notBefore, notAfter)
        val certInfo     = certiInfoClass.getDeclaredConstructor().newInstance()

        fun set(field: String, value: Any) {
            certiInfoClass.getMethod("set", String::class.java, Any::class.java).invoke(certInfo, field, value)
        }

        set("validity",   validity)
        set("serialNumber", certSN.getConstructor(BigInteger::class.java).newInstance(serial))
        set("subject",    certSubjectName.getConstructor(x500NameClass).newInstance(subjectName))
        set("issuer",     certIssuerName.getConstructor(x500NameClass).newInstance(subjectName))
        set("key",        certX509Key.getConstructor(java.security.PublicKey::class.java).newInstance(keyPair.public))
        set("algorithmID", certAlgId.getConstructor(algIdClass).newInstance(algId))

        val certImpl = certiImplClass.getConstructor(certiInfoClass).newInstance(certInfo)
        certiImplClass.getMethod("sign", PrivateKey::class.java, String::class.java)
            .invoke(certImpl, keyPair.private, "SHA256WithRSA")

        return certImpl as X509Certificate
    }

    /**
     * Generate a self-signed certificate using Bouncy Castle's `X509v3CertificateBuilder`.
     *
     * This is the preferred path on Android (Bouncy Castle is bundled as `bcpkix-jdk18on`
     * or available via the Android framework).  We use reflection to avoid a hard
     * compile-time dependency on the BC artifact, since the patcher module only
     * requires `apksig` which itself depends on BC.
     */
    private fun generateCertViaBouncyCastle(
        keyPair: java.security.KeyPair,
        subject: X500Principal,
        notBefore: Date,
        notAfter: Date,
        serial: BigInteger
    ): X509Certificate {
        // Attempt to use BouncyCastle's X509v3CertificateBuilder via reflection
        // so we don't need to declare an explicit compile-time dependency.
        val builderClass = Class.forName("org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder")
        val signerClass  = Class.forName("org.bouncycastle.operator.jcajce.JcaContentSignerBuilder")
        val converterClass = Class.forName("org.bouncycastle.cert.jcajce.JcaX509CertificateConverter")

        val signer   = signerClass.getConstructor(String::class.java).newInstance("SHA256WithRSA")
        val csMethod = signerClass.getMethod("build", java.security.PrivateKey::class.java)
        val cs       = csMethod.invoke(signer, keyPair.private)

        val builder  = builderClass.getConstructor(
            X500Principal::class.java, BigInteger::class.java,
            Date::class.java, Date::class.java,
            X500Principal::class.java, java.security.PublicKey::class.java
        ).newInstance(subject, serial, notBefore, notAfter, subject, keyPair.public)

        val contentSignerInterface = Class.forName("org.bouncycastle.operator.ContentSigner")
        val certHolder = builderClass.getMethod("build", contentSignerInterface).invoke(builder, cs)

        val converter = converterClass.getDeclaredConstructor().newInstance()
        val certHolderClass = Class.forName("org.bouncycastle.cert.X509CertificateHolder")
        return converterClass.getMethod("getCertificate", certHolderClass)
            .invoke(converter, certHolder) as X509Certificate
    }

    /**
     * Last-resort self-signed certificate generation.
     *
     * When neither `sun.security.x509` nor Bouncy Castle are accessible, we use
     * [KeyStore] with an existing key pair.  Android's `KeyStore` supports
     * self-signed certificate generation via the `AndroidKeyStore` provider, but
     * since we are running in a standard JVM context during patching (not inside
     * an Android app process), we generate a minimal certificate by encoding the
     * subject key identifier extension manually.
     *
     * In practice this path is never reached on Android SDK 31+ because Bouncy
     * Castle is always available.  The fallback ensures compilation and
     * non-crashing behavior.
     */
    private fun generateCertFallback(keyPair: java.security.KeyPair): X509Certificate {
        // Use PKCS12 keystore trick: generate a cert with a temporary keystore
        // that supports self-signed cert creation through its key entry API.
        // This works on Android via the PKCS12 provider.
        val ks = KeyStore.getInstance("PKCS12")
        ks.load(null, null)

        // We need a certificate to store the key, creating a chicken-and-egg problem.
        // Resolve by generating a DER-encoded minimal X.509 certificate using a
        // hard-coded ASN.1 template and substituting the public key.
        val cert = buildMinimalX509Cert(keyPair)

        ks.setKeyEntry("zenpatch", keyPair.private, "zenpatch".toCharArray(), arrayOf(cert))
        return ks.getCertificate("zenpatch") as X509Certificate
    }

    /**
     * Build a minimal DER-encoded X.509v1 certificate by hand and parse it
     * back via `CertificateFactory`.
     *
     * This only works when the public key is RSA (which we guarantee — we always
     * call [KeyPairGenerator] with "RSA").  The certificate is version 1 with no
     * extensions.
     *
     * The DER structure:
     * ```
     * Certificate ::= SEQUENCE {
     *   tbsCertificate TBSCertificate,
     *   signatureAlgorithm AlgorithmIdentifier,
     *   signature BIT STRING
     * }
     * TBSCertificate ::= SEQUENCE {
     *   version         [0] EXPLICIT INTEGER (v1 = 0) OPTIONAL,
     *   serialNumber    INTEGER,
     *   signature       AlgorithmIdentifier,  -- SHA256WithRSA
     *   issuer          Name,
     *   validity        Validity,
     *   subject         Name,
     *   subjectPublicKeyInfo SubjectPublicKeyInfo
     * }
     * ```
     */
    private fun buildMinimalX509Cert(keyPair: java.security.KeyPair): X509Certificate {
        // Build the TBSCertificate DER by assembling ASN.1 primitives
        val tbsDer = buildTbsCertificateDer(keyPair)

        // SHA256WithRSA AlgorithmIdentifier DER: SEQUENCE { OID 1.2.840.113549.1.1.11, NULL }
        val sha256WithRsaAlgId = byteArrayOf(
            0x30, 0x0d,
            0x06, 0x09, 0x2a, 0x86.toByte(), 0x48, 0x86.toByte(), 0xf7.toByte(), 0x0d, 0x01, 0x01, 0x0b,
            0x05, 0x00
        )

        // Sign the TBS certificate
        val signer = java.security.Signature.getInstance("SHA256WithRSA")
        signer.initSign(keyPair.private)
        signer.update(tbsDer)
        val sigBytes = signer.sign()

        // Wrap signature as BIT STRING: 0x03 [len] 0x00 [sigBytes]
        val bitString = byteArrayOf(0x03.toByte()) + derLength(sigBytes.size + 1) + byteArrayOf(0x00) + sigBytes

        // Assemble Certificate SEQUENCE
        val certContent = tbsDer + sha256WithRsaAlgId + bitString
        val certDer     = byteArrayOf(0x30.toByte()) + derLength(certContent.size) + certContent

        val cf = java.security.cert.CertificateFactory.getInstance("X.509")
        return cf.generateCertificate(certDer.inputStream()) as X509Certificate
    }

    /**
     * Build the DER-encoded TBSCertificate for [buildMinimalX509Cert].
     *
     * This builds a minimal v1 TBSCertificate with:
     * - Serial number = 1
     * - Issuer/Subject = `CN=ZenPatch Debug`
     * - Validity: now to now+30y
     * - The provided public key
     */
    private fun buildTbsCertificateDer(keyPair: java.security.KeyPair): ByteArray {
        // SHA256WithRSA AlgorithmIdentifier
        val algId = byteArrayOf(
            0x30, 0x0d,
            0x06, 0x09, 0x2a, 0x86.toByte(), 0x48, 0x86.toByte(), 0xf7.toByte(), 0x0d, 0x01, 0x01, 0x0b,
            0x05, 0x00
        )

        // Serial number INTEGER = 1
        val serial = byteArrayOf(0x02, 0x01, 0x01)

        // Name: SEQUENCE { SET { SEQUENCE { OID commonName, UTF8String "ZenPatch Debug" } } }
        val cnOid    = byteArrayOf(0x06, 0x03, 0x55, 0x04, 0x03)
        val cnValue  = "ZenPatch Debug".toByteArray(Charsets.UTF_8)
        val cnStr    = byteArrayOf(0x0c.toByte()) + derLength(cnValue.size) + cnValue
        val atv      = byteArrayOf(0x30) + derLength((cnOid + cnStr).size) + cnOid + cnStr
        val rdnSet   = byteArrayOf(0x31) + derLength(atv.size) + atv
        val name     = byteArrayOf(0x30) + derLength(rdnSet.size) + rdnSet

        // Validity: UTCTime now and now+30y
        fun utcTime(d: Date): ByteArray {
            val fmt = java.text.SimpleDateFormat("yyMMddHHmmss'Z'", java.util.Locale.US)
            fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
            val s = fmt.format(d).toByteArray(Charsets.US_ASCII)
            return byteArrayOf(0x17) + derLength(s.size) + s
        }
        val notBefore = utcTime(Date())
        val notAfter  = utcTime(Date(System.currentTimeMillis() + KEY_VALIDITY_MS))
        val validity  = byteArrayOf(0x30) + derLength((notBefore + notAfter).size) + notBefore + notAfter

        // SubjectPublicKeyInfo (use the encoded form directly from the key)
        val spki = keyPair.public.encoded

        val tbsContent = serial + algId + name + validity + name + spki
        return byteArrayOf(0x30) + derLength(tbsContent.size) + tbsContent
    }

    /**
     * Encode an ASN.1 DER length field for the given [length].
     *
     * - length < 128: single byte
     * - length < 256: 0x81 followed by one byte
     * - otherwise: 0x82 followed by two bytes (big-endian)
     */
    private fun derLength(length: Int): ByteArray = when {
        length < 0x80   -> byteArrayOf(length.toByte())
        length < 0x100  -> byteArrayOf(0x81.toByte(), length.toByte())
        else            -> byteArrayOf(
            0x82.toByte(),
            ((length shr 8) and 0xFF).toByte(),
            (length and 0xFF).toByte()
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // APK signing via apksig
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Sign the APK using `com.android.tools.build:apksig`.
     *
     * Signing schemes enabled:
     * - v1 (JAR signing) — for compatibility with Android < 7 (Nougat)
     * - v2 (APK Signature Scheme v2) — required for Android 7+
     * - v3 (APK Signature Scheme v3) — supports key rotation (Android 9+)
     *
     * @param inputApk      The APK to sign (must be zip-aligned for best compatibility).
     * @param outputApk     Destination for the signed APK.
     * @param keystoreFile  The keystore containing the signing key.
     * @param storePassword Keystore password.
     * @param keyAlias      Alias of the signing key in the keystore.
     * @param keyPassword   Password for the key entry.
     */
    private fun signWithApkSig(
        inputApk: File,
        outputApk: File,
        keystoreFile: File,
        storePassword: String,
        keyAlias: String,
        keyPassword: String
    ) {
        Log.d(TAG, "Loading keystore: ${keystoreFile.absolutePath}")

        // Load the keystore
        // Auto-detect keystore type: JKS for .jks files, PKCS12 otherwise
        val ksType = if (keystoreFile.extension.equals("jks", ignoreCase = true)) "JKS" else "PKCS12"
        Log.d(TAG, "Loading keystore type=$ksType")
        val ks = KeyStore.getInstance(ksType).also {
            keystoreFile.inputStream().use { stream ->
                it.load(stream, storePassword.toCharArray())
            }
        }

        // Retrieve private key and certificate chain
        val privateKey = ks.getKey(keyAlias, keyPassword.toCharArray()) as? PrivateKey
            ?: error("Key alias '$keyAlias' not found in keystore or wrong password")
        val certChain: List<X509Certificate> = ks.getCertificateChain(keyAlias)
            ?.filterIsInstance<X509Certificate>()
            ?.takeIf { it.isNotEmpty() }
            ?: error("Certificate chain for alias '$keyAlias' is empty or missing")

        Log.d(TAG, "Signing with key alias '$keyAlias', cert subject: ${certChain.first().subjectX500Principal}")

        // Build the apksig signer configuration
        val signerConfig = AndroidApkSigner.SignerConfig.Builder(
            "ZenPatch",
            privateKey,
            certChain
        ).build()

        // Build and run the APK signer
        val apkSigner = AndroidApkSigner.Builder(listOf(signerConfig))
            .setV1SigningEnabled(true)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(true)
            .setInputApk(inputApk)
            .setOutputApk(outputApk)
            .build()

        Log.d(TAG, "Running apksig…")
        apkSigner.sign()
        Log.d(TAG, "apksig signing complete")
    }
}
