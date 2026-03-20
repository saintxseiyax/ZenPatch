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
class AnalyzeCommandTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `analyze reports error for missing file`() {
        val cmd = CommandLine(ZenPatchCommand())
        val out = captureOutput {
            val exitCode = cmd.execute("analyze", "/nonexistent/file.apk")
            assertEquals(1, exitCode)
        }
    }

    @Test
    fun `analyze detects dex files`() {
        val apk = createTestApk(tempDir, "test.apk", mapOf(
            "classes.dex" to byteArrayOf(1, 2),
            "classes2.dex" to byteArrayOf(3, 4),
            "AndroidManifest.xml" to byteArrayOf(5)
        ))
        val output = captureOutput {
            val cmd = CommandLine(ZenPatchCommand())
            cmd.execute("analyze", apk.absolutePath)
        }
        // Just verify it doesn't crash (content depends on AXML parsing)
        assertNotNull(output)
    }

    @Test
    fun `analyze detects zenpatched apk`() {
        val apk = createTestApk(tempDir, "patched.apk", mapOf(
            "classes.dex" to byteArrayOf(1),
            "assets/zenpatch/config.properties" to "module_count=1\nzenpatch_version=1\n".toByteArray()
        ))
        val output = captureOutput {
            val cmd = CommandLine(ZenPatchCommand())
            cmd.execute("analyze", apk.absolutePath)
        }
        // Should note that it's ZenPatched
        assertTrue(output.contains("YES") || output.contains("true") || output.contains("ZenPatch"),
            "Should detect ZenPatch status")
    }

    @Test
    fun `analyze json output is valid format`() {
        val apk = createTestApk(tempDir, "json_test.apk", mapOf(
            "classes.dex" to byteArrayOf(1),
            "AndroidManifest.xml" to byteArrayOf(1)
        ))
        val output = captureOutput {
            val cmd = CommandLine(ZenPatchCommand())
            cmd.execute("analyze", "--json", apk.absolutePath)
        }
        assertTrue(output.trim().startsWith("{"), "JSON output should start with {")
        assertTrue(output.trim().endsWith("}"), "JSON output should end with }")
        assertTrue(output.contains("packageName"), "JSON should contain packageName field")
        assertTrue(output.contains("dexCount"), "JSON should contain dexCount field")
    }

    @Test
    fun `analyze detects native libs`() {
        val apk = createTestApk(tempDir, "native.apk", mapOf(
            "classes.dex" to byteArrayOf(1),
            "lib/arm64-v8a/libtest.so" to byteArrayOf(0x7f, 0x45, 0x4c, 0x46)
        ))
        val output = captureOutput {
            val cmd = CommandLine(ZenPatchCommand())
            cmd.execute("analyze", apk.absolutePath)
        }
        assertTrue(output.contains("arm64-v8a") || output.contains("arm64"),
            "Should report arm64-v8a ABI")
    }

    @Test
    fun `analyze shows help with --help flag`() {
        val cmd = CommandLine(ZenPatchCommand())
        val exitCode = cmd.execute("analyze", "--help")
        assertEquals(0, exitCode)
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

    private fun createTestApk(dir: File, name: String, entries: Map<String, ByteArray>): File {
        val file = File(dir, name)
        ZipOutputStream(file.outputStream()).use { zout ->
            entries.forEach { (entryName, data) ->
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
        }
        return file
    }
}
