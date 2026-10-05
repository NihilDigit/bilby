import java.io.ByteArrayOutputStream
import java.net.URI
import java.security.MessageDigest
import java.util.Base64
import javax.inject.Inject
import org.gradle.process.ExecOperations

plugins {
    `java-library`
}

// androidx.sqlite 的 sqlite-bundled-jvm 不带 Windows ARM64 的原生库(2.6.2 到 2.8.0-alpha01 都没有),
// WoA64 上 BundledSQLiteDriver 抛 "Cannot find a suitable SQLite binary for windows 11 | aarch64"。
//
// 这个模块在 Windows ARM64 主机上用 MSVC 编出 sqliteJni.dll,打成只含资源的 jar,由 :shared 以
// runtimeOnly 引入。androidx 的 NativeLibraryLoader 经类加载器找 natives/windows_arm64/sqliteJni.dll,
// 它在运行时类路径上就能加载,gradle run、单测与打包走同一条路。做法照 Animeko 的
// ci-helper/sqlite-woa64。
//
// 源码是 SQLite 官方的 amalgamation 与 androidx.sqlite 的 sqlite_bindings.cpp,都以 sha256 钉死,
// 编译宏与 androidx 的官方构建一致。要 Visual Studio 的「C++ ARM64 生成工具」组件,windows-11-arm
// runner 自带。
//
// androidx 发布 windows_arm64 之后,删掉本目录、settings 里的 include 与 :shared 里那条 runtimeOnly。

// binding 取自 androidx.sqlite 2.6.1 发版时这个文件所在的 commit(androidx 不给库版本打 tag)。
// 运行时解析到的是 Room 传递进来的 2.6.2;JNI 这层在补丁版本之间不变,Animeko 实测 2.6.1 的 binding
// 在 2.6.2 上工作正常。升级 androidx.sqlite 后加载或行为出错,把这三个值换成新版本对应的 commit。
val bindingSqliteVersion = "2.6.1"
val bindingCommit = "ce10e55447f4e0fd21a9001d0589a6e1e7a5a8d7"
val bindingSha256 = "4C535DFF9D2E30E8B0B5203455167020E102185E22F3056A27DA0B1AB71D3897"

val sqliteVersion = "3.50.1"
val sqliteAmalgamationVersion = "3500100"
val sqliteAmalgamationYear = "2025"
val sqliteAmalgamationSha256 = "41716B44AC8777188C4C3F1F370F01C9CB9E3B6428EB5C981D086C35DE2D9D3F"

// jar 里只有 DLL,没有类。不写的话 Gradle 按运行它的 JDK 给 jar 标上 JVM 版本,字节码为 17 的
// :desktop 解析时拒收。
java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

// 看的是构建主机:原生编译,不能从 x64 交叉构建。别的主机上只出一个空 jar,也没有人依赖它。
val isWindowsArm64Host = System.getProperty("os.name").startsWith("Windows") &&
    System.getProperty("os.arch") == "aarch64"

/** 同一份文件的一个下载来源。googlesource 的 `?format=TEXT` 返回 base64,[base64Encoded] 标明这种。 */
data class DownloadSource(val url: String, val base64Encoded: Boolean = false) : java.io.Serializable

/**
 * 从 [sources] 之一下载同一份文件并校验 sha256。来源按顺序试,一轮都失败才退避重试,单个来源的
 * 临时故障立刻换下一个。校验不符说明内容不对,直接失败,不再试别的来源。
 */
abstract class DownloadAndVerify : DefaultTask() {
    @get:Input
    abstract val sources: ListProperty<DownloadSource>

    @get:Input
    abstract val sha256: Property<String>

    @get:OutputFile
    abstract val target: RegularFileProperty

    @TaskAction
    fun run() {
        val sources = sources.get()
        var lastError: Exception? = null
        for (attempt in 1..5) {
            for (source in sources) {
                val bytes = try {
                    URI(source.url).toURL().openStream().use { it.readBytes() }
                } catch (e: Exception) {
                    lastError = e
                    logger.warn("下载 ${source.url} 失败(第 $attempt/5 轮):${e.message}")
                    continue
                }
                val content = if (source.base64Encoded) {
                    Base64.getMimeDecoder().decode(String(bytes, Charsets.US_ASCII).trim())
                } else {
                    bytes
                }
                val actual = MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02X".format(it) }
                if (!actual.equals(sha256.get(), ignoreCase = true)) {
                    throw GradleException("${source.url} 的 SHA-256 不符:应为 ${sha256.get()},实为 $actual")
                }
                target.get().asFile.apply { parentFile.mkdirs() }.writeBytes(content)
                return
            }
            if (attempt < 5) Thread.sleep(minOf(60_000L, 5_000L * (1L shl attempt)))
        }
        throw GradleException("各来源都下载失败:${sources.map { it.url }}", lastError)
    }
}

