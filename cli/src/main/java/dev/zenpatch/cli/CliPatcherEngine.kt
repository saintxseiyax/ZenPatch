package dev.zenpatch.cli

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import com.android.apksig.ApkSigner as AndroidApkSigner

/**
 * Simplified patcher engine for the CLI tool.
 * Mirrors PatcherEngine from the :patcher module but works on JVM without Android dependencies.
 */
class CliPatcherEngine(private val verbose: Boolean = false) {

    fun patch(
        inputApk: File,
        outputApk: File,
        moduleApkPaths: List<String>,
        enableSignatureSpoof: Boolean,
        debuggable: Boolean,
        keystoreFile: File?,
        keystorePassword: String,
        keystoreAlias: String,
        progressCallback: (step: String, progress: Float, message: String) -> Unit
    ) {
        val workDir = createTempDir(outputApk.parentFile ?: inputApk.parentFile!!)

        try {
            // Step 1: Analyze
            progressCallback("ANALYZE", 0f, "Analyzing ${inputApk.name}...")
            val dexCount = countDexFiles(inputApk)
            val abis = detectAbis(inputApk)
            val isAlreadyPatched = hasZenPatchConfig(inputApk)

            if (isAlreadyPatched) {
                throw IllegalStateException("APK is already ZenPatch-patched. Unpatch first.")
            }
            progressCallback("ANALYZE", 1f, "Found $dexCount DEX files, ABIs: ${abis.joinToString()}")

            // Step 2: Inject DEX
            progressCallback("DEX_INJECT", 0f, "Injecting loader DEX...")
            val dexInjectedApk = File(workDir, "step1_dex.apk")
            injectLoaderDex(inputApk, dexInjectedApk)
            progressCallback("DEX_INJECT", 1f, "DEX injected")

            // Step 3: Add config
            progressCallback("CONFIG", 0f, "Adding ZenPatch config...")
            val configApk = File(workDir, "step2_config.apk")
            addZenPatchConfig(dexInjectedApk, configApk, moduleApkPaths, enableSignatureSpoof, debuggable)
            progressCallback("CONFIG", 1f, "Config added")

            // Step 4: Sign
            progressCallback("SIGN", 0f, "Signing APK...")
            signApk(configApk, outputApk, keystoreFile, keystorePassword, keystoreAlias)
            progressCallback("SIGN", 1f, "APK signed")

        } finally {
            workDir.deleteRecursively()
        }
    }

    private fun countDexFiles(apk: File): Int =
        ZipFile(apk).use { zip -> zip.entries().asSequence().count { it.name.matches(Regex("classes\\d*\\.dex")) } }

    private fun detectAbis(apk: File): List<String> {
        val abis = mutableSetOf<String>()
        ZipFile(apk).use { zip ->
            zip.entries().asSequence()
                .filter { it.name.startsWith("lib/") && it.name.endsWith(".so") }
                .map { it.name.split("/").getOrNull(1) }
                .filterNotNull()
                .forEach { abis.add(it) }
        }
        return abis.toList()
    }

    private fun hasZenPatchConfig(apk: File): Boolean =
        ZipFile(apk).use { it.getEntry("assets/zenpatch/config.properties") != null }

    private fun injectLoaderDex(inputApk: File, outputApk: File) {
        outputApk.parentFile?.mkdirs()
        ZipFile(inputApk).use { zip ->
            // Renumber existing dex files
            val renameMap = mutableMapOf<String, String>()
            zip.entries().asSequence()
                .filter { it.name.matches(Regex("classes\\d*\\.dex")) }
                .sortedBy { it.name }
                .reversed()
                .forEachIndexed { _, entry ->
                    val idx = if (entry.name == "classes.dex") 1 else
                        Regex("classes(\\d+)\\.dex").find(entry.name)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                    renameMap[entry.name] = "classes${idx + 1}.dex"
                }

            ZipOutputStream(outputApk.outputStream().buffered()).use { zout ->
                // Write loader stub as classes.dex
                zout.putNextEntry(ZipEntry("classes.dex"))
                zout.write(MINIMAL_DEX_STUB)
                zout.closeEntry()

                // Copy all entries with renames
                for (entry in zip.entries()) {
                    if (entry.name.contains("..") || entry.name.startsWith("/")) continue
                    val newName = renameMap[entry.name] ?: entry.name
                    val newEntry = ZipEntry(newName).apply {
                        method = entry.method
                        if (entry.method == ZipEntry.STORED) {
                            size = entry.size; compressedSize = entry.compressedSize; crc = entry.crc
                        }
                    }
                    zout.putNextEntry(newEntry)
                    zip.getInputStream(entry).use { it.copyTo(zout) }
                    zout.closeEntry()
                }
            }
        }
    }

