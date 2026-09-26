package dev.bilby.update

import com.github.luben.zstd.ZstdDecompressCtx
import com.github.luben.zstd.ZstdException
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

/**
 * 差分包(Release 附件 bilby-windows-x64-<版本>-from-<旧版本>.zip)里每个补丁文件是一个
 * `<路径>.zst`,由 CI 以旧版的对应文件为前缀字典(zstd --patch-from)压成,见
 * .github/scripts/delta-updates.sh。拿本机文件作字典还原,结果与 [extractPatch] 解出的逐字节相同,
 * 同样逐个对照清单、还原修改时间。
 *
 * 对应文件默认是同一路径。换了名字的 jar(`shared-desktop-<哈希>.jar` 每次构建都换名)另带一个
 * `<路径>.base`,内容是旧版那个文件的路径。两边都没有的文件,CI 按普通 zstd 压缩,不需要字典。
 *
 * 本机文件不是差分所基于的那一版时,还原要么被 zstd 的帧校验拦下,要么摘要对不上,
 * 两者都抛 [ChecksumMismatchException],由调用方改下完整的补丁包。
 */
internal fun applyDelta(zip: File, manifest: UpdateManifest, installDir: File, target: File) {
    val root = target.canonicalFile
    val installRoot = installDir.canonicalFile
    ZipFile(zip).use { archive ->
        val expected = manifest.files.filter { it.patch }
        val allowed = expected.flatMap { listOf("${it.path}.zst", "${it.path}.base") }.toSet()
        val names = archive.entries().asSequence().filterNot { it.isDirectory }.map { it.name }.toSet()
        val foreign = names - allowed
        if (foreign.isNotEmpty()) throw ChecksumMismatchException("差分包里有清单外的文件:${foreign.joinToString()}")
        for (spec in expected) {
            val entry = archive.getEntry("${spec.path}.zst") ?: throw ChecksumMismatchException("差分包缺少:${spec.path}")
            val out = root.resolve(spec.path).canonicalFile
            check(out.path.startsWith(root.path + File.separator)) { "路径越界:${spec.path}" }
            val renamedFrom = archive.getEntry("${spec.path}.base")
                ?.let { base -> archive.getInputStream(base).use { it.readBytes().decodeToString().trim() } }
            val basePath = renamedFrom ?: spec.path
            val baseFile = installRoot.resolve(basePath).canonicalFile
            check(baseFile.path.startsWith(installRoot.path + File.separator)) { "路径越界:$basePath" }
            if (renamedFrom != null && !baseFile.isFile) throw ChecksumMismatchException("本机缺少差分的基准:$basePath")
            val base = baseFile.takeIf { it.isFile }?.readBytes()
            val restored = try {
                ZstdDecompressCtx().use { ctx ->
                    if (base != null) ctx.loadDict(base)
                    ctx.decompress(archive.getInputStream(entry).use { it.readBytes() }, Math.toIntExact(spec.size))
                }
            } catch (e: ZstdException) {
                throw ChecksumMismatchException("${spec.path}: ${e.message}")
            }
            val actual = MessageDigest.getInstance("SHA-256").digest(restored).toHex()
            if (actual != spec.sha256 || restored.size.toLong() != spec.size) {
                throw ChecksumMismatchException("${spec.path}: $actual != ${spec.sha256}")
            }
            out.parentFile.mkdirs()
            out.writeBytes(restored)
            out.setLastModified(spec.mtime)
        }
    }
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
