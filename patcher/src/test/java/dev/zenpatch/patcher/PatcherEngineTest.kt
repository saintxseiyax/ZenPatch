package dev.zenpatch.patcher

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PatcherEngineTest {

    @TempDir
    lateinit var tempDir: File

    private lateinit var dexInjector: DexInjector
    private lateinit var nativeLibInjector: NativeLibInjector
    private lateinit var splitMerger: SplitApkMerger
    private lateinit var manifestEditor: ManifestEditor

    @BeforeEach
    fun setUp() {
        dexInjector = DexInjector()
        nativeLibInjector = NativeLibInjector()
        splitMerger = SplitApkMerger()
        manifestEditor = ManifestEditor()
    }

    // ---- DexInjector Tests ----

    @Test
    fun `dexInjector renames existing dex files correctly`() {
        val apk = createMinimalApk(tempDir, "test_renaming.apk", mapOf(
            "classes.dex" to byteArrayOf(1, 2, 3),
            "classes2.dex" to byteArrayOf(4, 5, 6)
        ))
        val output = File(tempDir, "output.apk")
        val loaderDex = byteArrayOf(10, 11, 12)

        dexInjector.inject(apk, loaderDex, output)

        ZipFile(output).use { zip ->
            assertNotNull(zip.getEntry("classes.dex"), "classes.dex (loader) should exist")
            assertNotNull(zip.getEntry("classes2.dex"), "original classes.dex renamed to classes2.dex")
            assertNotNull(zip.getEntry("classes3.dex"), "original classes2.dex renamed to classes3.dex")

            // Verify loader content
            val loaderContent = zip.getInputStream(zip.getEntry("classes.dex")).use { it.readBytes() }
            assertArrayEquals(loaderDex, loaderContent)
        }
    }

    @Test
    fun `dexInjector prevents zip slip attack`() {
        val maliciousApk = createApkWithEntry(tempDir, "malicious.apk", "../../../evil.dex", byteArrayOf(1))
        val output = File(tempDir, "output_safe.apk")

        // Should not throw, but the malicious entry should be skipped
        dexInjector.inject(maliciousApk, byteArrayOf(10), output)

        ZipFile(output).use { zip ->
            assertNull(zip.getEntry("../../../evil.dex"), "Malicious entry should be excluded")
        }
    }

    @Test
    fun `dexInjector handles apk with no dex files`() {
        val apk = createMinimalApk(tempDir, "nodex.apk", mapOf(
            "AndroidManifest.xml" to byteArrayOf(1, 2)
        ))
        val output = File(tempDir, "output_nodex.apk")

        dexInjector.inject(apk, byteArrayOf(100, 101), output)

        ZipFile(output).use { zip ->
            assertNotNull(zip.getEntry("classes.dex"), "Loader dex should be injected")
        }
    }

    @Test
    fun `dexInjector handles apk with 5 dex files`() {
        val entries = (1..5).associate {
            val name = if (it == 1) "classes.dex" else "classes$it.dex"
            name to byteArrayOf(it.toByte())
        }
        val apk = createMinimalApk(tempDir, "multidex.apk", entries)
        val output = File(tempDir, "multidex_out.apk")

        dexInjector.inject(apk, byteArrayOf(99), output)

        ZipFile(output).use { zip ->
            (1..6).forEach { i ->
                val name = if (i == 1) "classes.dex" else "classes$i.dex"
                assertNotNull(zip.getEntry(name), "Expected $name after injection")
            }
        }
    }

    // ---- NativeLibInjector Tests ----

    @Test
    fun `nativeLibInjector injects lib for correct arch`() {
        val apk = createMinimalApk(tempDir, "nolib.apk", mapOf("classes.dex" to byteArrayOf(1)))
        val output = File(tempDir, "with_lib.apk")
        val libs = listOf(
            NativeLibInjector.NativeLib("arm64-v8a", "libtest.so", byteArrayOf(0x7f, 0x45, 0x4c, 0x46))
        )

        nativeLibInjector.inject(apk, libs, output)

        ZipFile(output).use { zip ->
            assertNotNull(zip.getEntry("lib/arm64-v8a/libtest.so"), "Native lib should be injected")
        }
    }

    @Test
    fun `nativeLibInjector injects libs for multiple architectures`() {
        val apk = createMinimalApk(tempDir, "nolib2.apk", mapOf("classes.dex" to byteArrayOf(1)))
        val output = File(tempDir, "multiarch.apk")
        val libs = listOf(
            NativeLibInjector.NativeLib("arm64-v8a", "libbridge.so", ByteArray(1024)),
            NativeLibInjector.NativeLib("armeabi-v7a", "libbridge.so", ByteArray(512))
        )

        nativeLibInjector.inject(apk, libs, output)

        ZipFile(output).use { zip ->
            assertNotNull(zip.getEntry("lib/arm64-v8a/libbridge.so"))
            assertNotNull(zip.getEntry("lib/armeabi-v7a/libbridge.so"))
        }
    }

    @Test
    fun `nativeLibInjector prevents path traversal in lib name`() {
        val apk = createMinimalApk(tempDir, "safe.apk", mapOf("classes.dex" to byteArrayOf(1)))
        val output = File(tempDir, "safe_out.apk")
        val libs = listOf(
            NativeLibInjector.NativeLib("arm64-v8a", "../../../evil.so", ByteArray(10))
        )

        // Should not crash, malicious entry should be skipped
        nativeLibInjector.inject(apk, libs, output)

        ZipFile(output).use { zip ->
            assertNull(zip.getEntry("lib/arm64-v8a/../../../evil.so"))
            assertNull(zip.getEntry("../../../evil.so"))
        }
    }

    // ---- SplitApkMerger Tests ----

    @Test
    fun `splitMerger with no splits just copies base`() {
        val base = createMinimalApk(tempDir, "base.apk", mapOf("classes.dex" to byteArrayOf(1, 2, 3)))
        val output = File(tempDir, "merged.apk")

        splitMerger.merge(base, emptyList(), output)

        assertTrue(output.exists(), "Merged APK should exist")
        ZipFile(output).use { zip ->
            assertNotNull(zip.getEntry("classes.dex"))
        }
    }

    @Test
    fun `splitMerger merges native libs from splits`() {
        val base = createMinimalApk(tempDir, "base2.apk", mapOf(
            "classes.dex" to byteArrayOf(1),
            "AndroidManifest.xml" to byteArrayOf(2)
        ))
        val split = createMinimalApk(tempDir, "split_arm64.apk", mapOf(
            "lib/arm64-v8a/libfoo.so" to byteArrayOf(0x7f, 0x45, 0x4c, 0x46),
            "AndroidManifest.xml" to byteArrayOf(3)  // should be skipped
        ))
        val output = File(tempDir, "merged2.apk")

        splitMerger.merge(base, listOf(split), output)

        ZipFile(output).use { zip ->
            assertNotNull(zip.getEntry("lib/arm64-v8a/libfoo.so"), "Native lib from split should be merged")
            // Only one AndroidManifest (from base)
            var manifestCount = 0
            zip.entries().asIterator().forEach { if (it.name == "AndroidManifest.xml") manifestCount++ }
            assertEquals(1, manifestCount, "Should have exactly one AndroidManifest.xml from base")
        }
    }

    // ---- ManifestEditor Tests ----

    @Test
    fun `manifestEditor adds config properties sidecar`() {
        val apk = createMinimalApk(tempDir, "app.apk", mapOf(
            "classes.dex" to byteArrayOf(1),
            "AndroidManifest.xml" to createMinimalAxml()
        ))
        val output = File(tempDir, "app_manifest.apk")

        manifestEditor.patch(apk, output, listOf("/sdcard/module.apk"))

        ZipFile(output).use { zip ->
            assertNotNull(zip.getEntry("assets/zenpatch/config.properties"),
                "config.properties sidecar should exist")
            val config = zip.getInputStream(zip.getEntry("assets/zenpatch/config.properties"))
                .use { it.bufferedReader().readText() }
            assertTrue(config.contains("module_count=1"), "Config should contain module count")
            assertTrue(config.contains("zenpatch_version=1"), "Config should contain version")
        }
    }

    @Test
    fun `manifestEditor prevents zip slip in entries`() {
        val apk = createApkWithEntry(tempDir, "malicious_app.apk", "../danger.xml", byteArrayOf(1))
        val output = File(tempDir, "safe_app.apk")

        manifestEditor.patch(apk, output)

        ZipFile(output).use { zip ->
            assertNull(zip.getEntry("../danger.xml"), "Zip slip entry should be excluded")
        }
    }

    // ---- Helper methods ----

    private fun createMinimalApk(dir: File, name: String, entries: Map<String, ByteArray>): File {
        val file = File(dir, name)
        ZipOutputStream(file.outputStream()).use { zout ->
            entries.forEach { (entryName, data) ->
                val crc = CRC32().also { it.update(data) }.value
                val entry = ZipEntry(entryName).apply {
                    method = ZipEntry.STORED
                    size = data.size.toLong()
                    compressedSize = data.size.toLong()
                    this.crc = crc
                }
                zout.putNextEntry(entry)
                zout.write(data)
                zout.closeEntry()
            }
        }
        return file
    }

    private fun createApkWithEntry(dir: File, name: String, entryName: String, data: ByteArray): File {
        return createMinimalApk(dir, name, mapOf(entryName to data))
    }

    private fun createMinimalAxml(): ByteArray {
        // Minimal binary AXML with just magic bytes
        return byteArrayOf(0x03, 0x00, 0x08, 0x00, 0x08, 0x00, 0x00, 0x00)
    }
}
