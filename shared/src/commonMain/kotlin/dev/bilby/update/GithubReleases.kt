package dev.bilby.update

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException

/** Release 的一个附件。[sha256] 取自 GitHub 为每个附件算好的 digest(小写十六进制)。 */
data class ReleaseAsset(
    val name: String,
    val url: String,
    val size: Long,
    val sha256: String?,
)

data class LatestRelease(
    val version: String,
    val notes: String,
    val pageUrl: String,
    val assets: List<ReleaseAsset>,
) {
    fun asset(name: String): ReleaseAsset? = assets.firstOrNull { it.name == name }
}

sealed interface ReleaseCheck {
    data class Newer(val release: LatestRelease) : ReleaseCheck
    data object UpToDate : ReleaseCheck
    data class Failed(val cause: Throwable) : ReleaseCheck
}

/** 下载内容与 Release 公布的摘要或清单不符。 */
class ChecksumMismatchException(message: String) : Exception(message)

/** 网络不通时该先换个网,其余原因只能稍后再试,界面按这两类措辞。 */
fun Throwable.isNetworkFailure(): Boolean = this is IOException

/**
 * 从 GitHub Releases 取最新版本并下载附件。两端共用;挑哪个附件、怎么安装归各平台。
 *
 * 不走 `BiliClient`:那是 B 站接口的唯一出口,这里访问的是 GitHub,两边的鉴权、签名、风控
 * 完全不相干。
 *
 * [latestReleaseUrl] 可换,是为了在本机对着假的 Release 端到端地走一遍更新流程。
 */
class GithubReleaseClient(
    private val http: HttpClient,
    private val userAgent: String,
    private val latestReleaseUrl: String = LATEST_RELEASE_URL,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun check(currentVersion: String): ReleaseCheck = try {
        val release = fetchLatest()
        when {
            release == null -> ReleaseCheck.UpToDate
            isNewerVersion(release.version, currentVersion) -> ReleaseCheck.Newer(release)
            else -> ReleaseCheck.UpToDate
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        ReleaseCheck.Failed(e)
    }

    /**
     * 草稿与预发布不算新版本,返回 null。releases/latest 本身已排除这两类,这里再防一道。
     * 草稿要等发布之后才推给用户,这正是 release 工作流发草稿的用意。
     */
    private suspend fun fetchLatest(): LatestRelease? {
        val response = http.get(latestReleaseUrl) {
            // GitHub 不带 UA 返回 403。版本头把响应钉在 v3 的字段命名上。
            header("Accept", "application/vnd.github+json")
            header("User-Agent", userAgent)
        }
        check(response.status.isSuccess()) { "HTTP ${response.status.value}" }
        val release = json.decodeFromString(ReleaseDto.serializer(), response.bodyAsText())
        if (release.draft || release.prerelease) return null
        val version = release.tagName.removePrefix("v")
        check(version.isNotEmpty()) { "Release 没有 tag" }
        return LatestRelease(
            version = version,
            notes = release.body.orEmpty().trim(),
            pageUrl = release.htmlUrl,
            assets = release.assets.map { asset ->
                ReleaseAsset(
                    name = asset.name,
                    url = asset.downloadUrl,
                    size = asset.size,
                    sha256 = asset.digest?.takeIf { it.startsWith(SHA256_PREFIX) }
                        ?.removePrefix(SHA256_PREFIX)?.lowercase(),
                )
            },
        )
    }

    /**
     * 边下边交给 [onChunk]:写文件与算摘要由调用方做,这里只管流与进度。
     *
     * 边下边写而不是整包读进内存:安装包有几十 MB,一次性读进来在低内存设备上是实打实的 OOM。
     * [onProgress] 每涨 1% 才回调一次,按块回调的话几十 MB 的包要触发几百次重组。
     */
    suspend fun download(
        asset: ReleaseAsset,
        onChunk: (buffer: ByteArray, length: Int) -> Unit,
        onProgress: (Float) -> Unit,
    ) {
        http.prepareGet(asset.url) { header("User-Agent", userAgent) }.execute { response ->
            check(response.status.isSuccess()) { "HTTP ${response.status.value}" }
            val channel = response.bodyAsChannel()
            val buffer = ByteArray(DOWNLOAD_CHUNK)
            var written = 0L
            var reported = 0f
            while (true) {
                val read = channel.readAvailable(buffer)
                if (read < 0) break
                if (read == 0) continue
                onChunk(buffer, read)
                written += read
                val progress = if (asset.size > 0) (written.toFloat() / asset.size).coerceIn(0f, 1f) else 0f
                if (progress - reported >= 0.01f) {
                    reported = progress
                    onProgress(progress)
                }
            }
            check(asset.size <= 0 || written == asset.size) { "下载不完整:$written / ${asset.size}" }
        }
    }

    companion object {
        const val LATEST_RELEASE_URL = "https://api.github.com/repos/NihilDigit/bilby/releases/latest"
        private const val SHA256_PREFIX = "sha256:"
        private const val DOWNLOAD_CHUNK = 64 * 1024
    }
}

/**
 * 只认数字段,逐段比,缺的一段按 0 计。
 *
 * 本机构建的版本是 `0.0.0-dev`,debug 还带 `-debug` 后缀,后缀一律截掉再比:开发机上任何
 * 正式 release 都会被判成更新,调试更新流程时不必先发版。
 */
fun isNewerVersion(candidate: String, current: String): Boolean {
    fun parts(v: String) = v.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
    val a = parts(candidate)
    val b = parts(current)
    for (i in 0 until maxOf(a.size, b.size)) {
        val x = a.getOrElse(i) { 0 }
        val y = b.getOrElse(i) { 0 }
        if (x != y) return x > y
    }
    return false
}

@Serializable
private data class ReleaseDto(
    @SerialName("tag_name") val tagName: String = "",
    @SerialName("html_url") val htmlUrl: String = "",
    val body: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<AssetDto> = emptyList(),
)

@Serializable
private data class AssetDto(
    val name: String,
    val size: Long = 0L,
    @SerialName("browser_download_url") val downloadUrl: String,
    val digest: String? = null,
)
