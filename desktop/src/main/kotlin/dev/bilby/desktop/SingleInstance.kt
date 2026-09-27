package dev.bilby.desktop

import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.DWORD
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.ptr.IntByReference
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileLock

/**
 * 同一份安装只跑一个进程。再次打开时把已在运行的窗口带到前台,自己退出。
 *
 * 两个进程共用数据目录里的数据库与更新暂存目录:后者曾让一个进程重下的安装包盖掉另一个
 * 进程刚校验过、正要交给 msiexec 的那份,安装以 1620(包已损坏)失败。
 *
 * 判定靠数据目录里一个文件的独占锁,进程退出时由系统释放,崩溃也不会留下残锁。前台切换由
 * 第二个进程来做,不经进程间消息:它刚由用户启动,Windows 允许它把前台交给别的窗口,
 * 而第一个进程在后台调 SetForegroundWindow 会被系统拦下,只闪任务栏。
 */
internal object SingleInstance {
    /** 持有到进程结束。释放它或让它被回收,锁就没了。 */
    private var lock: FileLock? = null

    /**
     * 拿到锁返回 true,照常启动。已有实例时把它的窗口带到前台并返回 false。
     *
     * 只管打包后的应用:gradle run 的进程与装好的应用共用数据目录,若也参与,开发时开着
     * 装好的那份就再也跑不起开发版。
     */
    fun claim(dataDir: File): Boolean {
        val launcher = System.getProperty("jpackage.app-path") ?: return true
        val channel = RandomAccessFile(File(dataDir, "instance.lock"), "rw").channel
        lock = channel.tryLock()
        if (lock != null) return true
        channel.close()
        runCatching { activateOther(launcher) }
        return false
    }

    /** 按启动器路径找另一个进程:同一份安装的进程都由它启动,锁文件里不必另记 pid。 */
    private fun activateOther(launcher: String) {
        val self = ProcessHandle.current().pid()
        val pids = ProcessHandle.allProcesses()
            .filter { it.pid() != self && it.info().command().orElse(null).equals(launcher, ignoreCase = true) }
            .map { it.pid().toInt() }
            .toList()
            .toSet()
        val window = mainWindowOf(pids) ?: return
        val user32 = User32.INSTANCE
        // 只在最小化时还原:SW_RESTORE 也会把最大化的窗口还原成普通大小。
        if (user32.GetWindowLong(window, WinUser.GWL_STYLE) and WinUser.WS_MINIMIZE != 0) {
            user32.ShowWindow(window, WinUser.SW_RESTORE)
        }
        user32.SetForegroundWindow(window)
    }

    /** 这些进程的可见顶层窗口。Bilby 只有一个主窗口,小窗也是它本身换了样式。 */
    private fun mainWindowOf(pids: Set<Int>): HWND? {
        if (pids.isEmpty()) return null
        val user32 = User32.INSTANCE
        var found: HWND? = null
        user32.EnumWindows({ hwnd, _ ->
            val pid = IntByReference()
            user32.GetWindowThreadProcessId(hwnd, pid)
            // 对话框与弹出层有 owner,主窗口没有。
            val match = pid.value in pids && user32.IsWindowVisible(hwnd) &&
                user32.GetWindow(hwnd, DWORD(WinUser.GW_OWNER.toLong())) == null
            if (match) found = hwnd
            !match
        }, null)
        return found
    }
}
