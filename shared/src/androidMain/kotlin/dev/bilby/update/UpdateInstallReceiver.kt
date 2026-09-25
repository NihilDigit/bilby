package dev.bilby.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import dev.bilby.AppContainerOwner

/**
 * PackageInstaller 会话的结果回调。
 *
 * 应用自更新几乎总要用户确认:系统先回一个 STATUS_PENDING_USER_ACTION,带着确认页的 Intent,
 * 由这里拉起。安装成功时进程随之被替换,收不到 SUCCESS 也无妨;只有失败需要告诉界面。
 */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val updater = (context.applicationContext as AppContainerOwner).container.platform.updater
            as? AndroidAppUpdater
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                confirm?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            PackageInstaller.STATUS_SUCCESS -> Unit
            PackageInstaller.STATUS_FAILURE_ABORTED -> updater?.onInstallFailed(UpdateFailure.InstallCancelled)
            else -> updater?.onInstallFailed(UpdateFailure.Install)
        }
    }
}
