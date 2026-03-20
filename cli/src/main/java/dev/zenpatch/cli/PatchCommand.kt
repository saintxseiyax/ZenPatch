package dev.zenpatch.cli

import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import java.io.File
import java.util.concurrent.Callable

@Command(
    name = "patch",
    description = ["Patch an APK with Xposed modules"],
    mixinStandardHelpOptions = true
)
class PatchCommand : Callable<Int> {

    @Parameters(index = "0", description = ["Input APK file to patch"])
    lateinit var inputApk: File

    @Option(names = ["--modules", "-m"], description = ["Comma-separated list of module APK paths"], split = ",")
    var modules: List<String> = emptyList()

    @Option(names = ["--out", "-o"], description = ["Output APK path (default: <input>_patched.apk)"])
    var output: File? = null

    @Option(names = ["--keystore", "-k"], description = ["Keystore file for signing"])
    var keystore: File? = null

    @Option(names = ["--ks-pass"], description = ["Keystore password (default: zenpatch)"])
    var keystorePassword: String = "zenpatch"

    @Option(names = ["--ks-alias"], description = ["Key alias (default: zenpatch)"])
    var keystoreAlias: String = "zenpatch"

    @Option(names = ["--no-sig-spoof"], description = ["Disable signature spoofing"])
    var noSigSpoof: Boolean = false

    @Option(names = ["--debuggable"], description = ["Make patched APK debuggable"])
    var debuggable: Boolean = false

    @Option(names = ["--verbose", "-v"], description = ["Verbose output"])
    var verbose: Boolean = false

    override fun call(): Int {
        // Validate input
        if (!inputApk.exists()) {
            System.err.println("ERROR: Input APK not found: ${inputApk.absolutePath}")
            return 1
        }
        if (!inputApk.canRead()) {
            System.err.println("ERROR: Cannot read input APK: ${inputApk.absolutePath}")
            return 1
        }

        val outputApk = output ?: File(inputApk.parent, "${inputApk.nameWithoutExtension}_patched.apk")
        outputApk.parentFile?.mkdirs()

        println("ZenPatch CLI - Patching APK")
        println("  Input:   ${inputApk.absolutePath}")
        println("  Output:  ${outputApk.absolutePath}")
        println("  Modules: ${if (modules.isEmpty()) "none" else modules.joinToString(", ")}")
        println("  Sig spoof: ${!noSigSpoof}")

        // Validate module paths
        for (modPath in modules) {
            val modFile = File(modPath)
            if (!modFile.exists()) {
                System.err.println("WARNING: Module not found: $modPath")
            }
        }

        return try {
            val patcher = CliPatcherEngine(verbose = verbose)
            patcher.patch(
                inputApk = inputApk,
                outputApk = outputApk,
                moduleApkPaths = modules,
                enableSignatureSpoof = !noSigSpoof,
                debuggable = debuggable,
                keystoreFile = keystore,
                keystorePassword = keystorePassword,
                keystoreAlias = keystoreAlias
            ) { step, progress, message ->
                if (verbose || progress == 1.0f) {
                    println("[${step}] $message")
                } else {
                    print("\r[${step}] $message (${(progress * 100).toInt()}%)")
                }
            }
            println("\nPatching complete: ${outputApk.absolutePath}")
            println("Size: ${outputApk.length() / 1024} KB")
            0
        } catch (e: Exception) {
            System.err.println("\nERROR: ${e.message}")
            if (verbose) e.printStackTrace()
            1
        }
    }
}
