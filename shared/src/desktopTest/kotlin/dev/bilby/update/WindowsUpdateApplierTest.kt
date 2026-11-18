package dev.bilby.update

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.FileInputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.DosFileAttributeView
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.time.Instant
import kotlin.concurrent.thread

/**
 * 真跑 Windows 独立更新器(`WindowsUpdateApplier`,经 `DesktopAppUpdater.launchWindowsApplier` 启动),
 * 对着临时目录里的假安装目录验证补丁事务、占用退避、回滚恢复、句柄隔离与只读属性清理。只在 Windows 上跑。
 */
class WindowsUpdateApplierTest {
    private val root: File = Files.createTempDirectory("bilby-applier").toFile()
    private val install = root.resolve("install")
    private val staging = root.resolve("staging")
    private val helperRoot = root.resolve("helper")
    private val files = staging.resolve("files")
    private val started = install.resolve("started.txt")
    private val failed = staging.resolve("failed")
    private val journal = install.resolve(".bilby-update.journal")

    @Before
    fun windowsOnly() {
        assumeTrue("更新器只在 Windows 上用", System.getProperty("os.name").startsWith("Windows"))
        install.resolve("app").mkdirs()
        files.resolve("app").mkdirs()
        install.resolve("relaunch.cmd").writeText("@echo started> \"%~dp0started.txt\"\r\n")
    }

    @After
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun installed(path: String, content: String) =
        install.resolve(path).apply { parentFile.mkdirs(); writeText(content) }

    private fun stage(path: String, content: String) =
        files.resolve(path).apply { parentFile.mkdirs(); writeText(content) }

    private fun text(path: String) = install.resolve(path).readText()

    private fun sha256(file: File) =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    /** 本机 1.0 换到 2.0:a.jar 与 Bilby.cfg 换掉,b-2.jar 新增,b-1.jar 不在新版里。 */
    private fun standardUpdate() {
        installed("app/a.jar", "a 1")
        installed("app/b-1.jar", "b 1")
        installed("app/Bilby.cfg", "cfg 1")
        stage("app/a.jar", "a 2")
        stage("app/b-2.jar", "b 2")
        stage("app/Bilby.cfg", "cfg 2")
    }

    private fun run(mode: String = "patch", processId: Long = exitedPid(), waitSeconds: Int = 120): Int {
        val checksums = staging.resolve("staged.sha256")
        val keep = staging.resolve("keep.txt")
        if (mode == "patch") {
            checksums.parentFile.mkdirs()
            checksums.writeText(
                files.walkTopDown().filter { it.isFile }.joinToString("") {
                    "${sha256(it)}  files/${it.relativeTo(files).invariantSeparatorsPath}\n"
                },
            )
            keep.writeText("app/a.jar\napp/b-2.jar\napp/Bilby.cfg\n")
        }
        val process = DesktopAppUpdater.launchWindowsApplier(
            mode = mode,
            installDir = File(shortPath(install)),
            exe = install.resolve("relaunch.cmd"),
            staging = staging,
            source = if (mode == "patch") files else null,
            checksums = checksums.takeIf { it.isFile },
            keep = keep.takeIf { it.isFile },
            watchedProcessSpecs = listOf("$processId:-1"),
            waitSeconds = waitSeconds,
            helperRoot = helperRoot,
        )
        val code = process.waitFor()
        staging.resolve("applier.log").takeIf { it.isFile }?.let { println(it.readText()) }
        staging.resolve("update.log").takeIf { it.isFile }?.let { println(it.readText()) }
        return code
    }

    private fun shortPath(dir: File): String {
        val process = ProcessBuilder("cmd.exe", "/c", "for %I in (\"${dir.absolutePath}\") do @echo %~sI").start()
        return process.inputStream.bufferedReader().readText().trim().also { process.waitFor() }.ifEmpty { dir.absolutePath }
    }

    private fun exitedPid(): Long =
        ProcessBuilder("cmd.exe", "/c", "exit").start().also { it.waitFor() }.pid()

    private fun waitForFile(file: File, attempts: Int = 50) {
        repeat(attempts) {
            if (file.isFile) return
            Thread.sleep(100)
        }
    }

    private fun assertNoLeftovers() {
        val left = install.walkTopDown().filter { it.name.endsWith(".old") || it.name.endsWith(".new") }.toList()
        assertEquals(emptyList<File>(), left)
        assertFalse("事务记录没删", journal.exists())
    }

