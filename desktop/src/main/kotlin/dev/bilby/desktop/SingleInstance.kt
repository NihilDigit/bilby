package dev.bilby.desktop

import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.DWORD
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.ptr.IntByReference
import dev.bilby.BiliLog
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.ClosedChannelException
import java.nio.channels.FileLock
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 同一份安装只跑一个进程。再次打开时把已在运行的窗口带到前台、将命令行参数经本地 Unix 域套接字转交过去,自己退出。
 *
 * 两个进程共用数据目录里的数据库与更新暂存目录:后者曾让一个进程重下的安装包盖掉另一个
 * 进程刚校验过、正要交给 msiexec 的那份,安装以 1620(包已损坏)失败。
 *
 * 判定靠数据目录里一个文件的独占锁,进程退出时由系统释放,崩溃也不会留下残锁。前台切换由
 * 第二个进程来做:它刚由用户启动,Windows 允许它把前台交给别的窗口,
 * 而第一个进程在后台调 SetForegroundWindow 会被系统拦下,只闪任务栏。
 */
internal class SingleInstance private constructor(
    private val server: ServerSocketChannel?,
) {
    private val listening = AtomicBoolean(false)

    /** 在守护线程上收听后续实例转发过来的启动参数。开发模式下 `server == null`,直接返回。 */
    fun listen(onArgs: (List<String>) -> Unit) {
        val channel = server ?: return
        if (!listening.compareAndSet(false, true)) return
        Thread({
            while (channel.isOpen) {
                val client = try {
                    channel.accept()
                } catch (_: ClosedChannelException) {
                    break
                } catch (t: Throwable) {
                    if (channel.isOpen) BiliLog.w("单实例套接字接收连接失败", t)
                    break
                }
                client.use { conn ->
                    runCatching {
                        val input = DataInputStream(Channels.newInputStream(conn))
                        val count = input.readInt().coerceIn(0, MAX_FORWARDED_ARGS)
                        val received = ArrayList<String>(count)
                        repeat(count) {
                            val len = input.readInt().coerceIn(0, MAX_ARG_BYTES)
                            val bytes = ByteArray(len)
                            input.readFully(bytes)
                            received.add(bytes.decodeToString())
                        }
                        if (received.isNotEmpty()) onArgs(received)
                    }.onFailure { BiliLog.w("读取转发启动参数失败", it) }
                }
            }
        }, "bilby-single-instance").apply {
            isDaemon = true
            start()
        }
    }

    companion object {
        private const val MAX_FORWARDED_ARGS = 64
        private const val MAX_ARG_BYTES = 16_384

        /** 持有到进程结束。释放它或让它被回收,锁就没了。 */
        private var lock: FileLock? = null

        /**
         * 拿到锁返回 [SingleInstance] 句柄,照常启动。已有实例时把它的窗口带到前台、转发 [args] 并返回 `null`。
         *
         * 只管打包后的应用:gradle run 的进程与装好的应用共用数据目录,若也参与,开发时开着
         * 装好的那份就再也跑不起开发版。
         */
        fun claimOrForward(dataDir: File, args: List<String>): SingleInstance? {
            val launcher = System.getProperty("jpackage.app-path") ?: return SingleInstance(null)
            dataDir.mkdirs()
            val channel = RandomAccessFile(File(dataDir, "instance.lock"), "rw").channel
            lock = channel.tryLock()
            val socketPath = File(dataDir, "instance.sock").toPath()
            if (lock != null) {
                val server = runCatching {
                    Files.deleteIfExists(socketPath)
                    ServerSocketChannel.open(StandardProtocolFamily.UNIX).apply {
                        bind(UnixDomainSocketAddress.of(socketPath))
                    }.also { bound ->
                        Runtime.getRuntime().addShutdownHook(Thread({
                            runCatching { bound.close() }
                            runCatching { Files.deleteIfExists(socketPath) }
                        }, "bilby-single-instance-cleanup"))
                    }
                }.onFailure { BiliLog.w("创建单实例本地套接字失败", it) }.getOrNull()
                return SingleInstance(server)
            }
            channel.close()
            runCatching { activateOther(launcher) }
                .onFailure { BiliLog.w("唤醒已有实例窗口失败", it) }
            if (args.isNotEmpty()) {
                forwardArgs(socketPath, args)
            }
            return null
        }

        private fun forwardArgs(socketPath: java.nio.file.Path, args: List<String>) {
            val address = UnixDomainSocketAddress.of(socketPath)
            var lastError: Throwable? = null
            repeat(5) { attempt ->
                val sent = runCatching {
                    SocketChannel.open(StandardProtocolFamily.UNIX).use { client ->
                        client.connect(address)
                        val output = DataOutputStream(Channels.newOutputStream(client))
                        val bounded = args.take(MAX_FORWARDED_ARGS)
                        output.writeInt(bounded.size)
                        for (arg in bounded) {
                            val bytes = arg.encodeToByteArray()
                            val len = bytes.size.coerceAtMost(MAX_ARG_BYTES)
                            output.writeInt(len)
                            output.write(bytes, 0, len)
                        }
                        output.flush()
                    }
                    true
                }.onFailure { lastError = it }.getOrDefault(false)
                if (sent) return
                if (attempt < 4) Thread.sleep(50)
            }
            lastError?.let { BiliLog.w("向已有实例转发启动参数失败", it) }
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
}
