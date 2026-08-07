package dev.bilby.data

import android.os.Build
import dev.bilby.BiliLog
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import io.ktor.utils.io.core.isEmpty
import io.ktor.utils.io.core.readBytes
import io.ktor.utils.io.readRemaining
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** GitHub release 里我们要的那几项。其余字段一律忽略,见 [Json] 的 ignoreUnknownKeys。 */
@Serializable
private data class ReleaseDto(
    @SerialName("tag_name") val tagName: String = "",
    val name: String = "",
    val body: String = "",
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<AssetDto> = emptyList(),
)

@Serializable
private data class AssetDto(
    val name: String = "",
    @SerialName("browser_download_url") val downloadUrl: String = "",
    val size: Long = 0,
)

/** 查到的可用更新。[assetName] 保留下来是为了在界面上让人看清将要装的是哪个变体。 */
data class UpdateInfo(
    val version: String,
    val notes: String,
    val assetName: String,
    val downloadUrl: String,
    val sizeBytes: Long,
)

sealed interface UpdateCheck {
    data class Available(val info: UpdateInfo) : UpdateCheck
    data object UpToDate : UpdateCheck
    data class Failed(val message: String) : UpdateCheck
}

/**
 * 从 GitHub Releases 手动更新。
 *
 * **只在用户点击时才跑**,没有后台轮询、没有启动时自动检查 —— 那属于会主动打扰人的东西,
 * 和这个 app 拒绝红点与推送是同一条约束(DESIGN 1.3)。
 *
 * 不走 `BiliClient`:那是 B 站接口的唯一出口,而这里访问的是 GitHub。第三方服务另走一条
 * (SponsorBlock 也是这样),两边的鉴权、签名、风控完全不相干,混在一起只会让
 * "为什么这个请求带了 B 站 cookie" 变成一个需要解释的问题。
 */
class UpdateRepository(
    private val httpClient: HttpClient,
    private val json: Json,
) {

    suspend fun check(currentVersion: String): UpdateCheck = runCatching {
        val response = httpClient.get(LATEST_RELEASE_URL) {
            // GitHub 要求带 UA,不带会 403。版本头把响应钉在 v3 的字段命名上。
            header("Accept", "application/vnd.github+json")
            header("User-Agent", "Bilby")
        }
        if (!response.status.isSuccess()) {
            return UpdateCheck.Failed("GitHub 返回 ${response.status.value}")
        }
        val release = json.decodeFromString<ReleaseDto>(response.bodyAsText())
        if (release.draft || release.prerelease) return UpdateCheck.UpToDate

        val latest = release.tagName.removePrefix("v")
        if (latest.isEmpty()) return UpdateCheck.Failed("release 没有 tag")
        if (!isNewerThan(latest, currentVersion)) return UpdateCheck.UpToDate

        // **必须挑对架构**:release 是分 ABI 出包的(bilby-<版本>-<abi>.apk),
        // 装错架构的包会直接安装失败,而失败信息只说"解析包出现问题",无从归因。
        val asset = release.assets.firstOrNull { it.matchesThisDevice() }
            ?: return UpdateCheck.Failed("这个版本没有适配本机架构的安装包")

        UpdateCheck.Available(
            UpdateInfo(
                version = latest,
                notes = release.body.trim(),
                assetName = asset.name,
                downloadUrl = asset.downloadUrl,
                sizeBytes = asset.size,
            )
        )
    }.getOrElse {
        BiliLog.w("查更新失败", it)
        UpdateCheck.Failed(it.message ?: "网络错误")
    }

    /**
     * 下载到给定文件。[onProgress] 收到的是 0..1;总长未知(服务端没给 Content-Length)时
     * 用 asset 里的 size 兜底。
     *
     * 边下边写而不是整包读进内存:APK 有几十 MB,一次性 `bodyAsBytes()` 在低内存设备上
     * 是实打实的 OOM 风险。
     */
    suspend fun download(
        info: UpdateInfo,
        target: File,
        onProgress: (Float) -> Unit,
    ): Result<File> = runCatching {
        val response = httpClient.get(info.downloadUrl) { header("User-Agent", "Bilby") }
        if (!response.status.isSuccess()) error("下载失败:HTTP ${response.status.value}")

        val total = info.sizeBytes.takeIf { it > 0 } ?: Long.MAX_VALUE
        var written = 0L
        val channel = response.bodyAsChannel()
        target.outputStream().use { out ->
            while (!channel.isClosedForRead) {
                val packet = channel.readRemaining(DOWNLOAD_CHUNK)
                while (!packet.isEmpty) {
                    val bytes = packet.readBytes()
                    out.write(bytes)
                    written += bytes.size
                    onProgress((written.toFloat() / total).coerceIn(0f, 1f))
                }
            }
        }
        onProgress(1f)
        target
    }.onFailure { BiliLog.w("下载更新包失败", it) }

    /**
     * 只认 `x.y.z` 形式的数字段,逐段比。
     *
     * 本机构建的版本是 `0.0.0-dev`,debug 还带 `-debug` 后缀 —— 后缀一律截掉再比,
     * 于是开发机上任何正式 release 都会被判成更新,这正是想要的:调试更新流程时不必先发版。
     */
    private fun isNewerThan(candidate: String, current: String): Boolean {
        fun parse(v: String) = v.substringBefore('-').split('.')
            .map { it.toIntOrNull() ?: 0 }
        val a = parse(candidate)
        val b = parse(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun AssetDto.matchesThisDevice(): Boolean {
        if (!name.endsWith(".apk")) return false
        // SUPPORTED_ABIS 按优先级排序,取第一个匹配上的就是最合适的那个包。
        return Build.SUPPORTED_ABIS.any { abi -> name.contains("-$abi.") }
    }

    private companion object {
        const val LATEST_RELEASE_URL =
            "https://api.github.com/repos/NihilDigit/bilby/releases/latest"
        const val DOWNLOAD_CHUNK = 64L * 1024
    }
}