    private fun creationTime(path: String): FileTime =
        Files.getAttribute(install.resolve(path).toPath(), "basic:creationTime") as FileTime

    @Test
    fun patchReplacesFilesAndKeepsOnlyLogs() {
        standardUpdate()
        val installedAt = FileTime.from(Instant.parse("2020-01-01T00:00:00Z"))
        for (path in listOf("app/a.jar", "app/Bilby.cfg")) {
            Files.setAttribute(install.resolve(path).toPath(), "basic:creationTime", installedAt)
        }
        assertEquals(0, run())
        assertEquals("a 2", text("app/a.jar"))
        assertEquals("b 2", text("app/b-2.jar"))
        assertEquals("cfg 2", text("app/Bilby.cfg"))
        assertEquals(installedAt, creationTime("app/a.jar"))
        assertEquals(installedAt, creationTime("app/Bilby.cfg"))
        assertFalse("新版没有的 jar 没删", install.resolve("app/b-1.jar").exists())
        assertNoLeftovers()
        assertFalse("装过的暂存文件没删", files.exists())
        assertFalse(staging.resolve("staged.sha256").exists())
        assertTrue(staging.resolve("update.log").isFile)
        assertFalse(failed.exists())
        waitForFile(started)
        assertTrue("没有重新拉起应用", started.isFile)
    }

    @Test
    fun briefLockOnTargetIsWaitedOut() {
        standardUpdate()
        val lock = FileInputStream(install.resolve("app/a.jar"))
        thread { Thread.sleep(1500); lock.close() }
        assertEquals(0, run())
        assertEquals("a 2", text("app/a.jar"))
        assertNoLeftovers()
    }

    @Test
    fun lastingLockRollsBackToTheOldImage() {
        standardUpdate()
        FileInputStream(install.resolve("app/a.jar")).use { assertEquals(1, run()) }
        assertEquals("a 1", text("app/a.jar"))
        assertEquals("cfg 1", text("app/Bilby.cfg"))
        assertEquals("b 1", text("app/b-1.jar"))
        assertFalse("回滚没删新增的文件", install.resolve("app/b-2.jar").exists())
        assertNoLeftovers()
        assertTrue(failed.isFile)
        assertTrue("失败后暂存应留着重试", files.isDirectory)
        waitForFile(started)
        assertTrue(started.isFile)
    }

    @Test
    fun recoverRollsBackAnInterruptedSwap() {
        installed("app/b-2.jar", "b 2")
        installed("app/b-1.jar", "b 1")
        installed("app/a.jar", "a 2")
        installed("app/a.jar.old", "a 1")
        installed("app/Bilby.cfg", "cfg 1")
        installed("app/Bilby.cfg.new", "cfg 2")
        journal.writeText("staging\t${staging.absolutePath}\n+\tapp\\b-2.jar\n~\tapp\\a.jar\n~\tapp\\Bilby.cfg\n")
        assertEquals(1, run(mode = "recover"))
        assertEquals("a 1", text("app/a.jar"))
        assertEquals("cfg 1", text("app/Bilby.cfg"))
        assertEquals("b 1", text("app/b-1.jar"))
        assertFalse(install.resolve("app/b-2.jar").exists())
        assertNoLeftovers()
        assertTrue("下次启动要能提示更新未完成", failed.isFile)
        waitForFile(started)
        assertTrue(started.isFile)
    }

    @Test
    fun appThatDoesNotExitFailsTheUpdateAndRelaunches() {
        standardUpdate()
        assertEquals(1, run(processId = ProcessHandle.current().pid(), waitSeconds = 1))
        assertEquals("a 1", text("app/a.jar"))
        assertTrue(failed.isFile)
        waitForFile(started)
        assertTrue(started.isFile)
    }

    @Test
    fun competingRecoverExitsQuietlyWhileJournalIsLocked() {
        journal.writeText("staging\t${staging.absolutePath}\n~\tapp\\a.jar\n")
        FileChannel.open(
            journal.toPath(),
            StandardOpenOption.READ,
            StandardOpenOption.WRITE,
        ).use { ch ->
            ch.lock().use {
                assertEquals(0, run(mode = "recover"))
                assertTrue("被独占锁定的日志不该被竞争进程删除", journal.isFile)
                assertFalse("竞争进程不该抢先拉起应用", started.isFile)
            }
        }
    }

