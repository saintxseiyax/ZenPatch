package dev.zenpatch.cli

import picocli.CommandLine
import picocli.CommandLine.Command
import java.util.concurrent.Callable

@Command(
    name = "zenpatch",
    description = ["ZenPatch - Non-root Xposed framework for Android 12-16"],
    subcommands = [
        PatchCommand::class,
        AnalyzeCommand::class,
        VerifyCommand::class,
        ListModulesCommand::class,
        CommandLine.HelpCommand::class
    ],
    mixinStandardHelpOptions = true,
    version = ["ZenPatch CLI 1.0.0-alpha01"]
)
class ZenPatchCommand : Callable<Int> {
    override fun call(): Int {
        CommandLine.usage(this, System.out)
        return 0
    }
}
