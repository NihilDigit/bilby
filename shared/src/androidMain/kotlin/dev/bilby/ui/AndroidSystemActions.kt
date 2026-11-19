package dev.bilby.ui

import android.app.Activity
import android.content.ContentValues
import android.os.Environment
import android.provider.MediaStore
import dev.bilby.BiliLog
import dev.bilby.api.BiliClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Android 上的 [SystemActions]。持有 Activity:切语言要重建它,拉起分享面板要一个前台界面。 */
class AndroidSystemActions(
    private val activity: Activity,
    private val biliClient: BiliClient? = null,
) : SystemActions {
    override fun shareVideo(bvid: String, title: String) = ShareLink.video(activity, bvid, title)
    override fun shareLiveRoom(roomId: Long, title: String) = ShareLink.liveRoom(activity, roomId, title)
    override fun shareSpace(mid: Long, name: String) = ShareLink.space(activity, mid, name)
    override fun openInBrowser(url: String) = ShareLink.openInBrowser(activity, url)

    override val supportsCalendar: Boolean get() = true

    override fun addCalendarEvent(title: String, startEpochSeconds: Long, description: String) =
        CalendarEvent.insert(activity, title, startEpochSeconds, description)

    override val supportsLanguageSwitch: Boolean get() = true
    override fun currentLanguage(): AppLanguage = AppLanguageStore.current(activity)
    override fun applyLanguage(language: AppLanguage) = AppLanguageStore.apply(activity, language)

    override val supportsCopyImage: Boolean get() = false
    override val supportsSaveImage: Boolean get() = biliClient != null

    override suspend fun saveImage(url: String): Boolean? {
        val client = biliClient ?: return false
        return withContext(Dispatchers.IO) {
            val normalized = ImageSaveHelper.originalImageUrl(url)
            val (fileName, ext) = ImageSaveHelper.suggestFileNameAndExtension(normalized)
            val mimeType = ImageSaveHelper.mimeTypeForExtension(ext)
            val bytes = runCatching {
                ImageSaveHelper.downloadOriginalBytes(client, normalized)
            }.onFailure {
                BiliLog.w("下载图片失败 file=$fileName", it)
            }.getOrNull() ?: return@withContext false

            val resolver = activity.applicationContext.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, mimeType)
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Bilby")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = runCatching {
                resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            }.onFailure {
                BiliLog.w("创建相册记录失败 file=$fileName", it)
            }.getOrNull() ?: return@withContext false

            runCatching {
                resolver.openOutputStream(uri)?.use { out ->
                    out.write(bytes)
                    out.flush()
                } ?: error("openOutputStream returned null")
                val ready = ContentValues().apply {
                    put(MediaStore.Images.Media.IS_PENDING, 0)
                }
                val updated = resolver.update(uri, ready, null, null)
                check(updated > 0) { "MediaStore update IS_PENDING=0 affected 0 rows" }
                true
            }.onFailure { error ->
                BiliLog.w("写入相册图片失败 file=$fileName", error)
                runCatching { resolver.delete(uri, null, null) }
                    .onFailure { cleanupError ->
                        BiliLog.w("清理失败的相册记录失败 file=$fileName", cleanupError)
                    }
            }.getOrDefault(false)
        }
    }
}