    @Test
    fun readOnlyTargetIsOverwrittenCleanly() {
        standardUpdate()
        val targetPath = install.resolve("app/a.jar").toPath()
        Files.getFileAttributeView(targetPath, DosFileAttributeView::class.java).setReadOnly(true)
        assertEquals(0, run())
        assertEquals("a 2", text("app/a.jar"))
        assertNoLeftovers()
    }

    @Test
    fun existingJournalAbortsNewPatchWithoutDeletingJournal() {
        standardUpdate()
        val sentinel = "staging\t${staging.absolutePath}\n~\tapp\\a.jar\n"
        journal.writeText(sentinel)
        assertEquals(1, run())
        assertTrue("未恢复的旧事务日志不应被新补丁误删", journal.isFile)
        assertEquals(sentinel, journal.readText())
        assertEquals("a 1", text("app/a.jar"))
    }

    @Test
    fun relaunchDoesNotHoldApplierLogHandle() {
        standardUpdate()
        val done = install.resolve("relaunch-done.txt")
        install.resolve("relaunch.cmd").writeText(
            "@echo started> \"%~dp0started.txt\"\r\n@ping -n 3 127.0.0.1 >nul\r\n@echo done> \"%~dp0relaunch-done.txt\"\r\n",
        )
        assertEquals(0, run())
        waitForFile(started)
        assertTrue(started.isFile)
        val applierLog = staging.resolve("applier.log").toPath()
        assertTrue("重启后的应用不应继承 applier.log 句柄导致无法删除", Files.deleteIfExists(applierLog))
        waitForFile(done)
    }

    @Test
    fun recoverKeepsJournalAndOldBackupWhenTargetIsLockedAndSucceedsOnRetry() {
        installed("app/a.jar", "a 2")
        val oldBackup = installed("app/a.jar.old", "a 1")
        installed("app/Bilby.cfg", "cfg 2")
        installed("app/Bilby.cfg.old", "cfg 1")
        journal.writeText("staging\t${staging.absolutePath}\n~\tapp\\a.jar\n~\tapp\\Bilby.cfg\n")

        FileInputStream(install.resolve("app/a.jar")).use {
            assertEquals(1, run(mode = "recover"))
            assertTrue("部分回滚失败时必须保留事务日志以便重试", journal.isFile)
            assertTrue("部分回滚失败时必须保留剩余 .old 备份", oldBackup.isFile)
            assertEquals("a 1", oldBackup.readText())
            assertFalse("回滚未完成时不应拉起半残旧版", started.isFile)
            assertTrue(failed.isFile)
        }

        // 释放文件锁后再次执行 recover,应补完剩余条目并删掉日志、正常拉起旧版。
        assertEquals(1, run(mode = "recover"))
        assertEquals("a 1", text("app/a.jar"))
        assertEquals("cfg 1", text("app/Bilby.cfg"))
        assertNoLeftovers()
        waitForFile(started)
        assertTrue("恢复完成后应拉起应用", started.isFile)
    }

    @Test
    fun concurrentPatchFailsFastWhileInstallLockIsHeldWithoutTouchingLeftovers() {
        standardUpdate()
        val existingOld = installed("app/a.jar.old", "old sentinel")
        val existingNew = installed("app/a.jar.new", "new sentinel")
        val lockFile = install.resolve(".bilby-update.lock").toPath()

        FileChannel.open(
            lockFile,
            StandardOpenOption.CREATE,
            StandardOpenOption.READ,
            StandardOpenOption.WRITE,
        ).use { ch ->
            ch.lock().use {
                assertEquals(1, run(mode = "patch"))
                assertTrue("持锁期间并发更新不应删除 .old 文件", existingOld.isFile)
                assertEquals("old sentinel", existingOld.readText())
                assertTrue("持锁期间并发更新不应删除 .new 文件", existingNew.isFile)
                assertEquals("new sentinel", existingNew.readText())
                assertFalse("持锁失败不应创建事务日志", journal.exists())
                assertFalse("并发更新持锁期间不应抢先拉起应用", started.isFile)
                assertEquals("a 1", text("app/a.jar"))
            }
        }
    }
}
