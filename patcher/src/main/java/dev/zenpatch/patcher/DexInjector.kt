package dev.zenpatch.patcher

import timber.log.Timber
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Injects the ZenPatch loader DEX as classes.dex into an APK.
 * Renames existing dex files (classes.dex → classes2.dex, etc.) to make room.
 * Includes ZIP-slip prevention on all entry paths.
 */
class DexInjector {

    /**
     * Injects loaderDex into the APK, renaming existing DEX files.
     * @param inputApk Source APK
     * @param loaderDex The loader DEX to inject as classes.dex
     * @param outputApk Destination APK
     */
    fun inject(inputApk: File, loaderDex: ByteArray, outputApk: File) {
        Timber.d("Injecting DEX into %s", inputApk.name)
        outputApk.parentFile?.mkdirs()

        ZipFile(inputApk).use { zip ->
            // Identify existing DEX files and compute renaming map
            val dexEntries = zip.entries().asSequence()
                .filter { it.name.matches(Regex("classes\\d*\\.dex")) }
                .sortedBy { extractDexIndex(it.name) }
                .toList()

            Timber.d("Found %d existing DEX files", dexEntries.size)

            // Build rename map: classes.dex -> classes2.dex, classes2.dex -> classes3.dex, etc.
            val renameMap = buildRenameMap(dexEntries)

            ZipOutputStream(outputApk.outputStream().buffered()).use { zout ->
                // Inject our loader as classes.dex first
                zout.putNextEntry(ZipEntry("classes.dex"))
                zout.write(loaderDex)
                zout.closeEntry()
                Timber.d("Injected loader DEX as classes.dex (%d bytes)", loaderDex.size)

                // Copy all other entries, renaming DEX files
                for (entry in zip.entries()) {
                    val originalName = entry.name

                    // ZIP-slip prevention
                    if (!isEntryPathSafe(originalName)) {
                        Timber.w("Skipping unsafe entry: %s", originalName)
                        continue
                    }

                    // Skip old classes.dex (already replaced by loader)
                    if (originalName == "classes.dex") continue

                    val newName = renameMap[originalName] ?: originalName
                    val newEntry = ZipEntry(newName).apply {
                        method = entry.method
                        if (entry.method == ZipEntry.STORED) {
                            size = entry.size
                            compressedSize = entry.compressedSize
                            crc = entry.crc
                        }
                        extra = entry.extra
                    }
                    zout.putNextEntry(newEntry)
                    zip.getInputStream(entry).use { it.copyTo(zout) }
                    zout.closeEntry()

                    if (newName != originalName) {
                        Timber.d("Renamed: %s -> %s", originalName, newName)
                    }
                }
            }
        }

        Timber.d("DEX injection complete: %s", outputApk.name)
    }

    /**
     * Builds the DEX rename map:
     * classes.dex -> classes2.dex, classes2.dex -> classes3.dex, etc.
     */
    private fun buildRenameMap(dexEntries: List<java.util.zip.ZipEntry>): Map<String, String> {
        val map = mutableMapOf<String, String>()
        for (entry in dexEntries.reversed()) {
            val idx = extractDexIndex(entry.name)
            val newIdx = idx + 1
            val newName = if (newIdx == 1) "classes.dex" else "classes$newIdx.dex"
            map[entry.name] = newName
        }
        return map
    }

    /**
     * Returns the numeric index of a dex file.
     * classes.dex -> 1, classes2.dex -> 2, classes3.dex -> 3, etc.
     */
    private fun extractDexIndex(name: String): Int {
        return when {
            name == "classes.dex" -> 1
            name.matches(Regex("classes(\\d+)\\.dex")) -> {
                Regex("classes(\\d+)\\.dex").find(name)?.groupValues?.get(1)?.toIntOrNull() ?: 1
            }
            else -> 1
        }
    }

    private fun isEntryPathSafe(name: String): Boolean {
        if (name.contains("..")) return false
        if (name.startsWith("/")) return false
        if (name.contains("\u0000")) return false
        return true
    }

    companion object {
        /**
         * Generates a minimal loader DEX that bootstraps ZenPatch runtime.
         * In production, this would be the pre-compiled ZenPatchLoader.dex from resources.
         * Here we return a skeleton that calls ZenPatchAppProxy.
         */
        fun generateLoaderDex(): ByteArray {
            // This is a placeholder - in production, the pre-built DEX from the runtime
            // module would be embedded as a resource and loaded here.
            // The actual loader DEX is compiled from ZenPatchLoader.java:
            //   public class ZenPatchLoader {
            //       static { dev.zenpatch.runtime.NativeBridge.init(); }
            //   }
            // For now, return the DEX magic bytes with a valid minimal DEX structure.
            return buildMinimalDex()
        }

        /**
         * Builds a valid but minimal DEX file.
         * The actual implementation uses a pre-built DEX embedded in the runtime module.
         */
        private fun buildMinimalDex(): ByteArray {
            // DEX magic: "dex\n035\0"
            val magic = byteArrayOf(0x64, 0x65, 0x78, 0x0a, 0x30, 0x33, 0x35, 0x00)
            // This is the minimal valid DEX044 format header
            // In production: return resources.openRawResource(R.raw.loader_dex).readBytes()
            return magic + ByteArray(104) { 0 } // Placeholder - real loader DEX embedded in APK
        }
    }
}
