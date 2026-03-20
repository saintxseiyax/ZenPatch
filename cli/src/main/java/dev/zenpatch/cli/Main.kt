package dev.zenpatch.cli

import picocli.CommandLine
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val exitCode = CommandLine(ZenPatchCommand())
        .setCaseInsensitiveEnumValuesAllowed(true)
        .setExpandAtFiles(false)
        .execute(*args)
    exitProcess(exitCode)
}