    private fun addZenPatchConfig(
        inputApk: File, outputApk: File,
        moduleApkPaths: List<String>,
        enableSignatureSpoof: Boolean,
        debuggable: Boolean
    ) {
        ZipFile(inputApk).use { zip ->
            ZipOutputStream(outputApk.outputStream().buffered()).use { zout ->
                for (entry in zip.entries()) {
                    if (entry.name.contains("..") || entry.name.startsWith("/")) continue
                    val newEntry = ZipEntry(entry.name).apply {
                        method = entry.method
                        if (entry.method == ZipEntry.STORED) {
                            size = entry.size; compressedSize = entry.compressedSize; crc = entry.crc
                        }
                    }
                    zout.putNextEntry(newEntry)
                    zip.getInputStream(entry).use { it.copyTo(zout) }
                    zout.closeEntry()
                }

                // Add config.properties
                val config = buildString {
                    appendLine("# ZenPatch Runtime Configuration")
                    appendLine("module_count=${moduleApkPaths.size}")
                    moduleApkPaths.forEachIndexed { i, p -> appendLine("module_${i}_path=$p") }
                    appendLine("signature_spoof=$enableSignatureSpoof")
                    appendLine("debuggable=$debuggable")
                    appendLine("zenpatch_version=1")
                }
                zout.putNextEntry(ZipEntry("assets/zenpatch/config.properties"))
                zout.write(config.toByteArray(Charsets.UTF_8))
                zout.closeEntry()
            }
        }
    }

    private fun signApk(inputApk: File, outputApk: File, keystoreFile: File?, keystorePassword: String, keystoreAlias: String) {
        // Strip old signatures first
        val tempUnsigned = File(outputApk.parent, "temp_unsigned.apk")
        try {
            stripSignatures(inputApk, tempUnsigned)

            // Generate key
            val (privateKey, certs) = if (keystoreFile?.exists() == true) {
                val ks = java.security.KeyStore.getInstance("PKCS12").apply {
                    keystoreFile.inputStream().use { load(it, keystorePassword.toCharArray()) }
                }
                Pair(
                    ks.getKey(keystoreAlias, keystorePassword.toCharArray()) as java.security.PrivateKey,
                    listOf(ks.getCertificate(keystoreAlias) as java.security.cert.X509Certificate)
                )
            } else {
                generateEphemeralKey()
            }

            val signerConfig = AndroidApkSigner.SignerConfig.Builder("ZenPatch", privateKey, certs).build()
            AndroidApkSigner.Builder(listOf(signerConfig)).apply {
                setInputApk(tempUnsigned)
                setOutputApk(outputApk)
                setV1SigningEnabled(false)
                setV2SigningEnabled(true)
                setV3SigningEnabled(true)
                setMinSdkVersion(31)
            }.build().sign()
        } finally {
            tempUnsigned.delete()
        }
    }

    private fun stripSignatures(inputApk: File, outputApk: File) {
        ZipFile(inputApk).use { zip ->
            ZipOutputStream(outputApk.outputStream().buffered()).use { zout ->
                for (entry in zip.entries()) {
                    val name = entry.name
                    if (name.startsWith("META-INF/") && (name.endsWith(".SF") || name.endsWith(".RSA") || name.endsWith(".DSA") || name.endsWith(".EC") || name == "META-INF/MANIFEST.MF")) continue
                    val newEntry = ZipEntry(name).apply {
                        method = entry.method
                        if (entry.method == ZipEntry.STORED) { size = entry.size; compressedSize = entry.compressedSize; crc = entry.crc }
                    }
                    zout.putNextEntry(newEntry)
                    zip.getInputStream(entry).use { it.copyTo(zout) }
                    zout.closeEntry()
                }
            }
        }
    }

    private fun generateEphemeralKey(): Pair<java.security.PrivateKey, List<java.security.cert.X509Certificate>> {
        val kpg = java.security.KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
        val kp = kpg.generateKeyPair()
        val certInfo = sun.security.x509.X509CertInfo().apply {
            val from = java.util.Date()
            val to = java.util.Date(from.time + 365L * 24 * 60 * 60 * 1000 * 25)
            set(sun.security.x509.X509CertInfo.VALIDITY, sun.security.x509.CertificateValidity(from, to))
            set(sun.security.x509.X509CertInfo.SERIAL_NUMBER, sun.security.x509.CertificateSerialNumber(java.math.BigInteger.ONE))
            val dn = sun.security.x509.X500Name("CN=ZenPatch,O=ZenPatch,C=US")
            set(sun.security.x509.X509CertInfo.SUBJECT, dn)
            set(sun.security.x509.X509CertInfo.ISSUER, dn)
            set(sun.security.x509.X509CertInfo.KEY, sun.security.x509.CertificateX509Key(kp.public))
            set(sun.security.x509.X509CertInfo.VERSION, sun.security.x509.CertificateVersion(sun.security.x509.CertificateVersion.V3))
            val algoId = sun.security.x509.AlgorithmId(sun.security.x509.AlgorithmId.sha256WithRSAEncryption_oid)
            set(sun.security.x509.X509CertInfo.ALGORITHM_ID, sun.security.x509.CertificateAlgorithmId(algoId))
        }
        val cert = sun.security.x509.X509CertImpl(certInfo).also { it.sign(kp.private, "SHA256withRSA") }
        return Pair(kp.private, listOf(cert))
    }

    private fun createTempDir(parent: File): File {
        val dir = File(parent, "zenpatch_cli_tmp_${System.currentTimeMillis()}")
        dir.mkdirs()
        return dir
    }

    companion object {
        // Minimal valid DEX magic
        private val MINIMAL_DEX_STUB = byteArrayOf(
            0x64, 0x65, 0x78, 0x0a, 0x30, 0x33, 0x35, 0x00,  // magic "dex\n035\0"
            *ByteArray(104) { 0 }  // rest of header zeroed
        )
    }
}