/** 用 MSVC 的 arm64 工具链把 amalgamation 与 JNI binding 编成 sqliteJni.dll。 */
abstract class CompileSqliteJni @Inject constructor(
    private val execOperations: ExecOperations,
) : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val amalgamationDirectory: DirectoryProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val bindingSource: RegularFileProperty

    @get:Input
    abstract val expectedSqliteVersion: Property<String>

    @get:Input
    abstract val javaHome: Property<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun run() {
        val amalgamationDir = amalgamationDirectory.get().asFile
        val header = amalgamationDir.resolve("sqlite3.h").readText()
        val expected = expectedSqliteVersion.get()
        if (!Regex("""#define\s+SQLITE_VERSION\s+"${Regex.escape(expected)}"""").containsMatchIn(header)) {
            throw GradleException("下载的 amalgamation 不是 SQLite $expected")
        }
        val vcvars = findVisualStudioWithArm64Tools().resolve("VC\\Auxiliary\\Build\\vcvarsall.bat")
        if (!vcvars.isFile) throw GradleException("找不到 $vcvars")

        val jdk = File(javaHome.get())
        val dll = outputDirectory.get().asFile.apply { mkdirs() }.resolve("sqliteJni.dll")
        val objDir = temporaryDir
        val sqliteObj = objDir.resolve("sqlite3.obj")
        val bindingObj = objDir.resolve("sqlite_bindings.obj")

        // 与 androidx 官方构建相同的编译宏
        val defines = listOf(
            "HAVE_USLEEP=1",
            "SQLITE_DEFAULT_AUTOVACUUM=1",
            "SQLITE_DEFAULT_MEMSTATUS=0",
            "SQLITE_DEFAULT_WAL_SYNCHRONOUS=1",
            "SQLITE_ENABLE_COLUMN_METADATA",
            "SQLITE_ENABLE_FTS3",
            "SQLITE_ENABLE_FTS3_PARENTHESIS",
            "SQLITE_ENABLE_FTS4",
            "SQLITE_ENABLE_FTS5",
            "SQLITE_ENABLE_JSON1",
            "SQLITE_ENABLE_MATH_FUNCTIONS",
            "SQLITE_ENABLE_NORMALIZE",
            "SQLITE_ENABLE_RTREE",
            "SQLITE_ENABLE_STAT4",
            "SQLITE_HAVE_ISNAN",
            "SQLITE_OMIT_BUILTIN_TEST",
            "SQLITE_OMIT_DEPRECATED",
            "SQLITE_OMIT_PROGRESS_CALLBACK",
            "SQLITE_OMIT_SHARED_CACHE",
            "SQLITE_SECURE_DELETE",
            "SQLITE_TEMP_STORE=3",
            "SQLITE_THREADSAFE=2",
        ).joinToString(" ") { "/D$it" }

        // /Brepro 让产物可复现;/MT 静态链接 CRT,不依赖目标机器装没装 VC++ 运行库。
        // 命令写进 .cmd 再执行:直接交给 cmd.exe /c,命令串里的引号会被 Windows 的参数规则拆散。
        val script = objDir.resolve("build-sqlite-jni.cmd")
        script.writeText(
            listOf(
                "@echo off",
                "call \"${vcvars.absolutePath}\" arm64 || exit /b 1",
                "cl /nologo /O2 /Brepro /MT /utf-8 $defines " +
                    "/I\"${amalgamationDir.absolutePath}\" " +
                    "/Fo\"${sqliteObj.absolutePath}\" " +
                    "/c \"${amalgamationDir.resolve("sqlite3.c").absolutePath}\" || exit /b 1",
                "cl /nologo /O2 /Brepro /MT /EHsc /std:c++17 /utf-8 $defines " +
                    "/I\"${amalgamationDir.absolutePath}\" " +
                    "/I\"${jdk.resolve("include").absolutePath}\" " +
                    "/I\"${jdk.resolve("include\\win32").absolutePath}\" " +
                    "/Fo\"${bindingObj.absolutePath}\" " +
                    "/c \"${bindingSource.get().asFile.absolutePath}\" || exit /b 1",
                "link /nologo /Brepro /DLL /OUT:\"${dll.absolutePath}\" " +
                    "/IMPLIB:\"${objDir.resolve("sqliteJni.lib").absolutePath}\" " +
                    "\"${sqliteObj.absolutePath}\" \"${bindingObj.absolutePath}\" || exit /b 1",
            ).joinToString("\r\n", postfix = "\r\n"),
        )
        execOperations.exec { commandLine("cmd.exe", "/d", "/s", "/c", script.absolutePath) }
    }

    private fun findVisualStudioWithArm64Tools(): File {
        val vswhere = File(
            System.getenv("ProgramFiles(x86)") ?: "C:\\Program Files (x86)",
            "Microsoft Visual Studio\\Installer\\vswhere.exe",
        )
        if (!vswhere.isFile) throw GradleException("找不到 $vswhere,需要装带 C++ ARM64 生成工具的 Visual Studio")
        val output = ByteArrayOutputStream()
        execOperations.exec {
            commandLine(
                vswhere.absolutePath,
                "-latest", "-products", "*",
                "-requires", "Microsoft.VisualStudio.Component.VC.Tools.ARM64",
                "-property", "installationPath",
            )
            standardOutput = output
        }
        val path = output.toString(Charsets.UTF_8.name()).trim()
        if (path.isEmpty()) throw GradleException("没有装 Visual Studio 的「MSVC C++ ARM64 生成工具」组件")
        return File(path)
    }
}

