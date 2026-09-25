package dev.bilby.update

import kotlinx.serialization.Serializable
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * 一个版本的应用目录清单(Release 附件 bilby-windows-x64-<版本>-files.json),由 :desktop 的
 * packageReleaseUpdate 任务生成。[ManifestFile.patch] 标出装进 app.zip 的文件,即每次构建都会
 * 变的那几个:exe、jar、AOT 缓存与启动配置。
 */
@Serializable
data class UpdateManifest(
    val version: String,
    val files: List<ManifestFile>,
)

@Serializable
data class ManifestFile(
    /** 相对应用目录,以 / 分隔。 */
    val path: String,
    val size: Long,
    val sha256: String,
    /** 毫秒。AOT 缓存按 jar 的修改时间校验,替换后要原样还原。 */
    val mtime: Long,
    val patch: Boolean = false,
)

/**
 * 补丁包之外的文件,本机都与新版本逐字节相同时才能增量更新。本机多出的文件不管:类路径写在
 * Bilby.cfg 里,多余的文件不会被加载。
 *
 * 补丁包里的文件不比较,一律替换:jar 即使内容没变,修改时间也跟新的 AOT 缓存对不上。
 */
internal fun canPatch(installDir: File, manifest: UpdateManifest): Boolean =
    manifest.files.filterNot { it.patch }.all { entry ->
        val file = installDir.resolve(entry.path)
        file.isFile && file.length() == entry.size && file.inputStream().use(::sha256Hex) == entry.sha256
    }

/**
 * 把补丁包解到 [target],逐个对照清单校验并还原修改时间。只收清单里标了 patch 的路径,
 * 清单里有而包里缺的也算失败:替换一半的应用目录比不更新更糟。
 */
internal fun extractPatch(zip: File, manifest: UpdateManifest, target: File) {
    val expected = manifest.files.filter { it.patch }.associateBy { it.path }
    val root = target.canonicalFile
    val seen = mutableSetOf<String>()
    ZipFile(zip).use { archive ->
        for (entry in archive.entries()) {
            if (entry.isDirectory) continue
            val spec = expected[entry.name] ?: throw ChecksumMismatchException("补丁包里有清单外的文件:${entry.name}")
            val out = root.resolve(spec.path).canonicalFile
            check(out.path.startsWith(root.path + File.separator)) { "路径越界:${spec.path}" }
            out.parentFile.mkdirs()
            val actual = archive.getInputStream(entry).use { input ->
                out.outputStream().use { output -> sha256Hex(input) { buffer, length -> output.write(buffer, 0, length) } }
            }
            if (actual != spec.sha256 || out.length() != spec.size) {
                throw ChecksumMismatchException("${spec.path}: $actual != ${spec.sha256}")
            }
            out.setLastModified(spec.mtime)
            seen += spec.path
        }
    }
    val missing = expected.keys - seen
    if (missing.isNotEmpty()) throw ChecksumMismatchException("补丁包缺少:${missing.joinToString()}")
}

internal fun sha256Hex(input: InputStream, onChunk: (ByteArray, Int) -> Unit = { _, _ -> }): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(256 * 1024)
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        digest.update(buffer, 0, read)
        onChunk(buffer, read)
    }
    return digest.digest().toHex()
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
