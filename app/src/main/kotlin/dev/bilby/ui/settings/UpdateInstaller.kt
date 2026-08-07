package dev.bilby.ui.settings

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import dev.bilby.BiliLog
import java.io.File

/**
 * 把下好的 APK 交给系统安装器。
 *
 * **这里不安装任何东西**,只是把文件递过去 —— 装不装由系统那张安装确认页问用户,
 * `REQUEST_INSTALL_PACKAGES` 给的也只是"能拉起这张页"的资格。
 *
 * 递的必须是 `content://`:从 Android N 起把 `file://` 放进 Intent 会抛
 * `FileUriExposedException`,因为对面是另一个进程,拿到路径也没有权限读。
 * `FLAG_GRANT_READ_URI_PERMISSION` 把读权限**临时**授予安装器,随任务结束回收。
 */
internal object UpdateInstaller {

    /** 下载目录。和 `res/xml/file_paths.xml` 里暴露的那一个子目录必须一致。 */
    fun downloadDir(context: Context): File =
        File(context.cacheDir, "updates").apply { mkdirs() }

    fun install(context: Context, apk: File) {
        val uri = runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        }.getOrElse {
            BiliLog.w("生成安装包 content URI 失败", it)
            return
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            // 从非 Activity 上下文拉起需要 NEW_TASK;设置页本身是 Activity,
            // 但这条让它在任何上下文下都成立。
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
            .onFailure { BiliLog.w("拉起安装器失败", it) }
    }
}
