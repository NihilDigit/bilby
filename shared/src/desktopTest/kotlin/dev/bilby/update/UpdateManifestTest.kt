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

    private fun fixture(name: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/update/$name")) { name }.use { it.readBytes() }

    private val deltaTarget = fixture("delta-target.bin")

    private val deltaManifest = UpdateManifest(
        version = "0.0.2",
        files = listOf(
            ManifestFile(
                path = "app/shared-desktop-bb.jar",
                size = deltaTarget.size.toLong(),
                sha256 = MessageDigest.getInstance("SHA-256").digest(deltaTarget).toHex(),
                mtime = 1_700_000_000_000L,
                patch = true,
            ),
        ),
    )

    private fun deltaZip(base: String? = null): File = root.resolve("delta.zip").apply {
        ZipOutputStream(outputStream()).use { out ->
            out.putNextEntry(ZipEntry("app/shared-desktop-bb.jar.zst"))
            out.write(fixture("delta-target.bin.zst"))
            out.closeEntry()
            if (base != null) {
                out.putNextEntry(ZipEntry("app/shared-desktop-bb.jar.base"))
                out.write(base.toByteArray())
                out.closeEntry()
            }
        }
    }

    // 夹具由 zstd CLI 的 --patch-from 生成,参数与 delta-updates.sh 相同;验证的是 CLI 压出的
    // 差分 zstd-jni 能否还原。
    @Test
    fun deltaFromCliRestoresAgainstInstalledBase() {
        install.resolve("app").mkdirs()
        install.resolve("app/shared-desktop-bb.jar").writeBytes(fixture("delta-base.bin"))
        val staged = root.resolve("staged")
        applyDelta(deltaZip(), deltaManifest, install, staged)
        val jar = staged.resolve("app/shared-desktop-bb.jar")
        assertTrue(jar.readBytes().contentEquals(deltaTarget))
        assertEquals(1_700_000_000_000L, jar.lastModified())
    }

    // shared-desktop 的 jar 每次构建换名,字典是 .base 指向的旧文件,不是新路径上的(本机没有)。
    @Test
    fun renamedJarRestoresAgainstNamedBase() {
        install.resolve("app").mkdirs()
        install.resolve("app/shared-desktop-aa.jar").writeBytes(fixture("delta-base.bin"))
        val staged = root.resolve("staged")
        applyDelta(deltaZip(base = "app/shared-desktop-aa.jar"), deltaManifest, install, staged)
        assertTrue(staged.resolve("app/shared-desktop-bb.jar").readBytes().contentEquals(deltaTarget))
    }

    // 调用方据这个异常退回完整补丁包,换成别的异常就成了更新失败。
    @Test
    fun deltaAgainstWrongBaseFailsAsChecksumMismatch() {
        install.resolve("app").mkdirs()
        install.resolve("app/shared-desktop-bb.jar").writeBytes(deltaTarget)
        assertThrows(ChecksumMismatchException::class.java) {
            applyDelta(deltaZip(), deltaManifest, install, root.resolve("a"))
        }
        assertThrows(ChecksumMismatchException::class.java) {
            applyDelta(deltaZip(base = "app/shared-desktop-gone.jar"), deltaManifest, install, root.resolve("b"))
        }
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
