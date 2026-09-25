package dev.bilby.update

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class UpdateManifestTest {
    private val root: File = Files.createTempDirectory("bilby-manifest").toFile()
    private val install = root.resolve("install")

    @After
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun entry(path: String, content: String, patch: Boolean = false) = ManifestFile(
        path = path,
        size = content.toByteArray().size.toLong(),
        sha256 = MessageDigest.getInstance("SHA-256").digest(content.toByteArray()).toHex(),
        mtime = 1_700_000_000_000L,
        patch = patch,
    )

    private fun write(path: String, content: String) =
        install.resolve(path).apply { parentFile.mkdirs(); writeText(content) }

    private val manifest = UpdateManifest(
        version = "0.0.2",
        files = listOf(
            entry("Bilby.exe", "exe v2", patch = true),
            entry("app/desktop.jar", "jar v2", patch = true),
            entry("runtime/lib/modules", "modules"),
        ),
    )

    @Test
    fun patchFilesMayDifferButEverythingElseMustMatch() {
        write("Bilby.exe", "exe v1")
        write("app/desktop.jar", "jar v1")
        write("runtime/lib/modules", "modules")
        write("app/leftover.txt", "not in the new version")
        assertTrue(canPatch(install, manifest))

        // 同样大小、不同内容:只比大小会误判为可以增量。
        write("runtime/lib/modules", "MODULES")
        assertFalse(canPatch(install, manifest))
    }

    @Test
    fun missingRuntimeFileNeedsFullInstall() {
        write("Bilby.exe", "exe v1")
        assertFalse(canPatch(install, manifest))
    }

    private fun zip(vararg files: Pair<String, String>): File = root.resolve("app.zip").apply {
        ZipOutputStream(outputStream()).use { out ->
            files.forEach { (name, content) ->
                out.putNextEntry(ZipEntry(name))
                out.write(content.toByteArray())
                out.closeEntry()
            }
        }
    }

    @Test
    fun extractRestoresManifestMtime() {
        val staged = root.resolve("staged")
        extractPatch(zip("Bilby.exe" to "exe v2", "app/desktop.jar" to "jar v2"), manifest, staged)
        val jar = staged.resolve("app/desktop.jar")
        assertEquals("jar v2", jar.readText())
        assertEquals(1_700_000_000_000L, jar.lastModified())
    }

    @Test
    fun extractRejectsIncompleteOrForeignArchives() {
        // 缺一个、内容不符、带越界路径,三种都不能留下半替换的暂存目录。
        assertThrows(ChecksumMismatchException::class.java) {
            extractPatch(zip("Bilby.exe" to "exe v2"), manifest, root.resolve("a"))
        }
        assertThrows(ChecksumMismatchException::class.java) {
            extractPatch(zip("Bilby.exe" to "exe v2", "app/desktop.jar" to "jar v1"), manifest, root.resolve("b"))
        }
        assertThrows(ChecksumMismatchException::class.java) {
            extractPatch(
                zip("Bilby.exe" to "exe v2", "app/desktop.jar" to "jar v2", "../evil.dll" to "x"),
                manifest,
                root.resolve("c"),
            )
        }
    }
}
