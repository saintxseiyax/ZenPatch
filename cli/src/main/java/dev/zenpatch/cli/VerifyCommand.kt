package dev.zenpatch.cli

import com.android.apksig.ApkVerifier
import picocli.CommandLine.Command
import picocli.CommandLine.Parameters
import picocli.CommandLine.Option
import java.io.File
import java.util.concurrent.Callable
import java.util.zip.ZipFile

@Command(
    name = "verify",
    description = ["Verify integrity of a (patched) APK"],
    mixinStandardHelpOptions = true
)
class VerifyCommand : Callable<Int> {

    @Parameters(index = "0", description = ["APK file to verify"])
    lateinit var apkFile: File

    @Option(names = ["--verbose", "-v"], description = ["Show detailed verification info"])
    var verbose: Boolean = false

    override fun call(): Int {
        if (!apkFile.exists()) {
            System.err.println("ERROR: File not found: ${apkFile.absolutePath}")
            return 1
        }

        println("Verifying: ${apkFile.name}")

        var allPassed = true

        // 1. APK signature verification
        try {
            val result = ApkVerifier.Builder(apkFile).build().verify()
            if (result.isVerified) {
                println("  [PASS] APK Signature: v${result.verifiedSchemes.joinToString("+v") { it.id.toString() }}")
                if (verbose) {
                    result.signerCertificates.forEachIndexed { i, cert ->
                        println("         Signer #${i+1}: ${cert.subjectX500Principal.name}")
                        println("         SHA-256: ${cert.encoded.toHexString()}")
                    }
                }
            } else {
                println("  [FAIL] APK Signature: NOT verified")
                result.errors.forEach { println("         Error: $it") }
                allPassed = false
            }
        } catch (e: Exception) {
            println("  [FAIL] APK Signature: ${e.message}")
            allPassed = false
        }

        // 2. ZIP integrity
        try {
            ZipFile(apkFile).use { zip ->
                var entryCount = 0
                for (entry in zip.entries()) {
                    zip.getInputStream(entry).use { stream ->
                        val buf = ByteArray(8192)
                        while (stream.read(buf) != -1) { /* CRC check by ZipFile */ }
                    }
                    entryCount++
                }
                println("  [PASS] ZIP integrity: $entryCount entries verified")
            }
        } catch (e: Exception) {
            println("  [FAIL] ZIP integrity: ${e.message}")
            allPassed = false
        }

        // 3. ZenPatch patch presence check
        try {
            ZipFile(apkFile).use { zip ->
                val hasConfig = zip.getEntry("assets/zenpatch/config.properties") != null
                val hasLoaderDex = zip.getEntry("classes.dex") != null

                if (hasConfig) {
                    println("  [PASS] ZenPatch config: assets/zenpatch/config.properties present")
                    if (verbose) {
                        zip.getInputStream(zip.getEntry("assets/zenpatch/config.properties")).use {
                            it.bufferedReader().lines().forEach { line -> println("         $line") }
                        }
                    }
                } else {
                    println("  [WARN] ZenPatch config: NOT found (may not be a ZenPatch APK)")
                }

                if (hasLoaderDex) {
                    val dexEntry = zip.getEntry("classes.dex")
                    println("  [INFO] DEX files: ${countDexFiles(zip)} total")
                } else {
                    println("  [FAIL] No classes.dex found")
                    allPassed = false
                }

                val hasCert = zip.getEntry("assets/zenpatch/original_cert.der") != null
                if (hasCert) {
                    println("  [PASS] Original cert: embedded for signature spoofing")
                } else {
                    println("  [WARN] Original cert: not embedded (signature spoofing disabled)")
                }
            }
        } catch (e: Exception) {
            println("  [FAIL] ZenPatch check: ${e.message}")
        }

        println("\nResult: ${if (allPassed) "PASS" else "FAIL"}")
        return if (allPassed) 0 else 1
    }

    private fun countDexFiles(zip: ZipFile): Int {
        return zip.entries().asSequence().count { it.name.matches(Regex("classes\\d*\\.dex")) }
    }

    private fun ByteArray.toHexString() = take(8).joinToString("") { "%02x".format(it) } + "..."
}