val downloadSqliteAmalgamation = tasks.register<DownloadAndVerify>("downloadSqliteAmalgamation") {
    sources = listOf(
        DownloadSource("https://www.sqlite.org/$sqliteAmalgamationYear/sqlite-amalgamation-$sqliteAmalgamationVersion.zip"),
    )
    sha256 = sqliteAmalgamationSha256
    target = layout.buildDirectory.file("sqlite-woa64/sqlite-amalgamation-$sqliteAmalgamationVersion.zip")
}

val unzipSqliteAmalgamation = tasks.register<Sync>("unzipSqliteAmalgamation") {
    from(zipTree(downloadSqliteAmalgamation.flatMap { it.target })) {
        // 去掉压缩包里顶层的 sqlite-amalgamation-<版本>/
        eachFile { relativePath = RelativePath(true, *relativePath.segments.drop(1).toTypedArray()) }
        includeEmptyDirs = false
    }
    into(layout.buildDirectory.dir("sqlite-woa64/amalgamation"))
}

val downloadSqliteBinding = tasks.register<DownloadAndVerify>("downloadSqliteBinding") {
    val path = "sqlite/sqlite-bundled/src/jvmAndroidMain/jni/sqlite_bindings.cpp"
    // GitHub 上的 androidx/androidx 是 AOSP frameworks/support 的镜像,commit 相同,内容由 sha256 钉住。
    // 先试它:googlesource 对 GitHub 托管 runner 的出口时常持续返回 503(Animeko 踩过)。
    sources = listOf(
        DownloadSource("https://raw.githubusercontent.com/androidx/androidx/$bindingCommit/$path"),
        DownloadSource(
            "https://android.googlesource.com/platform/frameworks/support/+/$bindingCommit/$path?format=TEXT",
            base64Encoded = true,
        ),
    )
    sha256 = bindingSha256
    target = layout.buildDirectory.file("sqlite-woa64/sqlite_bindings.cpp")
}

if (isWindowsArm64Host) {
    val compileSqliteJni = tasks.register<CompileSqliteJni>("compileSqliteJni") {
        description = "Builds natives/windows_arm64/sqliteJni.dll for androidx.sqlite $bindingSqliteVersion"
        amalgamationDirectory = layout.dir(unzipSqliteAmalgamation.map { it.destinationDir })
        bindingSource = downloadSqliteBinding.flatMap { it.target }
        expectedSqliteVersion = sqliteVersion
        // 只用它的 include/ 下的 jni.h,哪个 JDK 都一样
        javaHome = providers.systemProperty("java.home")
        outputDirectory = layout.buildDirectory.dir("sqlite-woa64/native")
    }
    tasks.jar {
        from(compileSqliteJni.flatMap { it.outputDirectory.file("sqliteJni.dll") }) {
            into("natives/windows_arm64")
        }
    }
}
