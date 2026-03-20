package dev.zenpatch.cli

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import picocli.CommandLine
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class VerifyCommandTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `verify reports error for missing file`() {
        val cmd = CommandLine(ZenPatchCommand())
        val exitCode = cmd.execute("verify", "/nonexistent/patched.apk")
        assertEquals(1, exitCode)
    }

    @Test
    fun `verify detects corrupt zip`() {
        val corruptFile = File(tempDir, "corrupt.apk")
        corruptFile.writeBytes(ByteArray(100) { it.toByte() })

        val cmd = CommandLine(ZenPatchCommand())
        val exitCode = cmd.execute("verify", corruptFile.absolutePath)
        assertEquals(1, exitCode)
    }

    @Test
    fun `verify detects zenpatched apk`() {
        val apk = createZenPatchedApk(tempDir, "valid_patched.apk")
        val output = captureOutput {
            val cmd = CommandLine(ZenPatchCommand())
            cmd.execute("verify", apk.absolutePath)
        }
        assertTrue(output.contains("config") || output.contains("zenpatch"),
            "Verify should detect ZenPatch config")
    }

    @Test
    fun `verify detects non-patched apk`() {
        val apk = createNonPatchedApk(tempDir, "not_patched.apk")
        val output = captureOutput {
            val cmd = CommandLine(ZenPatchCommand())
            cmd.execute("verify", apk.absolutePath)
        }
        assertTrue(output.contains("NOT") || output.contains("WARN") || output.contains("not"),
            "Verify should note APK is not ZenPatch-patched")
    }

    @Test
    fun `verify checks zip integrity`() {
        val apk = createNonPatchedApk(tempDir, "integrity_check.apk")
        val output = captureOutput {
            val cmd = CommandLine(ZenPatchCommand())
            cmd.execute("verify", apk.absolutePath)
        }
        assertTrue(output.contains("PASS") || output.contains("entries"),
            "ZIP integrity check should pass for valid APK")
    }

    @Test
    fun `verify verbose shows extra details`() {
        val apk = createZenPatchedApk(tempDir, "verbose_test.apk")
        val output = captureOutput {
            val cmd = CommandLine(ZenPatchCommand())
            cmd.execute("verify", "--verbose", apk.absolutePath)
        }
        // Verbose should show more details
        assertTrue(output.length > 50, "Verbose output should be longer than standard")
    }

    @Test
    fun `verify shows help with --help`() {
        val cmd = CommandLine(ZenPatchCommand())
        val exitCode = cmd.execute("verify", "--help")
        assertEquals(0, exitCode)
    }

    @Test
    fun `verify shows PASS result line`() {
        val apk = createNonPatchedApk(tempDir, "result_test.apk")
        val output = captureOutput {
            val cmd = CommandLine(ZenPatchCommand())
            cmd.execute("verify", apk.absolutePath)
        }
        assertTrue(output.contains("Result:"), "Should show a Result: line")
    }

    private fun captureOutput(block: () -> Unit): String {
        val baos = ByteArrayOutputStream()
        val ps = PrintStream(baos)
        val oldOut = System.out
        System.setOut(ps)
        try {
            block()
        } finally {
            System.setOut(oldOut)
        }
        return baos.toString()
    }

    private fun createNonPatchedApk(dir: File, name: String): File {
        val file = File(dir, name)
        ZipOutputStream(file.outputStream()).use { zout ->
            val dex = byteArrayOf(1, 2, 3, 4)
            val crc = CRC32().also { it.update(dex) }.value
            zout.putNextEntry(ZipEntry("classes.dex").apply {
                method = ZipEntry.STORED
                size = dex.size.toLong()
                compressedSize = dex.size.toLong()
                this.crc = crc
            })
            zout.write(dex)
            zout.closeEntry()
        }
        return file
    }

    private fun createZenPatchedApk(dir: File, name: String): File {
        val file = File(dir, name)
        ZipOutputStream(file.outputStream()).use { zout ->
            fun addEntry(entryName: String, data: ByteArray) {
                val crc = CRC32().also { it.update(data) }.value
                zout.putNextEntry(ZipEntry(entryName).apply {
                    method = ZipEntry.STORED
                    size = data.size.toLong()
                    compressedSize = data.size.toLong()
                    this.crc = crc
                })
                zout.write(data)
                zout.closeEntry()
            }
            addEntry("classes.dex", byteArrayOf(1, 2, 3))
            addEntry("classes2.dex", byteArrayOf(4, 5, 6))
            addEntry("assets/zenpatch/config.properties",
                "module_count=1\nzenpatch_version=1\npackage_name=com.example\n".toByteArray())
        }
        return file
    }
}
